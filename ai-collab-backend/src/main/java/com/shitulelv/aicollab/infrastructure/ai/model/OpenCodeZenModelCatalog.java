package com.shitulelv.aicollab.infrastructure.ai.model;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Zen 模型目录。免费/可执行判定不再只看 {@code -free} 后缀，而是以当前上游可用列表为基础，
 * 结合可信目录的定价、退役状态与原生协议元数据；协议不受支持时在配置/连接测试阶段明确拒绝。
 */
@Component
public class OpenCodeZenModelCatalog {
    private final OpenCodeZenTransport transport;

    public OpenCodeZenModelCatalog(OpenCodeZenTransport transport) {
        this.transport = transport;
    }

    /** 完整选择结果：可执行 Chat 模型 + 因协议不受支持而排除的模型。 */
    public ZenFreeModelSelection selection(String apiKey, AiRequestMetadata metadata) {
        return transport.freeModelSelection(apiKey, metadata);
    }

    /** 当前可作为 Zen 预设的模型（Chat 协议、可用且免费）。 */
    public List<String> freeModels(String apiKey, AiRequestMetadata metadata) {
        return transport.listFreeModels(apiKey, metadata);
    }

    /**
     * 只有当前可执行（Chat 协议、可用且免费）的模型才允许被设置为 Zen 预设。
     * 模型可用但其原生协议不是 Chat Completions 时，给出明确的协议不匹配原因。
     */
    public void requireExecutableModel(String model, ZenFreeModelSelection selection) {
        String candidate = model == null ? "" : model.strip();
        if (candidate.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model required");
        }
        if (selection != null && selection.models().contains(candidate)) {
            return;
        }
        ZenModelProtocol protocol = selection == null ? null : selection.unsupportedProtocols().get(candidate);
        if (protocol != null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "模型 " + candidate + " 的原生协议为 " + protocol.wireName()
                            + "，当前 Zen 执行路径仅适配 Chat Completions，暂不支持该模型");
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "model must be a current free Chat model");
    }
}
