package com.shitulelv.aicollab.agent.domain.model;

import com.shitulelv.aicollab.agent.domain.model.builtin.*;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定 Scenario 注册表。由后端固定注册，不允许用户动态安装。
 * <p>
 * 选择顺序（确定性）：
 * 1. 用户显式 scenario code（不区分大小写）
 * 2. 页面 route 的固定映射（TASK / MILESTONE / PLANNING → ITERATION_PLANNING，DASHBOARD → PROJECT_HEALTH）
 * 3. 固定兜底 Scenario（PROJECT_RESEARCH，DOCUMENT 等未映射 route 也落到这里）
 * <p>
 * 不根据 goal 关键词做隐式路由：写工作流（MEETING_TO_TASKS / ITERATION_PLANNING）
 * 必须由用户显式选择，否则只能得到只读兜底场景。
 */
@Component
public final class AgentScenarioRegistry {

    private final Map<String, AgentScenario> scenarios;

    public AgentScenarioRegistry() {
        Map<String, AgentScenario> indexed = new LinkedHashMap<>();
        register(new ProjectHealthScenario(), indexed);
        register(new WeeklyReportScenario(), indexed);
        register(new MeetingToTasksScenario(), indexed);
        register(new IterationPlanningScenario(), indexed);
        register(new DeliveryReadinessScenario(), indexed);
        register(new ProjectResearchScenario(), indexed);
        this.scenarios = Map.copyOf(indexed);
    }

    private static void register(AgentScenario scenario, Map<String, AgentScenario> map) {
        if (map.putIfAbsent(scenario.code(), scenario) != null) {
            throw new IllegalArgumentException("Scenario code 重复: " + scenario.code());
        }
    }

    /**
     * 获取指定 Scenario，不存在时抛出异常。
     */
    public AgentScenario require(String code) {
        AgentScenario scenario = scenarios.get(code);
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario 不存在: " + code);
        }
        return scenario;
    }

    /**
     * 确定性选择 Scenario。
     * 1. 显式 code 优先（不区分大小写）；
     * 2. route 固定映射（根据页面上下文选择最可能需要的只读 Scenario）；
     * 3. 固定兜底 PROJECT_RESEARCH。
     * <p>
     * 不做 goal 关键词匹配：隐式路由已移除，写工作流必须显式选择。
     */
    public AgentScenario select(String explicitCode, AgentPageContext page) {
        // 1. 显式 code
        if (explicitCode != null && !explicitCode.isBlank()) {
            return require(explicitCode.trim().toUpperCase());
        }

        // 2. route 固定映射：只保留确定性映射，不根据 goal 文本推断
        if (page != null && page.route() != null) {
            String route = page.route().toUpperCase();
            if (route.contains("TASK")) return require("ITERATION_PLANNING");
            if (route.contains("MILESTONE")) return require("ITERATION_PLANNING");
            if (route.contains("PLANNING")) return require("ITERATION_PLANNING");
            if (route.contains("DASHBOARD")) return require("PROJECT_HEALTH");
        }

        // 3. 最终兜底（DOCUMENT 等未映射 route 也走这里）
        return require("PROJECT_RESEARCH");
    }

    /**
     * 列出所有可用 Scenario。
     */
    public List<AgentScenario> listAll() {
        return List.copyOf(scenarios.values());
    }

    /**
     * 检查 Scenario 是否存在。
     */
    public boolean exists(String code) {
        return scenarios.containsKey(code);
    }
}
