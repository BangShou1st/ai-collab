package com.shitulelv.aicollab.agent.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.InternalAiTokenFilter;
import com.shitulelv.aicollab.internal.ai.task.InternalAiTaskController;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 三类 P2 索引任务（{@code INDEX_DOCUMENT / DELETE_DOCUMENT_INDEX / REINDEX_GENERATION}）的
 * {@code snapshot / result / fail} 与补偿终态联动单元测试（不依赖 Docker）。
 */
class AiIndexTaskCallbackTest {

    private static final UUID JOB_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DOC_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DOC_ID_2 = UUID.fromString("33333333-3333-3333-3333-444444444444");
    private static final UUID SNAPSHOT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID GEN_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID PARENT_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID LEASE = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-27T08:00:00Z"), ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    private final ObjectMapper json = new ObjectMapper();
    private final KeyPair keys = keyPair();
    private final InternalTokenIssuer issuer = new InternalTokenIssuer(keys.getPrivate(), "k1", 300);
    private final InternalTokenVerifier verifier = new InternalTokenVerifier(Map.of("k1", keys.getPublic()), 60);

    private AiJobRepository repository;
    private AiIndexJobStore store;
    private List<Object> published;
    private AiJobService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = mock(AiJobRepository.class);
        store = mock(AiIndexJobStore.class);
        published = new ArrayList<>();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        service = new AiJobService(repository, new AiJobProperties(true, 3, 60),
                meters, json, published::add, CLOCK, store);
        AiTaskStreamPublisher publisher = mock(AiTaskStreamPublisher.class);
        mvc = MockMvcBuilders.standaloneSetup(new InternalAiTaskController(service, publisher))
                .addFilters(new InternalAiTokenFilter(verifier, json))
                .build();
    }

    @Test
    void indexDocumentSnapshotReturnsBodyMetadataOr404WhenMissing() throws Exception {
        AiJobRecord running = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.RUNNING, DOC_ID, null, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(running));
        when(store.findIndexDocumentSnapshot(PROJECT_ID, DOC_ID)).thenReturn(Optional.of(
                new AiIndexJobStore.IndexDocumentBodyInfo(
                        PROJECT_ID, DOC_ID, SNAPSHOT_ID, "tika-page-clean-v2/chunk-offset-v2", 0)));

        String token = issuer.issueIndexTaskToken(JOB_ID, 0L, PROJECT_ID, DOC_ID);
        JsonNode snap = json(mvc.perform(get("/internal/ai/tasks/" + JOB_ID + "/snapshot")
                .header("Authorization", "Bearer " + token)).andReturn(), 200);
        assertThat(snap.path("schemaVersion").asInt()).isOne();
        assertThat(snap.path("taskId").asText()).isEqualTo(JOB_ID.toString());
        assertThat(snap.path("taskType").asText()).isEqualTo("INDEX_DOCUMENT");
        assertThat(snap.path("projectId").asText()).isEqualTo(PROJECT_ID.toString());
        assertThat(snap.path("documentId").asText()).isEqualTo(DOC_ID.toString());
        assertThat(snap.has("generationId")).isTrue();
        assertThat(snap.get("generationId").isNull()).isTrue();
        assertThat(snap.path("bodySnapshotId").asText()).isEqualTo(SNAPSHOT_ID.toString());
        assertThat(snap.path("bodyChunkCount").asInt()).isZero();

        when(store.findIndexDocumentSnapshot(PROJECT_ID, DOC_ID)).thenReturn(Optional.empty());
        JsonNode notFound = json(mvc.perform(get("/internal/ai/tasks/" + JOB_ID + "/snapshot")
                .header("Authorization", "Bearer " + token)).andReturn(), 404);
        assertThat(notFound.path("code").asText()).isEqualTo("NOT_FOUND");
    }

    @Test
    void indexDocumentResultValidatesSnapshotAndRowCountAndUpdatesRegularUploadOnly() throws Exception {
        AiJobRecord regular = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.RUNNING, DOC_ID, null, null);
        AiJobRecord succeeded = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.SUCCEEDED, DOC_ID, null, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(regular));
        when(repository.casTransition(any(), eq(AiJobStatus.SUCCEEDED), eq(0L), any(), any(), any()))
                .thenReturn(Optional.of(succeeded));
        when(store.findDocumentBodyState(DOC_ID)).thenReturn(Optional.of(
                new AiIndexJobStore.DocumentBodyState(PROJECT_ID, "INDEXING", false, SNAPSHOT_ID, 0)));

        String token = issuer.issueIndexTaskToken(JOB_ID, 0L, PROJECT_ID, DOC_ID);
        String okBody = """
                {"epoch":0,"leaseToken":"%s","payload":{
                  "documentId":"%s","bodySnapshotId":"%s",
                  "generations":[{"generationId":"%s","indexedRows":0,"embeddedRows":0}],
                  "embeddingModel":"qwen3-embedding:0.6b","dimension":1024}}
                """.formatted(LEASE, DOC_ID, SNAPSHOT_ID, GEN_ID);

        JsonNode res = json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/result")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(okBody)).andReturn(), 200);
        assertThat(res.path("status").asText()).isEqualTo("SUCCEEDED");
        verify(store).markDocumentReadyFromIndex(PROJECT_ID, DOC_ID, "qwen3-embedding:0.6b", 1024, 0, NOW);

        // 重建子任务只置任务 SUCCEEDED，不改文档状态
        setUp();
        AiJobRecord child = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.RUNNING, DOC_ID, GEN_ID, PARENT_ID);
        AiJobRecord childDone = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.SUCCEEDED,
                DOC_ID, GEN_ID, PARENT_ID);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(child));
        when(repository.casTransition(any(), eq(AiJobStatus.SUCCEEDED), eq(0L), any(), any(), any()))
                .thenReturn(Optional.of(childDone));
        when(store.findDocumentBodyState(DOC_ID)).thenReturn(Optional.of(
                new AiIndexJobStore.DocumentBodyState(PROJECT_ID, "READY", false, SNAPSHOT_ID, 12)));
        String childBody = """
                {"epoch":0,"leaseToken":"%s","payload":{
                  "documentId":"%s","bodySnapshotId":"%s",
                  "generations":[{"generationId":"%s","indexedRows":12,"embeddedRows":10}],
                  "embeddingModel":"qwen3-embedding:0.6b","dimension":1024}}
                """.formatted(LEASE, DOC_ID, SNAPSHOT_ID, GEN_ID);
        json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/result")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(childBody)).andReturn(), 200);
        verify(store, never()).markDocumentReadyFromIndex(any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void indexDocumentResultRejectsStaleSnapshotWith409AndRowMismatchWith422() throws Exception {
        AiJobRecord running = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.RUNNING, DOC_ID, null, null);
        AiJobRecord failed = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.FAILED, DOC_ID, null, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(running));
        when(repository.casTransition(any(), eq(AiJobStatus.FAILED), eq(0L), eq("INDEX_SNAPSHOT_STALE"), any(), any()))
                .thenReturn(Optional.of(failed));
        when(store.findDocumentBodyState(DOC_ID)).thenReturn(Optional.of(
                new AiIndexJobStore.DocumentBodyState(PROJECT_ID, "INDEXING", false, UUID.randomUUID(), 10)));

        String token = issuer.issueIndexTaskToken(JOB_ID, 0L, PROJECT_ID, DOC_ID);
        String body = """
                {"epoch":0,"leaseToken":"%s","payload":{
                  "documentId":"%s","bodySnapshotId":"%s",
                  "generations":[{"generationId":"%s","indexedRows":10,"embeddedRows":8}],
                  "embeddingModel":"qwen3-embedding:0.6b","dimension":1024}}
                """.formatted(LEASE, DOC_ID, SNAPSHOT_ID, GEN_ID);

        JsonNode stale = json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/result")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 409);
        assertThat(stale.path("code").asText()).isEqualTo("INDEX_SNAPSHOT_STALE");
        verify(store, never()).markDocumentFailedFromIndex(any(), any(), any(), any());

        // 行数不符返回 422
        setUp();
        when(repository.find(JOB_ID)).thenReturn(Optional.of(running));
        when(store.findDocumentBodyState(DOC_ID)).thenReturn(Optional.of(
                new AiIndexJobStore.DocumentBodyState(PROJECT_ID, "INDEXING", false, SNAPSHOT_ID, 11)));
        JsonNode mismatch = json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/result")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 422);
        assertThat(mismatch.path("code").asText()).isEqualTo("INVALID_REQUEST");
        verify(repository, never()).casTransition(any(), any(), eq(0L), any(), any(), any());
    }

    @Test
    void indexDocumentTerminalFailMarksRegularUploadFailedWithFixedMessage() throws Exception {
        AiJobRecord running = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.RUNNING, DOC_ID, null, null);
        AiJobRecord failed = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.FAILED, DOC_ID, null, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(running));
        when(repository.casTransition(any(), eq(AiJobStatus.FAILED), eq(0L), eq("EMBEDDING_DIMENSION_MISMATCH"), any(), any()))
                .thenReturn(Optional.of(failed));

        String token = issuer.issueIndexTaskToken(JOB_ID, 0L, PROJECT_ID, DOC_ID);
        String failBody = """
                {"epoch":0,"leaseToken":"%s","errorCode":"EMBEDDING_DIMENSION_MISMATCH","retryable":false}
                """.formatted(LEASE);

        json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/fail")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(failBody)).andReturn(), 200);
        verify(store).markDocumentFailedFromIndex(
                PROJECT_ID, DOC_ID, AiJobService.INDEX_DOCUMENT_FAILED_MESSAGE, NOW);

        // 可重试失败不改文档状态
        setUp();
        AiJobRecord retryable = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.FAILED_RETRYABLE,
                DOC_ID, null, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(running));
        when(repository.casTransition(any(), eq(AiJobStatus.FAILED_RETRYABLE), eq(0L), any(), any(), any()))
                .thenReturn(Optional.of(retryable));
        json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/fail")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"epoch\":0,\"leaseToken\":\"" + LEASE + "\",\"errorCode\":\"INDEX_GENERATION_UNAVAILABLE\",\"retryable\":true}"))
                .andReturn(), 200);
        verify(store, never()).markDocumentFailedFromIndex(any(), any(), any(), any());

        // /fail 回报 INDEX_SNAPSHOT_STALE 时任务进 FAILED，但严禁把文档置 FAILED（保护新正文）
        setUp();
        AiJobRecord staleFailed = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.FAILED, DOC_ID, null, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(running));
        when(repository.casTransition(any(), eq(AiJobStatus.FAILED), eq(0L), eq("INDEX_SNAPSHOT_STALE"), any(), any()))
                .thenReturn(Optional.of(staleFailed));
        JsonNode staleFailRes = json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/fail")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"epoch\":0,\"leaseToken\":\"" + LEASE + "\",\"errorCode\":\"INDEX_SNAPSHOT_STALE\",\"retryable\":false}"))
                .andReturn(), 200);
        assertThat(staleFailRes.path("status").asText()).isEqualTo("FAILED");
        verify(store, never()).markDocumentFailedFromIndex(any(), any(), any(), any());

        // result / fail 同样校验请求体 epoch 与令牌 claims.epoch() 一致
        String wrongEpochResult = "{\"epoch\":1,\"leaseToken\":\"" + LEASE + "\",\"payload\":{}}";
        assertThat(json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/result")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(wrongEpochResult)).andReturn(), 409)
                .path("code").asText()).isEqualTo("TASK_EPOCH_STALE");
        String wrongEpochFail = "{\"epoch\":1,\"leaseToken\":\"" + LEASE + "\",\"errorCode\":\"ERR\",\"retryable\":false}";
        assertThat(json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/fail")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(wrongEpochFail)).andReturn(), 409)
                .path("code").asText()).isEqualTo("TASK_EPOCH_STALE");
    }

    @Test
    void deleteDocumentIndexSnapshotAndIdempotentResult() throws Exception {
        AiJobRecord running = job(AiJobTypes.DELETE_DOCUMENT_INDEX, PROJECT_ID, AiJobStatus.RUNNING, DOC_ID, null, null);
        AiJobRecord done = job(AiJobTypes.DELETE_DOCUMENT_INDEX, PROJECT_ID, AiJobStatus.SUCCEEDED, DOC_ID, null, null);
        when(repository.find(JOB_ID)).thenReturn(
                Optional.of(running), Optional.of(running), Optional.of(running),
                Optional.of(done), Optional.of(done));
        when(repository.casTransition(any(), eq(AiJobStatus.SUCCEEDED), eq(0L), any(), any(), any()))
                .thenReturn(Optional.of(done));

        String token = issuer.issueIndexTaskToken(JOB_ID, 0L, PROJECT_ID, null);
        JsonNode snap = json(mvc.perform(get("/internal/ai/tasks/" + JOB_ID + "/snapshot")
                .header("Authorization", "Bearer " + token)).andReturn(), 200);
        assertThat(snap.path("taskType").asText()).isEqualTo("DELETE_DOCUMENT_INDEX");
        assertThat(snap.path("projectId").asText()).isEqualTo(PROJECT_ID.toString());
        assertThat(snap.path("documentId").asText()).isEqualTo(DOC_ID.toString());

        String body = "{\"epoch\":0,\"leaseToken\":\"" + LEASE + "\",\"payload\":{\"documentId\":\"" + DOC_ID + "\",\"deletedRows\":12}}";
        json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/result")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 200);
        json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/result")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 200);
        verify(repository).finishAttempt(JOB_ID, 1, AiJobAttemptStatus.SUCCEEDED, null, NOW);
    }

    @Test
    void reindexGenerationSnapshotAndResultFansOutReadyDocuments() throws Exception {
        AiJobRecord running = job(AiJobTypes.REINDEX_GENERATION, null, AiJobStatus.RUNNING, null, null, null);
        AiJobRecord fannedOut = new AiJobRecord(JOB_ID, null, AiJobTypes.REINDEX_GENERATION, AiJobStatus.SUCCEEDED,
                1, "{}", 3, 1, 0L, LEASE, null, null, null, null, null, NOW, NOW, 3,
                null, GEN_ID, null, AiJobTypes.INDEX_PHASE_FANNED_OUT);
        AiJobRecord child1 = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.QUEUED, DOC_ID, GEN_ID, JOB_ID);
        AiJobRecord child2 = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.QUEUED, DOC_ID_2, GEN_ID, JOB_ID);

        when(repository.find(JOB_ID)).thenReturn(Optional.of(running));
        when(repository.casCompleteReindex(running, 0L, GEN_ID, AiJobTypes.INDEX_PHASE_FANNED_OUT, NOW))
                .thenReturn(Optional.of(fannedOut));
        when(store.listReadyDocuments(null, AiIndexJobStore.PRUNE_BATCH_SIZE)).thenReturn(List.of(
                new AiIndexJobStore.DocumentRef(DOC_ID, PROJECT_ID),
                new AiIndexJobStore.DocumentRef(DOC_ID_2, PROJECT_ID)));
        when(repository.createIndexJob(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, DOC_ID, GEN_ID, JOB_ID, 3))
                .thenReturn(child1);
        when(repository.createIndexJob(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, DOC_ID_2, GEN_ID, JOB_ID, 3))
                .thenReturn(child2);

        String token = issuer.issueIndexTaskToken(JOB_ID, 0L, null, null);
        JsonNode snap = json(mvc.perform(get("/internal/ai/tasks/" + JOB_ID + "/snapshot")
                .header("Authorization", "Bearer " + token)).andReturn(), 200);
        assertThat(snap.path("taskId").asText()).isEqualTo(JOB_ID.toString());
        assertThat(snap.path("taskType").asText()).isEqualTo("REINDEX_GENERATION");
        assertThat(snap.has("projectId")).isFalse();

        String body = """
                {"epoch":0,"leaseToken":"%s","payload":{
                  "generationId":"%s","embeddingModel":"qwen3-embedding:0.6b","dimension":1024}}
                """.formatted(LEASE, GEN_ID);
        JsonNode res = json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/result")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 200);
        assertThat(res.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(published).containsExactly(new AiJobQueuedEvent(child1), new AiJobQueuedEvent(child2));
    }

    @Test
    void compensationExhaustionAndDeadlineMarkRegularUploadDocumentFailedOnly() {
        AiJobRecord exhaustedRegular = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.RUNNING,
                DOC_ID, null, null);
        AiJobRecord expiredRegular = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.QUEUED,
                DOC_ID_2, null, null);
        AiJobRecord failedRegular = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.FAILED,
                DOC_ID, null, null);
        AiJobRecord deadlineFailed = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.EXPIRED,
                DOC_ID_2, null, null);
        AiJobRecord exhaustedChild = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.RUNNING,
                DOC_ID, GEN_ID, PARENT_ID);
        AiJobRecord failedChild = job(AiJobTypes.INDEX_DOCUMENT, PROJECT_ID, AiJobStatus.FAILED,
                DOC_ID, GEN_ID, PARENT_ID);
        // attemptCount=3 == maxAttempts=3
        AiJobRecord exhaustedRegularAtMax = new AiJobRecord(
                exhaustedRegular.id(), PROJECT_ID, AiJobTypes.INDEX_DOCUMENT, AiJobStatus.RUNNING,
                1, "{}", 3, 3, 0L, LEASE, NOW.minusSeconds(10), null, null, null, null, NOW, NOW, 2,
                DOC_ID, null, null, null);
        AiJobRecord exhaustedChildAtMax = new AiJobRecord(
                UUID.randomUUID(), PROJECT_ID, AiJobTypes.INDEX_DOCUMENT, AiJobStatus.RUNNING,
                1, "{}", 3, 3, 0L, LEASE, NOW.minusSeconds(10), null, null, null, null, NOW, NOW, 2,
                DOC_ID, GEN_ID, PARENT_ID, null);

        when(repository.findDeadlineExceeded(NOW, 100)).thenReturn(List.of(expiredRegular));
        when(repository.findLeaseExpired(NOW, 100)).thenReturn(List.of(exhaustedRegularAtMax, exhaustedChildAtMax));
        when(repository.findRetryDue(NOW, 100)).thenReturn(List.of());
        when(repository.findStaleQueued(any(), eq(NOW), eq(100))).thenReturn(List.of());
        when(repository.casFence(expiredRegular, AiJobStatus.EXPIRED, "DEADLINE_EXCEEDED", NOW))
                .thenReturn(Optional.of(deadlineFailed));
        when(repository.casFence(exhaustedRegularAtMax, AiJobStatus.FAILED, "LEASE_EXPIRED", NOW))
                .thenReturn(Optional.of(failedRegular));
        when(repository.casFence(exhaustedChildAtMax, AiJobStatus.FAILED, "LEASE_EXPIRED", NOW))
                .thenReturn(Optional.of(failedChild));

        AiJobService.CompensationSummary summary = service.compensate(NOW, 100);

        assertThat(summary.expired()).isOne();
        assertThat(summary.exhausted()).isEqualTo(2);
        verify(store).markDocumentFailedFromIndex(PROJECT_ID, DOC_ID, AiJobService.INDEX_DOCUMENT_FAILED_MESSAGE, NOW);
        verify(store).markDocumentFailedFromIndex(PROJECT_ID, DOC_ID_2, AiJobService.INDEX_DOCUMENT_FAILED_MESSAGE, NOW);
    }

    @Test
    void memoryExtractionResultThroughControllerStillWorksAndValidatesSchema() throws Exception {
        AiJobRecord memoryJob = job(AiJobTypes.MEMORY_EXTRACTION, PROJECT_ID, AiJobStatus.RUNNING, null, null, null);
        AiJobRecord succeeded = job(AiJobTypes.MEMORY_EXTRACTION, PROJECT_ID, AiJobStatus.SUCCEEDED, null, null, null);
        when(repository.find(JOB_ID)).thenReturn(Optional.of(memoryJob));
        String snapshotPayload = "{\"jobId\":\"" + JOB_ID + "\",\"projectId\":\"" + PROJECT_ID
                + "\",\"attempt\":1,\"leaseEpoch\":0,\"facts\":[{\"ref\":\"fact:project\",\"kind\":\"PROJECT\",\"text\":\"x\"}],\"existingMemories\":[]}";
        String hash = "sha256:" + hexSha256Prefix32(snapshotPayload);
        when(repository.findAttemptSnapshot(JOB_ID, 1, 0L)).thenReturn(Optional.of(
                new AiJobRepository.AttemptSnapshot(JOB_ID, PROJECT_ID, 1, 0L, hash, "[\"fact:project\"]", snapshotPayload)));
        when(repository.appendResult(eq(JOB_ID), eq(1), eq("MEMORY_CANDIDATE"), eq("a".repeat(64)),
                any(), eq(hash), eq("[\"fact:project\"]"), eq(NOW)))
                .thenReturn(new AiJobRepository.ResultWrite(true, new AiJobResultRecord(
                        UUID.randomUUID(), JOB_ID, 1, "MEMORY_CANDIDATE", "a".repeat(64), "{}",
                        "PENDING_REVIEW", hash, "[\"fact:project\"]", NOW)));
        when(repository.casTransition(any(), eq(AiJobStatus.SUCCEEDED), eq(0L), any(), any(), any()))
                .thenReturn(Optional.of(succeeded));

        String token = issuer.issueTaskToken(JOB_ID, 0L, UUID.randomUUID(), PROJECT_ID, List.of("MEMORY_EXTRACTION"));
        String okMemory = """
                {"epoch":0,"leaseToken":"%s","payload":{"schemaVersion":1,"candidates":[
                  {"kind":"DECISION","title":"t","content":"c","confidence":0.8,"sourceRef":"fact:project","dedupeKey":"%s"}]}}
                """.formatted(LEASE, "a".repeat(64));
        JsonNode res = json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/result")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(okMemory)).andReturn(), 200);
        assertThat(res.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(res.path("accepted").asInt()).isOne();

        String badSchema = "{\"epoch\":0,\"leaseToken\":\"" + LEASE + "\",\"payload\":{\"schemaVersion\":2,\"candidates\":[]}}";
        JsonNode err = json(mvc.perform(post("/internal/ai/tasks/" + JOB_ID + "/result")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(badSchema)).andReturn(), 422);
        assertThat(err.path("code").asText()).isEqualTo("SCHEMA_VIOLATION");
    }

    private static String hexSha256Prefix32(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(expectedStatus);
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static AiJobRecord job(String type, UUID projectId, AiJobStatus status,
            UUID docId, UUID genId, UUID parentId) {
        return new AiJobRecord(JOB_ID, projectId, type, status, 1, "{}", 3, 1, 0L, LEASE,
                NOW.plusSeconds(60), null, null, null, null, NOW.minusSeconds(60), NOW, 2,
                docId, genId, parentId, null);
    }

    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
