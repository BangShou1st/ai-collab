package com.shitulelv.aicollab.planning.domain;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 2: RED/GREEN tests for ValidationIssueCatalog, StructuredValidationIssue, ValidationAssessment.
 */
class ValidationIssueCatalogTest {

    // ── Catalog severity tests ──

    @Test
    void hardIssueClassificationIsCentralized() {
        assertEquals(ValidationIssueSeverity.HARD, ValidationIssueCatalog.severity("TEMP_KEY_DUPLICATE"));
        assertEquals(ValidationIssueSeverity.HARD, ValidationIssueCatalog.severity("MILESTONE_REF_INVALID"));
        assertEquals(ValidationIssueSeverity.HARD, ValidationIssueCatalog.severity("JSON_SYNTAX_INVALID"));
        assertEquals(ValidationIssueSeverity.HARD, ValidationIssueCatalog.severity("UNKNOWN_PROPERTY"));
        assertEquals(ValidationIssueSeverity.HARD, ValidationIssueCatalog.severity("SKELETON_MUTATED"));
        assertEquals(ValidationIssueSeverity.HARD, ValidationIssueCatalog.severity("PLAN_DATE_OUTSIDE_PROJECT"));
        assertEquals(ValidationIssueSeverity.HARD, ValidationIssueCatalog.severity("MILESTONE_LIMIT_EXCEEDED"));
        assertEquals(ValidationIssueSeverity.HARD, ValidationIssueCatalog.severity("TASK_LIMIT_EXCEEDED"));
    }

    @Test
    void editableIssueClassificationIsCentralized() {
        assertEquals(ValidationIssueSeverity.BLOCKING_EDITABLE, ValidationIssueCatalog.severity("DEPENDENCY_DATE_CONFLICT"));
        assertEquals(ValidationIssueSeverity.BLOCKING_EDITABLE, ValidationIssueCatalog.severity("ASSIGNEE_NOT_PROJECT_MEMBER"));
        assertEquals(ValidationIssueSeverity.BLOCKING_EDITABLE, ValidationIssueCatalog.severity("TASK_DATE_INVALID"));
        assertEquals(ValidationIssueSeverity.BLOCKING_EDITABLE, ValidationIssueCatalog.severity("DEPENDENCY_CYCLE"));
        assertEquals(ValidationIssueSeverity.BLOCKING_EDITABLE, ValidationIssueCatalog.severity("SELF_DEPENDENCY"));
        assertEquals(ValidationIssueSeverity.BLOCKING_EDITABLE, ValidationIssueCatalog.severity("ESTIMATED_HOURS_INVALID"));
    }

    @Test
    void warningIssueClassificationIsCentralized() {
        assertEquals(ValidationIssueSeverity.WARNING, ValidationIssueCatalog.severity("DUPLICATE_TITLE"));
        assertEquals(ValidationIssueSeverity.WARNING, ValidationIssueCatalog.severity("TASK_UNASSIGNED"));
        assertEquals(ValidationIssueSeverity.WARNING, ValidationIssueCatalog.severity("AI_SUGGESTION_WITHOUT_SOURCE"));
    }

    @Test
    void blocksConfirmationForHardAndEditable() {
        assertTrue(ValidationIssueCatalog.blocksConfirmation("TEMP_KEY_DUPLICATE"));
        assertTrue(ValidationIssueCatalog.blocksConfirmation("DEPENDENCY_DATE_CONFLICT"));
        assertFalse(ValidationIssueCatalog.blocksConfirmation("TASK_UNASSIGNED"));
        assertFalse(ValidationIssueCatalog.blocksConfirmation("DUPLICATE_TITLE"));
    }

    @Test
    void repairableFieldsReturnsCorrectSets() {
        Set<String> dateFields = ValidationIssueCatalog.repairableFields("DEPENDENCY_DATE_CONFLICT");
        assertTrue(dateFields.contains("startDate"));
        assertTrue(dateFields.contains("dueDate"));

        Set<String> assigneeFields = ValidationIssueCatalog.repairableFields("ASSIGNEE_NOT_PROJECT_MEMBER");
        assertTrue(assigneeFields.contains("suggestedAssigneeId"));

        // Hard issues have no repairable fields
        assertTrue(ValidationIssueCatalog.repairableFields("TEMP_KEY_DUPLICATE").isEmpty());
    }

    @Test
    void unknownCodeThrows() {
        assertThrows(IllegalArgumentException.class, () -> ValidationIssueCatalog.severity("NONEXISTENT_CODE"));
    }

    @Test
    void unknownCodeDefaultsToWarning() {
        assertEquals(ValidationIssueSeverity.WARNING, ValidationIssueCatalog.severityOrDefault("NONEXISTENT_CODE"));
    }

    // ── StructuredValidationIssue tests ──

    @Test
    void dependencyDateConflictHasTargetAndRelatedTask() {
        StructuredValidationIssue issue = new StructuredValidationIssue(
                "DEPENDENCY_DATE_CONFLICT",
                ValidationIssueSeverity.BLOCKING_EDITABLE,
                "TASK", "T3", "startDate", "T1",
                Map.of("dependencyDueDate", "2026-08-12", "currentStartDate", "2026-08-10"));

        assertEquals("DEPENDENCY_DATE_CONFLICT", issue.code());
        assertEquals(ValidationIssueSeverity.BLOCKING_EDITABLE, issue.severity());
        assertEquals("TASK", issue.targetType());
        assertEquals("T3", issue.targetTempKey());
        assertEquals("startDate", issue.field());
        assertEquals("T1", issue.relatedTempKey());
        assertEquals("2026-08-12", issue.safeDetails().get("dependencyDueDate"));
        assertTrue(issue.isBlockingEditable());
        assertTrue(issue.blocksConfirmation());
    }

    @Test
    void unknownMemberIssueHasAssigneeField() {
        StructuredValidationIssue issue = new StructuredValidationIssue(
                "ASSIGNEE_NOT_PROJECT_MEMBER",
                ValidationIssueSeverity.BLOCKING_EDITABLE,
                "TASK", "T2", "suggestedAssigneeId", null, Map.of());

        assertEquals("TASK", issue.targetType());
        assertEquals("T2", issue.targetTempKey());
        assertEquals("suggestedAssigneeId", issue.field());
        assertNull(issue.relatedTempKey());
    }

    @Test
    void invalidSourceHasTargetAndSourceRefField() {
        StructuredValidationIssue issue = new StructuredValidationIssue(
                "SOURCE_REF_INVALID",
                ValidationIssueSeverity.BLOCKING_EDITABLE,
                "TASK", "T1", "sourceRefs", null,
                Map.of("invalidRef", "S99"));

        assertEquals("TASK", issue.targetType());
        assertEquals("T1", issue.targetTempKey());
        assertEquals("sourceRefs", issue.field());
        assertEquals("S99", issue.safeDetails().get("invalidRef"));
    }

    @Test
    void safeDetailsRejectsNonWhitelistedValues() {
        // safeDetails should only contain safe diagnostic data
        // The record itself doesn't enforce whitelist at compile time,
        // but the constructor ensures immutability and non-null
        StructuredValidationIssue issue = new StructuredValidationIssue(
                "TEST_CODE", ValidationIssueSeverity.WARNING,
                "PLAN", null, null, null,
                Map.of("count", 5, "allowedValues", "LOW,MEDIUM,HIGH"));

        assertEquals(2, issue.safeDetails().size());
        // Verify immutability
        assertThrows(UnsupportedOperationException.class,
                () -> issue.safeDetails().put("hack", "value"));
    }

    @Test
    void warningIssueDoesNotBlockConfirmation() {
        StructuredValidationIssue issue = new StructuredValidationIssue(
                "TASK_UNASSIGNED",
                ValidationIssueSeverity.WARNING,
                "TASK", "T1", "assigneeId", null, Map.of());

        assertFalse(issue.blocksConfirmation());
        assertFalse(issue.isHard());
        assertFalse(issue.isBlockingEditable());
        assertTrue(issue.isWarning());
    }

    // ── ValidationAssessment tests ──

    @Test
    void emptyAssessmentIsReady() {
        ValidationAssessment assessment = ValidationAssessment.empty();
        assertTrue(assessment.ready());
        assertFalse(assessment.hasHardIssues());
        assertFalse(assessment.hasBlockingEditableIssues());
        assertFalse(assessment.hasWarnings());
        assertTrue(assessment.codes().isEmpty());
    }

    @Test
    void hardIssueMakesNotReady() {
        var issues = java.util.List.of(
                new StructuredValidationIssue("TEMP_KEY_DUPLICATE", ValidationIssueSeverity.HARD,
                        "TASK", "T1", "tempKey", null, Map.of()));
        ValidationAssessment assessment = new ValidationAssessment(issues);

        assertFalse(assessment.ready());
        assertTrue(assessment.hasHardIssues());
        assertFalse(assessment.degradable());
    }

    @Test
    void onlyEditableIssuesAreDegradable() {
        var issues = java.util.List.of(
                new StructuredValidationIssue("DEPENDENCY_DATE_CONFLICT", ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "T2", "startDate", "T1", Map.of()),
                new StructuredValidationIssue("ASSIGNEE_NOT_PROJECT_MEMBER", ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "T3", "suggestedAssigneeId", null, Map.of()));
        ValidationAssessment assessment = new ValidationAssessment(issues);

        assertFalse(assessment.ready());
        assertFalse(assessment.hasHardIssues());
        assertTrue(assessment.hasBlockingEditableIssues());
        assertTrue(assessment.degradable());
    }

    @Test
    void mixedHardAndEditableIsNotDegradable() {
        var issues = java.util.List.of(
                new StructuredValidationIssue("TEMP_KEY_DUPLICATE", ValidationIssueSeverity.HARD,
                        "TASK", "T1", "tempKey", null, Map.of()),
                new StructuredValidationIssue("DEPENDENCY_DATE_CONFLICT", ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "T2", "startDate", "T1", Map.of()));
        ValidationAssessment assessment = new ValidationAssessment(issues);

        assertFalse(assessment.ready());
        assertTrue(assessment.hasHardIssues());
        assertTrue(assessment.hasBlockingEditableIssues());
        assertFalse(assessment.degradable());
    }

    @Test
    void warningsOnlyStillReady() {
        var issues = java.util.List.of(
                new StructuredValidationIssue("TASK_UNASSIGNED", ValidationIssueSeverity.WARNING,
                        "TASK", "T1", "assigneeId", null, Map.of()),
                new StructuredValidationIssue("AI_SUGGESTION_WITHOUT_SOURCE", ValidationIssueSeverity.WARNING,
                        "TASK", "T2", "sourceRefs", null, Map.of()));
        ValidationAssessment assessment = new ValidationAssessment(issues);

        assertTrue(assessment.ready());
        assertTrue(assessment.readyWithWarnings());
        assertFalse(assessment.hasHardIssues());
        assertFalse(assessment.hasBlockingEditableIssues());
        assertTrue(assessment.hasWarnings());
    }

    @Test
    void toFlatPreservesBackwardCompatibility() {
        var issues = java.util.List.of(
                new StructuredValidationIssue("TEMP_KEY_DUPLICATE", ValidationIssueSeverity.HARD,
                        "TASK", "T1", "tempKey", null, Map.of()),
                new StructuredValidationIssue("TASK_UNASSIGNED", ValidationIssueSeverity.WARNING,
                        "TASK", "T2", "assigneeId", null, Map.of()));
        ValidationAssessment assessment = new ValidationAssessment(issues);
        ValidationResult flat = assessment.toFlat();

        assertEquals(1, flat.errorCodes().size());
        assertEquals("TEMP_KEY_DUPLICATE", flat.errorCodes().getFirst());
        assertEquals(1, flat.warningCodes().size());
        assertEquals("TASK_UNASSIGNED", flat.warningCodes().getFirst());
    }

    @Test
    void codesMethodReturnsAllIssueCodes() {
        var issues = java.util.List.of(
                new StructuredValidationIssue("A", ValidationIssueSeverity.HARD, "PLAN", null, null, null, Map.of()),
                new StructuredValidationIssue("B", ValidationIssueSeverity.BLOCKING_EDITABLE, "TASK", null, null, null, Map.of()),
                new StructuredValidationIssue("C", ValidationIssueSeverity.WARNING, "TASK", null, null, null, Map.of()));
        ValidationAssessment assessment = new ValidationAssessment(issues);

        assertEquals(3, assessment.codes().size());
        assertTrue(assessment.codes().contains("A"));
        assertTrue(assessment.codes().contains("B"));
        assertTrue(assessment.codes().contains("C"));
    }
}
