package com.shitulelv.aicollab.internal.ai.credential;

import com.shitulelv.aicollab.agent.job.AiJobRecord;
import com.shitulelv.aicollab.agent.job.AiJobRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProviderRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
import com.shitulelv.aicollab.internal.ai.InternalAiClaims;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 凭据租借（契约 §2、R1 §9.4）。
 *
 * <p>校验顺序：令牌 scope/purp/uid/pid → 任务绑定（tid/状态/epoch）→ 项目成员 → 路由解析。
 * 前三类失败统一 {@link CredentialLeaseException.Kind#FORBIDDEN}（403，不区分原因）；
 * 路由解析不到可用提供商为 {@link CredentialLeaseException.Kind#UNAVAILABLE}（503）。</p>
 *
 * <p><b>路由复用</b>（与 P1 之前 已删除的同步模型代理控制器 的身份解析两步完全一致）：
 * <ol>
 *   <li>令牌带调用用户 → {@link UserAiProviderService#resolve}（用户用途分配 → 用户默认，分配可指向共享模型），
 *       即原 {@code ed25519_claim} 分支经 {@code RoutingChatModelGateway} 走的逻辑；</li>
 *   <li>用户侧解析不到 → 第一个已发布且具备 CHAT 能力的系统共享模型（原 {@code shared_provider} 分支）。
 *       P1 之前 query plan 与记忆抽取的令牌都不带 callerUserId，生产上实际走的正是这一分支，保留它保证切换后行为不变。</li>
 * </ol>
 * 两种结果都再过 {@link UserAiProviderService#requireSnapshotAuthorized}。</p>
 *
 * <p><b>日志红线</b>：只记 {@code leaseId/purpose/model} 与固定原因标签；绝不记 apiKey、密文或请求头。</p>
 */
@Service
public class CredentialLeaseService {

    private static final Logger log = LoggerFactory.getLogger(CredentialLeaseService.class);

    /** 契约 §2.3：expiresAt ≤ 签发后 300s。 */
    static final long LEASE_TTL_SECONDS = InternalTokenIssuer.P1_MAX_TTL_SECONDS;

    private final UserAiProviderService userProviders;
    private final ModelSecretCipher secrets;
    private final ProjectAccessGuard access;
    private final AiJobRepository jobs;
    private final CredentialLeaseRepository leases;
    private final SharedAiProviderRepository sharedProviders;
    private final NonChatLeaseRouter nonChatRouter;
    private final Clock clock;

    /** P2 契约 §12.3 R1-3：系统级 EMBEDDING 租借只允许这两类任务。 */
    static final java.util.Set<String> SYSTEM_EMBEDDING_JOB_TYPES = java.util.Set.of(
            "INDEX_DOCUMENT", "REINDEX_GENERATION");
    private static final String JOB_TYPE_INDEX_DOCUMENT = "INDEX_DOCUMENT";

    @Autowired
    public CredentialLeaseService(UserAiProviderService userProviders, ModelSecretCipher secrets,
            ProjectAccessGuard access, AiJobRepository jobs, CredentialLeaseRepository leases,
            ObjectProvider<SharedAiProviderRepository> sharedProviders,
            ObjectProvider<NonChatLeaseRouter> nonChatRouter) {
        this(userProviders, secrets, access, jobs, leases,
                sharedProviders == null ? null : sharedProviders.getIfAvailable(),
                nonChatRouter == null ? null : nonChatRouter.getIfAvailable(), Clock.systemUTC());
    }

    /** 无非聊天路由（EMBEDDING/RERANK 一律 503）的构造，供只测聊天路由的单元测试使用。 */
    CredentialLeaseService(UserAiProviderService userProviders, ModelSecretCipher secrets,
            ProjectAccessGuard access, AiJobRepository jobs, CredentialLeaseRepository leases,
            SharedAiProviderRepository sharedProviders, Clock clock) {
        this(userProviders, secrets, access, jobs, leases, sharedProviders, null, clock);
    }

    CredentialLeaseService(UserAiProviderService userProviders, ModelSecretCipher secrets,
            ProjectAccessGuard access, AiJobRepository jobs, CredentialLeaseRepository leases,
            SharedAiProviderRepository sharedProviders, NonChatLeaseRouter nonChatRouter, Clock clock) {
        this.sharedProviders = sharedProviders;
        this.nonChatRouter = nonChatRouter;
        this.userProviders = Objects.requireNonNull(userProviders, "userProviders");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.access = Objects.requireNonNull(access, "access");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 租借请求（已从 JSON 解析为强类型；purpose 非法时为空）。 */
    public record Request(LeasePurpose purpose, UUID userId, UUID projectId, UUID taskId, UUID runId) {
    }

    /** 租借结果。{@code apiKey} 是唯一出现明文的位置；{@link #toString()} 不含明文。 */
    public record Grant(
            UUID leaseId,
            String protocol,
            String providerType,
            String baseUrl,
            String apiPath,
            String model,
            String apiKey,
            Map<String, Object> params,
            List<String> capabilities,
            OffsetDateTime expiresAt) {

        @Override
        public String toString() {
            return "Grant[leaseId=" + leaseId + ", protocol=" + protocol + ", model=" + model
                    + ", apiKey=***]";
        }
    }

    public Grant lease(Request request, InternalAiClaims claims) {
        LeasePurpose purpose = authorize(request, claims);
        if (purpose.routingPurpose().isEmpty()) {
            return leaseNonChat(purpose, request);
        }
        ModelPurpose routing = purpose.routingPurpose()
                .orElseThrow(() -> CredentialLeaseException.unavailable("purpose_not_routable"));

        UserAiProvider provider = resolveProvider(request.userId(), routing);
        if (provider == null || !provider.enabled() || provider.providerType() == null
                || !provider.providerType().chatCompatible()) {
            throw CredentialLeaseException.unavailable("provider_unusable");
        }
        String protocol = protocolOf(provider);
        String baseUrl = provider.baseUrl() == null ? "" : provider.baseUrl().strip();
        if (!(baseUrl.startsWith("https://") || baseUrl.startsWith("http://"))) {
            throw CredentialLeaseException.unavailable("provider_base_url_invalid");
        }
        requireParamsInRange(provider.temperature(), provider.maxOutputTokens());
        String apiKey;
        try {
            apiKey = secrets.decrypt(provider.encryptedApiKey());
        } catch (RuntimeException decryptFailure) {
            // 不带上异常文本：解密失败的消息可能含密文片段。
            throw CredentialLeaseException.unavailable("credential_unreadable");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw CredentialLeaseException.unavailable("credential_missing");
        }

        OffsetDateTime issuedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
        OffsetDateTime expiresAt = issuedAt.plusSeconds(LEASE_TTL_SECONDS);
        UUID leaseId = UUID.randomUUID();
        leases.insert(new CredentialLeaseRepository.LeaseRecord(leaseId, request.userId(), request.projectId(),
                purpose, provider.id(), provider.providerType().name(), provider.modelName(),
                request.taskId(), request.runId(), issuedAt, expiresAt));
        log.info("credential lease issued: leaseId={} purpose={} model={}", leaseId, purpose, provider.modelName());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("temperature", provider.temperature());
        params.put("maxOutputTokens", provider.maxOutputTokens());
        return new Grant(leaseId, protocol, provider.providerType().name(), baseUrl,
                blankToNull(provider.apiPath()), provider.modelName(), apiKey, params,
                capabilitiesOf(provider.capabilities()), expiresAt);
    }

    private UserAiProvider resolveProvider(UUID userId, ModelPurpose routing) {
        UserAiProvider provider;
        try {
            provider = userProviders.resolve(userId, routing);
        } catch (BusinessException userUnresolved) {
            provider = firstPublishedChatCapable()
                    .map(SharedAiProvider::toRuntimeUserAiProvider)
                    .orElseThrow(() -> CredentialLeaseException.unavailable("provider_unresolved"));
        }
        try {
            userProviders.requireSnapshotAuthorized(provider);
        } catch (BusinessException unauthorized) {
            throw CredentialLeaseException.unavailable("provider_unauthorized");
        }
        return provider;
    }

    /** 原同步模型代理控制器 的选择规则（随该控制器删除迁到这里）。 */
    private Optional<SharedAiProvider> firstPublishedChatCapable() {
        if (sharedProviders == null) {
            return Optional.empty();
        }
        List<SharedAiProvider> candidates = sharedProviders.findAllPublished();
        if (candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        Optional<SharedAiProvider> chatCapable = candidates.stream()
                .filter(p -> p != null && p.isPublished()
                        && (p.capabilities() == null || p.capabilities().isEmpty()
                        || p.capabilities().contains(ModelCapability.CHAT)))
                .findFirst();
        if (chatCapable.isPresent()) {
            return chatCapable;
        }
        return candidates.stream().filter(p -> p != null && p.isPublished()).findFirst();
    }

    private LeasePurpose authorize(Request request, InternalAiClaims claims) {
        if (request == null || claims == null) {
            throw CredentialLeaseException.forbidden("request_missing");
        }
        LeasePurpose purpose = request.purpose();
        if (purpose == null) {
            throw CredentialLeaseException.forbidden("purpose_invalid");
        }
        // credentials:lease scope 由 InternalAiTokenFilter 按 PATH_SCOPES 校验（缺失即 401，契约 R2），这里不重复。
        if (!claims.purposes().contains(purpose.name())) {
            throw CredentialLeaseException.forbidden("purpose_not_granted");
        }
        if (purpose == LeasePurpose.EMBEDDING && request.taskId() != null) {
            requireSystemEmbeddingTask(request, claims);
            return purpose;
        }
        if (request.userId() == null || !request.userId().equals(claims.userId())) {
            throw CredentialLeaseException.forbidden("uid_mismatch");
        }
        if (request.projectId() == null || !request.projectId().equals(claims.projectId())) {
            throw CredentialLeaseException.forbidden("pid_mismatch");
        }
        if (request.taskId() != null) {
            requireTaskBinding(request, claims);
        }
        try {
            access.requireMember(request.projectId(), request.userId());
        } catch (BusinessException notMember) {
            throw CredentialLeaseException.forbidden("not_member");
        }
        return purpose;
    }

    private void requireTaskBinding(Request request, InternalAiClaims claims) {
        if (!request.taskId().equals(claims.taskId()) || claims.epoch() == null) {
            throw CredentialLeaseException.forbidden("tid_mismatch");
        }
        Optional<AiJobRecord> job = jobs.find(request.taskId());
        if (job.isEmpty()) {
            throw CredentialLeaseException.forbidden("task_missing");
        }
        AiJobRecord current = job.get();
        if (!current.status().leased()
                || current.leaseEpoch() != claims.epoch()
                || !request.projectId().equals(current.projectId())) {
            throw CredentialLeaseException.forbidden("task_not_leased");
        }
    }

    /**
     * 系统级 EMBEDDING 租借（P2 契约 §12.3 R1-3）：带 taskId 时 userId/projectId 可为 null，按 tid 校验任务绑定。
     * 请求里给了 userId/projectId 时仍必须与令牌一致；不做项目成员校验（索引任务是系统任务）。
     */
    private void requireSystemEmbeddingTask(Request request, InternalAiClaims claims) {
        if (!request.taskId().equals(claims.taskId()) || claims.epoch() == null) {
            throw CredentialLeaseException.forbidden("tid_mismatch");
        }
        if (request.userId() != null && !request.userId().equals(claims.userId())) {
            throw CredentialLeaseException.forbidden("uid_mismatch");
        }
        if (request.projectId() != null && !request.projectId().equals(claims.projectId())) {
            throw CredentialLeaseException.forbidden("pid_mismatch");
        }
        AiJobRecord job = jobs.find(request.taskId())
                .orElseThrow(() -> CredentialLeaseException.forbidden("task_missing"));
        if (job.status() == null || !job.status().leased() || job.leaseEpoch() != claims.epoch()) {
            throw CredentialLeaseException.forbidden("task_not_leased");
        }
        if (!SYSTEM_EMBEDDING_JOB_TYPES.contains(job.jobType())) {
            throw CredentialLeaseException.forbidden("task_type_not_allowed");
        }
        if (JOB_TYPE_INDEX_DOCUMENT.equals(job.jobType())
                && (claims.projectId() == null || !claims.projectId().equals(job.projectId()))) {
            throw CredentialLeaseException.forbidden("pid_mismatch");
        }
    }

    /** EMBEDDING / RERANK：走 {@link NonChatLeaseRouter}，参数与协议见 P2 契约 P1-R4/P1-R5/R1-4。 */
    private Grant leaseNonChat(LeasePurpose purpose, Request request) {
        if (nonChatRouter == null) {
            throw CredentialLeaseException.unavailable("purpose_not_routable");
        }
        NonChatLeaseRouter.Route route = nonChatRouter.route(purpose, request.projectId());
        OffsetDateTime issuedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.MILLIS);
        OffsetDateTime expiresAt = issuedAt.plusSeconds(LEASE_TTL_SECONDS);
        UUID leaseId = UUID.randomUUID();
        leases.insert(new CredentialLeaseRepository.LeaseRecord(leaseId, request.userId(), request.projectId(),
                purpose, route.providerId(), route.providerType(), route.model(),
                request.taskId(), request.runId(), issuedAt, expiresAt));
        log.info("credential lease issued: leaseId={} purpose={} model={}", leaseId, purpose, route.model());
        return new Grant(leaseId, route.protocol(), route.providerType(), route.baseUrl(),
                blankToNull(route.apiPath()), route.model(), route.apiKey(), route.params(),
                route.capabilities(), expiresAt);
    }

    /**
     * 下发前校验生成参数（Python 侧要求 temperature∈[0,2]、maxOutputTokens≥1，越界整次调用失败）。
     * 越界按"提供商配置不合格"处理：503 AI_PROVIDER_PARAMS_INVALID（不可重试，R3 §11），不静默裁剪，在解密 key 之前拒绝。
     */
    static void requireParamsInRange(double temperature, int maxOutputTokens) {
        if (Double.isNaN(temperature) || temperature < 0.0 || temperature > 2.0) {
            throw CredentialLeaseException.unavailable(CredentialLeaseException.REASON_PROVIDER_PARAMS_INVALID);
        }
        if (maxOutputTokens < 1) {
            throw CredentialLeaseException.unavailable(CredentialLeaseException.REASON_PROVIDER_PARAMS_INVALID);
        }
    }

    /** providerType → 契约 §2.3 协议枚举；Ollama / ModelBridge 走 OpenAI 兼容。 */
    static String protocolOf(UserAiProvider provider) {
        return switch (provider.providerType()) {
            case OPENAI_COMPATIBLE -> "OPENAI_CHAT";
            case ANTHROPIC -> "ANTHROPIC_MESSAGES";
            case GEMINI -> "GEMINI_GENERATE_CONTENT";
            case LOCAL_CUSTOM -> throw CredentialLeaseException.unavailable("provider_protocol_unsupported");
        };
    }

    /** ModelCapability → 契约 §2.3 的 {@code STREAMING/TOOLS/JSON}。 */
    static List<String> capabilitiesOf(EnumSet<ModelCapability> capabilities) {
        List<String> result = new ArrayList<>(3);
        if (capabilities == null) {
            return result;
        }
        if (capabilities.contains(ModelCapability.STREAMING)) {
            result.add("STREAMING");
        }
        if (capabilities.contains(ModelCapability.NATIVE_TOOLS)) {
            result.add("TOOLS");
        }
        if (capabilities.contains(ModelCapability.STRUCTURED_OUTPUT)) {
            result.add("JSON");
        }
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
