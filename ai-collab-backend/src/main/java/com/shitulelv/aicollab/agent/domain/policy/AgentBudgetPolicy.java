package com.shitulelv.aicollab.agent.domain.policy;

import com.shitulelv.aicollab.agent.domain.model.AgentBudget;

public final class AgentBudgetPolicy {
    public AgentBudget reserveModelStep(AgentBudget budget) {
        return budget.debitStep();
    }

    public AgentBudget reserveToolCall(AgentBudget budget) {
        return budget.debitTool();
    }

    public AgentBudget reserveChild(AgentBudget budget) {
        return budget.debitChild();
    }
}
