package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.StructuredValidationIssue;
import com.shitulelv.aicollab.planning.domain.TaskPlanStatus;
import com.shitulelv.aicollab.planning.domain.ValidationAssessment;
import com.shitulelv.aicollab.planning.domain.ValidationIssueSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 6: RED/GREEN tests for GenerationOutcomeDecider and status transitions.
 */
class GenerationOutcomeDeciderTest {

    private final GenerationOutcomeDecider decider = new GenerationOutcomeDecider();

    // ── Decider tests ──

    @Test
    void readyPlanEndsReady() {
        ValidationAssessment assessment = ValidationAssessment.empty();
        assertEquals(GenerationOutcomeDecider.GenerationOutcome.READY, decider.decide(assessment));
        assertEquals(TaskPlanStatus.READY, decider.decideStatus(assessment));
    }

    @Test
    void warningsOnlyEndsReady() {
        var issues = List.of(
                new StructuredValidationIssue("TASK_UNASSIGNED", ValidationIssueSeverity.WARNING,
                        "TASK", "T1", "assigneeId", null, Map.of()));
        ValidationAssessment assessment = new ValidationAssessment(issues);
        assertEquals(GenerationOutcomeDecider.GenerationOutcome.READY, decider.decide(assessment));
    }

    @Test
    void blockingEditableEndsReadyWithIssues() {
        var issues = List.of(
                new StructuredValidationIssue("DEPENDENCY_DATE_CONFLICT", ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "T2", "startDate", "T1", Map.of()));
        ValidationAssessment assessment = new ValidationAssessment(issues);
        assertEquals(GenerationOutcomeDecider.GenerationOutcome.READY_WITH_ISSUES, decider.decide(assessment));
        assertEquals(TaskPlanStatus.READY_WITH_ISSUES, decider.decideStatus(assessment));
    }

    @Test
    void hardIssueEndsFailed() {
        var issues = List.of(
                new StructuredValidationIssue("TEMP_KEY_DUPLICATE", ValidationIssueSeverity.HARD,
                        "TASK", "T1", "tempKey", null, Map.of()));
        ValidationAssessment assessment = new ValidationAssessment(issues);
        assertEquals(GenerationOutcomeDecider.GenerationOutcome.FAILED, decider.decide(assessment));
        assertEquals(TaskPlanStatus.FAILED, decider.decideStatus(assessment));
    }

    @Test
    void mixedHardAndEditableEndsFailed() {
        var issues = List.of(
                new StructuredValidationIssue("TEMP_KEY_DUPLICATE", ValidationIssueSeverity.HARD,
                        "TASK", "T1", "tempKey", null, Map.of()),
                new StructuredValidationIssue("DEPENDENCY_DATE_CONFLICT", ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "T2", "startDate", "T1", Map.of()));
        ValidationAssessment assessment = new ValidationAssessment(issues);
        assertEquals(GenerationOutcomeDecider.GenerationOutcome.FAILED, decider.decide(assessment));
    }

    // ── Status transition tests ──

    @Test
    void skeletonGeneratingCanTransitionToDetailGenerating() {
        assertTrue(TaskPlanStatus.SKELETON_GENERATING.canTransitionTo(TaskPlanStatus.DETAIL_GENERATING));
    }

    @Test
    void detailGeneratingCanTransitionToRepairing() {
        assertTrue(TaskPlanStatus.DETAIL_GENERATING.canTransitionTo(TaskPlanStatus.REPAIRING));
    }

    @Test
    void detailGeneratingCanTransitionToReadyWithIssues() {
        assertTrue(TaskPlanStatus.DETAIL_GENERATING.canTransitionTo(TaskPlanStatus.READY_WITH_ISSUES));
    }

    @Test
    void repairingCanTransitionToReady() {
        assertTrue(TaskPlanStatus.REPAIRING.canTransitionTo(TaskPlanStatus.READY));
    }

    @Test
    void repairingCanTransitionToReadyWithIssues() {
        assertTrue(TaskPlanStatus.REPAIRING.canTransitionTo(TaskPlanStatus.READY_WITH_ISSUES));
    }

    @Test
    void repairingCanTransitionToFailed() {
        assertTrue(TaskPlanStatus.REPAIRING.canTransitionTo(TaskPlanStatus.FAILED));
    }

    @Test
    void readyWithIssuesCanOnlyRegenerate() {
        assertTrue(TaskPlanStatus.READY_WITH_ISSUES.canTransitionTo(TaskPlanStatus.SKELETON_GENERATING));
        assertFalse(TaskPlanStatus.READY_WITH_ISSUES.canTransitionTo(TaskPlanStatus.CONFIRMING));
        assertFalse(TaskPlanStatus.READY_WITH_ISSUES.canTransitionTo(TaskPlanStatus.READY));
    }

    @Test
    void readyWithIssuesCannotConfirm() {
        assertFalse(TaskPlanStatus.READY_WITH_ISSUES.canTransitionTo(TaskPlanStatus.CONFIRMING));
    }

    @Test
    void readyCanConfirm() {
        assertTrue(TaskPlanStatus.READY.canTransitionTo(TaskPlanStatus.CONFIRMING));
    }

    @Test
    void confirmedIsTerminal() {
        assertFalse(TaskPlanStatus.CONFIRMED.canTransitionTo(TaskPlanStatus.READY));
        assertFalse(TaskPlanStatus.CONFIRMED.canTransitionTo(TaskPlanStatus.SKELETON_GENERATING));
    }

    @Test
    void generatingIncludesRepairing() {
        assertTrue(TaskPlanStatus.REPAIRING.isGenerating());
        assertTrue(TaskPlanStatus.SKELETON_GENERATING.isGenerating());
        assertTrue(TaskPlanStatus.DETAIL_GENERATING.isGenerating());
        assertFalse(TaskPlanStatus.READY.isGenerating());
        assertFalse(TaskPlanStatus.READY_WITH_ISSUES.isGenerating());
    }
}
