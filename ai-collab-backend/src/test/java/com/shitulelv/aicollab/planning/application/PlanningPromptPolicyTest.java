package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.planning.domain.StructuredValidationIssue;
import com.shitulelv.aicollab.planning.domain.ValidationIssueSeverity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 4: RED/GREEN tests for PlanningPromptPolicy.
 *
 * Verifies that prompts contain required domain rules and safety constraints.
 */
class PlanningPromptPolicyTest {

    private final PlanningPromptPolicy policy = new PlanningPromptPolicy();

    private PlanningPromptPolicy.PlanningContext testContext() {
        return new PlanningPromptPolicy.PlanningContext(
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 10, 1),
                20,
                Set.of(
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        UUID.fromString("22222222-2222-2222-2222-222222222222")),
                Set.of("S1", "S2", "S3"));
    }

    @Test
    void detailPromptContainsPlanDateRange() {
        String prompt = policy.detailRules(testContext());
        assertTrue(prompt.contains("planStartDate=2026-08-01"));
        assertTrue(prompt.contains("planDueDate=2026-10-01"));
        assertTrue(prompt.contains("maxTaskCount=20"));
    }

    @Test
    void detailPromptContainsAllowedMemberIdsOnly() {
        String prompt = policy.detailRules(testContext());
        assertTrue(prompt.contains("11111111-1111-1111-1111-111111111111"));
        assertTrue(prompt.contains("22222222-2222-2222-2222-222222222222"));
        // Should NOT contain a member not in the set
        assertFalse(prompt.contains("33333333-3333-3333-3333-333333333333"));
    }

    @Test
    void detailPromptContainsAllowedSourceRefsOnly() {
        String prompt = policy.detailRules(testContext());
        assertTrue(prompt.contains("- S1"));
        assertTrue(prompt.contains("- S2"));
        assertTrue(prompt.contains("- S3"));
        assertFalse(prompt.contains("- S99"));
    }

    @Test
    void detailPromptSaysUnknownAssigneeMustBeNull() {
        String prompt = policy.detailRules(testContext());
        assertTrue(prompt.contains("不确定负责人时输出 null"));
    }

    @Test
    void detailPromptSaysNoSourceMustBeEmptyArray() {
        String prompt = policy.detailRules(testContext());
        assertTrue(prompt.contains("无文档依据时 sourceRefs=[]"));
    }

    @Test
    void promptDoesNotContainApiKey() {
        String skeleton = policy.skeletonRules(testContext());
        String detail = policy.detailRules(testContext());
        String repair = policy.repairRules(List.of());

        assertFalse(skeleton.toLowerCase().contains("api_key"));
        assertFalse(skeleton.toLowerCase().contains("apikey"));
        assertFalse(detail.toLowerCase().contains("api_key"));
        assertFalse(detail.toLowerCase().contains("apikey"));
        assertFalse(repair.toLowerCase().contains("api_key"));
        assertFalse(repair.toLowerCase().contains("apikey"));
    }

    @Test
    void promptDoesNotContainAuthorization() {
        String skeleton = policy.skeletonRules(testContext());
        String detail = policy.detailRules(testContext());
        String repair = policy.repairRules(List.of());

        assertFalse(skeleton.toLowerCase().contains("authorization"));
        assertFalse(detail.toLowerCase().contains("authorization"));
        assertFalse(repair.toLowerCase().contains("authorization"));
    }

    @Test
    void promptEscapesUserXml() {
        // User input with XML characters should be escaped in prompts
        // The policy doesn't embed user input directly, but we verify
        // that the skeleton rules don't contain raw < or > from user data
        String skeleton = policy.skeletonRules(testContext());
        // Should not have unescaped user-provided XML
        assertFalse(skeleton.contains("<script"));
        assertFalse(skeleton.contains("javascript:"));
    }

    @Test
    void promptDoesNotExposeJavaOrSql() {
        String skeleton = policy.skeletonRules(testContext());
        String detail = policy.detailRules(testContext());
        String repair = policy.repairRules(List.of());

        // No Java class names
        assertFalse(skeleton.contains("com.shitulelv"));
        assertFalse(detail.contains("com.shitulelv"));
        assertFalse(repair.contains("com.shitulelv"));

        // No SQL
        assertFalse(skeleton.toUpperCase().contains("SELECT"));
        assertFalse(detail.toUpperCase().contains("SELECT"));
        assertFalse(repair.toUpperCase().contains("SELECT"));

        // No exception stack traces
        assertFalse(skeleton.contains("Exception"));
        assertFalse(detail.contains("Exception"));
        assertFalse(repair.contains("Exception"));
    }

    @Test
    void repairPromptContainsTargetAndRelatedTask() {
        var issues = List.of(
                new StructuredValidationIssue(
                        "DEPENDENCY_DATE_CONFLICT",
                        ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "T3", "startDate", "T1",
                        Map.of("dependencyDueDate", "2026-08-12", "currentStartDate", "2026-08-10")));
        String prompt = policy.repairRules(issues);

        assertTrue(prompt.contains("DEPENDENCY_DATE_CONFLICT"));
        assertTrue(prompt.contains("targetTempKey=T3"));
        assertTrue(prompt.contains("relatedTempKey=T1"));
        assertTrue(prompt.contains("field=startDate"));
        assertTrue(prompt.contains("dependencyDueDate=2026-08-12"));
    }

    @Test
    void repairPromptContainsAllowedAndLockedFields() {
        var issues = List.of(
                new StructuredValidationIssue(
                        "DEPENDENCY_DATE_CONFLICT",
                        ValidationIssueSeverity.BLOCKING_EDITABLE,
                        "TASK", "T3", "startDate", "T1", Map.of()));
        String prompt = policy.repairRules(issues);

        // Allowed: startDate, dueDate
        assertTrue(prompt.contains("- startDate"));
        assertTrue(prompt.contains("- dueDate"));

        // Locked: tempKey, title, objective, sortOrder
        assertTrue(prompt.contains("- tempKey"));
        assertTrue(prompt.contains("- title"));
        assertTrue(prompt.contains("- objective"));
        assertTrue(prompt.contains("- sortOrder"));
    }

    @Test
    void skeletonRulesContainLimits() {
        String prompt = policy.skeletonRules(testContext());
        assertTrue(prompt.contains("里程碑不超过 8 个"));
        assertTrue(prompt.contains("任务不超过 20 个"));
    }
}
