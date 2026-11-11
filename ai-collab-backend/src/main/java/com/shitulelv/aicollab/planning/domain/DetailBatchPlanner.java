package com.shitulelv.aicollab.planning.domain;

import com.shitulelv.aicollab.common.ai.token.TokenEstimator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 详情阶段动态批次规划器（设计文档 §5.4）。
 *
 * <p>替换写死的 {@code MAX_MODEL_TASKS_PER_BATCH=4} 切批，两条硬约束：</p>
 * <ol>
 *   <li><b>依赖拓扑</b>：同批任务之间不得有依赖边（跨批依赖允许）——避免详情互相引用失效；
 *       依赖图来自骨架/最新草稿的 {@code dependencyTempKeys}，只统计两端都在草案内的边，
 *       悬挂引用（指向不存在任务的依赖）交给领域校验器处理，不参与分批。</li>
 *   <li><b>token 预算</b>：批成本 = 基础 prompt + 输入实体 + 输出预留，用全后端唯一的
 *       {@link TokenEstimator} 估算，不得超过 maxBatchTokens。</li>
 * </ol>
 *
 * <p>算法：拓扑序（Kahn，同层保持原排序稳定）+ 贪心最早可放批次——对每个任务按拓扑序
 * 尝试放入最早的、既无依赖冲突又不超 token 预算的批次，放不下则新开批次。
 * 纯函数、无状态，单测覆盖：链式依赖分批、无依赖合并批、超预算拆批。</p>
 */
public final class DetailBatchPlanner {

    /** 基础 prompt 开销（计划输入 + JSON Schema + 业务规则 + 成员上下文 + 来源段）的估算常量。 */
    static final int BASE_PROMPT_TOKENS = 3_000;

    /** 单个里程碑的输出预留（description ≤400 字符 + sourceRefs 的 CJK 折算上界）。 */
    static final int OUTPUT_TOKENS_PER_MILESTONE = 400;

    /** 单个任务的输出预留（description ≤400 字符 + priority/工时/日期/依赖/来源字段）。 */
    static final int OUTPUT_TOKENS_PER_TASK = 550;

    /** 单批任务数安全上限：token 预算之外的输出规模兜底（防超长 description 挤爆输出窗口）。 */
    static final int MAX_TASKS_PER_BATCH = 8;

    private DetailBatchPlanner() {
    }

    /** 一个详情批次：本批需要补全的里程碑与任务（都是草案实体的子集引用）。 */
    public record Batch(List<PlanMilestone> milestones, List<PlanTask> tasks) {}

    /**
     * 规划批次。任何输入为空时返回空列表；正常情况返回 ≥1 个批次，且：
     * 所有任务/里程碑恰好出现一次、批内无依赖边、批成本不超 maxBatchTokens。
     *
     * @param milestones     草案全部里程碑（身份字段）
     * @param tasks          草案全部任务（身份字段 + dependencyTempKeys）
     * @param maxBatchTokens 单批 token 上限（prompt 预估 + 输出预留）
     */
    public static List<Batch> plan(List<PlanMilestone> milestones, List<PlanTask> tasks, int maxBatchTokens) {
        if (milestones == null || milestones.isEmpty() || tasks == null || tasks.isEmpty()) {
            return List.of();
        }
        List<PlanTask> ordered = topologicalOrder(tasks);
        // 批内任务集合，用于依赖冲突判断
        List<Set<String>> batchTaskKeys = new ArrayList<>();
        List<List<PlanTask>> batchTasks = new ArrayList<>();
        for (PlanTask task : ordered) {
            Set<String> dependencies = new HashSet<>(task.dependencyTempKeys());
            int target = -1;
            for (int i = 0; i < batchTasks.size(); i++) {
                if (batchTasks.get(i).size() >= MAX_TASKS_PER_BATCH) continue;
                // 依赖冲突：本批已包含该任务的任一依赖 → 不能同批
                boolean conflict = false;
                for (PlanTask existing : batchTasks.get(i)) {
                    if (dependencies.contains(existing.tempKey())) { conflict = true; break; }
                }
                if (conflict) continue;
                batchTasks.get(i).add(task);
                if (batchCost(milestones, batchTasks.get(i)) <= maxBatchTokens) {
                    target = i;
                    break;
                }
                // 超预算则回退本批，继续尝试下一批
                batchTasks.get(i).remove(batchTasks.get(i).size() - 1);
            }
            if (target < 0) {
                // 新开批次（单任务也可能超预算——极端长文本时批内只有它自己，尽力而为）
                List<PlanTask> fresh = new ArrayList<>();
                fresh.add(task);
                batchTasks.add(fresh);
                batchTaskKeys.add(new HashSet<>());
                target = batchTasks.size() - 1;
            }
            batchTaskKeys.get(target).add(task.tempKey());
        }

        // 里程碑归属：分给包含其首个任务的批次；无任务的里程碑归第 0 批（仍需输出 description）
        Map<String, Integer> milestoneBatch = new HashMap<>();
        for (int b = 0; b < batchTasks.size(); b++) {
            for (PlanTask task : batchTasks.get(b)) {
                milestoneBatch.putIfAbsent(task.milestoneTempKey(), b);
            }
        }
        List<Batch> batches = new ArrayList<>();
        for (int b = 0; b < batchTasks.size(); b++) batches.add(new Batch(new ArrayList<>(), batchTasks.get(b)));
        for (PlanMilestone milestone : milestones) {
            int b = milestoneBatch.getOrDefault(milestone.tempKey(), 0);
            batches.get(Math.min(b, batches.size() - 1)).milestones().add(milestone);
        }
        return List.copyOf(batches);
    }

    /**
     * 单批成本估算：基础 prompt + 全部里程碑/任务的输入文本 + 输出预留。
     * 输入文本按里程碑/任务的标题与目标（CJK 感知）折算；输出预留用常量上界。
     */
    public static int batchCost(List<PlanMilestone> allMilestones, List<PlanTask> batchTasks) {
        int input = 0;
        Set<String> batchMilestoneKeys = new LinkedHashSet<>();
        for (PlanTask task : batchTasks) {
            batchMilestoneKeys.add(task.milestoneTempKey());
            input += TokenEstimator.estimate(List.of(
                    task.tempKey(), task.milestoneTempKey(), task.title(), task.objective()));
        }
        int milestones = 0;
        for (PlanMilestone milestone : allMilestones) {
            if (batchMilestoneKeys.contains(milestone.tempKey())) {
                milestones++;
                input += TokenEstimator.estimate(List.of(
                        milestone.tempKey(), milestone.title(), milestone.objective()));
            }
        }
        return BASE_PROMPT_TOKENS + input
                + milestones * OUTPUT_TOKENS_PER_MILESTONE
                + batchTasks.size() * OUTPUT_TOKENS_PER_TASK;
    }

    /** Kahn 拓扑排序：只统计两端都在草案内的依赖边；同层按原顺序稳定；含环时剩余任务按原顺序追加（环本身由领域校验器判 HARD）。 */
    static List<PlanTask> topologicalOrder(List<PlanTask> tasks) {
        Map<String, PlanTask> byKey = new HashMap<>();
        for (PlanTask task : tasks) byKey.put(task.tempKey(), task);
        Map<String, Integer> pendingDependencies = new HashMap<>();
        List<PlanTask> ordered = new ArrayList<>();
        for (PlanTask task : tasks) {
            long pending = task.dependencyTempKeys().stream().filter(byKey::containsKey).distinct().count();
            pendingDependencies.put(task.tempKey(), (int) pending);
        }
        for (PlanTask task : tasks) {
            if (pendingDependencies.get(task.tempKey()) == 0) ordered.add(task);
        }
        int index = 0;
        while (index < ordered.size()) {
            PlanTask current = ordered.get(index++);
            for (PlanTask task : tasks) {
                Integer pending = pendingDependencies.get(task.tempKey());
                if (pending == null || pending == 0) continue;
                if (task.dependencyTempKeys().contains(current.tempKey())) {
                    pendingDependencies.put(task.tempKey(), pending - 1);
                    if (pending - 1 == 0) ordered.add(task);
                }
            }
        }
        // 环中任务按原顺序追加，保证全覆盖（环会被 TaskPlanDraftValidator 判 HARD，不在这里展开）
        if (ordered.size() < tasks.size()) {
            Set<String> placed = new HashSet<>();
            for (PlanTask task : ordered) placed.add(task.tempKey());
            for (PlanTask task : tasks) {
                if (placed.add(task.tempKey())) ordered.add(task);
            }
        }
        return ordered;
    }
}
