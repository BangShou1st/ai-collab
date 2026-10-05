package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 终态运行（FAILED/CANCELED/BUDGET_EXCEEDED）重试链路回归：真实 PostgreSQL 上
 * 验证派生新运行、旧记录保留、预算/取消标记不继承、重复点击与并发重试幂等。
 */
@Testcontainers(disabledWithoutDocker = true)
class AgentRunRetryPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static JdbcTemplate jdbc;
    static AgentRepository repository;
    static AgentRunService service;
    static AgentEventService events;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        repository = new AgentRepository(jdbc, json, new AgentRunEventRecorder(jdbc, json));
        events = mock(AgentEventService.class);
        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        service = new AgentRunService(access, repository, new AgentSkillRegistry(), json,
                events, new AgentEventRepository(jdbc, json), new AgentApprovalRepository(jdbc, json));
    }

    @BeforeEach
    void clearAgentFixtures() {
        org.mockito.Mockito.reset(events);
        jdbc.update("DELETE FROM agent_approval_revision");
        jdbc.update("DELETE FROM agent_tool_invocation");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_approval");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
    }

    @ParameterizedTest
    @EnumSource(value = AgentRunStatus.class, names = {"FAILED", "CANCELED", "BUDGET_EXCEEDED"})
    void retryFromTerminalStateCreatesDerivedRunWithFreshBudgetAndKeepsOldRecord(AgentRunStatus terminalStatus) {
        UUID userId = insertUser("retry");
        UUID projectId = insertProject(userId);
        var session = repository.createSession(projectId, userId, "会话");
        AgentRunView run = repository.createRun(projectId, session.id(), userId,
                "检查项目进度", false, "ITERATION_PLANNING", "{\"route\":\"TASK_BOARD\"}");
        jdbc.update("""
                UPDATE agent_run SET status=?, error_code='AI_PROVIDER_ERROR',
                  input_tokens_used=49000, output_tokens_used=19000,
                  input_tokens_actual=49000, output_tokens_actual=19000,
                  cancel_requested_at=CASE WHEN ?='CANCELED' THEN now() ELSE NULL END,
                  finished_at=now()
                WHERE id=?
                """, terminalStatus.name(), terminalStatus.name(), run.id());

        AgentRunView derived = service.retry(projectId, run.id(), userId);

        // 新运行：QUEUED、目标/技能/页面上下文复制，预算与取消标记不继承
        assertThat(derived.id()).isNotEqualTo(run.id());
        assertThat(derived.status()).isEqualTo(AgentRunStatus.QUEUED);
        assertThat(derived.goal()).isEqualTo("检查项目进度");
        assertThat(derived.skillCode()).isEqualTo("ITERATION_PLANNING");
        assertThat(derived.pageContextJson().replace(" ", "")).isEqualTo("{\"route\":\"TASK_BOARD\"}");
        assertThat(derived.maxSteps()).isEqualTo(24);
        var row = jdbc.queryForMap("""
                SELECT input_tokens_used, output_tokens_used, cancel_requested_at, retried_from_run_id
                FROM agent_run WHERE id=?
                """, derived.id());
        assertThat(((Number) row.get("input_tokens_used")).longValue()).isZero();
        assertThat(((Number) row.get("output_tokens_used")).longValue()).isZero();
        assertThat(row.get("cancel_requested_at")).isNull();
        assertThat(row.get("retried_from_run_id")).isEqualTo(run.id());
        // 派生运行带一条 USER 消息（与重新提交同一目标等价）
        Integer messages = jdbc.queryForObject(
                "SELECT count(*) FROM agent_message WHERE run_id=? AND role='USER'", Integer.class, derived.id());
        assertThat(messages).isEqualTo(1);
        // 旧运行记录完整保留：状态、错误码、消耗不变
        AgentRunView oldRun = repository.findRun(projectId, run.id()).orElseThrow();
        assertThat(oldRun.status()).isEqualTo(terminalStatus);
        assertThat(oldRun.errorCode()).isEqualTo("AI_PROVIDER_ERROR");
        assertThat(oldRun.inputTokensUsed()).isEqualTo(49000);
        // 事件：旧运行 RUN_RETRY_SCHEDULED 指向新运行，新运行 RUN_CREATED 标记来源
        ArgumentCaptor<UUID> eventRunIds = ArgumentCaptor.forClass(UUID.class);
        verify(events).append(eq(projectId), eventRunIds.capture(), eq(AgentEventType.RUN_RETRY_SCHEDULED), any());
        assertThat(eventRunIds.getValue()).isEqualTo(run.id());
        verify(events).append(eq(projectId), eq(derived.id()), eq(AgentEventType.RUN_CREATED), any());
    }

    @Test
    void repeatedRetryReturnsSameDerivedRunWithoutDuplicatingBusinessActions() {
        UUID userId = insertUser("dup");
        UUID projectId = insertProject(userId);
        var session = repository.createSession(projectId, userId, "会话");
        AgentRunView run = repository.createRun(projectId, session.id(), userId,
                "检查项目进度", false, null, null);
        jdbc.update("UPDATE agent_run SET status='FAILED', finished_at=now() WHERE id=?", run.id());

        AgentRunView first = service.retry(projectId, run.id(), userId);
        AgentRunView second = service.retry(projectId, run.id(), userId);

        assertThat(second.id()).isEqualTo(first.id());
        Integer derivedCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_run WHERE retried_from_run_id=?", Integer.class, run.id());
        assertThat(derivedCount).isEqualTo(1);
        Integer messages = jdbc.queryForObject(
                "SELECT count(*) FROM agent_message WHERE run_id=?", Integer.class, first.id());
        assertThat(messages).isEqualTo(1);
    }

    @Test
    void concurrentRetryYieldsSingleDerivedRun() throws Exception {
        UUID userId = insertUser("conc");
        UUID projectId = insertProject(userId);
        var session = repository.createSession(projectId, userId, "会话");
        AgentRunView run = repository.createRun(projectId, session.id(), userId,
                "检查项目进度", false, null, null);
        jdbc.update("UPDATE agent_run SET status='BUDGET_EXCEEDED', finished_at=now() WHERE id=?", run.id());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<AgentRunView>> results = List.of(
                    pool.submit(() -> { start.await(); return service.retry(projectId, run.id(), userId); }),
                    pool.submit(() -> { start.await(); return service.retry(projectId, run.id(), userId); }));
            start.countDown();
            AgentRunView first = results.get(0).get(10, TimeUnit.SECONDS);
            AgentRunView second = results.get(1).get(10, TimeUnit.SECONDS);

            assertThat(second.id()).isEqualTo(first.id());
            Integer derivedCount = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_run WHERE retried_from_run_id=?", Integer.class, run.id());
            assertThat(derivedCount).isEqualTo(1);
            Integer messages = jdbc.queryForObject(
                    "SELECT count(*) FROM agent_message WHERE run_id=?", Integer.class, first.id());
            assertThat(messages).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retryableFailureRequeuesSameRunWithoutDerivation() {
        UUID userId = insertUser("recov");
        UUID projectId = insertProject(userId);
        var session = repository.createSession(projectId, userId, "会话");
        AgentRunView run = repository.createRun(projectId, session.id(), userId,
                "检查项目进度", false, null, null);
        jdbc.update("UPDATE agent_run SET status='FAILED_RETRYABLE' WHERE id=?", run.id());

        AgentRunView retried = service.retry(projectId, run.id(), userId);

        assertThat(retried.id()).isEqualTo(run.id());
        assertThat(retried.status()).isEqualTo(AgentRunStatus.QUEUED);
        Integer derivedCount = jdbc.queryForObject(
                "SELECT count(*) FROM agent_run WHERE retried_from_run_id IS NOT NULL", Integer.class);
        assertThat(derivedCount).isZero();
    }

    private static UUID insertUser(String tag) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                id, "retry-" + tag + "-" + id.toString().substring(0, 8), "test-only-hash", "Retry Test");
        return id;
    }

    private static UUID insertProject(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                id, "Agent retry", ownerId, ownerId);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", id, ownerId);
        return id;
    }
}
