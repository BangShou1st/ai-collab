package com.shitulelv.aicollab.internal.ai.credential;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.job.AiJobRecord;
import com.shitulelv.aicollab.agent.job.AiJobRepository;
import com.shitulelv.aicollab.agent.job.AiJobStatus;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProviderRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import com.shitulelv.aicollab.internal.ai.InternalAiClaims;
import com.shitulelv.aicollab.internal.ai.InternalAiTokenFilter;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenVerifier;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 凭据租借（契约 §2、R1 §9.4）的非 Docker 单元测试：校验矩阵、purpose 映射、路由复用与共享模型兜底、
 * 响应形状与 no-store、日志不含明文 apiKey。真库审计行与 OutputCapture 端到端见
 * {@code CredentialLeaseIntegrationTest}（需要 Docker）。
 */
class CredentialLeaseServiceTest {

    private static final long NOW = 1_800_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TASK = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PROVIDER = UUID.fromString("55555555-5555-5555-5555-555555555555");
    /** 明文哨兵：拼接构造，避免出现在任何扫描规则里。 */
    private static final String SECRET = "sk-test-" + "lease-sentinel-" + "7f3a9c2e41b8d605";
    private static final String CIPHERTEXT = "enc:opaque-ciphertext";

    private UserAiProviderService userProviders;
    private ModelSecretCipher secrets;
    private ProjectAccessGuard access;
    private AiJobRepository jobs;
    private CredentialLeaseRepository leases;
    private SharedAiProviderRepository shared;
    private CredentialLeaseService service;

    @BeforeEach
    void setUp() {
        userProviders = mock(UserAiProviderService.class);
        secrets = mock(ModelSecretCipher.class);
        access = mock(ProjectAccessGuard.class);
        jobs = mock(AiJobRepository.class);
        leases = mock(CredentialLeaseRepository.class);
        shared = mock(SharedAiProviderRepository.class);
        service = new CredentialLeaseService(userProviders, secrets, access, jobs, leases, shared, CLOCK);
        when(access.requireMember(PROJECT, USER)).thenReturn(ProjectRole.MEMBER);
        when(secrets.decrypt(CIPHERTEXT)).thenReturn(SECRET);
    }

    @Test
    void purposeMappingMatchesLegacyModelCallRoutes() {
        // P1 之前：query plan 不传 purpose → KNOWLEDGE_CHAT；记忆抽取显式传 AGENT。
        assertThat(LeasePurpose.QUERY_PLAN.routingPurpose()).contains(ModelPurpose.KNOWLEDGE_CHAT);
        assertThat(LeasePurpose.MEMORY_EXTRACTION.routingPurpose()).contains(ModelPurpose.AGENT);
        assertThat(LeasePurpose.KNOWLEDGE_CHAT.routingPurpose()).contains(ModelPurpose.KNOWLEDGE_CHAT);
        assertThat(LeasePurpose.AGENT.routingPurpose()).contains(ModelPurpose.AGENT);
        assertThat(LeasePurpose.PLANNING.routingPurpose()).contains(ModelPurpose.PLANNING);
        assertThat(LeasePurpose.EMBEDDING.routingPurpose()).isEmpty();
        assertThat(LeasePurpose.parse("query_plan")).isEmpty();
        assertThat(LeasePurpose.parse("QUERY_PLAN")).contains(LeasePurpose.QUERY_PLAN);
    }

    @Test
    void queryPlanLeaseResolvesUserRouteAndRecordsAuditRowWithoutSecret() {
        UserAiProvider provider = provider(ModelProviderType.ANTHROPIC, false);
        when(userProviders.resolve(USER, ModelPurpose.KNOWLEDGE_CHAT)).thenReturn(provider);

        CredentialLeaseService.Grant grant = service.lease(
                new CredentialLeaseService.Request(LeasePurpose.QUERY_PLAN, USER, PROJECT, null, null),
                queryPlanClaims());

        assertThat(grant.protocol()).isEqualTo("ANTHROPIC_MESSAGES");
        assertThat(grant.providerType()).isEqualTo("ANTHROPIC");
        assertThat(grant.apiKey()).isEqualTo(SECRET);
        assertThat(grant.model()).isEqualTo("model-x");
        assertThat(grant.capabilities()).containsExactly("STREAMING", "JSON");
        assertThat(grant.expiresAt()).isEqualTo(OffsetDateTime.ofInstant(Instant.ofEpochSecond(NOW + 300), ZoneOffset.UTC));
        assertThat(grant.toString()).doesNotContain(SECRET);
        verify(userProviders).requireSnapshotAuthorized(provider);

        ArgumentCaptor<CredentialLeaseRepository.LeaseRecord> row =
                ArgumentCaptor.forClass(CredentialLeaseRepository.LeaseRecord.class);
        verify(leases).insert(row.capture());
        assertThat(row.getValue().id()).isEqualTo(grant.leaseId());
        assertThat(row.getValue().providerId()).isEqualTo(PROVIDER);
        assertThat(row.getValue().providerType()).isEqualTo("ANTHROPIC");
        assertThat(row.getValue().purpose()).isEqualTo(LeasePurpose.QUERY_PLAN);
        assertThat(row.getValue().toString()).doesNotContain(SECRET).doesNotContain(CIPHERTEXT);
    }

    @Test
    void fallsBackToFirstPublishedSharedProviderLikeLegacyGateway() {
        when(userProviders.resolve(USER, ModelPurpose.AGENT))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        SharedAiProvider embeddingOnly = shared(UUID.randomUUID(), EnumSet.of(ModelCapability.RERANK));
        SharedAiProvider chat = shared(PROVIDER, EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING));
        when(shared.findAllPublished()).thenReturn(List.of(embeddingOnly, chat));
        when(jobs.find(TASK)).thenReturn(Optional.of(job(AiJobStatus.RUNNING, 2L)));

        CredentialLeaseService.Grant grant = service.lease(
                new CredentialLeaseService.Request(LeasePurpose.MEMORY_EXTRACTION, USER, PROJECT, TASK, null),
                taskClaims(2L));

        assertThat(grant.protocol()).isEqualTo("OPENAI_CHAT");
        assertThat(grant.capabilities()).containsExactly("STREAMING");
        verify(userProviders).requireSnapshotAuthorized(any());
    }

    @Test
    void everyAuthorizationFailureIsForbiddenAndNeverTouchesCredentials() {
        when(jobs.find(TASK)).thenReturn(Optional.of(job(AiJobStatus.RUNNING, 2L)));
        UUID stranger = UUID.randomUUID();

        List<Runnable> attempts = List.of(
                // purpose 不在 purp 内
                () -> service.lease(req(LeasePurpose.AGENT, USER, PROJECT, null), queryPlanClaims()),
                // userId != uid
                () -> service.lease(req(LeasePurpose.QUERY_PLAN, stranger, PROJECT, null), queryPlanClaims()),
                // projectId != pid
                () -> service.lease(req(LeasePurpose.QUERY_PLAN, USER, UUID.randomUUID(), null), queryPlanClaims()),
                // 缺 credentials:lease scope
                () -> service.lease(req(LeasePurpose.QUERY_PLAN, USER, PROJECT, null),
                        new InternalAiClaims(USER, PROJECT, Set.of("QUERY_PLAN"), null, null, Set.of("plan"))),
                // purpose 非法
                () -> service.lease(req(null, USER, PROJECT, null), queryPlanClaims()),
                // taskId 与 tid 不一致
                () -> service.lease(req(LeasePurpose.MEMORY_EXTRACTION, USER, PROJECT, UUID.randomUUID()),
                        taskClaims(2L)),
                // epoch 不一致
                () -> service.lease(req(LeasePurpose.MEMORY_EXTRACTION, USER, PROJECT, TASK), taskClaims(1L)));
        for (Runnable attempt : attempts) {
            assertThatThrownBy(attempt::run).isInstanceOfSatisfying(CredentialLeaseException.class,
                    failure -> assertThat(failure.kind()).isEqualTo(CredentialLeaseException.Kind.FORBIDDEN));
        }

        // 任务不在 LEASED/RUNNING
        when(jobs.find(TASK)).thenReturn(Optional.of(job(AiJobStatus.QUEUED, 2L)));
        assertForbidden(() -> service.lease(req(LeasePurpose.MEMORY_EXTRACTION, USER, PROJECT, TASK), taskClaims(2L)));
        // 不是项目成员
        doThrow(new BusinessException(ErrorCode.PROJECT_NOT_FOUND)).when(access).requireMember(PROJECT, USER);
        assertForbidden(() -> service.lease(req(LeasePurpose.QUERY_PLAN, USER, PROJECT, null), queryPlanClaims()));

        verify(secrets, never()).decrypt(any());
        verify(leases, never()).insert(any());
    }

    @Test
    void unresolvableProviderIsUnavailable() {
        when(userProviders.resolve(USER, ModelPurpose.KNOWLEDGE_CHAT))
                .thenThrow(new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE));
        when(shared.findAllPublished()).thenReturn(List.of());
        assertUnavailable(() -> service.lease(req(LeasePurpose.QUERY_PLAN, USER, PROJECT, null), queryPlanClaims()));

        InternalAiClaims embedding = new InternalAiClaims(USER, PROJECT, Set.of("EMBEDDING"), null, null,
                Set.of(InternalTokenIssuer.SCOPE_CREDENTIALS_LEASE));
        assertUnavailable(() -> service.lease(req(LeasePurpose.EMBEDDING, USER, PROJECT, null), embedding));

        UserAiProvider revoked = provider(ModelProviderType.OPENAI_COMPATIBLE, false);
        doReturn(revoked).when(userProviders).resolve(USER, ModelPurpose.KNOWLEDGE_CHAT);
        doThrow(new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID))
                .when(userProviders).requireSnapshotAuthorized(revoked);
        assertUnavailable(() -> service.lease(req(LeasePurpose.QUERY_PLAN, USER, PROJECT, null), queryPlanClaims()));
        verify(leases, never()).insert(any());
    }

    @Test
    void controllerReturnsCamelCaseNoStoreAndNeverLogsTheKey() throws Exception {
        when(userProviders.resolve(USER, ModelPurpose.KNOWLEDGE_CHAT))
                .thenReturn(provider(ModelProviderType.GEMINI, false));
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        InternalTokenIssuer issuer = new InternalTokenIssuer(keyPair.getPrivate(), "k1", 300, CLOCK);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CredentialLeaseController(service))
                .addFilters(new InternalAiTokenFilter(
                        new InternalTokenVerifier(Map.of("k1", keyPair.getPublic()), 60, CLOCK), new ObjectMapper()))
                .build();
        String token = issuer.issueQueryPlanToken(USER, PROJECT);
        String body = "{\"purpose\":\"QUERY_PLAN\",\"userId\":\"" + USER + "\",\"projectId\":\"" + PROJECT
                + "\",\"taskId\":null,\"runId\":null}";

        ListAppender<ILoggingEvent> appender = attach(CredentialLeaseService.class, CredentialLeaseController.class,
                InternalAiTokenFilter.class);
        MvcResult ok;
        try {
            ok = mvc.perform(post("/internal/ai/credentials/lease").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(jsonPath("$.protocol").value("GEMINI_GENERATE_CONTENT"))
                    .andExpect(jsonPath("$.providerType").value("GEMINI"))
                    .andExpect(jsonPath("$.baseUrl").value("https://api.example.test"))
                    .andExpect(jsonPath("$.apiKey").value(SECRET))
                    .andExpect(jsonPath("$.params.maxOutputTokens").value(4096))
                    .andExpect(jsonPath("$.expiresAt").value("2027-01-15T08:05:00Z"))
                    .andReturn();
            // 错 purpose → 403 LEASE_FORBIDDEN，同样 no-store。
            mvc.perform(post("/internal/ai/credentials/lease").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body.replace("QUERY_PLAN", "AGENT")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("LEASE_FORBIDDEN"))
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        } finally {
            detach(appender, CredentialLeaseService.class, CredentialLeaseController.class,
                    InternalAiTokenFilter.class);
        }
        assertThat(ok.getResponse().getContentAsString()).contains("\"leaseId\"");
        String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(logs).contains("leaseId=").contains("purpose=QUERY_PLAN").contains("model=model-x");
        assertThat(logs).doesNotContain(SECRET).doesNotContain(CIPHERTEXT).doesNotContain(token);
    }

    // ---------- helpers ----------

    private static CredentialLeaseService.Request req(LeasePurpose purpose, UUID user, UUID project, UUID task) {
        return new CredentialLeaseService.Request(purpose, user, project, task, null);
    }

    private static InternalAiClaims queryPlanClaims() {
        return new InternalAiClaims(USER, PROJECT, Set.of("QUERY_PLAN"), null, null,
                Set.of("plan", "credentials:lease", "usage:report"));
    }

    private static InternalAiClaims taskClaims(long epoch) {
        return new InternalAiClaims(USER, PROJECT, Set.of("MEMORY_EXTRACTION"), TASK, epoch,
                Set.of("task:" + TASK, "credentials:lease", "usage:report"));
    }

    private static UserAiProvider provider(ModelProviderType type, boolean sharedFlag) {
        return new UserAiProvider(PROVIDER, USER, "p", type, "https://api.example.test", null, CIPHERTEXT,
                "model-x", true, 0.2, 4096,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.STRUCTURED_OUTPUT),
                true, OffsetDateTime.now(CLOCK), OffsetDateTime.now(CLOCK), sharedFlag);
    }

    private static SharedAiProvider shared(UUID id, EnumSet<ModelCapability> capabilities) {
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        return new SharedAiProvider(id, UUID.randomUUID(), UUID.randomUUID(), "shared",
                ModelProviderType.OPENAI_COMPATIBLE, "https://shared.example.test", null, CIPHERTEXT, "model-x",
                true, SharedAiProvider.STATUS_PUBLISHED, 0.3, 2048, capabilities, 1L, now, now, now);
    }

    private static AiJobRecord job(AiJobStatus status, long epoch) {
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        return new AiJobRecord(TASK, PROJECT, "MEMORY_EXTRACTION", status, 1, "{}", 3, 1, epoch,
                UUID.randomUUID(), now.plusSeconds(60), null, null, null, USER, now, now, 1);
    }

    private static void assertForbidden(Runnable attempt) {
        assertThatThrownBy(attempt::run).isInstanceOfSatisfying(CredentialLeaseException.class,
                failure -> assertThat(failure.kind()).isEqualTo(CredentialLeaseException.Kind.FORBIDDEN));
    }

    private static void assertUnavailable(Runnable attempt) {
        assertThatThrownBy(attempt::run).isInstanceOfSatisfying(CredentialLeaseException.class,
                failure -> assertThat(failure.kind()).isEqualTo(CredentialLeaseException.Kind.UNAVAILABLE));
    }

    private static ListAppender<ILoggingEvent> attach(Class<?>... types) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        for (Class<?> type : types) {
            ((Logger) LoggerFactory.getLogger(type)).addAppender(appender);
        }
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender, Class<?>... types) {
        for (Class<?> type : types) {
            ((Logger) LoggerFactory.getLogger(type)).detachAppender(appender);
        }
    }
}
