package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.domain.DetailModelOutput;
import com.shitulelv.aicollab.planning.domain.PlanMilestone;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.planning.domain.SkeletonModelOutput;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 规划结构化输出的唯一对外契约门面。
 *
 * <p>生产生成链路（{@link TaskPlanGenerationOrchestrator}）与能力探针
 * （{@code AiCapabilityProbeService}）都必须经由这里取 schema 与做解析，
 * 这样"探针通过"才等价于"真实规划该模型可用"。任何一方复制一份 schema 或
 * 放宽解析规则，都会让探针重新失去鉴别力——这正是线上出现过的真实故障：
 * 探针用宽松的极简形状判定通过，而真实规划要求完整骨架契约，模型在真实
 * 规划中失败。</p>
 */
@Component
public class PlanningJsonContract {
    private final TaskPlanOutputParser parser;
    private final ObjectMapper json;

    public PlanningJsonContract(TaskPlanOutputParser parser, ObjectMapper json) {
        this.parser = parser;
        this.json = json;
    }

    /**
     * 骨架阶段的生产 schema（与真实规划完全一致，含长度上限与最小/最大条目数）。
     *
     * @param maxTaskCount 规划允许的最大任务数，与 {@code TaskPlanGenerationOrchestrator} 同口径
     */
    public String skeletonSchema(int maxTaskCount) {
        return TaskPlanGenerationOrchestrator.skeletonSchema(maxTaskCount);
    }

    /**
     * 用生产解析器校验一段骨架输出。
     *
     * <p>严格语义：未知字段、缺失必填字段、长度/条目数越界一律拒绝
     * （与真实规划失败时的判定完全一致）。</p>
     *
     * @return 解析后的骨架；不符合契约时抛出 {@code ModelOutputContractException}
     */
    public SkeletonModelOutput parseSkeleton(String output) {
        return parser.parseSkeleton(output);
    }

    /**
     * 细节阶段的生产 schema（与真实规划完全一致）。
     *
     * <p>细节阶段比骨架更严格：每个任务的全部业务字段都必须出现（可显式为 null），
     * 且包含 {@code estimatedHours}/{@code startDate}/{@code dueDate}/{@code suggestedAssigneeId}
     * 等 union 类型字段——这些正是上游结构化输出语法最容易拒绝的形状。</p>
     */
    public String detailSchema(List<PlanMilestone> milestones, List<PlanTask> tasks) {
        TaskPlanDraft skeleton = new TaskPlanDraft("探针摘要", List.of(), List.of(),
                milestones, tasks, List.of());
        return TaskPlanGenerationOrchestrator.detailSchema(skeleton, milestones, tasks);
    }

    /**
     * 用生产解析器校验一段细节输出。
     *
     * <p>与骨架阶段同源：未知字段、缺失必填字段、描述长度越界一律拒绝。</p>
     */
    public DetailModelOutput parseDetail(String output) {
        return parser.parseDetail(output);
    }

    /**
     * 校验骨架的业务完整性：里程碑与任务非空，且每个任务都指向已存在的里程碑。
     *
     * <p>这些规则由 {@code TaskPlanDraftValidator} 在真实规划中强制执行，
     * 探针同样必须覆盖，否则"探针通过但真实规划被领域校验拒绝"仍会发生。</p>
     */
    public static boolean isSkeletonCoherent(SkeletonModelOutput skeleton) {
        if (skeleton == null || skeleton.summary() == null || skeleton.summary().isBlank()) return false;
        if (skeleton.milestones() == null || skeleton.milestones().isEmpty()) return false;
        if (skeleton.tasks() == null || skeleton.tasks().isEmpty()) return false;
        var milestoneKeys = skeleton.milestones().stream()
                .map(SkeletonModelOutput.SkeletonMilestone::tempKey)
                .filter(key -> key != null && !key.isBlank())
                .collect(java.util.stream.Collectors.toSet());
        if (milestoneKeys.isEmpty()) return false;
        return skeleton.tasks().stream().allMatch(task ->
                task != null
                        && task.tempKey() != null && !task.tempKey().isBlank()
                        && task.title() != null && !task.title().isBlank()
                        && task.objective() != null && !task.objective().isBlank()
                        && milestoneKeys.contains(task.milestoneTempKey()));
    }

    /**
     * 校验细节输出与骨架的 key 一一对应。
     *
     * <p>这一条是探针曾经最大的盲区：只做 {@code parseDetail} 会放行一个真实规划必然失败的模型。
     * 真实规划在合并细节时走 {@code TaskPlanGenerationOrchestrator.mergeDetailIntoSkeleton}，
     * 它会拒绝重复 key、未知 key 与缺失 key（{@code DETAIL_DUPLICATE_MILESTONE_KEY}、
     * {@code DETAIL_UNKNOWN_MILESTONE_KEY}、{@code DETAIL_MISSING_MILESTONE_KEY} 等），
     * 任何一条不满足都会让整个规划在 DETAIL 阶段失败。
     * 探针若不校验这层，就会出现"探针通过、真实规划失败"的假阳性。</p>
     *
     * @return 不满足一一对应时返回失败原因（可直接用于诊断），满足时返回 {@code null}
     */
    public static String detailKeyMismatch(SkeletonModelOutput skeleton, DetailModelOutput detail) {
        if (skeleton == null || detail == null) return "DETAIL_MISSING_OUTPUT";
        if (detail.milestones() == null || detail.tasks() == null) return "DETAIL_MISSING_OUTPUT";
        if (detail.milestones().size() != skeleton.milestones().size()
                || detail.tasks().size() != skeleton.tasks().size()) {
            return "DETAIL_BATCH_COUNTS_MUST_MATCH";
        }
        var expectedMilestones = skeleton.milestones().stream()
                .map(SkeletonModelOutput.SkeletonMilestone::tempKey)
                .collect(java.util.stream.Collectors.toSet());
        var expectedTasks = skeleton.tasks().stream()
                .map(SkeletonModelOutput.SkeletonTask::tempKey)
                .collect(java.util.stream.Collectors.toSet());
        var actualMilestones = new java.util.HashSet<String>();
        for (var item : detail.milestones()) {
            if (item == null || item.tempKey() == null || !actualMilestones.add(item.tempKey())) {
                return "DETAIL_DUPLICATE_MILESTONE_KEY";
            }
        }
        var actualTasks = new java.util.HashSet<String>();
        for (var item : detail.tasks()) {
            if (item == null || item.tempKey() == null || !actualTasks.add(item.tempKey())) {
                return "DETAIL_DUPLICATE_TASK_KEY";
            }
        }
        if (!actualMilestones.equals(expectedMilestones)) return "DETAIL_MILESTONE_KEYS_MUST_MATCH";
        if (!actualTasks.equals(expectedTasks)) return "DETAIL_TASK_KEYS_MUST_MATCH";
        return null;
    }

    /**
     * 生产同源的修复提示词。
     *
     * <p>真实规划在某一阶段输出不合契约时，不是原样重试，而是把
     * <b>原始输出（base64）</b>连同<b>结构化失败信息</b>（category / jsonPath / validationCodes）
     * 和<b>该阶段的 schema</b>一起交给模型做一次定向修复。
     * 探针必须用同一种修复方式，否则探针的稳定性会低于真实规划，
     * 表现为"探针偶发失败、真实规划却成功"（误报）。</p>
     *
     * <p>原始输出以 base64 承载，避免不可信内容破坏提示词结构
     * （与生产 {@code TaskPlanGenerationOrchestrator.repairPrompt} 一致）。</p>
     */
    public String repairPrompt(String raw, String schema, String stage,
                               String category, String jsonPath, List<String> validationCodes) {
        return "<UNTRUSTED_INVALID_OUTPUT_BASE64>\n"
                + java.util.Base64.getEncoder().encodeToString(
                        (raw == null ? "" : raw).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "\n</UNTRUSTED_INVALID_OUTPUT_BASE64>\n"
                + "stage=" + stage + "\n"
                + "category=" + (category == null ? "UNKNOWN" : category) + "\n"
                + (jsonPath != null ? "path=" + jsonPath + "\n" : "")
                + (validationCodes != null && !validationCodes.isEmpty()
                        ? "validation_codes=" + String.join(",", validationCodes) + "\n" : "")
                + "错误码=PLANNING_MODEL_INVALID_OUTPUT"
                + "\n<JSON_SCHEMA>\n" + schema + "\n</JSON_SCHEMA>\n"
                + "只输出 JSON。";
    }
}
