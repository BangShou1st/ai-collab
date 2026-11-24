package com.shitulelv.aicollab.internal.ai;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.RoutingChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.shared.SharedAiProviderRepository;
import com.shitulelv.aicollab.internal.ai.token.InternalTokenIssuer;
import com.shitulelv.aicollab.retrieval.rerank.RerankProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Python worker → Java 内部模型网关（冻结契约 §2）。
 *
 * <p>职责边界：Python 侧不持任何 Provider 密钥，所有模型调用都必须回到 Java 的
 * {@link ChatModelGateway}；本端点只做“校验 + 身份解析 + 统一模型调用 + 错误码归一”，
 * 不写业务表、不做预算结算（账本仍在 Java 侧既有链路）。</p>
 *
 * <p><b>模型身份解析优先级（Stage 4.1）</b>：
 * <ol>
 *   <li>Ed25519 短令牌声明（{@code callerUserId} 与可选 {@code projectId}）→ {@code ed25519_claim}；</li>
 *   <li>已发布的系统共享模型档案（{@link SharedAiProviderRepository#findAllPublished()}）→ {@code shared_provider}；</li>
 *   <li>遗留配置 {@code rerank.model-user-id}（记录废弃 WARN）→ {@code legacy_fallback}；</li>
 *   <li>均不可用时 fail-closed 返回 {@code 503 MODEL_UNAVAILABLE}。</li>
 * </ol></p>
 *
 * <p><b>不泄露上游信息</b>：错误响应只含固定错误码与固定中文描述，绝不回显上游异常
 * 文本、Prompt 内容、服务令牌或密钥。</p>
 */
@RestController
@RequestMapping("/internal/ai")
public class InternalModelCallController {

    private static final Logger log = LoggerFactory.getLogger(InternalModelCallController.class);

    static final int MAX_PROMPT_BYTES = 64 * 1024;
    static final int DEFAULT_DEADLINE_MS = 3000;
    static final int MAX_DEADLINE_MS = 30000;
    static final int MAX_CALL_ID_CHARS = 128;
    static final String METRIC_INTERNAL_MODEL_DURATION = "internal.ai.model.duration";
    static final String METRIC_MODEL_DURATION = "rerank.model.duration";
    static final String CALLER_TYPE_ED25519_CLAIM = "ed25519_claim";
    static final String CALLER_TYPE_SHARED_PROVIDER = "shared_provider";
    static final String CALLER_TYPE_LEGACY_FALLBACK = "legacy_fallback";
    private static final int SCHEMA_VERSION = 1;
    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_ERROR = "error";
    private static final String UNKNOWN = "unknown";

    private final ChatModelGateway chatModelGateway;
    private final RerankProperties rerankProperties;
    private final MeterRegistry meterRegistry;
    private final SharedAiProviderRepository sharedProviderRepository;
    private final ExecutorService modelCallExecutor;

    @Autowired
    public InternalModelCallController(
            ChatModelGateway chatModelGateway,
            RerankProperties rerankProperties,
            MeterRegistry meterRegistry,
            ObjectProvider<SharedAiProviderRepository> sharedProviderRepository) {
        this(chatModelGateway, rerankProperties, meterRegistry,
                sharedProviderRepository == null ? null : sharedProviderRepository.getIfAvailable());
    }

    /** 兼容构造器：直接注入可选的共享模型仓储。 */
    public InternalModelCallController(
            ChatModelGateway chatModelGateway,
            RerankProperties rerankProperties,
            MeterRegistry meterRegistry,
            SharedAiProviderRepository sharedProviderRepository) {
        this.chatModelGateway = chatModelGateway;
        this.rerankProperties = rerankProperties;
        this.meterRegistry = meterRegistry;
        this.sharedProviderRepository = sharedProviderRepository;
        // 模型调用是阻塞 IO：虚拟线程按需创建、无队列、无界平台线程风险，
        // 且 deadline 到点后 cancel(true) 的中断语义与平台线程一致。
        this.modelCallExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /** 兼容既有 3 参构造器（无共享模型仓储，供现有测试直接使用）。 */
    public InternalModelCallController(
            ChatModelGateway chatModelGateway, RerankProperties rerankProperties, MeterRegistry meterRegistry) {
        this(chatModelGateway, rerankProperties, meterRegistry, (SharedAiProviderRepository) null);
    }

    /** 测试便捷构造器：指标落在独立的 SimpleMeterRegistry，不依赖 Spring 容器。 */
    InternalModelCallController(ChatModelGateway chatModelGateway, RerankProperties rerankProperties) {
        this(chatModelGateway, rerankProperties, new SimpleMeterRegistry(), (SharedAiProviderRepository) null);
    }

    @PostMapping("/v1/model-calls")
    public ResponseEntity<Object> modelCall(
            @RequestBody(required = false) InternalModelCallRequest request,
            HttpServletRequest httpRequest) {
        ValidatedCall call = validate(request);
        if (call.error() != null) {
            log.warn("internal model call 请求非法: callId={} code={}", call.callId(), call.error().name());
            return error(call.error(), call.errorMessage());
        }

        ResolvedIdentity identity = resolveIdentity(call, httpRequest);
        if (identity == null) {
            log.error("internal model call 拒绝: 缺少可用模型调用身份（Ed25519 claim / 共享模型 / rerank.model-user-id 均不可用），fail closed");
            return error(InternalAiErrorCode.MODEL_UNAVAILABLE, "内部模型网关未配置可用模型归属");
        }

        ChatCompletionResult result;
        long startNanos = System.nanoTime();
        try {
            result = invoke(call, identity, call.outputFormat());
        } catch (TimeoutException timeout) {
            log.warn("internal model call 超时: callId={} purpose={} callerType={} deadlineMs={}",
                    call.callId(), call.purpose(), identity.callerType(), call.deadlineMs());
            recordModelDuration(call.purpose(), identity.callerType(), null, null, OUTCOME_ERROR,
                    elapsedMillis(startNanos));
            return error(InternalAiErrorCode.DEADLINE_EXCEEDED, "模型调用超过 deadline");
        } catch (BusinessException business) {
            if (call.outputFormat() == ChatCompletionCommand.OutputFormat.JSON_OBJECT
                    && structuredOutputUnsupported(business)) {
                log.warn("internal model call 供应商缺少 STRUCTURED_OUTPUT，降级 PROMPT_JSON 重试一次: callId={}",
                        call.callId());
                try {
                    result = invoke(call, identity, ChatCompletionCommand.OutputFormat.PROMPT_JSON);
                } catch (TimeoutException retryTimeout) {
                    log.warn("internal model call 降级重试超时: callId={}", call.callId());
                    recordModelDuration(call.purpose(), identity.callerType(), null, null, OUTCOME_ERROR,
                            elapsedMillis(startNanos));
                    return error(InternalAiErrorCode.DEADLINE_EXCEEDED, "模型调用超过 deadline");
                } catch (BusinessException retryBusiness) {
                    return businessError(retryBusiness, call, identity.callerType(), startNanos);
                } catch (RuntimeException retryUnexpected) {
                    return unexpectedError(retryUnexpected, call, identity.callerType(), startNanos);
                }
            } else {
                return businessError(business, call, identity.callerType(), startNanos);
            }
        } catch (RuntimeException unexpected) {
            return unexpectedError(unexpected, call, identity.callerType(), startNanos);
        }

        recordModelDuration(call.purpose(), identity.callerType(), result.provider(), result.model(),
                OUTCOME_SUCCESS, result.latencyMs());
        return ResponseEntity.ok(new InternalModelCallResponse(
                SCHEMA_VERSION,
                result.content(),
                usageOf(result),
                modelAliasOf(result),
                result.latencyMs()));
    }

    @PreDestroy
    void shutdown() {
        modelCallExecutor.shutdownNow();
    }

    private ResolvedIdentity resolveIdentity(ValidatedCall call, HttpServletRequest httpRequest) {
        Map<?, ?> claims = httpRequest != null
                && httpRequest.getAttribute(InternalAiTokenFilter.VERIFIED_CLAIMS_ATTRIBUTE) instanceof Map<?, ?> map
                ? map : null;
        Optional<UUID> claimProjectId = parseOptionalUuidClaim(claims, InternalTokenIssuer.CLAIM_PROJECT_ID);
        if (claimProjectId == null) {
            log.warn("internal model call 拒绝: projectId 声明格式非法: callId={}", call.callId());
            return null;
        }
        Optional<UUID> claimCallerId = parseOptionalUuidClaim(claims, InternalTokenIssuer.CLAIM_CALLER_USER_ID);
        if (claimCallerId == null) {
            log.warn("internal model call 拒绝: callerUserId 声明格式非法: callId={}", call.callId());
            return null;
        }
        if (claimCallerId.isPresent()) {
            return new ResolvedIdentity(
                    CALLER_TYPE_ED25519_CLAIM, claimCallerId.get(), claimProjectId.orElse(null), null);
        }

        if (sharedProviderRepository != null) {
            Optional<SharedAiProvider> shared = selectPublishedSharedProvider(sharedProviderRepository.findAllPublished());
            if (shared.isPresent()) {
                return new ResolvedIdentity(
                        CALLER_TYPE_SHARED_PROVIDER,
                        shared.get().publisherUserId(),
                        claimProjectId.orElse(null),
                        shared.get());
            }
        }

        UUID legacyCallerId = rerankProperties.modelUserIdAsUuid().orElse(null);
        if (legacyCallerId != null) {
            log.warn("internal model call 命中已废弃遗留兜底 RERANK_MODEL_USER_ID（请迁移至 Ed25519 callerUserId 声明或系统共享模型档案）: callId={} purpose={}",
                    call.callId(), call.purpose());
            return new ResolvedIdentity(
                    CALLER_TYPE_LEGACY_FALLBACK, legacyCallerId, claimProjectId.orElse(null), null);
        }
        return null;
    }

    private static Optional<SharedAiProvider> selectPublishedSharedProvider(List<SharedAiProvider> candidates) {
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
        return candidates.stream()
                .filter(p -> p != null && p.isPublished())
                .findFirst();
    }

    /**
     * 解析声明中的 UUID：未声明或空白返回 {@link Optional#empty()}；格式非法返回 {@code null} 以便 fail-closed。
     */
    private static Optional<UUID> parseOptionalUuidClaim(Map<?, ?> claims, String key) {
        if (claims == null || !claims.containsKey(key)) {
            return Optional.empty();
        }
        Object raw = claims.get(key);
        if (raw == null) {
            return Optional.empty();
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(text));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private ChatCompletionResult invoke(ValidatedCall call, ResolvedIdentity identity,
            ChatCompletionCommand.OutputFormat outputFormat) throws TimeoutException {
        ChatCompletionCommand command = new ChatCompletionCommand(
                identity.projectId(),
                call.systemPrompt(),
                call.userPrompt(),
                outputFormat,
                call.purpose(),
                null,
                List.of(),
                identity.callerUserId());
        Future<ChatCompletionResult> future = modelCallExecutor.submit(() -> {
            if (identity.sharedProvider() != null && chatModelGateway instanceof RoutingChatModelGateway routing) {
                return routing.completeWithSnapshot(
                        command,
                        AiRequestMetadata.fresh(),
                        identity.sharedProvider().toRuntimeUserAiProvider(),
                        null);
            }
            return chatModelGateway.complete(command);
        });
        try {
            ChatCompletionResult result = future.get(call.deadlineMs(), TimeUnit.MILLISECONDS);
            if (result == null) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型网关返回空结果");
            }
            return result;
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof BusinessException business) {
                throw business;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型网关调用失败");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模型网关调用被中断");
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw timeout;
        }
    }

    private ValidatedCall validate(InternalModelCallRequest request) {
        if (request == null) {
            return ValidatedCall.invalid(InternalAiErrorCode.INVALID_REQUEST, "请求体不能为空", null);
        }
        String callId = request.callId();
        if (request.schemaVersion() == null || request.schemaVersion() != SCHEMA_VERSION) {
            return ValidatedCall.invalid(InternalAiErrorCode.SCHEMA_VIOLATION, "schemaVersion 必须为 1", callId);
        }
        if (callId == null || callId.isBlank()) {
            return ValidatedCall.invalid(InternalAiErrorCode.INVALID_REQUEST, "callId 必填", null);
        }
        if (callId.length() > MAX_CALL_ID_CHARS) {
            return ValidatedCall.invalid(InternalAiErrorCode.INVALID_REQUEST, "callId 不能超过 128 字符", callId);
        }
        String systemPrompt = request.systemPrompt() == null ? "" : request.systemPrompt();
        String userPrompt = request.userPrompt() == null ? "" : request.userPrompt();
        int promptBytes = systemPrompt.getBytes(StandardCharsets.UTF_8).length
                + userPrompt.getBytes(StandardCharsets.UTF_8).length;
        if (promptBytes > MAX_PROMPT_BYTES) {
            return ValidatedCall.invalid(InternalAiErrorCode.INVALID_REQUEST,
                    "systemPrompt + userPrompt 不能超过 64 KiB", callId);
        }
        int deadlineMs = request.deadlineMs() == null ? DEFAULT_DEADLINE_MS : request.deadlineMs();
        if (deadlineMs < 1 || deadlineMs > MAX_DEADLINE_MS) {
            return ValidatedCall.invalid(InternalAiErrorCode.INVALID_REQUEST,
                    "deadlineMs 必须在 1..30000 之间", callId);
        }
        ModelPurpose purpose;
        try {
            purpose = request.purpose() == null || request.purpose().isBlank()
                    ? ModelPurpose.KNOWLEDGE_CHAT
                    : ModelPurpose.valueOf(request.purpose().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidPurpose) {
            return ValidatedCall.invalid(InternalAiErrorCode.INVALID_REQUEST, "purpose 取值非法", callId);
        }
        ChatCompletionCommand.OutputFormat outputFormat;
        try {
            outputFormat = request.outputFormat() == null || request.outputFormat().isBlank()
                    ? ChatCompletionCommand.OutputFormat.PROMPT_JSON
                    : ChatCompletionCommand.OutputFormat.valueOf(
                            request.outputFormat().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidFormat) {
            return ValidatedCall.invalid(InternalAiErrorCode.INVALID_REQUEST, "outputFormat 取值非法", callId);
        }
        return ValidatedCall.valid(callId, systemPrompt, userPrompt, purpose, outputFormat, deadlineMs);
    }

    private static boolean structuredOutputUnsupported(BusinessException failure) {
        return failure.getErrorCode() == ErrorCode.AI_PROVIDER_UNAVAILABLE
                && failure.getMessage() != null
                && failure.getMessage().contains(ModelCapability.STRUCTURED_OUTPUT.name());
    }

    private ResponseEntity<Object> businessError(
            BusinessException failure, ValidatedCall call, String callerType, long startNanos) {
        ErrorCode source = failure.getErrorCode();
        InternalAiErrorCode mapped;
        if (source.httpStatus() == HttpStatus.TOO_MANY_REQUESTS) {
            mapped = InternalAiErrorCode.MODEL_RATE_LIMITED;
        } else if (source == ErrorCode.AI_MODEL_TIMEOUT) {
            mapped = InternalAiErrorCode.DEADLINE_EXCEEDED;
        } else if (source.name().startsWith("AI_PROVIDER_") || source.name().startsWith("AI_MODEL_")) {
            // 供应商不可用/凭据无效/响应非法等一律按"模型不可用"，不向 worker 暴露内部错误码。
            mapped = InternalAiErrorCode.MODEL_UNAVAILABLE;
        } else {
            mapped = InternalAiErrorCode.INTERNAL;
        }
        log.warn("internal model call 失败: callId={} purpose={} callerType={} sourceError={} mapped={}",
                call.callId(), call.purpose(), callerType, source.name(), mapped.name());
        recordModelDuration(call.purpose(), callerType, null, null, OUTCOME_ERROR, elapsedMillis(startNanos));
        return error(mapped);
    }

    private ResponseEntity<Object> unexpectedError(
            RuntimeException failure, ValidatedCall call, String callerType, long startNanos) {
        log.error("internal model call 未预期异常: callId={} purpose={} callerType={} type={}",
                call.callId(), call.purpose(), callerType, failure.getClass().getSimpleName());
        recordModelDuration(call.purpose(), callerType, null, null, OUTCOME_ERROR, elapsedMillis(startNanos));
        return error(InternalAiErrorCode.INTERNAL);
    }

    /**
     * 观测拆分（Stage 4.1）：统一发射 {@code internal.ai.model.duration{purpose,provider,model,outcome,caller_type}}，
     * 并仅对 {@link ModelPurpose#KNOWLEDGE_CHAT} 保留兼容指标 {@code rerank.model.duration{provider,model,outcome}}。
     * 任何指标异常都吞掉，不改变响应。
     */
    private void recordModelDuration(
            ModelPurpose purpose, String callerType,
            String provider, String model, String outcome, long latencyMs) {
        try {
            String providerTag = provider == null || provider.isBlank() ? UNKNOWN : provider;
            String modelTag = model == null || model.isBlank() ? providerTag : model;
            long recordedLatency = Math.max(0L, latencyMs);
            Timer.builder(METRIC_INTERNAL_MODEL_DURATION)
                    .tag("purpose", purpose.name())
                    .tag("provider", providerTag)
                    .tag("model", modelTag)
                    .tag("outcome", outcome)
                    .tag("caller_type", callerType)
                    .publishPercentileHistogram()
                    .register(meterRegistry)
                    .record(recordedLatency, TimeUnit.MILLISECONDS);
            if (purpose == ModelPurpose.KNOWLEDGE_CHAT) {
                Timer.builder(METRIC_MODEL_DURATION)
                        .tag("provider", providerTag)
                        .tag("model", modelTag)
                        .tag("outcome", outcome)
                        .publishPercentileHistogram()
                        .register(meterRegistry)
                        .record(recordedLatency, TimeUnit.MILLISECONDS);
            }
        } catch (RuntimeException ignored) {
            // 指标不得改变业务结果
        }
    }

    private record ResolvedIdentity(
            String callerType,
            UUID callerUserId,
            UUID projectId,
            SharedAiProvider sharedProvider) {
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static ResponseEntity<Object> error(InternalAiErrorCode code) {
        return error(code, code.message());
    }

    private static ResponseEntity<Object> error(InternalAiErrorCode code, String message) {
        return ResponseEntity.status(code.httpStatus()).body(InternalAiErrorResponse.of(code, message));
    }

    private static InternalModelCallResponse.Usage usageOf(ChatCompletionResult result) {
        Integer inputTokens = result.promptTokens();
        Integer outputTokens = result.completionTokens();
        String source = inputTokens == null && outputTokens == null ? "UNKNOWN" : "PROVIDER";
        return new InternalModelCallResponse.Usage(inputTokens, outputTokens, source);
    }

    private static String modelAliasOf(ChatCompletionResult result) {
        String model = result.model();
        if (model == null || model.isBlank()) {
            model = result.provider();
        }
        return model == null || model.isBlank() ? "unknown" : model;
    }

    /** 校验结果：error 非空表示失败；否则携带已归一化的调用参数。 */
    private record ValidatedCall(
            InternalAiErrorCode error,
            String errorMessage,
            String callId,
            String systemPrompt,
            String userPrompt,
            ModelPurpose purpose,
            ChatCompletionCommand.OutputFormat outputFormat,
            int deadlineMs) {

        static ValidatedCall invalid(InternalAiErrorCode error, String message, String callId) {
            return new ValidatedCall(error, message, callId, "", "", ModelPurpose.KNOWLEDGE_CHAT,
                    ChatCompletionCommand.OutputFormat.PROMPT_JSON, DEFAULT_DEADLINE_MS);
        }

        static ValidatedCall valid(String callId, String systemPrompt, String userPrompt,
                ModelPurpose purpose, ChatCompletionCommand.OutputFormat outputFormat, int deadlineMs) {
            return new ValidatedCall(null, null, callId, systemPrompt, userPrompt,
                    purpose, outputFormat, deadlineMs);
        }
    }
}
