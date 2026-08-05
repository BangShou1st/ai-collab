package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.AgentDecisionParser;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Legacy 只读执行器。
 * 适用于只有 CHAT 能力的模型（不支持 NATIVE_TOOLS）。
 * <p>
 * 使用 ChatModelGateway 调用模型，模型返回 JSON 决策（call_tool/final），
 * 由 AgentDecisionParser 解析。只暴露只读工具，禁止所有审批写工具。
 * <p>
 * 注意：此执行器在调用前必须确认所有 exposed 工具均为只读。
 */
@Component
public class LegacyReadOnlyAgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(LegacyReadOnlyAgentExecutor.class);

    private final ChatModelGateway chatGateway;
    private final AgentDecisionParser decisionParser;
    private final ObjectMapper json;

    public LegacyReadOnlyAgentExecutor(
            ChatModelGateway chatGateway,
            AgentDecisionParser decisionParser,
            ObjectMapper json) {
        this.chatGateway = chatGateway;
        this.decisionParser = decisionParser;
        this.json = json;
    }

    /**
     * 使用 Legacy JSON 决策协议调用模型。
     *
     * @param messages 多轮消息历史（会被转换为 system+user 格式，包含 Tool Result 历史）
     * @param exposed  当前暴露的只读工具定义
     * @param correctionAttempted 是否已尝试过参数修正
     * @return 模型返回结果
     */
    public ModelTurnResult callModel(
            List<ModelMessage> messages,
            List<AgentToolDefinition> exposed,
            boolean correctionAttempted) {

        // 构建 system prompt 和 tool list 文本
        String systemPrompt = extractSystemPrompt(messages);
        String userPrompt = buildUserPromptWithHistory(messages);
        String toolList = buildToolListText(exposed);

        String fullSystemPrompt = systemPrompt + "\n\n" + toolList + "\n" + buildLegacyInstructions();

        ChatCompletionCommand command = new ChatCompletionCommand(
                fullSystemPrompt,
                userPrompt,
                ChatCompletionCommand.OutputFormat.JSON_OBJECT,
                ModelPurpose.AGENT,
                null,
                List.of());

        ChatCompletionResult completion = chatGateway.complete(command);

        AgentDecision decision = decisionParser.parse(completion.content(), correctionAttempted);

        return convertToModelTurnResult(completion, decision);
    }

    private ModelTurnResult convertToModelTurnResult(
            ChatCompletionResult completion, AgentDecision decision) {
        return switch (decision) {
            case AgentDecision.CallTool callTool -> {
                JsonNode args = callTool.arguments() != null
                        ? callTool.arguments() : json.createObjectNode();
                ModelToolCall toolCall = new ModelToolCall(
                        "legacy-" + System.nanoTime(),
                        callTool.tool(),
                        args);
                yield new ModelTurnResult(
                        callTool.reason(),
                        List.of(toolCall),
                        ModelFinishReason.TOOL_CALLS,
                        new ModelUsage(completion.promptTokens(), completion.completionTokens()),
                        completion.provider(),
                        completion.model(),
                        completion.latencyMs());
            }
            case AgentDecision.FinalAnswer finalAnswer -> new ModelTurnResult(
                    finalAnswer.answer(),
                    List.of(),
                    ModelFinishReason.STOP,
                    new ModelUsage(completion.promptTokens(), completion.completionTokens()),
                    completion.provider(),
                    completion.model(),
                    completion.latencyMs());
            default -> new ModelTurnResult(
                    decision.toString(),
                    List.of(),
                    ModelFinishReason.STOP,
                    new ModelUsage(completion.promptTokens(), completion.completionTokens()),
                    completion.provider(),
                    completion.model(),
                    completion.latencyMs());
        };
    }

    private String extractSystemPrompt(List<ModelMessage> messages) {
        return messages.stream()
                .filter(m -> m instanceof ModelMessage.System)
                .map(m -> ((ModelMessage.System) m).content())
                .findFirst()
                .orElse("你是 AI Collab 当前项目的受控协作 Agent。");
    }

    /**
     * 构建包含历史 Tool Result 的用户提示。
     * Legacy 模式下，Tool Result 被压缩为文本格式附加到用户提示中。
     */
    private String buildUserPromptWithHistory(List<ModelMessage> messages) {
        StringBuilder userPrompt = new StringBuilder();

        // 提取用户消息
        String userGoal = messages.stream()
                .filter(m -> m instanceof ModelMessage.User)
                .map(m -> ((ModelMessage.User) m).content())
                .findFirst()
                .orElse("");
        userPrompt.append(userGoal);

        // 提取 Tool Result 历史（Legacy 跨 Tick 恢复的关键）
        List<String> toolHistory = new ArrayList<>();
        for (ModelMessage message : messages) {
            if (message instanceof ModelMessage.Assistant assistant && !assistant.toolCalls().isEmpty()) {
                // 记录工具调用
                for (ModelToolCall tc : assistant.toolCalls()) {
                    toolHistory.add("之前调用了工具: " + tc.name() + "，参数: " + tc.arguments());
                }
            } else if (message instanceof ModelMessage.ToolResult toolResult) {
                // 记录工具结果
                String resultSummary = toolResult.result().toString();
                if (resultSummary.length() > 500) {
                    resultSummary = resultSummary.substring(0, 500) + "... [截断]";
                }
                toolHistory.add("工具 " + toolResult.toolName() + " 结果: " + resultSummary);
            }
        }

        if (!toolHistory.isEmpty()) {
            userPrompt.append("\n\n历史工具调用记录：\n");
            for (String record : toolHistory) {
                userPrompt.append("- ").append(record).append("\n");
            }
        }

        return userPrompt.toString();
    }

    private String extractUserPrompt(List<ModelMessage> messages) {
        return messages.stream()
                .filter(m -> m instanceof ModelMessage.User)
                .map(m -> ((ModelMessage.User) m).content())
                .findFirst()
                .orElse("");
    }

    private String buildToolListText(List<AgentToolDefinition> tools) {
        if (tools.isEmpty()) return "当前没有可用工具。";
        StringBuilder sb = new StringBuilder("可用工具（只读）：\n");
        for (AgentToolDefinition tool : tools) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append("\n");
        }
        return sb.toString();
    }

    private String buildLegacyInstructions() {
        return """
                回复格式要求（JSON）：
                调用工具：{"action":"call_tool","tool":"工具名","arguments":{},"reason":"调用原因"}
                最终回答：{"action":"final","answer":"最终回答","citations":[],"inferences":[]}

                安全规则：
                - 只使用上面列出的只读工具。
                - 写操作请直接在 final answer 中说明需要用户手动操作。
                - 不得猜测资源 ID、版本、权限或项目事实。
                - 事实来自工具/文档；推断必须标记。
                """;
    }
}
