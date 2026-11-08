package com.shitulelv.aicollab.planning.infrastructure.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * planning 只读工具注册表（设计文档 §5.2 的 4 个工具）。
 * 白名单键即工具名；{@code PlanningToolLoop} 按阶段允许集从这里取工具执行。
 */
@Component
public class PlanningToolRegistry {

    private final Map<String, PlanningReadOnlyTool> tools = new LinkedHashMap<>();

    public PlanningToolRegistry(List<PlanningReadOnlyTool> discovered) {
        for (PlanningReadOnlyTool tool : discovered) {
            tools.put(tool.name(), tool);
        }
    }

    /** 按名取工具；不在注册表（或名字非法）返回 null。 */
    public PlanningReadOnlyTool get(String name) {
        return name == null ? null : tools.get(name);
    }

    /** 当前注册的全部工具名（诊断用）。 */
    public List<String> names() { return List.copyOf(tools.keySet()); }
}
