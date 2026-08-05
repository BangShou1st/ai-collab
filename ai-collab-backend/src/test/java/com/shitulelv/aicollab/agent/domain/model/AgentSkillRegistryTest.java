package com.shitulelv.aicollab.agent.domain.model;

import com.shitulelv.aicollab.agent.domain.model.builtin.*;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class AgentSkillRegistryTest {

    private final AgentSkillRegistry registry = new AgentSkillRegistry();

    @Test
    void allSixBuiltinSkillsRegistered() {
        assertThat(registry.listAll()).hasSize(6);
    }

    @Test
    void skillCodesAreUnique() {
        Set<String> codes = registry.listAll().stream()
                .map(AgentSkill::code)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(codes).hasSize(6);
    }

    @Test
    void requireExistingSkill() {
        AgentSkill skill = registry.require("PROJECT_HEALTH");
        assertThat(skill.code()).isEqualTo("PROJECT_HEALTH");
        assertThat(skill.displayName()).isEqualTo("检查项目健康度");
    }

    @Test
    void requireNonExistingSkillThrows() {
        assertThatThrownBy(() -> registry.require("NONEXISTENT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Skill 不存在");
    }

    @Test
    void selectExplicitCode() {
        AgentSkill skill = registry.select("WEEKLY_REPORT", "生成周报", null);
        assertThat(skill.code()).isEqualTo("WEEKLY_REPORT");
    }

    @Test
    void selectByGoalKeywordHealth() {
        AgentSkill skill = registry.select(null, "检查项目健康度", null);
        assertThat(skill.code()).isEqualTo("PROJECT_HEALTH");
    }

    @Test
    void selectProjectHealthForStatusAndTaskCountQuestion() {
        AgentSkill skill = registry.select(null, "当前项目状态和任务数量", null);
        assertThat(skill.code()).isEqualTo("PROJECT_HEALTH");
    }

    @Test
    void selectByGoalKeywordWeeklyReport() {
        AgentSkill skill = registry.select(null, "生成周报", null);
        assertThat(skill.code()).isEqualTo("WEEKLY_REPORT");
    }

    @Test
    void selectByGoalKeywordMeeting() {
        AgentSkill skill = registry.select(null, "从会议纪要提取任务", null);
        assertThat(skill.code()).isEqualTo("MEETING_TO_TASKS");
    }

    @Test
    void selectByGoalKeywordIteration() {
        AgentSkill skill = registry.select(null, "规划下一个迭代", null);
        assertThat(skill.code()).isEqualTo("ITERATION_PLANNING");
    }

    @Test
    void selectByGoalKeywordDelivery() {
        AgentSkill skill = registry.select(null, "检查交付就绪度", null);
        assertThat(skill.code()).isEqualTo("DELIVERY_READINESS");
    }

    @Test
    void selectByGoalKeywordResearch() {
        AgentSkill skill = registry.select(null, "研究问题", null);
        assertThat(skill.code()).isEqualTo("PROJECT_RESEARCH");
    }

    @Test
    void selectByPageRouteDashboard() {
        AgentPageContext page = new AgentPageContext("DASHBOARD", null, null, null, null);
        AgentSkill skill = registry.select(null, null, page);
        assertThat(skill.code()).isEqualTo("PROJECT_HEALTH");
    }

    @Test
    void selectByPageRouteTaskBoard() {
        AgentPageContext page = new AgentPageContext("TASK_BOARD", null, null, null, null);
        AgentSkill skill = registry.select(null, null, page);
        assertThat(skill.code()).isEqualTo("PROJECT_HEALTH");
    }

    @Test
    void selectByPageRouteMilestoneList() {
        AgentPageContext page = new AgentPageContext("MILESTONE_LIST", null, null, null, null);
        AgentSkill skill = registry.select(null, null, page);
        assertThat(skill.code()).isEqualTo("ITERATION_PLANNING");
    }

    @Test
    void selectByPageRouteDocumentDetail() {
        AgentPageContext page = new AgentPageContext("DOCUMENT_DETAIL", null, null, null, null);
        AgentSkill skill = registry.select(null, null, page);
        assertThat(skill.code()).isEqualTo("MEETING_TO_TASKS");
    }

    @Test
    void selectByPageRouteUnknownDefaultsToProjectResearch() {
        AgentPageContext page = new AgentPageContext("UNKNOWN_ROUTE", null, null, null, null);
        AgentSkill skill = registry.select(null, null, page);
        assertThat(skill.code()).isEqualTo("PROJECT_RESEARCH");
    }

    @Test
    void selectDefaultToProjectResearch() {
        AgentSkill skill = registry.select(null, null, null);
        assertThat(skill.code()).isEqualTo("PROJECT_RESEARCH");
    }

    @Test
    void selectGoalTakesPriorityOverRoute() {
        // goal 匹配 WEEKLY_REPORT，route 匹配 PROJECT_HEALTH
        AgentPageContext page = new AgentPageContext("DASHBOARD", null, null, null, null);
        AgentSkill skill = registry.select(null, "周报", page);
        assertThat(skill.code()).isEqualTo("WEEKLY_REPORT");
    }

    @Test
    void selectExplicitCodeTakesPriorityOverGoalAndRoute() {
        AgentPageContext page = new AgentPageContext("DASHBOARD", null, null, null, null);
        AgentSkill skill = registry.select("DELIVERY_READINESS", "周报", page);
        assertThat(skill.code()).isEqualTo("DELIVERY_READINESS");
    }

    @Test
    void existsForRegisteredSkills() {
        assertThat(registry.exists("PROJECT_HEALTH")).isTrue();
        assertThat(registry.exists("WEEKLY_REPORT")).isTrue();
        assertThat(registry.exists("MEETING_TO_TASKS")).isTrue();
        assertThat(registry.exists("ITERATION_PLANNING")).isTrue();
        assertThat(registry.exists("DELIVERY_READINESS")).isTrue();
        assertThat(registry.exists("PROJECT_RESEARCH")).isTrue();
    }

    @Test
    void existsForUnregisteredSkill() {
        assertThat(registry.exists("NONEXISTENT")).isFalse();
    }

    @Test
    void allSkillsHaveAllowedTools() {
        for (AgentSkill skill : registry.listAll()) {
            assertThat(skill.allowedTools()).isNotEmpty();
        }
    }

    @Test
    void allSkillsHaveInstruction() {
        for (AgentSkill skill : registry.listAll()) {
            assertThat(skill.instruction()).isNotBlank();
        }
    }

    @Test
    void allSkillsHaveOutputContract() {
        for (AgentSkill skill : registry.listAll()) {
            assertThat(skill.outputContract()).isNotBlank();
        }
    }

    @Test
    void writeSkillsAllowWriteTools() {
        assertThat(registry.require("MEETING_TO_TASKS").allowWriteTools()).isTrue();
        assertThat(registry.require("ITERATION_PLANNING").allowWriteTools()).isTrue();
    }

    @Test
    void readOnlySkillsDisallowWriteTools() {
        assertThat(registry.require("PROJECT_HEALTH").allowWriteTools()).isFalse();
        assertThat(registry.require("WEEKLY_REPORT").allowWriteTools()).isFalse();
        assertThat(registry.require("DELIVERY_READINESS").allowWriteTools()).isFalse();
        assertThat(registry.require("PROJECT_RESEARCH").allowWriteTools()).isFalse();
    }

    @Test
    void selectCaseInsensitive() {
        AgentSkill skill = registry.select("project_health", null, null);
        assertThat(skill.code()).isEqualTo("PROJECT_HEALTH");
    }
}
