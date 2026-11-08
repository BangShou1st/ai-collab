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
        // 本测试前提是"V66 存量数据跨迁移"：此时 agent_session 尚无 max_aggregate_tokens
        // 列（V83 才引入），故直插 legacy 形状的会话行，不走引用新列的 createSession
        var sessionId = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_session(id,project_id,creator_id,title) VALUES (?,?,?,'Upgrade')",
                sessionId, project, user);
        var run = repository.createRun(project, sessionId, user, "Upgrade", false, "ITERATION_PLANNING", null);
        jdbc.update("UPDATE agent_run SET status='RUNNING',lease_expires_at=now()+interval '6 minutes',claim_version=1 WHERE id=?", run.id());
        var started = new ModelToolCall("started", "list_tasks", json.createObjectNode());
        var pending = new ModelToolCall("not-started", "list_tasks", json.createObjectNode());
        var timeout = new ModelToolCall("old-timeout", "list_tasks", json.createObjectNode());
        // 以 V66 形状手工落"含 3 个工具调用的模型轮"：生产 recordModelTurn 现依赖 V83 的
        // 会话聚合列（预算治理 §4.1），本测试必须在 V66 schema 上造存量数据——
        // 迁移保护的对象正是这些调用行，fixture 语义与 recordModelTurn 写入一致
        var turnJson = json.valueToTree(new ModelTurnResult("", List.of(started, pending, timeout),
                ModelFinishReason.TOOL_CALLS, null, "OPENAI_COMPATIBLE", "fake", 1L)).toString();
        int seq = jdbc.queryForObject("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,usage_basis,latency_ms,model_provider,model_name)
                SELECT ?,COALESCE(max(sequence_no),0)+1,'MODEL_TURN',?::jsonb,'',0,0,false,'PROVIDER',1,'fake','fake'
                FROM agent_step WHERE run_id=?
                RETURNING sequence_no
                """, Integer.class, run.id(), turnJson, run.id());
        jdbc.update("UPDATE agent_run SET steps_used=steps_used+1, version=version+1 WHERE id=?", run.id());
        var calls = List.of(started, pending, timeout);
        for (int ordinal = 0; ordinal < calls.size(); ordinal++) {
            var call = calls.get(ordinal);
            UUID invocationId = UUID.nameUUIDFromBytes(
                    (run.id() + ":" + seq + ":" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("""
                    INSERT INTO agent_tool_invocation(run_id,turn_sequence,ordinal,invocation_id,tool_call_id,tool_name,arguments_json,status)
                    VALUES (?,?,?,?,?,?,?::jsonb,'PENDING')
                    """, run.id(), seq, ordinal, invocationId, call.id(), call.name(), call.arguments().toString());
        }
        run = repository.findRun(project, run.id()).orElseThrow();
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
