package com.shitulelv.aicollab.agent.infrastructure;

import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentToolRegistry depth 权限测试。
 * 验证不同 depth 和 role 组合下工具的可见性。
 */
class AgentToolRegistryDepthTest {
    private AgentTool readOnlyTool;
    private AgentTool writeTool;
    private AgentToolRegistry registry;

    @BeforeEach
    void setUp() {
        readOnlyTool = mock(AgentTool.class);
        when(readOnlyTool.name()).thenReturn("list_tasks");
        when(readOnlyTool.writesBusinessData()).thenReturn(false);
        when(readOnlyTool.definition()).thenReturn(
                AgentToolDefinition.openObject("list_tasks", "desc", false));

        writeTool = mock(ApprovalWriteAgentTool.class);
        when(writeTool.name()).thenReturn("create_task_after_approval");
        when(writeTool.writesBusinessData()).thenReturn(true);
        when(writeTool.definition()).thenReturn(
                AgentToolDefinition.openObject("create_task_after_approval", "desc", true));

        registry = new AgentToolRegistry(List.of(readOnlyTool, writeTool));
    }

    @Test
    void depthZeroSupervisorSeesApprovalTools() {
        AgentExecutionContext ctx = context("SUPERVISOR", 0);
        AgentSkill skill = skillWithBothTools();

        List<AgentToolDefinition> defs = registry.definitionsFor(ctx, skill);

        assertThat(defs).hasSize(2);
        assertThat(defs).extracting(AgentToolDefinition::name)
                .contains("list_tasks", "create_task_after_approval");
    }

    @Test
    void depthGreaterThanZeroSupervisorCannotSeeApprovalTools() {
        AgentExecutionContext ctx = context("SUPERVISOR", 1);
        AgentSkill skill = skillWithBothTools();

        List<AgentToolDefinition> defs = registry.definitionsFor(ctx, skill);

        assertThat(defs).hasSize(1);
        assertThat(defs).extracting(AgentToolDefinition::name).containsExactly("list_tasks");
    }

    @Test
    void projectRolesCanProposeApprovalToolsAtDepthZero() {
        AgentSkill skill = skillWithBothTools();

        for (String role : List.of("OWNER", "ADMIN", "MEMBER")) {
            List<AgentToolDefinition> defs = registry.definitionsFor(context(role, 0), skill);
            assertThat(defs).extracting(AgentToolDefinition::name)
                    .contains("list_tasks", "create_task_after_approval");
        }
    }

    @Test
    void readOnlyToolsVisibleForDifferentDepths() {
        AgentSkill readOnlySkill = skillReadOnlyOnly();

        // depth=0, SUPERVISOR
        List<AgentToolDefinition> defs0 = registry.definitionsFor(context("SUPERVISOR", 0), readOnlySkill);
        assertThat(defs0).hasSize(1);
        assertThat(defs0.get(0).name()).isEqualTo("list_tasks");

        // depth=5, SUPERVISOR
        List<AgentToolDefinition> defs5 = registry.definitionsFor(context("SUPERVISOR", 5), readOnlySkill);
        assertThat(defs5).hasSize(1);
        assertThat(defs5.get(0).name()).isEqualTo("list_tasks");

        // depth=0, MEMBER
        List<AgentToolDefinition> defsMember = registry.definitionsFor(context("MEMBER", 0), readOnlySkill);
        assertThat(defsMember).hasSize(1);
        assertThat(defsMember.get(0).name()).isEqualTo("list_tasks");
    }

    @Test
    void skillCannotExpandRoleOrDepthPermissions() {
        // Skill 声明允许 write 工具，但 MEMBER 无权看到
        AgentExecutionContext memberCtx = context("UNKNOWN", 0);
        AgentSkill writeSkill = skillWithBothTools();

        List<AgentToolDefinition> defs = registry.definitionsFor(memberCtx, writeSkill);

        // 只能看到只读工具，写工具被权限策略过滤
        assertThat(defs).hasSize(1);
        assertThat(defs.get(0).name()).isEqualTo("list_tasks");
    }

    // ========== 辅助方法 ==========

    private AgentExecutionContext context(String role, int depth) {
        return new AgentExecutionContext(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                role, false, AgentPageContext.empty(), AgentRuntimeLimits.defaults(), depth);
    }

    private AgentSkill skillWithBothTools() {
        AgentSkill skill = mock(AgentSkill.class);
        when(skill.allowedTools()).thenReturn(Set.of("list_tasks", "create_task_after_approval"));
        return skill;
    }

    private AgentSkill skillReadOnlyOnly() {
        AgentSkill skill = mock(AgentSkill.class);
        when(skill.allowedTools()).thenReturn(Set.of("list_tasks"));
        return skill;
    }
}
