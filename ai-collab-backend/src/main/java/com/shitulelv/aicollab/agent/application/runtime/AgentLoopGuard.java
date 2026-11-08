package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentDelegationGuard;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.agent.infrastructure.tool.DocumentResearchDelegateAgentTool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public final class AgentLoopGuard {
    private static final int SLIDING_WINDOW_SIZE = 6;
    private static final int REPETITION_THRESHOLD = 3;

    /**
     * 委派类工具清单（设计文档 §3.2 预测性拦截扩展）：这些工具的签名不使用原始
     * args/output，而使用 {@code toolName|objectiveHash(normalize(objective))}，
     * 使"参数微调的重复委派"（大小写/标点/空白变体）也能命中窗口判重。
     */
    private static final Set<String> DELEGATION_TOOL_NAMES =
            Set.of(DocumentResearchDelegateAgentTool.NAME);

    public boolean hasNoProgress(
            List<AgentStepView> steps, AgentDecision.CallTool nextCall) {
        if (steps == null || steps.isEmpty() || nextCall == null) {
            return false;
        }
        List<AgentStepView> completed = steps.stream()
                .filter(step -> step.type() == AgentStepType.TOOL_CALL_COMPLETED)
                .toList();
        if (completed.size() < 2) {
            return false;
        }

        // 滑动窗口：最近 N 个已完成步骤，按签名检测重复（委派工具按 objective 哈希签名）
        int windowStart = Math.max(0, completed.size() - SLIDING_WINDOW_SIZE);
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int i = windowStart; i < completed.size(); i++) {
            AgentStepView step = completed.get(i);
            if (step.output() == null) continue;
            JsonNode args = extractArguments(step.input());
            String sig = stepSignature(step.toolName(), args, step.output());
            counts.merge(sig, 1, Integer::sum);
            if (counts.get(sig) >= REPETITION_THRESHOLD) {
                return true;
            }
        }

        // 检查下一步调用是否与窗口中某个已有签名匹配
        // 如果下一步与某个已完成步骤的"判重键"相同，预测它会产生相同结果，
        // 则该签名总计出现 windowCount + 1 次。
        // 判重键对委派工具按 objective 哈希比较（参数微调也能预测命中），
        // 其余工具保持原始 tool+args 比较不变。
        String nextKey = predictionKey(nextCall.tool(),
                extractArguments(nextCall.arguments()));
        for (int i = windowStart; i < completed.size(); i++) {
            AgentStepView step = completed.get(i);
            if (step.output() == null) continue;
            JsonNode stepArgs = extractArguments(step.input());
            String stepSig = stepSignature(step.toolName(), stepArgs, step.output());
            int stepCount = counts.getOrDefault(stepSig, 0);
            if (stepCount + 1 >= REPETITION_THRESHOLD) {
                if (nextKey.equals(predictionKey(step.toolName(), stepArgs))) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 预测性拦截的匹配键（纯函数）：委派工具为 {@code toolName|objectiveHash(normalize(objective))}，
     * 其余工具为原始 {@code toolName|args} 拼接。
     */
    private static String predictionKey(String toolName, JsonNode args) {
        if (toolName != null && DELEGATION_TOOL_NAMES.contains(toolName)) {
            String objective = args == null ? "" : args.path("objective").asText("");
            return toolName + "|" + AgentDelegationGuard.objectiveHash(objective);
        }
        return toolName + "|" + args;
    }

    /**
     * 步骤签名（纯函数）：委派类工具为 {@code toolName|objectiveHash(normalize(objective))}，
     * 忽略 output 与参数的表面差异；其余工具保持原 {@code toolName|args|output} 拼接，
     * 输出变化仍视为进展。
     */
    static String stepSignature(String toolName, JsonNode args, JsonNode output) {
        if (toolName != null && DELEGATION_TOOL_NAMES.contains(toolName)) {
            String objective = args == null ? "" : args.path("objective").asText("");
            return toolName + "|" + AgentDelegationGuard.objectiveHash(objective);
        }
        return toolName + "|" + args + "|" + output;
    }

    /**
     * 从 input_json 中提取 arguments。
     * 支持两种结构：
     * 1. 直接 arguments：{"limit": 5}
     * 2. 包装结构：{"toolCallId": "tc-1", "arguments": {"limit": 5}}
     */
    private JsonNode extractArguments(JsonNode input) {
        if (input == null) {
            return input;
        }
        if (input.has("arguments") && input.get("arguments").isObject()) {
            return input.get("arguments");
        }
        return input;
    }
}
