package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.domain.PlanMilestone;
import com.shitulelv.aicollab.planning.domain.PlanSource;
import com.shitulelv.aicollab.planning.domain.PlanTask;
import com.shitulelv.aicollab.planning.domain.TaskPlanDraft;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskPlanSkeletonSchemaTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void schemaBoundsTasksAndMilestonesToTheRequestedPlanSize() throws Exception {
        JsonNode schema = json.readTree(TaskPlanGenerationOrchestrator.skeletonSchema(4));

        assertThat(schema.at("/properties/tasks/maxItems").intValue()).isEqualTo(4);
        assertThat(schema.at("/properties/milestones/maxItems").intValue()).isEqualTo(4);
        assertThat(schema.at("/properties/assumptions/maxItems").intValue()).isEqualTo(8);
        assertThat(schema.at("/properties/risks/items/maxLength").intValue()).isEqualTo(160);
        assertThat(schema.at("/properties/summary/maxLength").intValue()).isEqualTo(300);
        assertThat(schema.at("/properties/tasks/items/properties/title/maxLength").intValue()).isEqualTo(80);
        assertThat(schema.at("/properties/tasks/items/properties/objective/maxLength").intValue()).isEqualTo(180);
    }

    @Test
    void schemaPreservesTheProductMaximumForLargerPlans() throws Exception {
        JsonNode schema = json.readTree(TaskPlanGenerationOrchestrator.skeletonSchema(40));

        assertThat(schema.at("/properties/tasks/maxItems").intValue()).isEqualTo(40);
        assertThat(schema.at("/properties/milestones/maxItems").intValue()).isEqualTo(8);
    }

    @Test
    void batchSchemasBoundEachCallAndAllowLaterBatchesToReusePriorMilestones() throws Exception {
        JsonNode first = json.readTree(TaskPlanGenerationOrchestrator.skeletonBatchSchema(4, 1, 2));
        JsonNode afterMilestoneLimit = json.readTree(TaskPlanGenerationOrchestrator.skeletonBatchSchema(4, 0, 0));

        assertThat(first.at("/properties/tasks/minItems").intValue()).isEqualTo(4);
        assertThat(first.at("/properties/tasks/maxItems").intValue()).isEqualTo(4);
        assertThat(first.at("/properties/milestones/minItems").intValue()).isEqualTo(1);
        assertThat(first.at("/properties/milestones/maxItems").intValue()).isEqualTo(2);
        assertThat(afterMilestoneLimit.at("/properties/milestones/minItems").intValue()).isZero();
        assertThat(afterMilestoneLimit.at("/properties/milestones/maxItems").intValue()).isZero();
    }

    @Test
    void schemaRejectsTaskLimitsOutsideTheCreatePlanContract() {
        assertThatThrownBy(() -> TaskPlanGenerationOrchestrator.skeletonSchema(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskPlanGenerationOrchestrator.skeletonSchema(41))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detailSchemaBoundsOutputToTheSkeletonAndAvailableSources() throws Exception {
        List<PlanMilestone> milestones = List.of(
                new PlanMilestone("m1", "里程碑 1", "目标", null, null, 0, List.of()),
                new PlanMilestone("m2", "里程碑 2", "目标", null, null, 1, List.of()));
        List<PlanTask> tasks = List.of(
                new PlanTask("t1", "m1", "任务 1", "目标", null, null, null, null, null,
                        null, null, List.of(), List.of(), 0),
                new PlanTask("t2", "m1", "任务 2", "目标", null, null, null, null, null,
                        null, null, List.of(), List.of(), 1),
                new PlanTask("t3", "m2", "任务 3", "目标", null, null, null, null, null,
                        null, null, List.of(), List.of(), 2),
                new PlanTask("t4", "m2", "任务 4", "目标", null, null, null, null, null,
                        null, null, List.of(), List.of(), 3));
        TaskPlanDraft skeleton = new TaskPlanDraft("摘要", List.of(), List.of(), milestones, tasks,
                List.of(new PlanSource("S1", null, "文档 1", "依据"),
                        new PlanSource("S2", null, "文档 2", "依据")));

        JsonNode schema = json.readTree(TaskPlanGenerationOrchestrator.detailSchema(skeleton));

        assertThat(schema.at("/properties/milestones/minItems").intValue()).isEqualTo(2);
        assertThat(schema.at("/properties/milestones/maxItems").intValue()).isEqualTo(2);
        assertThat(schema.at("/properties/tasks/minItems").intValue()).isEqualTo(4);
        assertThat(schema.at("/properties/tasks/maxItems").intValue()).isEqualTo(4);
        assertThat(schema.at("/properties/tasks/items/properties/description/maxLength").intValue()).isEqualTo(400);
        assertThat(schema.at("/properties/tasks/items/properties/sourceRefs/maxItems").intValue()).isEqualTo(2);
        assertThat(schema.at("/properties/tasks/items/properties/dependencyTempKeys/maxItems").intValue()).isEqualTo(3);
        assertThat(TaskPlanGenerationOrchestrator.detailSchema(skeleton)).doesNotContain("{{");

        JsonNode firstBatch = json.readTree(TaskPlanGenerationOrchestrator.detailSchema(
                skeleton, milestones.subList(0, 1), tasks.subList(0, 2)));
        assertThat(firstBatch.at("/properties/milestones/minItems").intValue()).isEqualTo(1);
        assertThat(firstBatch.at("/properties/milestones/maxItems").intValue()).isEqualTo(1);
        assertThat(firstBatch.at("/properties/tasks/minItems").intValue()).isEqualTo(2);
        assertThat(firstBatch.at("/properties/tasks/maxItems").intValue()).isEqualTo(2);
    }

    @Test
    void detailSchemaSupportsTheProductTaskCeiling() throws Exception {
        TaskPlanDraft skeleton = new TaskPlanDraft("摘要", List.of(), List.of(),
                java.util.Collections.nCopies(8, null),
                java.util.Collections.nCopies(40, null), List.of());

        JsonNode schema = json.readTree(TaskPlanGenerationOrchestrator.detailSchema(skeleton));

        assertThat(schema.at("/properties/milestones/maxItems").intValue()).isEqualTo(8);
        assertThat(schema.at("/properties/tasks/minItems").intValue()).isEqualTo(40);
        assertThat(schema.at("/properties/tasks/maxItems").intValue()).isEqualTo(40);
        assertThat(schema.at("/properties/tasks/items/properties/sourceRefs/maxItems").intValue()).isZero();
        assertThat(schema.at("/properties/tasks/items/properties/dependencyTempKeys/maxItems").intValue()).isEqualTo(39);
    }
}
