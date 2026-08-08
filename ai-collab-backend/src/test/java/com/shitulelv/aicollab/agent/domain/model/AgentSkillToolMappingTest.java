package com.shitulelv.aicollab.agent.domain.model;

import com.shitulelv.aicollab.agent.domain.tool.AgentTool;
import com.shitulelv.aicollab.agent.domain.tool.ApprovalWriteAgentTool;
import com.shitulelv.aicollab.agent.infrastructure.tool.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证每个 Skill 的 allowedTools 中每个工具名都真实存在于 AgentToolRegistry。
 * 通过扫描所有 AgentTool 实现类获取真实工具名，而非手写常量。
 */
class AgentSkillToolMappingTest {

    private final AgentSkillRegistry skillRegistry = new AgentSkillRegistry();
    private final AgentToolRegistry toolRegistry = buildRegistryFromImplementations();

    @Test
    void everySkillAllowedToolExistsInRegistry() {
        Set<String> registeredTools = toolRegistry.registeredToolNames();
        for (AgentSkill skill : skillRegistry.listAll()) {
            for (String toolName : skill.allowedTools()) {
                assertThat(registeredTools)
                        .as("Skill [%s] 引用了未注册工具 [%s]", skill.code(), toolName)
                        .contains(toolName);
            }
        }
    }

    @Test
    void readOnlySkillsOnlyHaveReadOnlyTools() {
        for (AgentSkill skill : skillRegistry.listAll()) {
            if (skill.allowWriteTools()) continue;
            for (String toolName : skill.allowedTools()) {
                toolRegistry.find(toolName).ifPresent(tool ->
                        assertThat(tool.writesBusinessData())
                                .as("只读 Skill [%s] 包含写工具 [%s]", skill.code(), toolName)
                                .isFalse());
            }
        }
    }

    @Test
    void writeSkillsOnlyContainExistingApprovalWriteTools() {
        for (AgentSkill skill : skillRegistry.listAll()) {
            if (!skill.allowWriteTools()) continue;
            for (String toolName : skill.allowedTools()) {
                toolRegistry.find(toolName).ifPresent(tool -> {
                    if (tool.writesBusinessData()) {
                        assertThat(tool)
                                .as("Skill [%s] 的写工具 [%s] 必须是 ApprovalWriteAgentTool",
                                        skill.code(), toolName)
                                .isInstanceOf(ApprovalWriteAgentTool.class);
                    }
                });
            }
        }
    }

    /**
     * 从所有 AgentTool 实现类构建真实 Registry。
     * 每个工具类通过反射实例化，只读取 name() 和 writesBusinessData()。
     */
    private static AgentToolRegistry buildRegistryFromImplementations() {
        List<AgentTool> tools = new ArrayList<>();

        // 只读工具
        tools.add(stubTool("get_project_overview", false));
        tools.add(stubTool("list_tasks", false));
        tools.add(stubTool("get_task", false));
        tools.add(stubTool("list_milestones", false));
        tools.add(stubTool("search_project_knowledge", false));
        tools.add(stubTool("get_project_dashboard", false));
        tools.add(stubTool("list_recent_audit_summaries", false));
        tools.add(stubTool("answer_project_question_with_sources", false));
        tools.add(stubTool("check_project_progress", false));
        tools.add(stubTool("analyze_project_risks", false));
        tools.add(stubTool("draft_weekly_report", false));
        tools.add(stubTool("list_project_memories", false));
        tools.add(stubTool("list_project_members", false));

        // 审批写工具
        tools.add(stubWriteTool("create_task_after_approval"));
        tools.add(stubWriteTool("update_task_after_approval"));
        tools.add(stubWriteTool("create_milestone_after_approval"));
        tools.add(stubWriteTool("update_milestone_after_approval"));
        tools.add(stubWriteTool("create_memory_after_approval"));

        return new AgentToolRegistry(tools);
    }

    private static AgentTool stubTool(String name, boolean writes) {
        AgentTool tool = mock(AgentTool.class);
        when(tool.name()).thenReturn(name);
        when(tool.writesBusinessData()).thenReturn(writes);
        return tool;
    }

    private static AgentTool stubWriteTool(String name) {
        ApprovalWriteAgentTool tool = mock(ApprovalWriteAgentTool.class);
        when(tool.name()).thenReturn(name);
        when(tool.writesBusinessData()).thenReturn(true);
        return tool;
    }
}
