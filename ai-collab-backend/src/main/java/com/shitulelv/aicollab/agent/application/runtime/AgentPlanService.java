package com.shitulelv.aicollab.agent.application.runtime;

import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.*;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 管理执行计划。支持初始计划和 Replan。
 * 计划步骤必须结构化，不允许模型通过计划绕过工具策略。
 */
@Service
public class AgentPlanService {
    private final AgentRepository repository;
    private final ObjectMapper json;

    public AgentPlanService(AgentRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    /**
     * 确保 Run 有执行计划。如果没有，根据 Skill 模板生成。
     */
    public AgentPlan ensurePlan(AgentRunView run, AgentSkill skill) {
        // 尝试从 Run 的 planJson 加载
        AgentPlan existing = loadPlan(run);
        if (existing != null) {
            return existing;
        }

        // 根据 Skill 模板生成计划
        AgentPlan plan = createPlan(skill, run.goal(), run.pageContextJson());
        savePlan(run, plan);
        return plan;
    }

    /**
     * 更新计划（Replan）。
     */
    public AgentPlan replan(AgentRunView run, AgentSkill skill, String reason) {
        AgentPlan current = loadPlan(run);
        int version = current != null ? current.version() + 1 : 1;
        AgentPlan newPlan = createPlan(skill, run.goal(), run.pageContextJson());
        AgentPlan replanned = newPlan.withVersion(version);
        savePlan(run, replanned);
        return replanned;
    }

    /**
     * 加载当前计划。
     */
    public AgentPlan loadPlan(AgentRunView run) {
        if (run.planJson() == null || run.planJson().isBlank()) {
            return null;
        }
        try {
            return json.readValue(run.planJson(), AgentPlan.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 根据 Skill 模板生成计划。
     */
    private AgentPlan createPlan(AgentSkill skill, String goal, String pageContextJson) {
        return switch (skill.code()) {
            case "PROJECT_HEALTH" -> healthPlan(goal);
            case "WEEKLY_REPORT" -> weeklyPlan(goal);
            case "MEETING_TO_TASKS" -> meetingPlan(goal);
            case "ITERATION_PLANNING" -> iterationPlan(goal);
            case "DELIVERY_READINESS" -> deliveryPlan(goal);
            default -> researchPlan(goal);
        };
    }

    private AgentPlan healthPlan(String goal) {
        List<AgentPlanStep> steps = new ArrayList<>();
        steps.add(new AgentPlanStep("1", "获取项目快照", "了解项目整体状态", AgentPlanStepStatus.PENDING,
                List.of("project.get_snapshot")));
        steps.add(new AgentPlanStep("2", "查询任务状态", "了解任务分布和逾期情况", AgentPlanStepStatus.PENDING,
                List.of("task.search")));
        steps.add(new AgentPlanStep("3", "查询里程碑", "了解里程碑进展", AgentPlanStepStatus.PENDING,
                List.of("milestone.list")));
        steps.add(new AgentPlanStep("4", "生成健康报告", "基于收集的数据生成报告", AgentPlanStepStatus.PENDING,
                List.of()));
        return AgentPlan.create("检查项目健康度", steps);
    }

    private AgentPlan weeklyPlan(String goal) {
        List<AgentPlanStep> steps = new ArrayList<>();
        steps.add(new AgentPlanStep("1", "获取近期活动", "了解项目近期进展", AgentPlanStepStatus.PENDING,
                List.of("project.get_recent_activity")));
        steps.add(new AgentPlanStep("2", "查询任务状态", "了解任务完成情况", AgentPlanStepStatus.PENDING,
                List.of("task.search")));
        steps.add(new AgentPlanStep("3", "查询里程碑", "了解里程碑进展", AgentPlanStepStatus.PENDING,
                List.of("milestone.list")));
        steps.add(new AgentPlanStep("4", "生成周报", "基于数据生成结构化周报", AgentPlanStepStatus.PENDING,
                List.of()));
        return AgentPlan.create("生成项目周报", steps);
    }

    private AgentPlan meetingPlan(String goal) {
        List<AgentPlanStep> steps = new ArrayList<>();
        steps.add(new AgentPlanStep("1", "获取文档内容", "读取会议纪要", AgentPlanStepStatus.PENDING,
                List.of("document.get_metadata")));
        steps.add(new AgentPlanStep("2", "搜索现有任务", "检查是否有重复任务", AgentPlanStepStatus.PENDING,
                List.of("task.search")));
        steps.add(new AgentPlanStep("3", "提取待办事项", "从会议纪要中提取任务", AgentPlanStepStatus.PENDING,
                List.of()));
        steps.add(new AgentPlanStep("4", "创建任务（需审批）", "批量创建新任务", AgentPlanStepStatus.PENDING,
                List.of("task.create_batch")));
        return AgentPlan.create("从会议纪要生成任务", steps);
    }

    private AgentPlan iterationPlan(String goal) {
        List<AgentPlanStep> steps = new ArrayList<>();
        steps.add(new AgentPlanStep("1", "获取里程碑", "了解当前迭代目标", AgentPlanStepStatus.PENDING,
                List.of("milestone.get")));
        steps.add(new AgentPlanStep("2", "查询任务", "了解待办和进行中任务", AgentPlanStepStatus.PENDING,
                List.of("task.search")));
        steps.add(new AgentPlanStep("3", "分析工作负载", "评估团队能力", AgentPlanStepStatus.PENDING,
                List.of("task.get_workload")));
        steps.add(new AgentPlanStep("4", "规划任务分配（需审批）", "创建或更新任务", AgentPlanStepStatus.PENDING,
                List.of("task.create_batch", "task.update")));
        return AgentPlan.create("规划迭代", steps);
    }

    private AgentPlan deliveryPlan(String goal) {
        List<AgentPlanStep> steps = new ArrayList<>();
        steps.add(new AgentPlanStep("1", "检查交付就绪度", "评估整体状态", AgentPlanStepStatus.PENDING,
                List.of("delivery.check_readiness")));
        steps.add(new AgentPlanStep("2", "查询任务", "了解未完成项", AgentPlanStepStatus.PENDING,
                List.of("task.search")));
        steps.add(new AgentPlanStep("3", "检查文档", "确认文档完整性", AgentPlanStepStatus.PENDING,
                List.of("document.get_metadata")));
        steps.add(new AgentPlanStep("4", "生成报告", "基于数据生成交付报告", AgentPlanStepStatus.PENDING,
                List.of()));
        return AgentPlan.create("检查交付就绪度", steps);
    }

    private AgentPlan researchPlan(String goal) {
        List<AgentPlanStep> steps = new ArrayList<>();
        steps.add(new AgentPlanStep("1", "搜索知识库", "查找相关资料", AgentPlanStepStatus.PENDING,
                List.of("knowledge.search")));
        steps.add(new AgentPlanStep("2", "获取文档元数据", "了解相关文档", AgentPlanStepStatus.PENDING,
                List.of("document.get_metadata")));
        steps.add(new AgentPlanStep("3", "生成研究报告", "基于事实生成报告", AgentPlanStepStatus.PENDING,
                List.of()));
        return AgentPlan.create("项目研究", steps);
    }

    private void savePlan(AgentRunView run, AgentPlan plan) {
        try {
            String planJson = json.writeValueAsString(plan);
            repository.updatePlan(run.projectId(), run.id(), run.version(), planJson);
        } catch (Exception e) {
            throw new IllegalStateException("保存计划失败", e);
        }
    }
}
