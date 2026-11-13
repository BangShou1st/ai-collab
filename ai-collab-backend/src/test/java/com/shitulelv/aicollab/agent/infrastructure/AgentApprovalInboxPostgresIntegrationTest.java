package com.shitulelv.aicollab.agent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRunEventRecorder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class AgentApprovalInboxPostgresIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    static JdbcTemplate jdbc;
    static AgentRepository runs;
    static AgentApprovalRepository approvals;
    static ObjectMapper json;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = new ObjectMapper().findAndRegisterModules();
        runs = new AgentRepository(jdbc, json, new AgentRunEventRecorder(jdbc, json));
        approvals = new AgentApprovalRepository(jdbc, json);
    }

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM agent_approval_revision");
        jdbc.update("DELETE FROM agent_tool_invocation");
        jdbc.update("DELETE FROM agent_step");
        jdbc.update("DELETE FROM agent_run_event");
        jdbc.update("DELETE FROM agent_approval");
        jdbc.update("DELETE FROM agent_message");
        jdbc.update("DELETE FROM agent_run");
        jdbc.update("DELETE FROM agent_session");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM app_user");
    }

    @Test
    void updatedSinceOnlyReturnsRowsWhoseUpdatedAtAdvanced() {
        Fixture fixture = fixture();
        OffsetDateTime cutoff = OffsetDateTime.parse("2026-11-14T01:00:00Z");
        UUID olderId = insertApproval(fixture, cutoff.minusMinutes(1), "PENDING");
        UUID newerId = insertApproval(fixture, cutoff.plusMinutes(1), "PENDING");

        assertThat(approvals.list(fixture.projectId(), "PENDING", cutoff))
                .extracting(AgentApprovalView::id)
                .containsExactly(newerId);
        assertThat(approvals.list(fixture.projectId(), "PENDING", null))
                .extracting(AgentApprovalView::id)
                .contains(olderId, newerId);
    }

    @Test
    void approveRejectAndExpireAdvanceUpdatedAtForIncrementalPolling() {
        Fixture fixture = fixture();
        OffsetDateTime initial = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2);
        UUID approvedId = insertApproval(fixture, initial, "PENDING");
        UUID rejectedId = insertApproval(fixture, initial, "PENDING");
        UUID expiringId = insertApproval(fixture, initial, "PENDING");

        AgentApprovalView approve = approvals.find(fixture.projectId(), approvedId).orElseThrow();
        approvals.approve(approve, fixture.userId(), UUID.randomUUID(), json.createObjectNode());
        AgentApprovalView reject = approvals.find(fixture.projectId(), rejectedId).orElseThrow();
        approvals.reject(reject, fixture.userId(), UUID.randomUUID(), "not now");
        approvals.expirePending(initial.plusHours(2));

        assertThat(jdbc.queryForObject(
                "SELECT updated_at>? FROM agent_approval WHERE id=?",
                Boolean.class, initial, approvedId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT updated_at>? FROM agent_approval WHERE id=?",
                Boolean.class, initial, rejectedId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT updated_at>? FROM agent_approval WHERE id=?",
                Boolean.class, initial, expiringId)).isTrue();
    }

    private static Fixture fixture() {
        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                userId, "inbox-" + userId.toString().substring(0, 8), "test-hash", "Inbox User");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by,status) VALUES (?,?,?,?,'PREPARING')",
                projectId, "Inbox Project", userId, userId);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                projectId, userId);
        var session = runs.createSession(projectId, userId, "Inbox");
        var run = runs.createRun(projectId, session.id(), userId, "test", false, null, null);
        return new Fixture(userId, projectId, run.id());
    }

    private static UUID insertApproval(Fixture fixture, OffsetDateTime updatedAt, String status) {
        UUID id = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        Integer sequence = jdbc.queryForObject(
                "SELECT COALESCE(max(sequence_no),0)+1 FROM agent_step WHERE run_id=?",
                Integer.class, fixture.runId());
        jdbc.update("""
                INSERT INTO agent_step(id,run_id,sequence_no,type,tool_name)
                VALUES (?,?,?,'APPROVAL_REQUESTED','create_task_after_approval')
                """, stepId, fixture.runId(), sequence);
        jdbc.update("""
                INSERT INTO agent_approval(
                  id,project_id,run_id,step_id,tool_name,arguments_json,arguments_hash,
                  diff_json,status,requester_id,nonce_hash,expires_at,session_id,
                  proposal_family,subject_key,revision,created_at,updated_at)
                VALUES (?,?,?,?,?,'{}'::jsonb,?,'{}'::jsonb,?,?,?,?,
                        (SELECT session_id FROM agent_run WHERE id=?),'TASK_CREATE',?,1,?,?)
                """, id, fixture.projectId(), fixture.runId(), stepId, "create_task_after_approval",
                "a".repeat(64), status, fixture.userId(), "b".repeat(64),
                updatedAt.plusHours(1), fixture.runId(), id, updatedAt, updatedAt);
        return id;
    }

    private record Fixture(UUID userId, UUID projectId, UUID runId) {
    }
}
