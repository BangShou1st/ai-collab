package com.shitulelv.aicollab.agent.application.view;

import java.util.List;

public record AgentRunDetailView(AgentRunView run, List<AgentStepView> steps) {
}
