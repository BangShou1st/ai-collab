package com.shitulelv.aicollab.agent.domain.policy;

import com.shitulelv.aicollab.agent.application.view.AgentStepView;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;

import java.util.List;

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
        return nextCall.tool().equals(previous.toolName())
                && nextCall.tool().equals(beforePrevious.toolName())
                && nextCall.arguments().equals(previous.input())
                && nextCall.arguments().equals(beforePrevious.input())
                && previous.output() != null
                && previous.output().equals(beforePrevious.output());
    }
}
