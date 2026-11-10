package com.shitulelv.aicollab.planning.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 动态批次规划器全边界单测（设计文档 §5.4）：
 * 链式依赖分批、无依赖合并批、超 token 预算拆批、悬挂依赖忽略、环兜底、无任务里程碑归属。
 */
class DetailBatchPlannerTest {

    private static PlanTask task(String tempKey, List<String> deps) {
        return new PlanTask(tempKey, "m1", "Task " + tempKey, "Do " + tempKey,
                null, null, null, null, null, null, null, deps, List.of(), 0);
    }

    private static PlanMilestone milestone(String tempKey) {
        return new PlanMilestone(tempKey, "Milestone " + tempKey, "Objective", null, null, 0, List.of());
    }

    /** 批内不得存在依赖边（A 依赖 B 且 A、B 同批 = 违规）。 */
    private static void assertNoIntraBatchEdges(List<DetailBatchPlanner.Batch> batches, List<PlanTask> allTasks) {
        for (DetailBatchPlanner.Batch batch : batches) {
            List<String> keys = batch.tasks().stream().map(PlanTask::tempKey).toList();
            for (PlanTask task : batch.tasks()) {
                for (String dependency : task.dependencyTempKeys()) {
                    assertThat(keys).as("批内任务 %s 的依赖 %s 不得同批", task.tempKey(), dependency)
                            .doesNotContain(dependency);
                }
            }
            assertThat(keys).doesNotHaveDuplicates();
        }
        // 全覆盖：所有任务恰好出现一次
        List<String> placed = batches.stream()
                .flatMap(batch -> batch.tasks().stream().map(PlanTask::tempKey)).sorted().toList();
        List<String> expected = allTasks.stream().map(PlanTask::tempKey).sorted().toList();
        assertThat(placed).isEqualTo(expected);
    }

    @Test
    void chainDependenciesAreSplitAcrossBatches() {
        // 链式依赖 A→B→C（C 依赖 B，B 依赖 A）：同批不得有依赖边
        List<PlanTask> tasks = List.of(task("A", List.of()), task("B", List.of("A")), task("C", List.of("B")));
        List<DetailBatchPlanner.Batch> batches = DetailBatchPlanner.plan(
                List.of(milestone("m1")), tasks, 24_000);

        assertThat(batches).hasSizeGreaterThanOrEqualTo(2);
        assertNoIntraBatchEdges(batches, tasks);
    }

    @Test
    void independentTasksMergeIntoSingleBatch() {
        // 无依赖任务在预算内合并为一批（替换写死的每批 4 个）
        List<PlanTask> tasks = List.of(
                task("t1", List.of()), task("t2", List.of()), task("t3", List.of()),
                task("t4", List.of()), task("t5", List.of()));
        List<DetailBatchPlanner.Batch> batches = DetailBatchPlanner.plan(
                List.of(milestone("m1")), tasks, 24_000);

        assertThat(batches).hasSize(1);
        assertThat(batches.getFirst().tasks()).hasSize(5);
        assertNoIntraBatchEdges(batches, tasks);
    }

    @Test
    void tokenBudgetSplitsLargeBatches() {
        // 小预算：每批最多 2 个任务（3000 基础 + 每任务约 555 + 每里程碑 400）
        List<PlanTask> tasks = List.of(
                task("t1", List.of()), task("t2", List.of()), task("t3", List.of()),
                task("t4", List.of()), task("t5", List.of()), task("t6", List.of()));
        List<DetailBatchPlanner.Batch> batches = DetailBatchPlanner.plan(
                List.of(milestone("m1")), tasks, 4_700);

        assertThat(batches.size()).isGreaterThanOrEqualTo(3);
        for (DetailBatchPlanner.Batch batch : batches) {
            int cost = DetailBatchPlanner.batchCost(List.of(milestone("m1")), batch.tasks());
            assertThat(cost).as("批 %s 的 token 成本 %d 不得超预算", batch.tasks(), cost).isLessThanOrEqualTo(4_700);
        }
        assertNoIntraBatchEdges(batches, tasks);
    }

    @Test
    void dependenciesToUnknownKeysDoNotForceSplitting() {
        // 悬挂依赖（指向不存在任务）不参与分批，交给领域校验器判 HARD
        List<PlanTask> tasks = List.of(task("t1", List.of("ghost")), task("t2", List.of()));
        List<DetailBatchPlanner.Batch> batches = DetailBatchPlanner.plan(
                List.of(milestone("m1")), tasks, 24_000);

        assertThat(batches).hasSize(1);
        assertNoIntraBatchEdges(batches, tasks);
    }

    @Test
    void cyclicDependenciesStillCoverAllTasks() {
        // 环由领域校验器判 HARD；规划器兜底全覆盖、不丢任务、不产生批内边违规断言通过
        List<PlanTask> tasks = List.of(task("A", List.of("B")), task("B", List.of("A")));
        List<DetailBatchPlanner.Batch> batches = DetailBatchPlanner.plan(
                List.of(milestone("m1")), tasks, 24_000);

        assertNoIntraBatchEdges(batches, tasks);
        // 环内任务互为依赖，必须分处两批
        assertThat(batches).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void milestoneWithoutTasksGoesToFirstBatch() {
        List<PlanTask> tasks = List.of(task("t1", List.of()));
        PlanMilestone orphan = milestone("mOrphan");
        List<DetailBatchPlanner.Batch> batches = DetailBatchPlanner.plan(
                List.of(milestone("m1"), orphan), tasks, 24_000);

        assertThat(batches).hasSize(1);
        // 里程碑不重复出现且全覆盖
        List<String> milestoneKeys = batches.stream()
                .flatMap(batch -> batch.milestones().stream().map(PlanMilestone::tempKey)).toList();
        assertThat(milestoneKeys).containsExactlyInAnyOrder("m1", "mOrphan");
    }

    @Test
    void taskCapLimitsBatchSizeEvenWithLargeBudget() {
        // token 预算之外的任务数安全上限（输出规模兜底）
        List<PlanTask> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) tasks.add(task("t" + i, List.of()));
        List<DetailBatchPlanner.Batch> batches = DetailBatchPlanner.plan(
                List.of(milestone("m1")), tasks, Integer.MAX_VALUE);

        for (DetailBatchPlanner.Batch batch : batches) {
            assertThat(batch.tasks().size()).isLessThanOrEqualTo(DetailBatchPlanner.MAX_TASKS_PER_BATCH);
        }
        assertNoIntraBatchEdges(batches, tasks);
    }

    @Test
    void emptyInputsProduceNoBatches() {
        assertThat(DetailBatchPlanner.plan(List.of(), List.of(), 24_000)).isEmpty();
        assertThat(DetailBatchPlanner.plan(List.of(milestone("m1")), List.of(), 24_000)).isEmpty();
        assertThat(DetailBatchPlanner.plan(List.of(), List.of(task("t1", List.of())), 24_000)).isEmpty();
    }
}
