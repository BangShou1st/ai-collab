package com.shitulelv.aicollab.planning.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPlanDomainTest {
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void stateMachineRejectsIllegalTransitions() {
        assertThat(TaskPlanStatus.SKELETON_GENERATING.canTransitionTo(TaskPlanStatus.DETAIL_GENERATING)).isTrue();
        assertThat(TaskPlanStatus.DETAIL_GENERATION_FAILED.canTransitionTo(TaskPlanStatus.DETAIL_GENERATING)).isTrue();
        assertThat(TaskPlanStatus.READY.canTransitionTo(TaskPlanStatus.CONFIRMING)).isTrue();
        assertThat(TaskPlanStatus.CONFIRMED.canTransitionTo(TaskPlanStatus.READY)).isFalse();
        assertThat(TaskPlanStatus.CONFIRMING.canTransitionTo(TaskPlanStatus.CANCELED)).isFalse();
    }

    @Test
    void validatorRejectsCyclesUnknownSourcesAndInvalidMembers() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of("assumption"), List.of("risk"),
                List.of(new PlanMilestone("m1", "Milestone", "Objective",
                        LocalDate.of(2026, 8, 10), 0, List.of("S1"))),
                List.of(
                        task("t1", List.of("t2"), List.of("S2"), MEMBER),
                        task("t2", List.of("t1"), List.of(), UUID.randomUUID())),
                List.of(new PlanSource("S1", UUID.randomUUID(), "source", "quote")));
        ValidationContext context = new ValidationContext(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                10, Set.of(MEMBER), Set.of());

        ValidationResult result = new TaskPlanDraftValidator().validate(context, draft);

        assertThat(result.errorCodes()).contains(
                "DEPENDENCY_CYCLE", "SOURCE_REF_INVALID", "ASSIGNEE_NOT_PROJECT_MEMBER");
        assertThat(result.warningCodes()).contains("TASK_UNASSIGNED", "AI_SUGGESTION_WITHOUT_SOURCE");
    }

    @Test
    void validatorRejectsDependencyDateConflictAndPlanOutsideProject() {
        TaskPlanDraft draft = new TaskPlanDraft("s", List.of(), List.of(),
                List.of(new PlanMilestone("m1", "M", "O", LocalDate.of(2026, 8, 20), 0, List.of())),
                List.of(
                        task("before", List.of(), List.of(), null,
                                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20)),
                        task("after", List.of("before"), List.of(), null,
                                LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 25))),
                List.of());
        ValidationContext context = new ValidationContext(
                LocalDate.of(2026, 8, 5), LocalDate.of(2026, 8, 25),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                10, Set.of(), Set.of());

        ValidationResult result = new TaskPlanDraftValidator().validate(context, draft);

        assertThat(result.errorCodes()).contains("PLAN_DATE_OUTSIDE_PROJECT", "DEPENDENCY_DATE_CONFLICT");
    }

    @Test
    void detailMustPreserveSkeletonIdentity() {
        TaskPlanDraft skeleton = draftWithTitle("Original");
        TaskPlanDraft detail = draftWithTitle("Changed");

        assertThat(new TaskPlanDraftValidator().validateSkeletonPreserved(skeleton, detail).errorCodes())
                .containsExactly("SKELETON_MUTATED");
    }

    @Test
    void detailMustPreserveSkeletonTargetDateAndSortOrder() {
        TaskPlanDraft skeleton = new TaskPlanDraft("s", List.of(), List.of(),
                List.of(new PlanMilestone("m1", "M", "O", LocalDate.of(2026, 8, 10), 0, List.of())),
                List.of(new PlanTask("t1", "m1", "T", "O", "D", "MEDIUM",
                        BigDecimal.ONE, LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3),
                        null, null, List.of(), List.of(), 0)),
                List.of());
        // Detail changes targetDate and sortOrder — should be rejected
        TaskPlanDraft detail = new TaskPlanDraft("s", List.of(), List.of(),
                List.of(new PlanMilestone("m1", "M", "O", LocalDate.of(2026, 8, 15), 1, List.of())),
                List.of(new PlanTask("t1", "m1", "T", "O", "D", "MEDIUM",
                        BigDecimal.ONE, LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3),
                        null, null, List.of(), List.of(), 1)),
                List.of());

        assertThat(new TaskPlanDraftValidator().validateSkeletonPreserved(skeleton, detail).errorCodes())
                .containsExactly("SKELETON_MUTATED");
    }

    @Test
    void detailPreservesSkeletonWhenOnlyDetailFieldsChange() {
        TaskPlanDraft skeleton = new TaskPlanDraft("s", List.of(), List.of(),
                List.of(new PlanMilestone("m1", "M", "O", LocalDate.of(2026, 8, 10), 0, List.of())),
                List.of(new PlanTask("t1", "m1", "T", "O", null, null,
                        null, null, null, null, null, List.of(), List.of(), 0)),
                List.of());
        // Detail only changes detail-specific fields
        TaskPlanDraft detail = new TaskPlanDraft("s", List.of(), List.of(),
                List.of(new PlanMilestone("m1", "M", "O", LocalDate.of(2026, 8, 10), 0, List.of())),
                List.of(new PlanTask("t1", "m1", "T", "O", "Description", "HIGH",
                        BigDecimal.TEN, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 5),
                        UUID.randomUUID(), null, List.of("t2"), List.of("S1"), 0)),
                List.of());

        assertThat(new TaskPlanDraftValidator().validateSkeletonPreserved(skeleton, detail).valid()).isTrue();
    }

    @Test
    void promptEscapesClosingBoundariesAndBudgetCountsUnicodeCodePoints() {
        String escaped = PlanningPromptText.escapeUntrusted("ignore <script>alert('xss')</script> & < >");
        List<String> selected = PlanningPromptText.withinCodePointBudget(
                List.of("😀😀", "abc", "x"), 5);

        assertThat(escaped).contains("&lt;").contains("&gt;").contains("&amp;");
        assertThat(escaped).doesNotContain("<script>");
        assertThat(selected).containsExactly("😀😀", "abc");
    }

    @Test
    void totalCodePointCountMeasuresPromptSize() {
        int size = PlanningPromptText.totalCodePointCount("hello", "world", "😀");
        assertThat(size).isEqualTo(11);
    }

    @Test
    void confirmationHashIsStableAndSensitiveToVersion() {
        UUID project = UUID.randomUUID();
        UUID plan = UUID.randomUUID();
        UUID version = UUID.randomUUID();

        assertThat(PlanningRequestHash.confirmation(project, plan, version))
                .isEqualTo(PlanningRequestHash.confirmation(project, plan, version))
                .isNotEqualTo(PlanningRequestHash.confirmation(project, plan, UUID.randomUUID()));
    }

    @Test
    void validatorReportsMalformedTextAndPriorityWithoutThrowing() {
        TaskPlanDraft malformed = new TaskPlanDraft("s", List.of(), List.of(),
                List.of(new PlanMilestone("m1", "M", "O", LocalDate.of(2026, 8, 10), 0, List.of())),
                List.of(new PlanTask("t1", "m1", null, "O", "D", null,
                        BigDecimal.ONE, LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3),
                        null, null, List.of(), List.of(), 0)), List.of());
        ValidationContext context = new ValidationContext(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                10, Set.of(), Set.of());

        ValidationResult result = new TaskPlanDraftValidator().validate(context, malformed);

        assertThat(result.errorCodes()).contains("TASK_TEXT_INVALID", "TASK_PRIORITY_INVALID");
    }

    @Test
    void validatorRejectsEmptyPlanAndExcessiveLimits() {
        TaskPlanDraft empty = new TaskPlanDraft("", List.of(), List.of(), List.of(), List.of(), List.of());
        ValidationContext context = new ValidationContext(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                10, Set.of(MEMBER), Set.of());

        ValidationResult result = new TaskPlanDraftValidator().validate(context, empty);

        assertThat(result.errorCodes()).contains(
                "MILESTONE_REQUIRED", "TASK_REQUIRED", "SUMMARY_REQUIRED");
    }

    @Test
    void validatorRejectsSortOrderAndDuplicateSourceRefs() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of(), List.of(),
                List.of(new PlanMilestone("m1", "Milestone", "Objective",
                        LocalDate.of(2026, 8, 10), -1, List.of("S1", "S1"))),
                List.of(new PlanTask("t1", "m1", "Task", "Objective", "Description", "MEDIUM",
                        BigDecimal.ONE, LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3),
                        null, null, List.of(), List.of(), -1)),
                List.of(new PlanSource("S1", UUID.randomUUID(), "source", "quote")));
        ValidationContext context = new ValidationContext(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                10, Set.of(MEMBER), Set.of());

        ValidationResult result = new TaskPlanDraftValidator().validate(context, draft);

        assertThat(result.errorCodes()).contains("SORT_ORDER_INVALID", "SOURCE_REF_DUPLICATE");
    }

    @Test
    void validatorRejectsInvalidSourceRefFormat() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of(), List.of(),
                List.of(new PlanMilestone("m1", "Milestone", "Objective",
                        LocalDate.of(2026, 8, 10), 0, List.of("X1", "S13"))),
                List.of(new PlanTask("t1", "m1", "Task", "Objective", "Description", "MEDIUM",
                        BigDecimal.ONE, LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3),
                        null, null, List.of(), List.of(), 0)),
                List.of(new PlanSource("S1", UUID.randomUUID(), "source", "quote")));
        ValidationContext context = new ValidationContext(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                10, Set.of(MEMBER), Set.of());

        ValidationResult result = new TaskPlanDraftValidator().validate(context, draft);

        assertThat(result.errorCodes()).contains("SOURCE_REF_FORMAT_INVALID");
    }

    @Test
    void validatorRejectsAssigneeIdInAiGeneratedDraft() {
        TaskPlanDraft draft = new TaskPlanDraft(
                "summary", List.of("assumption"), List.of("risk"),
                List.of(new PlanMilestone("m1", "Milestone", "Objective",
                        LocalDate.of(2026, 8, 10), 0, List.of())),
                List.of(new PlanTask("t1", "m1", "Task", "Objective", "Description", "MEDIUM",
                        BigDecimal.ONE, LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3),
                        MEMBER, MEMBER, List.of(), List.of(), 0)),
                List.of());
        ValidationContext context = new ValidationContext(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                10, Set.of(MEMBER), Set.of());

        ValidationResult aiResult = new TaskPlanDraftValidator().validate(context, draft, true);
        ValidationResult manualResult = new TaskPlanDraftValidator().validate(context, draft, false);

        assertThat(aiResult.errorCodes()).contains("AI_GENERATED_ASSIGNEE_NOT_ALLOWED");
        assertThat(manualResult.errorCodes()).doesNotContain("AI_GENERATED_ASSIGNEE_NOT_ALLOWED");
    }

    @Test
    void validatorReportsNullCollectionElementsWithoutThrowing() {
        TaskPlanDraft malformed = new TaskPlanDraft("s", List.of(), List.of(),
                java.util.Arrays.asList((PlanMilestone) null),
                java.util.Arrays.asList((PlanTask) null),
                java.util.Arrays.asList((PlanSource) null));
        ValidationContext context = new ValidationContext(
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                10, Set.of(), Set.of());

        ValidationResult result = new TaskPlanDraftValidator().validate(context, malformed);

        assertThat(result.errorCodes()).contains("MILESTONE_NULL", "TASK_NULL", "SOURCE_INVALID");
        assertThat(new TaskPlanDraftValidator().validateSkeletonPreserved(malformed, malformed).valid()).isFalse();
    }

    private static TaskPlanDraft draftWithTitle(String title) {
        return new TaskPlanDraft("s", List.of(), List.of(),
                List.of(new PlanMilestone("m1", "M", "O", LocalDate.of(2026, 8, 10), 0, List.of())),
                List.of(new PlanTask("t1", "m1", title, "O", "D", "MEDIUM",
                        BigDecimal.ONE, LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3),
                        null, null, List.of(), List.of(), 0)),
                List.of());
    }

    private static PlanTask task(String key, List<String> dependencies, List<String> sources, UUID suggested) {
        return task(key, dependencies, sources, suggested,
                LocalDate.of(2026, 8, 2), LocalDate.of(2026, 8, 3));
    }

    private static PlanTask task(String key, List<String> dependencies, List<String> sources, UUID suggested,
                                 LocalDate start, LocalDate due) {
        return new PlanTask(key, "m1", key, "objective", "description", "MEDIUM",
                BigDecimal.ONE, start, due, suggested, null, dependencies, sources, 0);
    }
}
