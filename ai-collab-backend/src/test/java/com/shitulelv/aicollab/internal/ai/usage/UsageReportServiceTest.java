package com.shitulelv.aicollab.internal.ai.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.internal.ai.InternalAiClaims;
import com.shitulelv.aicollab.internal.ai.InternalAiTokenFilter;
import com.shitulelv.aicollab.internal.ai.credential.CredentialLeaseRepository;
import com.shitulelv.aicollab.internal.ai.credential.LeasePurpose;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用量回报（契约 §3、R1 §9.5）的非 Docker 单元测试：逐条结果、lease 身份校验、caller_type、
 * errorCode 原样落库、整批 400。真库幂等（ON CONFLICT）见 {@code UsageReportIntegrationTest}（需要 Docker）。
 */
class UsageReportServiceTest {

    private static final long NOW = 1_800_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID LEASE = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID FOREIGN_LEASE = UUID.fromString("77777777-7777-7777-7777-777777777777");

    private CredentialLeaseRepository leases;
    private UsageReportRepository usage;
    private UsageReportService service;

    @BeforeEach
    void setUp() {
        leases = mock(CredentialLeaseRepository.class);
        usage = mock(UsageReportRepository.class);
        service = new UsageReportService(leases, usage, new SimpleMeterRegistry());
        when(leases.find(LEASE)).thenReturn(Optional.of(lease(LEASE, USER, PROJECT)));
        when(leases.find(FOREIGN_LEASE)).thenReturn(Optional.of(lease(FOREIGN_LEASE, UUID.randomUUID(), PROJECT)));
        when(usage.insertIfAbsent(any())).thenReturn(true);
    }

    @Test
    void everyCallIdGetsAResultAndOnlyOwnLeasesAreAccepted() {
        String ok = UUID.randomUUID().toString();
        String foreign = UUID.randomUUID().toString();
        String missing = UUID.randomUUID().toString();
        String badStatus = UUID.randomUUID().toString();
        List<UsageReportService.Result> results = service.report(List.of(
                item(ok, LEASE, "SUCCESS", null),
                item(foreign, FOREIGN_LEASE, "SUCCESS", null),
                item(missing, UUID.randomUUID(), "SUCCESS", null),
                item(badStatus, LEASE, "WEIRD", null),
                item("not-a-uuid", LEASE, "SUCCESS", null)), claims());

        assertThat(results).extracting(UsageReportService.Result::callId)
                .containsExactly(ok, foreign, missing, badStatus, "not-a-uuid");
        assertThat(results).extracting(UsageReportService.Result::accepted)
                .containsExactly(true, false, false, false, false);
        assertThat(results.get(1).reason()).isEqualTo("lease_identity_mismatch");
        assertThat(results.get(2).reason()).isEqualTo("lease_not_found");
        verify(usage, times(1)).insertIfAbsent(any());
    }

    @Test
    void writesPythonCallerFeatureProviderAndRawErrorCode() {
        String callId = UUID.randomUUID().toString();
        service.report(List.of(item(callId, LEASE, "PROVIDER_ERROR", "provider_rate_limited")), claims());

        ArgumentCaptor<UsageReportRepository.UsageRow> row = ArgumentCaptor.forClass(UsageReportRepository.UsageRow.class);
        verify(usage).insertIfAbsent(row.capture());
        assertThat(row.getValue().callId()).isEqualTo(UUID.fromString(callId));
        assertThat(row.getValue().leaseId()).isEqualTo(LEASE);
        assertThat(row.getValue().feature()).isEqualTo("MEMORY_EXTRACTION");
        assertThat(row.getValue().provider()).isEqualTo("ANTHROPIC");
        assertThat(row.getValue().errorCode()).isEqualTo("provider_rate_limited");
        assertThat(row.getValue().status()).isEqualTo("PROVIDER_ERROR");
        assertThat(row.getValue().inputTokens()).isEqualTo(12);
        assertThat(UsageReportRepository.CALLER_TYPE_PYTHON).isEqualTo("PYTHON");
    }

    @Test
    void duplicateCallIdIsStillAccepted() {
        when(usage.insertIfAbsent(any())).thenReturn(false);
        List<UsageReportService.Result> results = service.report(
                List.of(item(UUID.randomUUID().toString(), LEASE, "CANCELED", null)), claims());
        assertThat(results.getFirst().accepted()).isTrue();
        assertThat(results.getFirst().reason()).isNull();
    }

    @Test
    void tokenWithoutUsageScopeRejectsEveryItemWithoutWriting() {
        InternalAiClaims noScope = new InternalAiClaims(USER, PROJECT, Set.of(), null, null, Set.of("plan"));
        List<UsageReportService.Result> results = service.report(
                List.of(item(UUID.randomUUID().toString(), LEASE, "SUCCESS", null)), noScope);
        assertThat(results).singleElement().satisfies(result -> assertThat(result.accepted()).isFalse());
        verify(usage, never()).insertIfAbsent(any());
    }

    @Test
    void itemWithoutCallIdRejectsWholeBatchWithoutWriting() {
        for (String missingCallId : java.util.Arrays.asList(null, "", "   ")) {
            List<UsageReportService.Item> batch = List.of(
                    item(UUID.randomUUID().toString(), LEASE, "SUCCESS", null),
                    item(missingCallId, LEASE, "SUCCESS", null));
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.report(batch, claims()))
                    .isInstanceOf(UsageReportService.BatchRejectedException.class)
                    .extracting(failure -> ((UsageReportService.BatchRejectedException) failure).reason())
                    .isEqualTo("call_id_missing");
        }
        verify(usage, never()).insertIfAbsent(any());
    }

    @Test
    void controllerReturnsResultsEnvelopeAndRejectsMalformedBatches() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        InternalTokenIssuer issuer = new InternalTokenIssuer(keyPair.getPrivate(), "k1", 300, CLOCK);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UsageReportController(service))
                .addFilters(new InternalAiTokenFilter(
                        new InternalTokenVerifier(Map.of("k1", keyPair.getPublic()), 60, CLOCK), new ObjectMapper()))
                .build();
        String token = "Bearer " + issuer.issueQueryPlanToken(USER, PROJECT);
        String callId = UUID.randomUUID().toString();
        String body = "{\"items\":[{\"callId\":\"" + callId + "\",\"leaseId\":\"" + LEASE + "\","
                + "\"purpose\":\"QUERY_PLAN\",\"model\":\"m\",\"inputTokens\":1,\"outputTokens\":2,"
                + "\"latencyMs\":3,\"status\":\"SUCCESS\",\"errorCode\":null}]}";

        mvc.perform(post("/internal/ai/usage").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].callId").value(callId))
                .andExpect(jsonPath("$.results[0].accepted").value(true))
                .andExpect(jsonPath("$.results[0].reason").doesNotExist());

        List<String> oversized = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            oversized.add("{\"callId\":\"" + UUID.randomUUID() + "\",\"leaseId\":\"" + LEASE + "\",\"status\":\"SUCCESS\"}");
        }
        for (String bad : List.of("{}", "{\"items\":[]}", "not json", "{\"items\":[null]}",
                "{\"items\":[" + String.join(",", oversized) + "]}",
                "{\"items\":[{\"leaseId\":\"" + LEASE + "\",\"status\":\"SUCCESS\"}]}",
                "{\"items\":[{\"callId\":null,\"leaseId\":\"" + LEASE + "\",\"status\":\"SUCCESS\"}]}",
                "{\"items\":[{\"callId\":\"" + UUID.randomUUID() + "\",\"leaseId\":\"" + LEASE
                        + "\",\"status\":\"SUCCESS\"},{\"callId\":\"\",\"leaseId\":\"" + LEASE
                        + "\",\"status\":\"SUCCESS\"}]}")) {
            mvc.perform(post("/internal/ai/usage").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void indexDocumentAndReindexGenerationSystemEmbeddingTasksAreAcceptedWithoutUserIdOrProjectId()
            throws Exception {
        UUID indexTask = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID reindexTask = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID docId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID indexLeaseId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        UUID reindexLeaseId = UUID.fromString("99999999-9999-9999-9999-999999999999");

        OffsetDateTime issued = OffsetDateTime.now(CLOCK);
        when(leases.find(indexLeaseId)).thenReturn(Optional.of(new CredentialLeaseRepository.LeaseRecord(
                indexLeaseId, null, PROJECT, LeasePurpose.EMBEDDING, UUID.randomUUID(),
                "OLLAMA", "qwen3-embedding:0.6b", indexTask, null, issued, issued.plusSeconds(300))));
        when(leases.find(reindexLeaseId)).thenReturn(Optional.of(new CredentialLeaseRepository.LeaseRecord(
                reindexLeaseId, null, null, LeasePurpose.EMBEDDING, UUID.randomUUID(),
                "OLLAMA", "qwen3-embedding:0.6b", reindexTask, null, issued, issued.plusSeconds(300))));

        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        InternalTokenIssuer issuer = new InternalTokenIssuer(keyPair.getPrivate(), "k1", 300, CLOCK);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UsageReportController(service))
                .addFilters(new InternalAiTokenFilter(
                        new InternalTokenVerifier(Map.of("k1", keyPair.getPublic()), 60, CLOCK), new ObjectMapper()))
                .build();

        String indexCallId = UUID.randomUUID().toString();
        String indexToken = "Bearer " + issuer.issueIndexTaskToken(indexTask, 0L, PROJECT, docId);
        mvc.perform(post("/internal/ai/usage").header("Authorization", indexToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"callId\":\"" + indexCallId + "\",\"leaseId\":\"" + indexLeaseId
                                + "\",\"purpose\":\"EMBEDDING\",\"model\":\"qwen3-embedding:0.6b\","
                                + "\"inputTokens\":25,\"outputTokens\":0,\"latencyMs\":12,\"status\":\"SUCCESS\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].accepted").value(true));

        String reindexCallId = UUID.randomUUID().toString();
        String reindexToken = "Bearer " + issuer.issueIndexTaskToken(reindexTask, 1L, null, null);
        mvc.perform(post("/internal/ai/usage").header("Authorization", reindexToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"callId\":\"" + reindexCallId + "\",\"leaseId\":\"" + reindexLeaseId
                                + "\",\"purpose\":\"EMBEDDING\",\"model\":\"qwen3-embedding:0.6b\","
                                + "\"inputTokens\":50,\"outputTokens\":0,\"latencyMs\":18,\"status\":\"SUCCESS\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].accepted").value(true));

        ArgumentCaptor<UsageReportRepository.UsageRow> rows =
                ArgumentCaptor.forClass(UsageReportRepository.UsageRow.class);
        verify(usage, times(2)).insertIfAbsent(rows.capture());
        assertThat(rows.getAllValues().get(0).userId()).isNull();
        assertThat(rows.getAllValues().get(0).projectId()).isEqualTo(PROJECT);
        assertThat(rows.getAllValues().get(0).feature()).isEqualTo("EMBEDDING");
        assertThat(rows.getAllValues().get(1).userId()).isNull();
        assertThat(rows.getAllValues().get(1).projectId()).isNull();
        assertThat(rows.getAllValues().get(1).feature()).isEqualTo("EMBEDDING");
    }

    @Test
    void systemTaskIdentityRejectsNonEmbeddingMismatchedTaskOrMissingTaskIdWithoutNpe() {
        UUID task = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID otherTask = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID nonEmbeddingLease = UUID.randomUUID();
        UUID wrongTaskLease = UUID.randomUUID();
        UUID nullTaskLease = UUID.randomUUID();
        UUID wrongProjectLease = UUID.randomUUID();
        UUID nonNullUserLease = UUID.randomUUID();

        OffsetDateTime issued = OffsetDateTime.now(CLOCK);
        when(leases.find(nonEmbeddingLease)).thenReturn(Optional.of(new CredentialLeaseRepository.LeaseRecord(
                nonEmbeddingLease, null, PROJECT, LeasePurpose.RERANK, UUID.randomUUID(),
                "OPENAI_COMPATIBLE", "rerank-1", task, null, issued, issued.plusSeconds(300))));
        when(leases.find(wrongTaskLease)).thenReturn(Optional.of(new CredentialLeaseRepository.LeaseRecord(
                wrongTaskLease, null, PROJECT, LeasePurpose.EMBEDDING, UUID.randomUUID(),
                "OLLAMA", "qwen3", otherTask, null, issued, issued.plusSeconds(300))));
        when(leases.find(nullTaskLease)).thenReturn(Optional.of(new CredentialLeaseRepository.LeaseRecord(
                nullTaskLease, null, PROJECT, LeasePurpose.EMBEDDING, UUID.randomUUID(),
                "OLLAMA", "qwen3", null, null, issued, issued.plusSeconds(300))));
        when(leases.find(wrongProjectLease)).thenReturn(Optional.of(new CredentialLeaseRepository.LeaseRecord(
                wrongProjectLease, null, null, LeasePurpose.EMBEDDING, UUID.randomUUID(),
                "OLLAMA", "qwen3", task, null, issued, issued.plusSeconds(300))));
        when(leases.find(nonNullUserLease)).thenReturn(Optional.of(new CredentialLeaseRepository.LeaseRecord(
                nonNullUserLease, USER, PROJECT, LeasePurpose.EMBEDDING, UUID.randomUUID(),
                "OLLAMA", "qwen3", task, null, issued, issued.plusSeconds(300))));

        InternalAiClaims indexClaims = new InternalAiClaims(
                null, PROJECT, Set.of("EMBEDDING"), task, 0L, Set.of("usage:report"));
        List<UsageReportService.Result> mismatchResults = service.report(List.of(
                item(UUID.randomUUID().toString(), nonEmbeddingLease, "SUCCESS", null),
                item(UUID.randomUUID().toString(), wrongTaskLease, "SUCCESS", null),
                item(UUID.randomUUID().toString(), nullTaskLease, "SUCCESS", null),
                item(UUID.randomUUID().toString(), wrongProjectLease, "SUCCESS", null),
                item(UUID.randomUUID().toString(), nonNullUserLease, "SUCCESS", null)), indexClaims);
        assertThat(mismatchResults).hasSize(5).allSatisfy(result -> {
            assertThat(result.accepted()).isFalse();
            assertThat(result.reason()).isEqualTo("lease_identity_mismatch");
        });

        // Bounded token against a null-user system lease must also fail safely without NPE
        List<UsageReportService.Result> boundedAgainstSystem = service.report(
                List.of(item(UUID.randomUUID().toString(), wrongProjectLease, "SUCCESS", null)), claims());
        assertThat(boundedAgainstSystem).singleElement().satisfies(result -> {
            assertThat(result.accepted()).isFalse();
            assertThat(result.reason()).isEqualTo("lease_identity_mismatch");
        });

        // Missing tid when uid is null -> token_identity_missing
        InternalAiClaims missingTask = new InternalAiClaims(
                null, PROJECT, Set.of("EMBEDDING"), null, 0L, Set.of("usage:report"));
        List<UsageReportService.Result> missingTidResults = service.report(
                List.of(item(UUID.randomUUID().toString(), LEASE, "SUCCESS", null)), missingTask);
        assertThat(missingTidResults).singleElement().satisfies(result -> {
            assertThat(result.accepted()).isFalse();
            assertThat(result.reason()).isEqualTo("token_identity_missing");
        });
        verify(usage, never()).insertIfAbsent(any());
    }

    private static UsageReportService.Item item(String callId, UUID leaseId, String status, String errorCode) {
        return new UsageReportService.Item(callId, leaseId.toString(), "MEMORY_EXTRACTION", "model-x",
                12L, 34L, 56L, status, errorCode);
    }

    private static InternalAiClaims claims() {
        return new InternalAiClaims(USER, PROJECT, Set.of("MEMORY_EXTRACTION"), null, null,
                Set.of("usage:report", "credentials:lease"));
    }

    private static CredentialLeaseRepository.LeaseRecord lease(UUID id, UUID user, UUID project) {
        OffsetDateTime issued = OffsetDateTime.now(CLOCK);
        return new CredentialLeaseRepository.LeaseRecord(id, user, project, LeasePurpose.MEMORY_EXTRACTION,
                UUID.randomUUID(), "ANTHROPIC", "model-x", null, null, issued, issued.plusSeconds(300));
    }
}
