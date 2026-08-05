package com.shitulelv.aicollab.agent.infrastructure.tool;

import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
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
    private static final Set<String> WRITE_PROPOSAL_ROLES = Set.of(
            "OWNER", "ADMIN", "MEMBER", "SUPERVISOR");
    private final Map<String, AgentTool> tools;
    private final List<AgentToolProvider> providers;

    public AgentToolRegistry(List<AgentTool> tools) {
        this(tools, List.of());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentToolRegistry(List<AgentTool> tools, List<AgentToolProvider> providers) {
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (tool == null || tool.name() == null || tool.name().isBlank()
                    || indexed.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Agent 工具名不能为空或重复");
            }
        }
        this.tools = Map.copyOf(indexed);
        this.providers = List.copyOf(providers);
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Optional<AgentTool> find(String name, AgentExecutionContext context) {
        AgentTool internal = tools.get(name);
        if (internal != null) return Optional.of(internal);
        return providers.stream().flatMap(provider -> provider.tools(context).stream())
                .filter(tool -> tool.name().equals(name)).findFirst();
    }

    public Set<String> names() {
        return tools.keySet();
    }

    /**
     * 返回所有注册工具名（用于测试断言）。
     */
    public Set<String> registeredToolNames() {
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

    /**
     * 获取 Skill 允许且角色有权限的工具定义。
     */
    public List<AgentToolDefinition> definitionsFor(AgentExecutionContext context, AgentSkill skill) {
        AgentToolContext toolCtx = toToolContext(context);
        java.util.stream.Stream<AgentTool> internal = tools.values().stream()
                .filter(tool -> allowed(tool, toolCtx))
                .filter(tool -> skill.allowedTools().contains(tool.name()));
        java.util.stream.Stream<AgentTool> external = allowsExternal(skill)
                ? providers.stream().flatMap(provider -> provider.tools(context).stream())
                : java.util.stream.Stream.empty();
        return java.util.stream.Stream.concat(internal, external)
                .map(AgentTool::definition)
                .sorted(java.util.Comparator.comparing(AgentToolDefinition::name))
                .toList();
    }

    private static boolean allowsExternal(AgentSkill skill) {
        return "WEEKLY_REPORT".equals(skill.code()) || "PROJECT_RESEARCH".equals(skill.code());
    }

    /**
     * 检查工具是否允许使用。
     */
    public void checkPolicy(AgentTool tool, AgentToolContext context) {
        if (tool == null || context == null) {
            throw new IllegalArgumentException("工具或上下文为空");
        }
        if (!allowed(tool, context)) {
            throw new IllegalArgumentException("当前 Agent 运行不允许业务写工具");
        }
    }

    /**
     * 将 AgentExecutionContext 转换为 AgentToolContext。
     */
    public static AgentToolContext toToolContext(AgentExecutionContext ctx) {
        return new AgentToolContext(
                ctx.runId(),
                ctx.projectId(),
                ctx.requesterId(),
                ctx.projectRole(),
                ctx.scheduled(),
                ctx.depth());
    }

    private static boolean allowed(AgentTool tool, AgentToolContext context) {
        return !tool.writesBusinessData()
                || (context.depth() == 0 && WRITE_PROPOSAL_ROLES.contains(context.role()));
    }
}
