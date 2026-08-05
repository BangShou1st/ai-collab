package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import java.util.List;

public interface AgentToolProvider {
    List<AgentTool> tools(AgentExecutionContext context);
}
