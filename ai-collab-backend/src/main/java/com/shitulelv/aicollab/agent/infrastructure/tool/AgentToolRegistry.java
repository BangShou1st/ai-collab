package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class AgentToolRegistry {
    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(List<AgentTool> tools) {
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()
                    || indexed.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Agent 工具名不能为空或重复");
            }
        }
        this.tools = Map.copyOf(indexed);
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Set<String> names() {
        return tools.keySet();
    }
}
