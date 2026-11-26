package com.shitulelv.aicollab.internal.ai.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.job.AiJobService;
import com.shitulelv.aicollab.agent.job.AiJobStatus;
import com.shitulelv.aicollab.internal.ai.InternalAiTokenFilter;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内部 Job 端点测试（MockMvc + 真实 {@link InternalAiTokenFilter}）：
 * 缺失令牌 401、验签材料为空时 job 端点 503、claims 与路径/请求不一致 403、
 * 正常 snapshot/heartbeat/results，以及 STALE_LEASE 409 映射。
 */
class InternalAiJobControllerTest {

    private static final UUID JOB_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID PROJECT_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID MEMORY_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final int ATTEMPT = 2;
    private static final long LEASE_EPOCH = 7L;
    private static final String DEDUPE = "a".repeat(64);
    private static final String OPAQUE_TOKEN = "test-only-opaque-token-w5s2";
    private static final long NOW = 1_762_900_100L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AiJobService service;

    @BeforeEach
    void setUp() {
        service = mock(AiJobService.class);
    }

    // ------------------------------------------------------------------
    // 鉴权矩阵
    // ------------------------------------------------------------------

    @Test
    void missingTokenIs401ForJobEndpoints() throws Exception {
        mvc(ed25519()).perform(get("/internal/ai/v1/jobs/{id}/snapshot", JOB_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void emptyKeyRingJobEndpointsFailClosedWith503() throws Exception {
        mvc(new InternalTokenVerifier(Map.of(), 60, CLOCK)).perform(get("/internal/ai/v1/jobs/{id}/snapshot", JOB_ID)
                        .header("Authorization", "Bearer " + OPAQUE_TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MODEL_UNAVAILABLE"));
        verifyNoInteractions(service);
    }

    @Test
    void serviceTokenWithoutJobClaimsIs403() throws Exception {
        InternalTokenIssuer issuer = new InternalTokenIssuer(TOKEN_KEYS.privateKey(), "k1", 900, CLOCK);
        String serviceToken = issuer.issue(List.of("ai-worker", "backend"),
                List.of("job:snapshot"));

        mvc(ed25519()).perform(get("/internal/ai/v1/jobs/{id}/snapshot", JOB_ID)
                        .header("Authorization", "Bearer " + serviceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void claimsJobIdMismatchIs403() throws Exception {
        String token = jobToken(UUID.randomUUID(), ATTEMPT, LEASE_EPOCH);

        mvc(ed25519()).perform(get("/internal/ai/v1/jobs/{id}/snapshot", JOB_ID)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(service);
    }

    @Test
    void claimsAttemptMismatchIs403BeforeServiceCall() throws Exception {
        String token = jobToken(JOB_ID, ATTEMPT + 1, LEASE_EPOCH);

        mvc(ed25519()).perform(post("/internal/ai/v1/jobs/{id}/heartbeat", JOB_ID)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"attempt\":" + ATTEMPT
                                + ",\"leaseEpoch\":" + LEASE_EPOCH + "}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    // ------------------------------------------------------------------
    // 正常路径
    // ------------------------------------------------------------------

    @Test
    void snapshotReturnsBoundedProjection() throws Exception {
        when(service.snapshot(JOB_ID, ATTEMPT, LEASE_EPOCH)).thenReturn(new AiJobService.Snapshot(
                JOB_ID, PROJECT_ID, ATTEMPT, LEASE_EPOCH, "sha256:" + "b".repeat(32),
                List.of(new AiJobService.Fact("fact:project", "PROJECT", "项目：描述")),
                List.of(new AiJobService.ExistingMemory(MEMORY_ID, "DECISION", "标题", "内容"))));

        mvc(ed25519()).perform(get("/internal/ai/v1/jobs/{id}/snapshot", JOB_ID)
                        .header("Authorization", "Bearer " + jobToken(JOB_ID, ATTEMPT, LEASE_EPOCH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.projectId").value(PROJECT_ID.toString()))
                .andExpect(jsonPath("$.attempt").value(ATTEMPT))
                .andExpect(jsonPath("$.leaseEpoch").value(LEASE_EPOCH))
                .andExpect(jsonPath("$.snapshotHash").value("sha256:" + "b".repeat(32)))
                .andExpect(jsonPath("$.facts[0].ref").value("fact:project"))
                .andExpect(jsonPath("$.existingMemories[0].id").value(MEMORY_ID.toString()));
        verify(service).snapshot(JOB_ID, ATTEMPT, LEASE_EPOCH);
    }

    @Test
    void heartbeatReturnsStatusAndExpiry() throws Exception {
        OffsetDateTime expiresAt = OffsetDateTime.of(2026, 11, 12, 4, 2, 0, 0, ZoneOffset.UTC);
        when(service.heartbeat(JOB_ID, ATTEMPT, LEASE_EPOCH))
                .thenReturn(new AiJobService.Heartbeat(JOB_ID, AiJobStatus.RUNNING, expiresAt));

        mvc(ed25519()).perform(post("/internal/ai/v1/jobs/{id}/heartbeat", JOB_ID)
                        .header("Authorization", "Bearer " + jobToken(JOB_ID, ATTEMPT, LEASE_EPOCH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"attempt\":" + ATTEMPT
                                + ",\"leaseEpoch\":" + LEASE_EPOCH + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.leaseExpiresAt").value(expiresAt.toString()));
    }

    @Test
    void resultsReturnsAcceptedAndDuplicateCounts() throws Exception {
        when(service.submitResults(org.mockito.ArgumentMatchers.eq(JOB_ID),
                org.mockito.ArgumentMatchers.eq(ATTEMPT),
                org.mockito.ArgumentMatchers.eq(LEASE_EPOCH),
                org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(new AiJobService.ResultsOutcome(JOB_ID, AiJobStatus.SUCCEEDED, 1, 1));

        mvc(ed25519()).perform(post("/internal/ai/v1/jobs/{id}/results", JOB_ID)
                        .header("Authorization", "Bearer " + jobToken(JOB_ID, ATTEMPT, LEASE_EPOCH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resultsBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.jobId").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.duplicates").value(1));
    }

    // ------------------------------------------------------------------
    // 契约 / 错误映射
    // ------------------------------------------------------------------

    @Test
    void invalidCandidateIs422AndNeverReachesService() throws Exception {
        String badKey = "not-a-sha256";
        String body = "{\"schemaVersion\":1,\"attempt\":" + ATTEMPT + ",\"leaseEpoch\":"
                + LEASE_EPOCH + ",\"candidates\":[{\"kind\":\"DECISION\",\"title\":\"标题\","
                + "\"content\":\"内容\",\"confidence\":0.8,\"sourceRef\":\"fact:project\","
                + "\"dedupeKey\":\"" + badKey + "\"}]}";

        mvc(ed25519()).perform(post("/internal/ai/v1/jobs/{id}/results", JOB_ID)
                        .header("Authorization", "Bearer " + jobToken(JOB_ID, ATTEMPT, LEASE_EPOCH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SCHEMA_VIOLATION"))
                .andExpect(jsonPath("$.retryable").value(false));
        verifyNoInteractions(service);
    }

    @Test
    void staleLeaseFromServiceMapsTo409() throws Exception {
        when(service.heartbeat(JOB_ID, ATTEMPT, LEASE_EPOCH)).thenThrow(
                new com.shitulelv.aicollab.agent.job.AiJobException(
                        com.shitulelv.aicollab.agent.job.AiJobException.Kind.STALE_LEASE,
                        "租约已失效"));

        mvc(ed25519()).perform(post("/internal/ai/v1/jobs/{id}/heartbeat", JOB_ID)
                        .header("Authorization", "Bearer " + jobToken(JOB_ID, ATTEMPT, LEASE_EPOCH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"schemaVersion\":1,\"attempt\":" + ATTEMPT
                                + ",\"leaseEpoch\":" + LEASE_EPOCH + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_LEASE"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private MockMvc mvc(InternalTokenVerifier verifier) {
        InternalAiJobController controller = new InternalAiJobController(service);
        InternalAiTokenFilter filter = new InternalAiTokenFilter(verifier, objectMapper);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new InternalAiJobExceptionHandler())
                .addFilters(filter)
                .build();
    }

    private static InternalTokenVerifier ed25519() {
        return new InternalTokenVerifier(Map.of("k1", TOKEN_KEYS.publicKey()), 60, CLOCK);
    }

    private static String jobToken(UUID jobId, int attempt, long leaseEpoch) {
        KeyMaterial keys = TOKEN_KEYS;
        InternalTokenIssuer issuer = new InternalTokenIssuer(keys.privateKey(), "k1", 900, CLOCK);
        return issuer.issue(List.of("ai-worker", "backend"),
                List.of("job:snapshot", "job:heartbeat", "job:results", "model-call"),
                Map.of("jobId", jobId.toString(), "attempt", attempt, "leaseEpoch", leaseEpoch,
                        "projectRef", PROJECT_ID.toString(),
                        "snapshotHash", "sha256:" + "b".repeat(32)));
    }

    private static String resultsBody() {
        return "{\"schemaVersion\":1,\"attempt\":" + ATTEMPT + ",\"leaseEpoch\":" + LEASE_EPOCH
                + ",\"candidates\":[{\"kind\":\"DECISION\",\"title\":\"发布窗口\","
                + "\"content\":\"周五发布\",\"confidence\":0.8,\"sourceRef\":\"fact:project\","
                + "\"dedupeKey\":\"" + DEDUPE + "\"}]}";
    }

    private static final KeyMaterial TOKEN_KEYS = newMaterial();

    private static KeyMaterial newMaterial() {
        try {
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new KeyMaterial(keyPair.getPrivate(), keyPair.getPublic());
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record KeyMaterial(PrivateKey privateKey, PublicKey publicKey) {
    }
}
