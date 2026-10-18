package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sharedlibs.kilogateway.FreeModelPolicy;
import com.sharedlibs.kilogateway.KiloGatewayClient;
import com.sharedlibs.kilogateway.KiloGatewayException;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * 基于共享库 {@link KiloGatewayClient} 的传输实现。HTTP、SSE 解码、匿名身份与免费模型判定全部由共享库负责，
 * 本类只做错误码归一化与业务边界校验。
 */
@Component
public class KiloGatewayClientTransport implements KiloGatewayTransport {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(KiloGatewayClientTransport.class);
    private final KiloGatewayClient direct;
    private final org.springframework.beans.factory.ObjectProvider<KiloGatewayClient> provided;

    /**
     * 容器注入路径：共享库被 {@code kilo.gateway.enabled=false} 关闭时不缺少依赖导致启动失败，
     * 而是在真正使用 Kilo 预设时给出明确的业务错误。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public KiloGatewayClientTransport(org.springframework.beans.factory.ObjectProvider<KiloGatewayClient> provided) {
        this.provided = provided;
        this.direct = null;
    }

    /** 无 Spring 上下文使用（测试与默认构造）。 */
    public KiloGatewayClientTransport(KiloGatewayClient client) {
        this.direct = client;
        this.provided = null;
    }

    private KiloGatewayClient client() {
        if (direct != null) return direct;
        KiloGatewayClient available = provided.getIfAvailable();
        if (available == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE,
                    "Kilo Gateway 未启用（kilo.gateway.enabled=false）");
        }
        return available;
    }

    @Override
    public List<String> admittedModels() {
        try {
            return client().freeModels().stream()
                    .map(node -> node.path("id").asText(""))
                    .filter(FreeModelPolicy::isFreeModelId)
                    .distinct()
                    .sorted()
                    .toList();
        } catch (KiloGatewayException failure) {
            throw translate(failure);
        }
    }

    @Override
    public void validateModel(String model) {
        String candidate = model == null ? "" : model.strip();
        if (candidate.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model required");
        if (!FreeModelPolicy.isFreeModelId(candidate)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model must be a Kilo free model");
        }
        // 真实网络调用：目录可达即证明网关连通；模型必须仍在免费目录内。
        if (!admittedModels().contains(candidate)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "model is not currently served by the Kilo gateway");
        }
    }

    @Override
    public JsonNode chat(ObjectNode request) {
        try {
            return client().chat(request);
        } catch (KiloGatewayException failure) {
            throw translate(failure);
        } catch (IllegalArgumentException invalidRequest) {
            log.warn("Kilo gateway rejected the request: {}", sanitize(invalidRequest.getMessage()));
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        }
    }

    @Override
    public void streamChat(ObjectNode request, Consumer<JsonNode> onChunk) {
        try {
            client().streamChat(request, onChunk);
        } catch (KiloGatewayException failure) {
            throw translate(failure);
        } catch (IllegalArgumentException invalidRequest) {
            log.warn("Kilo gateway rejected the request: {}", sanitize(invalidRequest.getMessage()));
            throw new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        }
    }

    /**
     * 共享库异常 → 稳定业务错误码。上游正文只用于内部日志，绝不进入客户端响应：
     * 这里一律使用 {@link ErrorCode} 的固定提示，不携带异常消息。
     *
     * <p>共享库把超时、连接失败、SSE 中断、非法 JSON 与流未完成都记为负 statusCode，
     * 因此只有根因是 {@link java.net.http.HttpTimeoutException} 时才判为超时，
     * 未观察到 {@code [DONE]} 的截断流判为无效响应，其余按上游错误处理。</p>
     */
    private static BusinessException translate(KiloGatewayException failure) {
        log.warn("Kilo gateway call failed: status={}, provider={}, detail={}",
                failure.getStatusCode(), failure.getProvider(), sanitize(failure.getMessage()));
        return switch (failure.getStatusCode()) {
            case 401, 403 -> new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID);
            case 429 -> new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED);
            case KiloGatewayException.STATUS_INCOMPLETE_STREAM ->
                    new BusinessException(ErrorCode.AI_PROVIDER_INVALID_RESPONSE);
            default -> isTimeout(failure)
                    ? new BusinessException(ErrorCode.AI_MODEL_TIMEOUT)
                    : new BusinessException(ErrorCode.AI_PROVIDER_ERROR);
        };
    }

    private static boolean isTimeout(KiloGatewayException failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException) return true;
        }
        return false;
    }

    /** 压平换行/控制字符并限长，避免把上游原始正文（HTML、内部路径等）成块写入日志。 */
    private static String sanitize(String message) {
        if (message == null) return "-";
        String flattened = message.replaceAll("\\s+", " ").strip().replaceAll("[\\p{Cntrl}]", "");
        return flattened.length() > 200 ? flattened.substring(0, 200) + "..." : flattened;
    }
}
