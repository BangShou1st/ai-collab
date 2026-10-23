package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Consumer;

/**
 * Kilo Gateway 的 wire 边界：发现、校验与推理统一走共享库 {@code KiloGatewayClient}。
 * base URL、路径、免费模型判定与匿名调用语义来自共享库契约，应用侧不重复实现。
 *
 * <p>Kilo 免费模型匿名调用（不发送 Authorization）；非免费模型一律拒绝，凭证无关的模型目录可公开获取。</p>
 */
public interface KiloGatewayTransport {
    /** 网关当前在服务的免费模型 id：共享库免费模型判定与上游实时目录的交集，按 id 稳定排序。 */
    List<String> admittedModels();

    /** 非流式 OpenAI 兼容请求；返回网关原始 JSON（含 tool_calls）。 */
    JsonNode chat(ObjectNode request);

    /** 流式 SSE 请求：逐块回调网关原始 JSON。 */
    void streamChat(ObjectNode request, Consumer<JsonNode> onChunk);
}
