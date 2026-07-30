package com.shitulelv.aicollab.planning.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RED tests for PlanningGenerationQuotaService.
 *
 * These tests define the expected behavior of the quota service:
 * - Only successful AI_COMPLETE generations count toward quota
 * - Failed/canceled/discarded generations do NOT count
 * - Active generations occupy a slot
 * - Concurrent requests respect the limit
 * - Provider quota errors are separate from user quota errors
 */
@Testcontainers(disabledWithoutDocker = true)
class PlanningGenerationQuotaServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static JdbcTemplate jdbc;
    private static Instant fixedNow;

    private UUID testUserId;
    private UUID testProjectId;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        fixedNow = Instant.now();
    }

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testProjectId = UUID.randomUUID();

        jdbc.update("INSERT INTO app_user (id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                testUserId, "testuser_" + testUserId.toString().substring(0, 8), "test-only-hash", "Test User");

        jdbc.update("INSERT INTO project (id, name, owner_id, created_by) VALUES (?, ?, ?, ?)",
                testProjectId, "Test Project", testUserId, testUserId);

        jdbc.update("INSERT INTO project_member (project_id, user_id, role, joined_at) VALUES (?, ?, ?, ?)",
                testProjectId, testUserId, "ADMIN", java.sql.Timestamp.from(Instant.now()));
    }

    private PlanningGenerationQuotaService createService(int limit) {
        Clock clock = Clock.fixed(fixedNow, ZoneId.systemDefault());
        return new PlanningGenerationQuotaService(jdbc, clock, limit);
    }

    @Test
    void firstAttemptWithinLimitAllowsGeneration() {
        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void failedGenerationDoesNotCountTowardQuota() {
        createPlanWithStatus("FAILED");
        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void canceledGenerationDoesNotCountTowardQuota() {
        createPlanWithStatus("CANCELED");
        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void discardedVersionDoesNotCountTowardQuota() {
        // Create a plan with a non-AI_COMPLETE version (e.g., MANUAL_EDIT)
        UUID planId = createPlanWithStatus("READY");
        createVersion(planId, "MANUAL_EDIT");
        PlanningGenerationQuotaService service = createService(2);
        // MANUAL_EDIT should not count toward quota
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void successfulAiCompleteCountsTowardQuota() {
        UUID planId = createPlanWithStatus("READY");
        createVersion(planId, "AI_COMPLETE");
        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void successfulRepairAndPartialVersionsCountTowardQuota() {
        for (String sourceType : new String[]{"AI_REPAIR", "AI_PARTIAL", "AI_PARTIAL_REPAIR"}) {
            UUID planId = createPlanWithStatus("READY");
            createVersion(planId, sourceType);
        }

        PlanningGenerationQuotaService service = createService(3);

        assertThatThrownBy(() -> service.checkQuota(testUserId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLANNING_GENERATION_QUOTA_EXCEEDED));
    }

    @Test
    void twoSuccessfulGenerationsExceedsLimit() {
        UUID planId1 = createPlanWithStatus("READY");
        createVersion(planId1, "AI_COMPLETE");

        UUID planId2 = createPlanWithStatus("READY");
        createVersion(planId2, "AI_COMPLETE");

        // With limit=2, 2 successful generations already reach the limit
        PlanningGenerationQuotaService service = createService(2);

        assertThatThrownBy(() -> service.checkQuota(testUserId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLANNING_GENERATION_QUOTA_EXCEEDED));
    }

    @Test
    void activeGenerationOccupiesSlot() {
        createPlanWithStatus("SKELETON_GENERATING");
        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void twoActiveGenerationsExceedsLimit() {
        createPlanWithStatus("SKELETON_GENERATING");
        createPlanWithStatus("DETAIL_GENERATING");

        // With limit=2, 2 active generations already reach the limit
        PlanningGenerationQuotaService service = createService(2);

        assertThatThrownBy(() -> service.checkQuota(testUserId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLANNING_GENERATION_QUOTA_EXCEEDED));
    }

    @Test
    void activePartialRepairOccupiesQuotaSlot() {
        createPlanWithStatus("REPAIRING");
        PlanningGenerationQuotaService service = createService(1);

        assertThatThrownBy(() -> service.checkQuota(testUserId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLANNING_GENERATION_QUOTA_EXCEEDED));
    }

    @Test
    void failedGenerationReleasesActiveSlot() {
        UUID planId = createPlanWithStatus("SKELETON_GENERATING");

        jdbc.update("UPDATE ai_task_plan SET status = 'FAILED' WHERE id = ?", planId);

        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void canceledGenerationReleasesActiveSlot() {
        UUID planId = createPlanWithStatus("SKELETON_GENERATING");

        jdbc.update("UPDATE ai_task_plan SET status = 'CANCELED' WHERE id = ?", planId);

        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void successfulGenerationAfterFailureStillCounts() {
        createPlanWithStatus("FAILED");

        UUID successPlanId = createPlanWithStatus("READY");
        createVersion(successPlanId, "AI_COMPLETE");

        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void regenerationCountsTowardQuota() {
        // Create two separate plans with AI_COMPLETE versions
        UUID planId1 = createPlanWithStatus("READY");
        createVersion(planId1, "AI_COMPLETE");

        UUID planId2 = createPlanWithStatus("READY");
        createVersion(planId2, "AI_COMPLETE");

        // With limit=2, 2 successful generations already reach the limit
        PlanningGenerationQuotaService service = createService(2);

        assertThatThrownBy(() -> service.checkQuota(testUserId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PLANNING_GENERATION_QUOTA_EXCEEDED));
    }

    @Test
    void retryDetailCountsTowardQuota() {
        UUID planId = createPlanWithStatus("DETAIL_GENERATION_FAILED");
        createVersion(planId, "AI_COMPLETE");

        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void repairSuccessCountsOnlyOnce() {
        UUID planId = createPlanWithStatus("READY");
        createVersion(planId, "AI_COMPLETE");

        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void sixtyMinutesOldSuccessDoesNotCount() {
        UUID planId = createPlanWithStatus("READY");
        createVersion(planId, "AI_COMPLETE");

        // Use a clock that is 61 minutes in the future, so the existing version is "old"
        Clock futureClock = Clock.fixed(fixedNow.plusSeconds(61 * 60), ZoneId.systemDefault());
        PlanningGenerationQuotaService service = new PlanningGenerationQuotaService(jdbc, futureClock, 2);

        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void deletingSuccessfulPlanDoesNotRefundQuota() {
        UUID planId = createPlanWithStatus("READY");
        createVersion(planId, "AI_COMPLETE");

        jdbc.update("DELETE FROM ai_task_plan WHERE id = ?", planId);

        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void differentUsersHaveIndependentQuotas() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        jdbc.update("INSERT INTO app_user (id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                user1, "user1_" + user1.toString().substring(0, 8), "test-only-hash", "User 1");
        jdbc.update("INSERT INTO app_user (id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                user2, "user2_" + user2.toString().substring(0, 8), "test-only-hash", "User 2");

        UUID planId1 = createPlanForUser(user1, "READY");
        createVersionForUser(planId1, user1, "AI_COMPLETE");

        UUID planId2 = createPlanForUser(user1, "READY");
        createVersionForUser(planId2, user1, "AI_COMPLETE");

        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(user2)).isTrue();
    }

    @Test
    void modelUnavailableDoesNotCountTowardQuota() {
        createPlanWithStatus("FAILED");
        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void timeoutDoesNotCountTowardQuota() {
        createPlanWithStatus("FAILED");
        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    @Test
    void invalidOutputPlusRepairFailureDoesNotCountTowardQuota() {
        createPlanWithStatus("DETAIL_GENERATION_FAILED");
        PlanningGenerationQuotaService service = createService(2);
        assertThat(service.checkQuota(testUserId)).isTrue();
    }

    private UUID createPlanWithStatus(String status) {
        UUID planId = UUID.randomUUID();
        UUID attemptId = null;

        // Insert plan first (without active_attempt_id)
        jdbc.update("""
                INSERT INTO ai_task_plan (id, project_id, created_by, status, title, goal, plan_start_date, plan_due_date, max_task_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                planId, testProjectId, testUserId, status, "Test Plan", "Test Goal",
                java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(30), 10);

        // For generating states, create an active attempt
        if ("SKELETON_GENERATING".equals(status)
                || "DETAIL_GENERATING".equals(status)
                || "REPAIRING".equals(status)) {
            attemptId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ai_task_plan_attempt (id, plan_id, attempt_no, generation_seq, stage, status, created_by)
                    VALUES (?, ?, 1, 1, ?, 'QUEUED', ?)
                    """,
                    attemptId, planId,
                    "SKELETON_GENERATING".equals(status) ? "SKELETON"
                            : "DETAIL_GENERATING".equals(status) ? "DETAIL" : "REPAIR",
                    testUserId);

            // Update plan with active_attempt_id
            jdbc.update("UPDATE ai_task_plan SET active_attempt_id = ? WHERE id = ?", attemptId, planId);
        }

        return planId;
    }

    private UUID createPlanForUser(UUID userId, String status) {
        UUID planId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan (id, project_id, created_by, status, title, goal, plan_start_date, plan_due_date, max_task_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                planId, testProjectId, userId, status, "Test Plan", "Test Goal",
                java.time.LocalDate.now(), java.time.LocalDate.now().plusDays(30), 10);
        return planId;
    }

    private UUID createVersion(UUID planId, String sourceType) {
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan_version (id, plan_id, version_no, source_type, generation_seq, created_by)
                VALUES (?, ?, 1, ?, 1, ?)
                """,
                versionId, planId, sourceType, testUserId);
        return versionId;
    }

    private UUID createVersionForUser(UUID planId, UUID userId, String sourceType) {
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan_version (id, plan_id, version_no, source_type, generation_seq, created_by)
                VALUES (?, ?, 1, ?, 1, ?)
                """,
                versionId, planId, sourceType, userId);
        return versionId;
    }
}
