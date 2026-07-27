package com.shitulelv.aicollab.planning.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 1 RED: Freeze current validator rule semantics.
 *
 * These tests capture the EXACT behavior of the current validator.
 * Some tests reference StructuredValidationIssue which does not yet exist —
 * this is intentional RED: the test will fail to compile until Task 2 introduces the type.
 *
 * Tests that only use existing types (ValidationResult) should PASS against current code.
 * Tests that reference StructuredValidationIssue should FAIL to compile — this is expected RED.
 */
class TaskPlanDraftValidatorRuleCatalogTest {

    private final TaskPlanDraftValidator validator = new TaskPlanDraftValidator();

    private ValidationContext baseContext() {
        return new ValidationContext(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 12, 31),
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 10, 1),
                20,
                Set.of(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                Set.of()
        );
    }

    private PlanMilestone milestone(String key, String title) {
        return new PlanMilestone(key, title, "objective", null,
                LocalDate.of(2026, 8, 15), 0, List.of());
    }

    private PlanTask task(String key, String milestoneKey, String title) {
        return new PlanTask(key, milestoneKey, title, "objective",
                "description", "MEDIUM", BigDecimal.valueOf(10),
                LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, null, List.of(), List.of(), 0);
    }

    // ──────────────────────────────────────────────────────────────
    // R1: dependencyDueDateAfterDependentStartProducesStableCodeAndTarget
    // T2 depends on T1; T1.dueDate > T2.startDate → DEPENDENCY_DATE_CONFLICT
    // ──────────────────────────────────────────────────────────────
    @Test
    void dependencyDueDateAfterDependentStartProducesStableCodeAndTarget() {
        PlanMilestone m1 = milestone("M1", "Milestone 1");
        // T1 due 2026-08-20, T2 starts 2026-08-15 → conflict
        PlanTask t1 = new PlanTask("T1", "M1", "Task 1", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, null, List.of(), List.of(), 0);
        PlanTask t2 = new PlanTask("T2", "M1", "Task 2", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 25),
                null, null, List.of("T1"), List.of(), 1);
        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1, t2), List.of());

        ValidationResult result = validator.validate(baseContext(), draft);

        assertTrue(result.errorCodes().contains("DEPENDENCY_DATE_CONFLICT"),
                "Expected DEPENDENCY_DATE_CONFLICT but got: " + result.errorCodes());
    }

    // ──────────────────────────────────────────────────────────────
    // R2: unknownMemberDoesNotBecomeAValidSuggestion
    // suggestedAssigneeId points to non-member → ASSIGNEE_NOT_PROJECT_MEMBER
    // ──────────────────────────────────────────────────────────────
    @Test
    void unknownMemberDoesNotBecomeAValidSuggestion() {
        UUID unknownMember = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        PlanMilestone m1 = milestone("M1", "M1");
        PlanTask t1 = new PlanTask("T1", "M1", "Task 1", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                unknownMember, null, List.of(), List.of(), 0);
        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1), List.of());

        ValidationResult result = validator.validate(baseContext(), draft);

        assertTrue(result.errorCodes().contains("ASSIGNEE_NOT_PROJECT_MEMBER"),
                "Expected ASSIGNEE_NOT_PROJECT_MEMBER but got: " + result.errorCodes());
    }

    // ──────────────────────────────────────────────────────────────
    // R3: emptySourcesAreAllowedWithoutFabrication
    // sourceRefs=[] is valid when no source is required
    // ──────────────────────────────────────────────────────────────
    @Test
    void emptySourcesAreAllowedWithoutFabrication() {
        PlanMilestone m1 = milestone("M1", "M1");
        PlanTask t1 = new PlanTask("T1", "M1", "Task 1", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, null, List.of(), List.of(), 0);
        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1), List.of());

        ValidationResult result = validator.validate(baseContext(), draft);

        // sourceRefs=[] should NOT produce SOURCE_REF_INVALID
        assertFalse(result.errorCodes().contains("SOURCE_REF_INVALID"),
                "Empty sourceRefs should be valid, but got: " + result.errorCodes());
    }

    // ──────────────────────────────────────────────────────────────
    // R4: SELF_DEPENDENCY is rejected
    // ──────────────────────────────────────────────────────────────
    @Test
    void selfDependencyIsRejected() {
        PlanMilestone m1 = milestone("M1", "M1");
        PlanTask t1 = new PlanTask("T1", "M1", "Task 1", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, null, List.of("T1"), List.of(), 0);
        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1), List.of());

        ValidationResult result = validator.validate(baseContext(), draft);

        assertTrue(result.errorCodes().contains("SELF_DEPENDENCY"),
                "Expected SELF_DEPENDENCY but got: " + result.errorCodes());
    }

    // ──────────────────────────────────────────────────────────────
    // R5: DEPENDENCY_CYCLE is detected
    // T1 → T2 → T1
    // ──────────────────────────────────────────────────────────────
    @Test
    void dependencyCycleIsDetected() {
        PlanMilestone m1 = milestone("M1", "M1");
        PlanTask t1 = new PlanTask("T1", "M1", "Task 1", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 15),
                null, null, List.of("T2"), List.of(), 0);
        PlanTask t2 = new PlanTask("T2", "M1", "Task 2", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 12), LocalDate.of(2026, 8, 20),
                null, null, List.of("T1"), List.of(), 1);
        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1, t2), List.of());

        ValidationResult result = validator.validate(baseContext(), draft);

        assertTrue(result.errorCodes().contains("DEPENDENCY_CYCLE"),
                "Expected DEPENDENCY_CYCLE but got: " + result.errorCodes());
    }

    // ──────────────────────────────────────────────────────────────
    // R6: DEPENDENCY_DUPLICATE is detected
    // ──────────────────────────────────────────────────────────────
    @Test
    void duplicateDependencyIsDetected() {
        PlanMilestone m1 = milestone("M1", "M1");
        PlanTask t1 = new PlanTask("T1", "M1", "Task 1", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, null, List.of(), List.of(), 0);
        PlanTask t2 = new PlanTask("T2", "M1", "Task 2", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 25),
                null, null, List.of("T1", "T1"), List.of(), 1);
        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1, t2), List.of());

        ValidationResult result = validator.validate(baseContext(), draft);

        assertTrue(result.errorCodes().contains("DEPENDENCY_DUPLICATE"),
                "Expected DEPENDENCY_DUPLICATE but got: " + result.errorCodes());
    }

    // ──────────────────────────────────────────────────────────────
    // R7: DEPENDENCY_REF_INVALID for non-existent task
    // ──────────────────────────────────────────────────────────────
    @Test
    void dependencyOnNonExistentTaskIsRejected() {
        PlanMilestone m1 = milestone("M1", "M1");
        PlanTask t1 = new PlanTask("T1", "M1", "Task 1", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, null, List.of("NONEXISTENT"), List.of(), 0);
        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1), List.of());

        ValidationResult result = validator.validate(baseContext(), draft);

        assertTrue(result.errorCodes().contains("DEPENDENCY_REF_INVALID"),
                "Expected DEPENDENCY_REF_INVALID but got: " + result.errorCodes());
    }

    // ──────────────────────────────────────────────────────────────
    // R8: VALIDATION_MODE_AI_SKELETON skips date/hours/priority checks
    // ──────────────────────────────────────────────────────────────
    @Test
    void skeletonModeSkipsDetailChecks() {
        PlanMilestone m1 = milestone("M1", "M1");
        // Task with no dates, no priority, no hours — valid in skeleton mode
        PlanTask t1 = new PlanTask("T1", "M1", "Task 1", "obj",
                null, null, null, null, null,
                null, null, List.of(), List.of(), 0);
        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1), List.of());

        ValidationResult result = validator.validate(baseContext(), draft,
                TaskPlanDraftValidator.ValidationMode.AI_SKELETON);

        // Skeleton mode should not require dates, priority, estimatedHours
        assertFalse(result.errorCodes().contains("TASK_DATE_INVALID"),
                "Skeleton mode should skip TASK_DATE_INVALID");
        assertFalse(result.errorCodes().contains("TASK_PRIORITY_INVALID"),
                "Skeleton mode should skip TASK_PRIORITY_INVALID");
        assertFalse(result.errorCodes().contains("ESTIMATED_HOURS_INVALID"),
                "Skeleton mode should skip ESTIMATED_HOURS_INVALID");
    }

    // ──────────────────────────────────────────────────────────────
    // R9: aiGenerated=true rejects assigneeId on tasks
    // ──────────────────────────────────────────────────────────────
    @Test
    void aiGeneratedRejectsAssigneeId() {
        UUID memberId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        PlanMilestone m1 = milestone("M1", "M1");
        PlanTask t1 = new PlanTask("T1", "M1", "Task 1", "obj", "desc", "MEDIUM",
                BigDecimal.valueOf(10), LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 20),
                null, memberId, List.of(), List.of(), 0);
        TaskPlanDraft draft = new TaskPlanDraft("summary", List.of(), List.of(),
                List.of(m1), List.of(t1), List.of());

        ValidationResult result = validator.validate(baseContext(), draft,
                TaskPlanDraftValidator.ValidationMode.COMPLETE, true);

        assertTrue(result.errorCodes().contains("AI_GENERATED_ASSIGNEE_NOT_ALLOWED"),
                "Expected AI_GENERATED_ASSIGNEE_NOT_ALLOWED but got: " + result.errorCodes());
    }

    // ──────────────────────────────────────────────────────────────
    // R10: VALIDATION_RESULT stores exactly errorCodes and warningCodes
    // (Current flat list — no target/field/relatedKey)
    // This test documents the LIMITATION that Task 2 will fix.
    // ──────────────────────────────────────────────────────────────
    @Test
    void validationResultOnlyContainsFlatCodeLists() {
        ValidationResult vr = new ValidationResult(
                List.of("DEPENDENCY_DATE_CONFLICT"),
                List.of("TASK_UNASSIGNED"));

        // Current ValidationResult has no target, field, relatedKey
        // This documents the gap — Task 2 will add StructuredValidationIssue
        assertEquals(1, vr.errorCodes().size());
        assertEquals("DEPENDENCY_DATE_CONFLICT", vr.errorCodes().getFirst());
        assertEquals(1, vr.warningCodes().size());
        assertEquals("TASK_UNASSIGNED", vr.warningCodes().getFirst());
        assertTrue(vr.valid() == false);
    }

    // ──────────────────────────────────────────────────────────────
    // The following tests would reference StructuredValidationIssue
    // and ValidationIssueCatalog — they will NOT compile until Task 2.
    // This is intentional RED.
    //
    // Uncomment after Task 2 introduces the types:
    //
    // @Test
    // void dependencyDateConflictHasTargetAndRelatedTask() {
    //     StructuredValidationIssue issue = new StructuredValidationIssue(
    //             "DEPENDENCY_DATE_CONFLICT",
    //             ValidationIssueSeverity.BLOCKING_EDITABLE,
    //             "TASK", "T2", "startDate", "T1", Map.of());
    //     assertEquals("TASK", issue.targetType());
    //     assertEquals("T2", issue.targetTempKey());
    //     assertEquals("startDate", issue.field());
    //     assertEquals("T1", issue.relatedTempKey());
    // }
    //
    // @Test
    // void unknownMemberIssueHasAssigneeField() {
    //     // After Task 2, ASSIGNEE_NOT_PROJECT_MEMBER should carry
    //     // targetTempKey and field=suggestedAssigneeId or assigneeId
    // }
    //
    // @Test
    // void hardIssueClassificationIsCentralized() {
    //     assertEquals(ValidationIssueSeverity.HARD,
    //             ValidationIssueCatalog.severity("TEMP_KEY_DUPLICATE"));
    //     assertEquals(ValidationIssueSeverity.HARD,
    //             ValidationIssueCatalog.severity("MILESTONE_REF_INVALID"));
    // }
    //
    // @Test
    // void editableIssueClassificationIsCentralized() {
    //     assertEquals(ValidationIssueSeverity.BLOCKING_EDITABLE,
    //             ValidationIssueCatalog.severity("DEPENDENCY_DATE_CONFLICT"));
    //     assertEquals(ValidationIssueSeverity.BLOCKING_EDITABLE,
    //             ValidationIssueCatalog.severity("ASSIGNEE_NOT_PROJECT_MEMBER"));
    // }
}
