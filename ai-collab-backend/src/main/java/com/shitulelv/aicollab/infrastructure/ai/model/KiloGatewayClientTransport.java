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
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, invalidRequest.getMessage());
        }
    }

    @Override
    public void streamChat(ObjectNode request, Consumer<JsonNode> onChunk) {
        try {
            client().streamChat(request, onChunk);
        } catch (KiloGatewayException failure) {
            throw translate(failure);
        } catch (IllegalArgumentException invalidRequest) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, invalidRequest.getMessage());
        }
    }

    /**
     * 共享库异常 → 业务错误码：授权、额度、超时与其它上游失败分门别类。
     *
     * <p>共享库把超时、连接失败、SSE 中断与解码失败统一为 {@code statusCode = -1}，
     * 因此只有根因是 {@link java.net.http.HttpTimeoutException} 时才判为超时，
     * 其余（连接失败 / 流中断 / 非法 JSON）按上游错误处理，不伪装成超时。</p>
     */
    private static BusinessException translate(KiloGatewayException failure) {
        return switch (failure.getStatusCode()) {
            case 401, 403 -> new BusinessException(ErrorCode.AI_MODEL_CREDENTIAL_INVALID, failure.getMessage());
            case 429 -> new BusinessException(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED, failure.getMessage());
            default -> isTimeout(failure)
                    ? new BusinessException(ErrorCode.AI_MODEL_TIMEOUT, failure.getMessage())
                    : new BusinessException(ErrorCode.AI_PROVIDER_ERROR, failure.getMessage());
        };
    }

    private static boolean isTimeout(KiloGatewayException failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.http.HttpTimeoutException) return true;
        }
        return false;
    }
}
