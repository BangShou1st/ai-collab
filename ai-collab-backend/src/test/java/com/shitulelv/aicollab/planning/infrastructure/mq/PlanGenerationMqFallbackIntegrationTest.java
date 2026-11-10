package com.shitulelv.aicollab.planning.infrastructure.mq;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.planning.api.CreateTaskPlanRequest;
import com.shitulelv.aicollab.planning.application.TaskPlanGenerationOrchestrator;
import com.shitulelv.aicollab.planning.application.TaskPlanModelClient;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRecord;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 规划 dispatch 发送失败回落进程内集成测试（M2 §3.1 第 2 行"发送失败回落进程内，
 * 同 M1 模式"）：MQ 总闸开启但 broker 不可达（namesrv 指向死端口）时，
 * {@code trySendGeneration} 返回 false，事务提交后回落 {@code dispatchNow} 进程内
 * 执行入口 —— 计划不因 MQ 故障而滞留（恢复 Job 兜底之外的第二重保障）。
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=true",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=30",
        "storage.minio.endpoint=http://127.0.0.1:1",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret",
        "storage.minio.bucket=test-bucket",
        "document.dispatch-interval=3600s",
        "document.dispatch-initial-delay=3600s",
        // 恢复对账 Job 干扰断言，禁用
        "planning.recovery-delay-ms=3600s"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class PlanGenerationMqFallbackIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("planning.mq.enabled", () -> "true");
        // broker 不可达：生产者懒启动失败 → trySendGeneration=false → 回落进程内
        registry.add("planning.mq.namesrv-addr", () -> "127.0.0.1:1");
    }

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TaskPlanRepository repository;
    @Autowired
    TaskPlanGenerationOrchestrator orchestrator;
    @Autowired
    PlatformTransactionManager transactionManager;
    @MockitoBean
    TaskPlanModelClient model;

    private UUID ownerId;
    private UUID projectId;

    @BeforeAll
    static void relaxAwaitilityDefaults() {
        Awaitility.setDefaultPollInterval(Duration.ofMillis(300));
    }

    @BeforeEach
    void resetDatabaseAndMocks() {
        jdbc.update("DELETE FROM ai_task_plan_attempt");
        jdbc.update("DELETE FROM ai_task_plan");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");
        ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id, username, password_hash, display_name) VALUES (?, ?, ?, ?)",
                ownerId, "owner-" + ownerId.toString().substring(0, 8), "test-hash", "规划所有者");
        projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO project(id, name, owner_id, status, created_by) VALUES (?, ?, ?, 'PREPARING', ?)",
                projectId, "规划回落测试", ownerId, ownerId);
        jdbc.update("INSERT INTO project_member(project_id, user_id, role) VALUES (?, ?, 'OWNER')",
                projectId, ownerId);
        when(model.generate(any(), any(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.PLANNING_MODEL_UNAVAILABLE));
    }

    @Test
    void sendFailureFallsBackToInProcessDispatch() {
        TaskPlanRecord plan = repository.create(projectId, ownerId, new CreateTaskPlanRequest(
                "回落进程内规划", "验证发送失败回落", null,
                LocalDate.now(), LocalDate.now().plusDays(7), 5, List.of()));

        // 事务提交后：发送失败（broker 不可达）→ 进程内 dispatchNow 立即执行
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> orchestrator.dispatch(plan, ownerId, false));

        await().atMost(Duration.ofSeconds(60)).until(
                () -> "FAILED".equals(jdbc.queryForObject(
                        "SELECT status FROM ai_task_plan WHERE id=?", String.class, plan.id())));
        verify(model, times(1)).generate(any(), any(), anyString(), any(), any(), any(), any(), any());
    }
}
