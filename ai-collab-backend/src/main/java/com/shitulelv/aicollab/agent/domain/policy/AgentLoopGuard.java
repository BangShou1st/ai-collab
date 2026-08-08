package com.shitulelv.aicollab.agent.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class AgentLoopGuard {
    private static final int SLIDING_WINDOW_SIZE = 6;
    private static final int REPETITION_THRESHOLD = 3;

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

        // 滑动窗口：最近 N 个已完成步骤，检测 tool+args+output 签名重复
        int windowStart = Math.max(0, completed.size() - SLIDING_WINDOW_SIZE);
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int i = windowStart; i < completed.size(); i++) {
            AgentStepView step = completed.get(i);
            if (step.output() == null) continue;
            JsonNode args = extractArguments(step.input());
            String sig = step.toolName() + "|" + args + "|" + step.output();
            counts.merge(sig, 1, Integer::sum);
            if (counts.get(sig) >= REPETITION_THRESHOLD) {
                return true;
            }
        }

        // 检查下一步调用是否与窗口中某个已有签名匹配
        // 如果下一步 tool+args 与某个已完成步骤相同，预测它会产生相同 output，
        // 则该签名总计出现 windowCount + 1 次
        for (int i = windowStart; i < completed.size(); i++) {
            AgentStepView step = completed.get(i);
            if (step.output() == null) continue;
            JsonNode stepArgs = extractArguments(step.input());
            String stepSig = step.toolName() + "|" + stepArgs + "|" + step.output();
            int stepCount = counts.getOrDefault(stepSig, 0);
            if (stepCount + 1 >= REPETITION_THRESHOLD) {
                // 验证下一步的 tool+args 与此步骤匹配
                String nextArgsStr = nextCall.arguments() != null ? nextCall.arguments().toString() : "";
                String nextToolArgs = nextCall.tool() + "|" + nextArgsStr;
                String stepToolArgs = step.toolName() + "|" + stepArgs;
                if (nextToolArgs.equals(stepToolArgs)) {
                    return true;
                }
            }
        }

        return false;
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
