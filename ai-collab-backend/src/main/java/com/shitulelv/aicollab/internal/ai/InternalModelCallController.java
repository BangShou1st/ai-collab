package com.shitulelv.aicollab.internal.ai;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.retrieval.rerank.RerankProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
 * {@link ChatModelGateway}；本端点只做"校验 + 以服务账号调用 + 错误码归一"，
 * 不写业务表、不做预算结算（账本仍在 Java 侧既有链路）。</p>
 *
 * <p><b>鉴权</b>：由 {@link InternalAiTokenFilter} 在进入 MVC 前完成（常量时间比较、
 * 未配置令牌 fail closed）；本控制器假定请求已经过鉴权。</p>
 *
 * <p><b>临时方案（登记为遗留）</b>：现有 {@code ChatModelGateway} 必须有非空
 * {@code callerUserId} 才能解析用户级 Provider 配置，因此本端点用
 * {@code rerank.model-user-id} 配置的服务账号调用；未配置 → 503 MODEL_UNAVAILABLE。
 * W4 换成系统共享模型档案后删除该配置项。</p>
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
    private static final int SCHEMA_VERSION = 1;

    private final ChatModelGateway chatModelGateway;
    private final RerankProperties rerankProperties;
    private final ExecutorService modelCallExecutor;

    public InternalModelCallController(ChatModelGateway chatModelGateway, RerankProperties rerankProperties) {
        this.chatModelGateway = chatModelGateway;
        this.rerankProperties = rerankProperties;
        // 模型调用是阻塞 IO：虚拟线程按需创建、无队列、无界平台线程风险，
        // 且 deadline 到点后 cancel(true) 的中断语义与平台线程一致。
        this.modelCallExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PostMapping("/v1/model-calls")
    public ResponseEntity<Object> modelCall(@RequestBody(required = false) InternalModelCallRequest request) {
        ValidatedCall call = validate(request);
        if (call.error() != null) {
            log.warn("internal model call 请求非法: callId={} code={}", call.callId(), call.error().name());
            return error(call.error(), call.errorMessage());
        }

        UUID callerUserId = rerankProperties.modelUserIdAsUuid().orElse(null);
        if (callerUserId == null) {
            log.error("internal model call 拒绝: rerank.model-user-id 未配置或非法，fail closed");
            return error(InternalAiErrorCode.MODEL_UNAVAILABLE, "内部模型网关未配置服务调用账号");
        }

        ChatCompletionResult result;
        try {
            result = invoke(call, callerUserId, call.outputFormat());
        } catch (TimeoutException timeout) {
            log.warn("internal model call 超时: callId={} deadlineMs={}", call.callId(), call.deadlineMs());
            return error(InternalAiErrorCode.DEADLINE_EXCEEDED, "模型调用超过 deadline");
        } catch (BusinessException business) {
            if (call.outputFormat() == ChatCompletionCommand.OutputFormat.JSON_OBJECT
                    && structuredOutputUnsupported(business)) {
                log.warn("internal model call 供应商缺少 STRUCTURED_OUTPUT，降级 PROMPT_JSON 重试一次: callId={}",
                        call.callId());
                try {
                    result = invoke(call, callerUserId, ChatCompletionCommand.OutputFormat.PROMPT_JSON);
                } catch (TimeoutException retryTimeout) {
                    log.warn("internal model call 降级重试超时: callId={}", call.callId());
                    return error(InternalAiErrorCode.DEADLINE_EXCEEDED, "模型调用超过 deadline");
                } catch (BusinessException retryBusiness) {
                    return businessError(retryBusiness, call.callId());
                } catch (RuntimeException retryUnexpected) {
                    return unexpectedError(retryUnexpected, call.callId());
                }
            } else {
                return businessError(business, call.callId());
            }
        } catch (RuntimeException unexpected) {
            return unexpectedError(unexpected, call.callId());
        }

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

    private ChatCompletionResult invoke(ValidatedCall call, UUID callerUserId,
            ChatCompletionCommand.OutputFormat outputFormat) throws TimeoutException {
        ChatCompletionCommand command = new ChatCompletionCommand(
                null,
                call.systemPrompt(),
                call.userPrompt(),
                outputFormat,
                call.purpose(),
                null,
                List.of(),
                callerUserId);
        Future<ChatCompletionResult> future = modelCallExecutor.submit(() -> chatModelGateway.complete(command));
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

    private ResponseEntity<Object> businessError(BusinessException failure, String callId) {
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
        log.warn("internal model call 失败: callId={} sourceError={} mapped={}",
                callId, source.name(), mapped.name());
        return error(mapped);
    }

    private static ResponseEntity<Object> unexpectedError(RuntimeException failure, String callId) {
        log.error("internal model call 未预期异常: callId={} type={}",
                callId, failure.getClass().getSimpleName());
        return error(InternalAiErrorCode.INTERNAL);
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
