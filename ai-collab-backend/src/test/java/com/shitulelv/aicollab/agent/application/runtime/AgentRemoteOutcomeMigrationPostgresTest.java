package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolExecutionSafety;
import com.shitulelv.aicollab.agent.infrastructure.repository.*;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class AgentRemoteOutcomeMigrationPostgresTest {
    @Container static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @Test void v66StartedCallsRemainProtectedWhenDispatchColumnsAreIntroduced() {
        var ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        Flyway.configure().dataSource(ds).target("66").load().migrate();
        var jdbc = new JdbcTemplate(ds);
        var json = new ObjectMapper().findAndRegisterModules();
        var recorder = new AgentRunEventRecorder(jdbc, json);
        var repository = new AgentRepository(jdbc, json, recorder);
        UUID project = UUID.randomUUID(), user = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,'upgrade','test-hash','Upgrade')", user);
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,'Upgrade',?,?)", project, user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", project, user);
        var session = repository.createSession(project, user, "Upgrade");
        var run = repository.createRun(project, session.id(), user, "Upgrade", false, "ITERATION_PLANNING", null);
        jdbc.update("UPDATE agent_run SET status='RUNNING',lease_expires_at=now()+interval '6 minutes',claim_version=1 WHERE id=?", run.id());
        var started = new ModelToolCall("started", "list_tasks", json.createObjectNode());
        var pending = new ModelToolCall("not-started", "list_tasks", json.createObjectNode());
        var timeout = new ModelToolCall("old-timeout", "list_tasks", json.createObjectNode());
        run = repository.recordModelTurn(run, new ModelTurnResult("", List.of(started, pending, timeout), ModelFinishReason.TOOL_CALLS,
                null, "OPENAI_COMPATIBLE", "fake", 1L));
        new AgentEventRepository(jdbc, json).append(project, run.id(), AgentEventType.TOOL_CALL_STARTED,
                json.createObjectNode().put("invocationId", repository.invocationId(run, started).toString())
                        .put("callId", started.id()).put("toolName", started.name()));
        new AgentEventRepository(jdbc, json).append(project, run.id(), AgentEventType.TOOL_CALL_STARTED,
                json.createObjectNode().put("invocationId", repository.invocationId(run, timeout).toString()));
        jdbc.update("UPDATE agent_tool_invocation SET status='FAILED',result_json='{\"status\":\"FAILED\",\"retryable\":true,\"error\":\"TOOL_EXECUTION_TIMEOUT\"}'::jsonb WHERE invocation_id=?",
                repository.invocationId(run, timeout));
        Flyway.configure().dataSource(ds).load().migrate();
        assertThat(repository.dispatchedUnsafe(run, started)).isTrue();
        assertThat(repository.dispatchedUnsafe(run, pending)).isFalse();
        assertThat(repository.knownInvocationResult(run, timeout).orElseThrow().path("status").asText()).isEqualTo("UNKNOWN");
        var current = run;
        var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> repository.recordInvocationDispatch(current, started,
                AgentToolExecutionSafety.UNCONFIRMABLE_SIDE_EFFECT))).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(s -> repository.recordFailure(current, "RECOVERY", true));
        assertThat(jdbc.queryForList("SELECT status FROM agent_tool_invocation WHERE run_id=? ORDER BY ordinal", String.class, run.id()))
                .containsExactly("UNKNOWN", "SKIPPED", "UNKNOWN");
        assertThat(repository.findRun(project, run.id()).orElseThrow().status().name()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT dispatch_claim_version FROM agent_tool_invocation WHERE invocation_id=?", Integer.class,
                repository.invocationId(run, started))).isEqualTo(-1);
    }
}
