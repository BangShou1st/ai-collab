package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentRuntimeCoordinator;
import com.shitulelv.aicollab.agent.application.runtime.RoutingAgentModelExecutor;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "agent.enabled=false",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "storage.minio.endpoint=http://localhost:9",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "security.jwt.access-token-minutes=30"
})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AgentWriteProposalSpringIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired AgentRepository runs;
    @Autowired AgentApprovalRepository approvals;
    @Autowired AgentRuntimeCoordinator coordinator;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoBean RoutingAgentModelExecutor model;
    @MockitoBean MinioClient minio;

    @Test
    void createTaskProposalCompletesAndCanBeRevisedThroughUpdateTool() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                userId, "proposal-" + userId.toString().substring(0, 8), "test-hash", "Proposal User");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by,status) VALUES (?,?,?,?,'PREPARING')",
                projectId, "Proposal Project", userId, userId);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                projectId, userId);

        var session = runs.createSession(projectId, userId, "Create task proposal");
        var queued = runs.createRun(projectId, session.id(), userId,
                "创建一个新任务 修复登录页面白屏问题 截止日期是后天",
                false, "ITERATION_PLANNING", null);
        runs.claimNext("integration-worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(2));
        var running = runs.findRun(projectId, queued.id()).orElseThrow();

        var arguments = json.createObjectNode()
                .put("title", "修复登录页面白屏问题")
                .put("dueDate", "2026-08-09");
        var turn = new ModelTurnResult("", List.of(new ModelToolCall(
                "call-create-task", "create_task_after_approval", arguments)),
                ModelFinishReason.TOOL_CALLS, null, "test", "model", 10L);
        when(model.callModel(any(), any(), any(), anyBoolean())).thenReturn(turn);
        when(model.isLegacyMode(projectId)).thenReturn(false);

        var outcome = coordinator.advance(running);

        assertThat(outcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(outcome.answer()).contains("修复登录页面白屏问题", "2026-08-09", "待审批");
        var approval = approvals.list(projectId, "PENDING").getFirst();
        assertThat(approval.toolName()).isEqualTo("create_task_after_approval");

        var revision = runs.createRun(projectId, session.id(), userId,
                "把刚刚这个任务的负责人随机安排", false, "ITERATION_PLANNING", null);
        runs.claimNext("integration-worker", OffsetDateTime.now(ZoneOffset.UTC), Duration.ofMinutes(2));
        var revisionRun = runs.findRun(projectId, revision.id()).orElseThrow();
        var revisionArguments = json.createObjectNode()
                .put("approvalId", approval.id().toString())
                .set("changes", json.createObjectNode().put("assigneeId", userId.toString()));
        var revisionTurn = new ModelTurnResult("", List.of(new ModelToolCall(
                "call-update-task", "update_task_after_approval", revisionArguments)),
                ModelFinishReason.TOOL_CALLS, null, "test", "model", 10L);
        when(model.callModel(any(), any(), any(), anyBoolean())).thenReturn(revisionTurn);

        var revisionOutcome = coordinator.advance(revisionRun);

        assertThat(revisionOutcome.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(revisionOutcome.answer()).contains("修复登录页面白屏问题", "待审批");
        assertThat(approvals.list(projectId, "PENDING"))
                .singleElement()
                .satisfies(revised -> {
                    assertThat(revised.id()).isEqualTo(approval.id());
                    assertThat(revised.toolName()).isEqualTo("create_task_after_approval");
                    assertThat(revised.revision()).isEqualTo(2);
                    assertThat(revised.arguments().path("title").asText())
                            .isEqualTo("修复登录页面白屏问题");
                    assertThat(revised.arguments().path("dueDate").asText()).isEqualTo("2026-08-09");
                    assertThat(revised.arguments().path("assigneeId").asText()).isEqualTo(userId.toString());
                });
    }
}
