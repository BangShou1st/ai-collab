package com.shitulelv.aicollab.agent.domain.model;

import com.shitulelv.aicollab.agent.domain.model.builtin.*;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.AbstractMap;

/**
 * 固定 Skill 注册表。由后端固定注册，不允许用户动态安装。
 * <p>
 * 选择顺序（确定性）：
 * 1. 用户显式 skillCode
 * 2. goal 关键词匹配
 * 3. route 默认 Skill（按注册顺序第一个匹配的）
 * 4. 固定兜底 Skill（PROJECT_RESEARCH）
 */
@Component
public final class AgentSkillRegistry {

    /** goal 关键词 → Skill code 映射（优先级从高到低） */
    private static final Map<String, String> GOAL_KEYWORD_SKILLS = Map.ofEntries(
            new AbstractMap.SimpleEntry<>("周报", "WEEKLY_REPORT"),
            new AbstractMap.SimpleEntry<>("weekly", "WEEKLY_REPORT"),
            new AbstractMap.SimpleEntry<>("会议", "MEETING_TO_TASKS"),
            new AbstractMap.SimpleEntry<>("meeting", "MEETING_TO_TASKS"),
            new AbstractMap.SimpleEntry<>("纪要", "MEETING_TO_TASKS"),
            new AbstractMap.SimpleEntry<>("迭代", "ITERATION_PLANNING"),
            new AbstractMap.SimpleEntry<>("iteration", "ITERATION_PLANNING"),
            new AbstractMap.SimpleEntry<>("规划", "ITERATION_PLANNING"),
            new AbstractMap.SimpleEntry<>("交付", "DELIVERY_READINESS"),
            new AbstractMap.SimpleEntry<>("delivery", "DELIVERY_READINESS"),
            new AbstractMap.SimpleEntry<>("就绪", "DELIVERY_READINESS"),
            new AbstractMap.SimpleEntry<>("健康", "PROJECT_HEALTH"),
            new AbstractMap.SimpleEntry<>("health", "PROJECT_HEALTH"),
            new AbstractMap.SimpleEntry<>("项目状态", "PROJECT_HEALTH"),
            new AbstractMap.SimpleEntry<>("任务数量", "PROJECT_HEALTH"),
            new AbstractMap.SimpleEntry<>("任务数", "PROJECT_HEALTH"),
            new AbstractMap.SimpleEntry<>("项目进度", "PROJECT_HEALTH"),
            new AbstractMap.SimpleEntry<>("项目概况", "PROJECT_HEALTH"),
            new AbstractMap.SimpleEntry<>("研究", "PROJECT_RESEARCH"),
            new AbstractMap.SimpleEntry<>("research", "PROJECT_RESEARCH")
    );

    /** route → 确定的 Skill code 映射（每个 route 只映射到一个 Skill） */
    private static final Map<String, String> ROUTE_DEFAULT_SKILLS = Map.of(
            "DASHBOARD", "PROJECT_HEALTH",
            "TASK_BOARD", "PROJECT_HEALTH",
            "MILESTONE_LIST", "ITERATION_PLANNING",
            "PLANNING", "ITERATION_PLANNING",
            "DOCUMENT_LIST", "PROJECT_RESEARCH",
            "DOCUMENT_DETAIL", "MEETING_TO_TASKS",
            "AGENT", "PROJECT_RESEARCH"
    );

    private final Map<String, AgentSkill> skills;

    public AgentSkillRegistry() {
        Map<String, AgentSkill> indexed = new LinkedHashMap<>();
        register(new ProjectHealthSkill(), indexed);
        register(new WeeklyReportSkill(), indexed);
        register(new MeetingToTasksSkill(), indexed);
        register(new IterationPlanningSkill(), indexed);
        register(new DeliveryReadinessSkill(), indexed);
        register(new ProjectResearchSkill(), indexed);
        this.skills = Map.copyOf(indexed);
    }

    private static void register(AgentSkill skill, Map<String, AgentSkill> map) {
        if (map.putIfAbsent(skill.code(), skill) != null) {
            throw new IllegalArgumentException("Skill code 重复: " + skill.code());
        }
    }

    /**
     * 获取指定 Skill，不存在时抛出异常。
     */
    public AgentSkill require(String code) {
        AgentSkill skill = skills.get(code);
        if (skill == null) {
            throw new IllegalArgumentException("Skill 不存在: " + code);
        }
        return skill;
    }

    /**
     * 确定性选择 Skill。
     * 1. 显式 code 优先（不区分大小写）；
     * 2. goal 关键词匹配（取第一个命中的关键词对应的 Skill）；
     * 3. route 默认 Skill；
     * 4. 固定兜底 PROJECT_RESEARCH。
     */
    public AgentSkill select(String explicitCode, String userGoal, AgentPageContext page) {
        // 1. 显式 code
        if (explicitCode != null && !explicitCode.isBlank()) {
            return require(explicitCode.trim().toUpperCase());
        }

        // 2. goal 关键词匹配
        if (userGoal != null && !userGoal.isBlank()) {
            String goalLower = userGoal.toLowerCase();
            for (Map.Entry<String, String> entry : GOAL_KEYWORD_SKILLS.entrySet()) {
                if (goalLower.contains(entry.getKey())) {
                    return require(entry.getValue());
                }
            }
        }

        // 3. route 默认 Skill
        if (page != null && page.route() != null) {
            String routeCode = ROUTE_DEFAULT_SKILLS.get(page.route().toUpperCase());
            if (routeCode != null) {
                return require(routeCode);
            }
        }

        // 4. 固定兜底
        return require("PROJECT_RESEARCH");
    }

    /**
     * 列出所有可用 Skill。
     */
    public List<AgentSkill> listAll() {
        return List.copyOf(skills.values());
    }

    /**
     * 检查 Skill 是否存在。
     */
    public boolean exists(String code) {
        return skills.containsKey(code);
    }
}
