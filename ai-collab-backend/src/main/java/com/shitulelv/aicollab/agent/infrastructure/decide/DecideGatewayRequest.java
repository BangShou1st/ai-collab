package com.shitulelv.aicollab.agent.infrastructure.decide;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 发往 Python {@code POST /v1/agent/decide} 的请求契约（W4 冻结契约 §2/§3.2 逐字段对应）。
 *
 * <p>字段名与大小写是跨语言契约的一部分，不得自创或改名。{@code messages} 本波固定为空
 * （不把会话消息送到影子端点）；{@code policy} 本波固定只读；{@code budget.maxOutputTokens}
 * 固定 1000。</p>
 *
 * <p>本记录只作为"待发送快照"，不携带任何 Java 内部 id/元数据；{@code snapshotHash}
 * 由影子服务按 canonical JSON 计算后回填。</p>
 */
public record DecideGatewayRequest(
        int schemaVersion,
        String runId,
        int attempt,
        int turnNo,
        String snapshotHash,
        Scenario scenario,
        String goal,
        String locale,
        Context context,
        List<Tool> tools,
        Policy policy,
        Budget budget) {

    public DecideGatewayRequest {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("decide schemaVersion 必须为 1");
        }
        context = context == null ? new Context(List.of(), List.of(), List.of()) : context;
        tools = tools == null ? List.of() : List.copyOf(tools);
        policy = policy == null ? Policy.READ_ONLY : policy;
        budget = budget == null ? Budget.DEFAULT : budget;
    }

    /** 回填快照哈希（记录不可变，返回新实例）。 */
    public DecideGatewayRequest withSnapshotHash(String hash) {
        return new DecideGatewayRequest(schemaVersion, runId, attempt, turnNo, hash, scenario, goal,
                locale, context, tools, policy, budget);
    }

    /** 场景身份：只传 code/category；promptPack 为可选追溯字段，本波不发送。 */
    public record Scenario(String code, String category) {
    }

    /** 有界上下文；messages 本波固定为空列表。 */
    public record Context(List<String> facts, List<Message> messages, List<Observation> observations) {
        public Context {
            facts = facts == null ? List.of() : List.copyOf(facts);
            messages = messages == null ? List.of() : List.copyOf(messages);
            observations = observations == null ? List.of() : List.copyOf(observations);
        }
    }

    /** 会话消息（本波不发送，保留契约形状供后续波次使用）。 */
    public record Message(String role, String content) {
    }

    /** 工具观察：只有工具名与有界结果摘要，不含工具结果全文。 */
    public record Observation(String tool, String summary) {
    }

    /** 工具定义：只读工具，inputSchema 为压缩 JSON。 */
    public record Tool(String name, String description, JsonNode inputSchema, String risk) {
    }

    /** 授权策略：影子端点不得因 allowWrite 扩大工具集；本波固定 false/false。 */
    public record Policy(boolean allowWrite, boolean allowExternal) {
        public static final Policy READ_ONLY = new Policy(false, false);
    }

    /** 预算：输入 24000 上限、输出 1000、服务端硬 deadline。 */
    public record Budget(int maxInputTokens, int maxOutputTokens, int deadlineMs) {
        public static final Budget DEFAULT = new Budget(24000, 1000, 30000);
    }
}
