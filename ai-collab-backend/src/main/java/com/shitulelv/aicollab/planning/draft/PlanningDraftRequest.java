package com.shitulelv.aicollab.planning.draft;

import java.util.List;

/**
 * 发往 Python {@code POST /v1/planning/draft} 的请求契约（W5 冻结 §1 逐字段对应）。
 *
 * <p>字段名与大小写是跨语言契约的一部分，不得自创或改名。日期用 ISO 字符串
 * （{@code yyyy-MM-dd}），避免依赖宿主的 Jackson 时间模块配置。请求只承载有界快照：
 * goal ≤4000、项目名 ≤200、描述 ≤4000、成员 ≤50、既有标题各 ≤40，由
 * {@link PlanningDraftShadowService} 在发送前收敛。</p>
 */
public record PlanningDraftRequest(
        int schemaVersion,
        String goal,
        String locale,
        ProjectFacts projectFacts,
        String template,
        Constraints constraints,
        int deadlineMs) {

    public PlanningDraftRequest {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("planning draft schemaVersion 必须为 1");
        }
        locale = locale == null || locale.isBlank() ? "zh-CN" : locale;
        projectFacts = projectFacts == null ? ProjectFacts.EMPTY : projectFacts;
        template = template == null || template.isBlank() ? "ITERATION" : template;
        constraints = constraints == null ? Constraints.DEFAULT : constraints;
        if (deadlineMs < 1) {
            throw new IllegalArgumentException("planning draft deadlineMs 必须为正数");
        }
    }

    /** 项目事实（有界快照；不含文档正文、成员邮箱或 Prompt）。 */
    public record ProjectFacts(
            String projectName,
            String description,
            List<Member> members,
            List<String> existingMilestoneTitles,
            List<String> existingTaskTitles) {

        public static final ProjectFacts EMPTY = new ProjectFacts("", "", List.of(), List.of(), List.of());

        public ProjectFacts {
            projectName = projectName == null ? "" : projectName;
            description = description == null ? "" : description;
            members = members == null ? List.of() : List.copyOf(members);
            existingMilestoneTitles = existingMilestoneTitles == null
                    ? List.of() : List.copyOf(existingMilestoneTitles);
            existingTaskTitles = existingTaskTitles == null
                    ? List.of() : List.copyOf(existingTaskTitles);
        }
    }

    /** 成员事实：只含 id 与显示名（不传递邮箱/账号等敏感字段）。 */
    public record Member(String id, String name) {
        public Member {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
        }
    }

    /** 生成约束：maxTasks 1–40、maxMilestones 1–8；日期可空。 */
    public record Constraints(
            int maxTasks,
            int maxMilestones,
            String startDate,
            String dueDate) {

        public static final Constraints DEFAULT = new Constraints(20, 6, null, null);

        public Constraints {
            if (maxTasks < 1) {
                maxTasks = 1;
            }
            if (maxMilestones < 1) {
                maxMilestones = 1;
            }
            startDate = startDate == null || startDate.isBlank() ? null : startDate;
            dueDate = dueDate == null || dueDate.isBlank() ? null : dueDate;
        }
    }
}
