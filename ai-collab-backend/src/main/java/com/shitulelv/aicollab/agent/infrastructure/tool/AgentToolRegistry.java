package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
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

    public Set<String> namesFor(AgentToolContext context) {
        return tools.values().stream()
                .filter(tool -> allowed(tool, context))
                .map(AgentTool::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public List<AgentToolDefinition> definitionsFor(AgentToolContext context) {
        return tools.values().stream()
                .filter(tool -> allowed(tool, context))
                .map(AgentTool::definition)
                .sorted(java.util.Comparator.comparing(AgentToolDefinition::name))
                .toList();
    }

    private static boolean allowed(AgentTool tool, AgentToolContext context) {
        return !tool.writesBusinessData()
                || (context.depth() == 0 && "SUPERVISOR".equals(context.role()));
    }
}
