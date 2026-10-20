package com.shitulelv.aicollab.planning.application;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 PostgreSQL 环境下的计划恢复任务集成测试。
 * 验证 TaskPlanRecoveryJob 的恢复事务契约、状态持久化、active_attempt_id 维护及正常数据的隔离性。
 */
@SpringJUnitConfig(classes = TaskPlanRecoveryPostgresIntegrationTest.TestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class TaskPlanRecoveryPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Configuration
    @EnableTransactionManagement
    static class TestConfig {
        @Bean
        public DataSource dataSource() {
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            Flyway.configure()
                    .dataSource(ds)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            return ds;
        }

        @Bean
        public PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        public JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        public TaskPlanRecoveryJob taskPlanRecoveryJob(JdbcTemplate jdbcTemplate) {
            return new TaskPlanRecoveryJob(jdbcTemplate);
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TaskPlanRecoveryJob recoveryJob;

    private UUID userId;
    private UUID projectId;
    private int attemptCounter = 1;

    @BeforeEach
    void setUp() {
        attemptCounter = 1;
        jdbc.execute("""
                TRUNCATE ai_task_plan_confirmation, ai_task_plan_attempt, ai_task_plan_version,
                         ai_task_plan, project_member, project, app_user CASCADE
                """);
        userId = insertUser("recovery-tester");
        projectId = insertProject("Recovery Project", userId);
    }

    @Test
    @DisplayName("恢复超时 QUEUED/RUNNING attempt，更新对应计划状态并清空 active_attempt_id；未超时及终态数据保持不变")
    void recoverStaleQueuedAndRunningAttemptsAndClearActiveAttempt() {
        // 1. 超时的 SKELETON_GENERATING 计划与 RUNNING attempt
        UUID expiredPlan1 = insertPlan(projectId, "Expired Skeleton Plan", "SKELETON_GENERATING", 15);
        UUID expiredAttempt1 = insertAttempt(expiredPlan1, "SKELETON", "RUNNING", 15, null);
        setActiveAttempt(expiredPlan1, expiredAttempt1);

        // 2. 超时的 DETAIL_GENERATING 计划与 QUEUED attempt
        UUID expiredPlan2 = insertPlan(projectId, "Expired Detail Plan", "DETAIL_GENERATING", 20);
        UUID expiredAttempt2 = insertAttempt(expiredPlan2, "DETAIL", "QUEUED", 20, null);
        setActiveAttempt(expiredPlan2, expiredAttempt2);

        // 3. 对照组：未超时的活跃计划与 RUNNING attempt（5分钟前，不应被恢复）
        UUID freshPlan = insertPlan(projectId, "Fresh Plan", "SKELETON_GENERATING", 5);
        UUID freshAttempt = insertAttempt(freshPlan, "SKELETON", "RUNNING", 5, null);
        setActiveAttempt(freshPlan, freshAttempt);

        // 4. 对照组：已完成的终态计划与 SUCCESS attempt（20分钟前，不应被恢复）
        UUID completedPlan = insertPlan(projectId, "Completed Plan", "READY", 20);
        UUID completedAttempt = insertAttempt(completedPlan, "DETAIL", "SUCCESS", 20, null);
        setActiveAttempt(completedPlan, completedAttempt);

        // Act: 执行恢复任务
        recoveryJob.recoverStaleWork();

        // Assert 1: 超时的 Attempt 1 应标记为 FAILED 并设置中断原因与完成时间
        Map<String, Object> attempt1Result = getAttempt(expiredAttempt1);
        assertThat(attempt1Result.get("status")).isEqualTo("FAILED");
        assertThat(attempt1Result.get("error_code")).isEqualTo("PROCESS_RESTARTED");
        assertThat(attempt1Result.get("error_summary")).isEqualTo("进程重启中断处理");
        assertThat(attempt1Result.get("finished_at")).isNotNull();

        // Assert 2: 计划 1 应置为 FAILED，清空 active_attempt_id 并记录错误
        Map<String, Object> plan1Result = getPlan(expiredPlan1);
        assertThat(plan1Result.get("status")).isEqualTo("FAILED");
        assertThat(plan1Result.get("active_attempt_id")).isNull();
        assertThat(plan1Result.get("last_error_code")).isEqualTo("PROCESS_RESTARTED");
        assertThat(plan1Result.get("last_error_summary")).isEqualTo("进程重启中断处理");

        // Assert 3: 超时的 Attempt 2 应标记为 FAILED
        Map<String, Object> attempt2Result = getAttempt(expiredAttempt2);
        assertThat(attempt2Result.get("status")).isEqualTo("FAILED");
        assertThat(attempt2Result.get("error_code")).isEqualTo("PROCESS_RESTARTED");
        assertThat(attempt2Result.get("finished_at")).isNotNull();

        // Assert 4: DETAIL_GENERATING 的计划 2 应置为 DETAIL_GENERATION_FAILED 并清空 active_attempt_id
        Map<String, Object> plan2Result = getPlan(expiredPlan2);
        assertThat(plan2Result.get("status")).isEqualTo("DETAIL_GENERATION_FAILED");
        assertThat(plan2Result.get("active_attempt_id")).isNull();
        assertThat(plan2Result.get("last_error_code")).isEqualTo("PROCESS_RESTARTED");

        // Assert 5: 未超时的对照组 Attempt 保持 RUNNING，计划保持 SKELETON_GENERATING，active_attempt_id 不变
        Map<String, Object> freshAttemptResult = getAttempt(freshAttempt);
        assertThat(freshAttemptResult.get("status")).isEqualTo("RUNNING");
        assertThat(freshAttemptResult.get("error_code")).isNull();

        Map<String, Object> freshPlanResult = getPlan(freshPlan);
        assertThat(freshPlanResult.get("status")).isEqualTo("SKELETON_GENERATING");
        assertThat(freshPlanResult.get("active_attempt_id")).isEqualTo(freshAttempt);

        // Assert 6: 已终态的对照组保持不变
        Map<String, Object> completedAttemptResult = getAttempt(completedAttempt);
        assertThat(completedAttemptResult.get("status")).isEqualTo("SUCCESS");

        Map<String, Object> completedPlanResult = getPlan(completedPlan);
        assertThat(completedPlanResult.get("status")).isEqualTo("READY");
        assertThat(completedPlanResult.get("active_attempt_id")).isEqualTo(completedAttempt);
    }

    @Test
    @DisplayName("恢复超时的 REPAIRING 计划：根据 repair_previous_status 回退版本状态并清空 active_attempt_id")
    void recoverStaleRepairingPlansWithFallback() {
        // 1. 带历史状态 CONFIRMED 的 REPAIRING 计划
        UUID planA = insertPlan(projectId, "Repair Plan A", "REPAIRING", 15);
        UUID attemptA = insertAttempt(planA, "REPAIR", "RUNNING", 15, "CONFIRMED");
        setActiveAttempt(planA, attemptA);

        // 2. 无历史状态 (repair_previous_status = NULL) 的 REPAIRING 计划（应回退为 READY）
        UUID planB = insertPlan(projectId, "Repair Plan B", "REPAIRING", 15);
        UUID attemptB = insertAttempt(planB, "REPAIR", "RUNNING", 15, null);
        setActiveAttempt(planB, attemptB);

        // Act: 执行恢复任务
        recoveryJob.recoverStaleWork();

        // Assert Plan A: 恢复为 CONFIRMED
        Map<String, Object> planAResult = getPlan(planA);
        assertThat(planAResult.get("status")).isEqualTo("CONFIRMED");
        assertThat(planAResult.get("active_attempt_id")).isNull();
        assertThat(planAResult.get("last_error_code")).isEqualTo("PROCESS_RESTARTED");
        assertThat(planAResult.get("last_error_summary")).isEqualTo("进程重启中断修复，保留原版本");

        // Assert Plan B: 回退兜底为 READY
        Map<String, Object> planBResult = getPlan(planB);
        assertThat(planBResult.get("status")).isEqualTo("READY");
        assertThat(planBResult.get("active_attempt_id")).isNull();
        assertThat(planBResult.get("last_error_code")).isEqualTo("PROCESS_RESTARTED");
        assertThat(planBResult.get("last_error_summary")).isEqualTo("进程重启中断修复，保留原版本");
    }

    @Test
    @DisplayName("恢复 CONFIRMING 计划与 PROCESSING confirmation 记录")
    void recoverStaleConfirmingPlanAndConfirmationRecords() {
        // 1. 超时的 CONFIRMING 计划，存在成功的确认记录 -> 状态应变为 CONFIRMED
        UUID planSuccess = insertPlan(projectId, "Confirming Success Plan", "CONFIRMING", 15);
        UUID versionSuccess = insertVersion(planSuccess, 1);
        insertConfirmation(projectId, planSuccess, versionSuccess, "SUCCESS", 20);

        // 2. 超时的 CONFIRMING 计划，不存在成功的确认记录 -> 状态应变为 READY
        UUID planNoSuccess = insertPlan(projectId, "Confirming No Success Plan", "CONFIRMING", 15);

        // 3. 超时的 PROCESSING 状态 confirmation -> 应被更新为 FAILED
        UUID planProcessing = insertPlan(projectId, "Processing Plan", "CONFIRMING", 15);
        UUID versionProcessing = insertVersion(planProcessing, 1);
        UUID confirmationExpired = insertConfirmation(projectId, planProcessing, versionProcessing, "PROCESSING", 15);

        // 4. 对照组：未超时的 PROCESSING 状态 confirmation（3分钟前）-> 保持不变
        UUID planFresh = insertPlan(projectId, "Fresh Processing Plan", "READY", 3);
        UUID versionFresh = insertVersion(planFresh, 1);
        UUID confirmationFresh = insertConfirmation(projectId, planFresh, versionFresh, "PROCESSING", 3);

        // Act
        recoveryJob.recoverStaleWork();

        // Assert 1: planSuccess 变为 CONFIRMED，active_attempt_id 为 NULL
        Map<String, Object> planSuccessResult = getPlan(planSuccess);
        assertThat(planSuccessResult.get("status")).isEqualTo("CONFIRMED");
        assertThat(planSuccessResult.get("active_attempt_id")).isNull();

        // Assert 2: planNoSuccess 变为 READY，active_attempt_id 为 NULL
        Map<String, Object> planNoSuccessResult = getPlan(planNoSuccess);
        assertThat(planNoSuccessResult.get("status")).isEqualTo("READY");
        assertThat(planNoSuccessResult.get("active_attempt_id")).isNull();

        // Assert 3: 超时的 confirmationExpired 变为 FAILED
        Map<String, Object> confExpiredResult = getConfirmation(confirmationExpired);
        assertThat(confExpiredResult.get("status")).isEqualTo("FAILED");
        assertThat(confExpiredResult.get("error_code")).isEqualTo("PROCESS_RESTARTED");
        assertThat(confExpiredResult.get("error_summary")).isEqualTo("进程重启中断确认");
        assertThat(confExpiredResult.get("completed_at")).isNotNull();

        // Assert 4: 未超时的 confirmationFresh 保持 PROCESSING
        Map<String, Object> confFreshResult = getConfirmation(confirmationFresh);
        assertThat(confFreshResult.get("status")).isEqualTo("PROCESSING");
        assertThat(confFreshResult.get("completed_at")).isNull();
    }

    @Test
    @DisplayName("恢复任务的事务执行及持久化：提交后重新在独立查询中验证一致性")
    void recoveryTransactionExecutionAndPersistence() {
        UUID plan = insertPlan(projectId, "Tx Test Plan", "SKELETON_GENERATING", 30);
        UUID attempt = insertAttempt(plan, "SKELETON", "QUEUED", 30, null);
        setActiveAttempt(plan, attempt);

        recoveryJob.recoverStaleWork();

        Integer failedAttempts = jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_plan_attempt WHERE id = ? AND status = 'FAILED' AND error_code = 'PROCESS_RESTARTED'",
                Integer.class, attempt);
        assertThat(failedAttempts).isEqualTo(1);

        Integer failedPlans = jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_plan WHERE id = ? AND status = 'FAILED' AND active_attempt_id IS NULL",
                Integer.class, plan);
        assertThat(failedPlans).isEqualTo(1);
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────────

    private UUID insertUser(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO app_user(id, username, password_hash, display_name)
                VALUES (?, ?, ?, ?)
                """, id, "user-" + tag + "-" + id.toString().substring(0, 8), "hash", "User " + tag);
        return id;
    }

    private UUID insertProject(String name, UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO project(id, name, owner_id, created_by, start_date, due_date)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, name, ownerId, ownerId,
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30));
        return id;
    }

    private UUID insertPlan(UUID projId, String title, String status, int updatedMinutesAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan(id, project_id, title, goal, plan_start_date, plan_due_date, max_task_count, status, created_by, generation_seq, updated_at, created_at)
                VALUES (?, ?, ?, 'Test Goal', CURRENT_DATE, CURRENT_DATE + 30, 10, ?, ?, 1, now() - (? || ' minutes')::interval, now() - (? || ' minutes')::interval)
                """, id, projId, title, status, userId, String.valueOf(updatedMinutesAgo), String.valueOf(updatedMinutesAgo));
        return id;
    }

    private UUID insertAttempt(UUID planId, String stage, String status, int updatedMinutesAgo, String repairPrevStatus) {
        UUID id = UUID.randomUUID();
        int attemptNo = attemptCounter++;
        jdbc.update("""
                INSERT INTO ai_task_plan_attempt(id, plan_id, attempt_no, generation_seq, stage, status, created_by, updated_at, created_at, repair_previous_status)
                VALUES (?, ?, ?, 1, ?, ?, ?, now() - (? || ' minutes')::interval, now() - (? || ' minutes')::interval, ?)
                """, id, planId, attemptNo, stage, status, userId, String.valueOf(updatedMinutesAgo), String.valueOf(updatedMinutesAgo), repairPrevStatus);
        return id;
    }

    private void setActiveAttempt(UUID planId, UUID attemptId) {
        jdbc.update("UPDATE ai_task_plan SET active_attempt_id = ? WHERE id = ?", attemptId, planId);
    }

    private UUID insertVersion(UUID planId, int versionNo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ai_task_plan_version(id, plan_id, version_no, source_type, generation_seq, created_by, created_at)
                VALUES (?, ?, ?, 'AI_SKELETON', 1, ?, now())
                """, id, planId, versionNo, userId);
        return id;
    }

    private UUID insertConfirmation(UUID projId, UUID planId, UUID versionId, String status, int updatedMinutesAgo) {
        UUID id = UUID.randomUUID();
        String hash = "0".repeat(64);
        jdbc.update("""
                INSERT INTO ai_task_plan_confirmation(id, project_id, plan_id, version_id, idempotency_key, request_hash,
                                                      status, created_by, updated_at, created_at, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now() - (? || ' minutes')::interval, now() - (? || ' minutes')::interval, now() - (? || ' minutes')::interval)
                """, id, projId, planId, versionId, UUID.randomUUID(), hash, status, userId,
                String.valueOf(updatedMinutesAgo), String.valueOf(updatedMinutesAgo), String.valueOf(updatedMinutesAgo));
        return id;
    }

    private Map<String, Object> getPlan(UUID planId) {
        return jdbc.queryForMap("SELECT * FROM ai_task_plan WHERE id = ?", planId);
    }

    private Map<String, Object> getAttempt(UUID attemptId) {
        return jdbc.queryForMap("SELECT * FROM ai_task_plan_attempt WHERE id = ?", attemptId);
    }

    private Map<String, Object> getConfirmation(UUID confId) {
        return jdbc.queryForMap("SELECT * FROM ai_task_plan_confirmation WHERE id = ?", confId);
    }
}
