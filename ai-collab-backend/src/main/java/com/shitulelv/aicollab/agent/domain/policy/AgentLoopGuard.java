package com.shitulelv.aicollab.agent.domain.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public final class AgentLoopGuard {
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
        AgentStepView previous = completed.get(completed.size() - 1);
        AgentStepView beforePrevious = completed.get(completed.size() - 2);

        // 提取 arguments 进行比较
        JsonNode previousArgs = extractArguments(previous.input());
        JsonNode beforePreviousArgs = extractArguments(beforePrevious.input());

        return nextCall.tool().equals(previous.toolName())
                && nextCall.tool().equals(beforePrevious.toolName())
                && nextCall.arguments().equals(previousArgs)
                && nextCall.arguments().equals(beforePreviousArgs)
                && previous.output() != null
                && previous.output().equals(beforePrevious.output());
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
