package com.shitulelv.aicollab.agent.domain.model;

import com.shitulelv.aicollab.agent.domain.model.builtin.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class AgentScenarioRegistryTest {

    private final AgentScenarioRegistry registry = new AgentScenarioRegistry();

    @Test
    void explicitScenarioGrantsWriteCapableTools() {
        assertThat(registry.select("ITERATION_PLANNING", null).grantedTools())
                .contains("create_task_after_approval", "update_task_after_approval");
    }

    @Test
    void oldKeywordGoalsWithoutExplicitCodeFallBackToProjectResearch() {
        // 关键词路由（提案/周报/创建任务/迭代/会议/交付就绪等）已移除：
        // 没有显式 code 时一律兜底只读研究场景，不得隐式获得写工作流。
        for (String goal : List.of(
                "继续生成刚才的待审批提案，工时改为3小时",
                "生成周报",
                "创建任务",
                "规划下一个迭代",
                "从会议纪要提取任务",
                "检查交付就绪度")) {
            AgentScenario scenario = registry.select(null, null);
            assertThat(scenario.code())
                    .as("goal=%s 不得再触发关键词路由", goal)
                    .isEqualTo("PROJECT_RESEARCH");
            assertThat(scenario.grantedTools())
                    .as("goal=%s 不得隐式获得写工具", goal)
                    .doesNotContain("create_task_after_approval", "update_task_after_approval");
        }
    }

    @Test
    void allSixBuiltinScenariosRegistered() {
        assertThat(registry.listAll()).hasSize(6);
    }

    @Test
    void scenarioCodesAreUnique() {
        Set<String> codes = registry.listAll().stream()
                .map(AgentScenario::code)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(codes).hasSize(6);
    }

    @Test
    void requireExistingScenario() {
        AgentScenario scenario = registry.require("PROJECT_HEALTH");
        assertThat(scenario.code()).isEqualTo("PROJECT_HEALTH");
        assertThat(scenario.displayName()).isEqualTo("检查项目健康度");
    }

    @Test
    void requireNonExistingScenarioThrows() {
        assertThatThrownBy(() -> registry.require("NONEXISTENT"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scenario 不存在");
    }

    @Test
    void selectExplicitCode() {
        AgentScenario scenario = registry.select("WEEKLY_REPORT", null);
        assertThat(scenario.code()).isEqualTo("WEEKLY_REPORT");
    }

    @Test
    void selectCaseInsensitive() {
        AgentScenario scenario = registry.select("project_health", null);
        assertThat(scenario.code()).isEqualTo("PROJECT_HEALTH");
    }

    @Test
    void selectExplicitCodeTakesPriorityOverRoute() {
        AgentPageContext page = new AgentPageContext("DASHBOARD", null, null, null, null);
        AgentScenario scenario = registry.select("DELIVERY_READINESS", page);
        assertThat(scenario.code()).isEqualTo("DELIVERY_READINESS");
    }

    @Test
    void selectByPageRouteDashboard() {
        AgentPageContext page = new AgentPageContext("DASHBOARD", null, null, null, null);
        AgentScenario scenario = registry.select(null, page);
        assertThat(scenario.code()).isEqualTo("PROJECT_HEALTH");
    }

    @Test
    void selectByPageRouteTaskBoard() {
        AgentPageContext page = new AgentPageContext("TASK_BOARD", null, null, null, null);
        AgentScenario scenario = registry.select(null, page);
        assertThat(scenario.code()).isEqualTo("ITERATION_PLANNING");
    }

    @Test
    void selectByPageRouteMilestoneList() {
        AgentPageContext page = new AgentPageContext("MILESTONE_LIST", null, null, null, null);
        AgentScenario scenario = registry.select(null, page);
        assertThat(scenario.code()).isEqualTo("ITERATION_PLANNING");
    }

    @Test
    void selectByPageRoutePlanning() {
        AgentPageContext page = new AgentPageContext("PLANNING", null, null, null, null);
        AgentScenario scenario = registry.select(null, page);
        assertThat(scenario.code()).isEqualTo("ITERATION_PLANNING");
    }

    @Test
    void selectByPageRouteDocumentDetailFallsBackToProjectResearch() {
        // DOCUMENT → MEETING_TO_TASKS 的硬映射已移除；文档页不再隐式进入写工作流。
        AgentPageContext page = new AgentPageContext("DOCUMENT_DETAIL", null, null, null, null);
        AgentScenario scenario = registry.select(null, page);
        assertThat(scenario.code()).isEqualTo("PROJECT_RESEARCH");
    }

    @Test
    void selectByPageRouteUnknownDefaultsToProjectResearch() {
        AgentPageContext page = new AgentPageContext("UNKNOWN_ROUTE", null, null, null, null);
        AgentScenario scenario = registry.select(null, page);
        assertThat(scenario.code()).isEqualTo("PROJECT_RESEARCH");
    }

    @Test
    void selectDefaultToProjectResearch() {
        AgentScenario scenario = registry.select(null, null);
        assertThat(scenario.code()).isEqualTo("PROJECT_RESEARCH");
    }

    @Test
    void existsForRegisteredScenarios() {
        assertThat(registry.exists("PROJECT_HEALTH")).isTrue();
        assertThat(registry.exists("WEEKLY_REPORT")).isTrue();
        assertThat(registry.exists("MEETING_TO_TASKS")).isTrue();
        assertThat(registry.exists("ITERATION_PLANNING")).isTrue();
        assertThat(registry.exists("DELIVERY_READINESS")).isTrue();
        assertThat(registry.exists("PROJECT_RESEARCH")).isTrue();
    }

    @Test
    void existsForUnregisteredScenario() {
        assertThat(registry.exists("NONEXISTENT")).isFalse();
    }

    @Test
    void allScenariosHaveGrantedTools() {
        for (AgentScenario scenario : registry.listAll()) {
            assertThat(scenario.grantedTools()).isNotEmpty();
        }
    }

    @Test
    void allScenariosHaveInstruction() {
        for (AgentScenario scenario : registry.listAll()) {
            assertThat(scenario.instruction()).isNotBlank();
        }
    }

    @Test
    void allScenariosHaveOutputContract() {
        for (AgentScenario scenario : registry.listAll()) {
            assertThat(scenario.outputContract()).isNotBlank();
        }
    }

    @Test
    void writeScenariosAreWorkflowsAndAllowWriteTools() {
        for (String code : List.of("MEETING_TO_TASKS", "ITERATION_PLANNING")) {
            AgentScenario scenario = registry.require(code);
            assertThat(scenario.allowWriteTools()).as(code).isTrue();
            assertThat(scenario.category()).as(code).isEqualTo(AgentScenarioCategory.WORKFLOW);
        }
    }

    @Test
    void readOnlyScenariosAreFixedScenarios() {
        for (String code : List.of("PROJECT_HEALTH", "WEEKLY_REPORT", "DELIVERY_READINESS", "PROJECT_RESEARCH")) {
            AgentScenario scenario = registry.require(code);
            assertThat(scenario.allowWriteTools()).as(code).isFalse();
            assertThat(scenario.category()).as(code).isEqualTo(AgentScenarioCategory.SCENARIO);
        }
    }

    @Test
    void builtinScenariosAreNotChildScope() {
        for (AgentScenario scenario : registry.listAll()) {
            assertThat(scenario.childScope()).as(scenario.code()).isFalse();
        }
    }
}
