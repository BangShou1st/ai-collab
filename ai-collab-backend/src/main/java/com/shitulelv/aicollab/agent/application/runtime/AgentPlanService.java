package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentPlan;
import com.shitulelv.aicollab.agent.domain.model.AgentPlanStep;
import com.shitulelv.aicollab.agent.domain.model.AgentPlanStepStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** 保存可展示的 Skill 参考步骤；实际执行和恢复由调用记录负责。 */
@Service
public class AgentPlanService {
    private final AgentRepository repository;
    private final ObjectMapper json;

    public AgentPlanService(AgentRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    public AgentPlan ensurePlan(AgentRunView run, AgentSkill skill) {
        AgentPlan existing = loadPlan(run);
        if (existing != null) return existing;
        AgentPlan plan = createPlan(skill.code());
        try {
            repository.updatePlan(run.projectId(), run.id(), run.version(), json.writeValueAsString(plan));
        } catch (Exception failure) {
            throw new IllegalStateException("保存参考步骤失败", failure);
        }
        return plan;
    }

    public AgentPlan loadPlan(AgentRunView run) {
        if (run.planJson() == null || run.planJson().isBlank()) return null;
        try {
            return json.readValue(run.planJson(), AgentPlan.class);
        } catch (Exception invalidStoredPlan) {
            return null;
        }
    }

    private static AgentPlan createPlan(String skillCode) {
        return switch (skillCode) {
            case "PROJECT_HEALTH" -> AgentPlan.create("检查项目健康度", List.of(
                    reference("1", "获取项目快照", "了解项目整体状态"),
                    reference("2", "查询任务状态", "了解任务分布和逾期情况"),
                    reference("3", "查询里程碑", "了解里程碑进展"),
                    reference("4", "生成健康报告", "基于收集的数据生成报告")));
            case "WEEKLY_REPORT" -> AgentPlan.create("生成项目周报", List.of(
                    reference("1", "获取近期活动", "了解项目近期进展"),
                    reference("2", "查询任务状态", "了解任务完成情况"),
                    reference("3", "查询里程碑", "了解里程碑进展"),
                    reference("4", "生成周报", "基于数据生成结构化周报")));
            case "MEETING_TO_TASKS" -> AgentPlan.create("从会议纪要生成任务", List.of(
                    reference("1", "获取文档内容", "读取会议纪要"),
                    reference("2", "搜索现有任务", "检查是否有重复任务"),
                    reference("3", "提取待办事项", "从会议纪要中提取任务"),
                    reference("4", "创建任务（需审批）", "批量创建新任务")));
            case "ITERATION_PLANNING" -> AgentPlan.create("规划迭代", List.of(
                    reference("1", "获取里程碑", "了解当前迭代目标"),
                    reference("2", "查询任务", "了解待办和进行中任务"),
                    reference("3", "分析工作负载", "评估团队能力"),
                    reference("4", "规划任务分配（需审批）", "创建或更新任务")));
            case "DELIVERY_READINESS" -> AgentPlan.create("检查交付就绪度", List.of(
                    reference("1", "检查交付就绪度", "评估整体状态"),
                    reference("2", "查询任务", "了解未完成项"),
                    reference("3", "检查文档", "确认文档完整性"),
                    reference("4", "生成报告", "基于数据生成交付报告")));
            default -> AgentPlan.create("项目研究", List.of(
                    reference("1", "搜索知识库", "查找相关资料"),
                    reference("2", "获取文档元数据", "了解相关文档"),
                    reference("3", "生成研究报告", "基于事实生成报告")));
        };
    }

    private static AgentPlanStep reference(String id, String title, String purpose) {
        // 保留既有 JSON 字段；这些值不充当执行状态或工具权限。
        return new AgentPlanStep(id, title, purpose, AgentPlanStepStatus.PENDING, List.of());
    }
}
