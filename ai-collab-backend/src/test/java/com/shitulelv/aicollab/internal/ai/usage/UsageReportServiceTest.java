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
                "{\"items\":[" + String.join(",", oversized) + "]}")) {
            mvc.perform(post("/internal/ai/usage").header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest());
        }
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
