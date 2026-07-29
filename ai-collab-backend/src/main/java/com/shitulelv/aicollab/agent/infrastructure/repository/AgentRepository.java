package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.*;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AgentRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public AgentSessionView createSession(UUID projectId, UUID creatorId, String title) {
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("""
                INSERT INTO agent_session(id,project_id,creator_id,title)
                VALUES (?,?,?,?)
                RETURNING id,project_id,creator_id,title,status,version,created_at,updated_at
                """, sessionMapper(), id, projectId, creatorId, title);
    }

    public Optional<AgentSessionView> findSession(UUID projectId, UUID sessionId) {
        return jdbc.query("""
                SELECT id,project_id,creator_id,title,status,version,created_at,updated_at
                FROM agent_session WHERE project_id=? AND id=?
                """, sessionMapper(), projectId, sessionId).stream().findFirst();
    }

    public List<AgentSessionView> listSessions(UUID projectId, int limit) {
        return jdbc.query("""
                SELECT id,project_id,creator_id,title,status,version,created_at,updated_at
                FROM agent_session WHERE project_id=?
                ORDER BY updated_at DESC,id DESC LIMIT ?
                """, sessionMapper(), projectId, limit);
    }

    @Transactional
    public AgentRunView createRun(
            UUID projectId, UUID sessionId, UUID requesterId, String goal, boolean scheduled) {
        UUID runId = UUID.randomUUID();
        AgentRunView run = jdbc.queryForObject("""
                INSERT INTO agent_run(
                  id,session_id,project_id,requester_id,goal,status,scheduled)
                SELECT ?,s.id,s.project_id,?,?, 'QUEUED',?
                FROM agent_session s
                WHERE s.project_id=? AND s.id=?
                RETURNING *
                """, runMapper(), runId, requesterId, goal, scheduled, projectId, sessionId);
        if (run == null) {
            throw new IllegalArgumentException("Agent 会话不存在");
        }
        jdbc.update("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'USER',?,'[]'::jsonb,'[]'::jsonb)
                """, sessionId, runId, goal);
        jdbc.update("UPDATE agent_session SET updated_at=now(),version=version+1 WHERE project_id=? AND id=?",
                projectId, sessionId);
        return run;
    }

    public Optional<AgentRunView> findRun(UUID projectId, UUID runId) {
        return jdbc.query("SELECT * FROM agent_run WHERE project_id=? AND id=?",
                runMapper(), projectId, runId).stream().findFirst();
    }

    public List<AgentStepView> listSteps(UUID projectId, UUID runId) {
        return jdbc.query("""
                SELECT s.* FROM agent_step s
                JOIN agent_run r ON r.id=s.run_id
                WHERE r.project_id=? AND r.id=?
                ORDER BY s.sequence_no
                """, stepMapper(), projectId, runId);
    }

    public List<AgentMessageView> listMessages(UUID projectId, UUID sessionId, int limit) {
        return jdbc.query("""
                SELECT m.* FROM agent_message m
                JOIN agent_session s ON s.id=m.session_id
                WHERE s.project_id=? AND s.id=?
                ORDER BY m.created_at,m.id LIMIT ?
                """, messageMapper(), projectId, sessionId, limit);
    }

    @Transactional
    public Optional<ClaimedAgentRun> claimNext(
            String workerId, OffsetDateTime now, Duration lease) {
        OffsetDateTime expires = now.plus(lease);
        return jdbc.query("""
                WITH candidate AS (
                  SELECT id,status FROM agent_run
                  WHERE (
                    status='QUEUED'
                    OR (status='RUNNING' AND lease_expires_at < ?)
                    OR (status='FAILED_RETRYABLE' AND retry_after <= ?)
                  )
                  ORDER BY created_at,id
                  FOR UPDATE SKIP LOCKED
                  LIMIT 1
                )
                UPDATE agent_run r
                SET status='RUNNING',lease_owner=?,lease_expires_at=?,
                    started_at=COALESCE(started_at,?),updated_at=?,version=version+1
                FROM candidate c
                WHERE r.id=c.id
                RETURNING r.id,r.session_id,r.project_id,r.requester_id,r.parent_run_id,
                  r.role,r.depth,r.goal,c.status AS previous_status,r.scheduled,
                  r.correction_attempted,r.version
                """, claimedMapper(), now, now, workerId, expires, now, now)
                .stream().findFirst();
    }

    public boolean updateStatus(
            UUID projectId, UUID runId, int version,
            AgentRunStatus expected, AgentRunStatus target, String errorCode) {
        return jdbc.update("""
                UPDATE agent_run
                SET status=?,error_code=?,lease_owner=NULL,lease_expires_at=NULL,
                    finished_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELED','BUDGET_EXCEEDED')
                                     THEN now() ELSE finished_at END,
                    updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status=?
                """, target.name(), errorCode, target.name(),
                projectId, runId, version, expected.name()) == 1;
    }

    @Transactional
    public void recordBudgetExceeded(AgentRunView run) {
        appendErrorStep(run, "AGENT_BUDGET_EXCEEDED");
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='BUDGET_EXCEEDED',
                  error_code='AGENT_BUDGET_EXCEEDED',finished_at=now(),
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version()));
    }

    @Transactional
    public void recordFailure(AgentRunView run, String errorCode, boolean retryable) {
        appendErrorStep(run, errorCode);
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status=?,error_code=?,
                  retry_count=retry_count+CASE WHEN ? THEN 1 ELSE 0 END,
                  retry_after=CASE WHEN ? THEN now()+interval '30 seconds' ELSE NULL END,
                  finished_at=CASE WHEN ? THEN NULL ELSE now() END,
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, retryable ? "FAILED_RETRYABLE" : "FAILED", errorCode,
                retryable, retryable, retryable, run.projectId(), run.id(), run.version()));
    }

    @Transactional
    public void recordInvalidDecision(
            AgentRunView run, ChatCompletionResult completion, String reason) {
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms,error_code)
                VALUES (?,?,'ERROR',?,?,?,?,?,'AGENT_INVALID_DECISION')
                """, run.id(), sequence, truncate(reason, 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()));
        boolean retry = !run.correctionAttempted();
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status=?,correction_attempted=true,
                  steps_used=steps_used+1,input_tokens_used=input_tokens_used+?,
                  output_tokens_used=output_tokens_used+?,token_usage_estimated=?,
                  error_code='AGENT_INVALID_DECISION',
                  lease_owner=NULL,lease_expires_at=NULL,
                  finished_at=CASE WHEN ? THEN NULL ELSE now() END,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, retry ? "QUEUED" : "FAILED",
                tokens(completion.promptTokens(), completion.content()),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                retry, run.projectId(), run.id(), run.version()));
    }

    @Transactional
    public void recordFinal(
            AgentRunView run, ChatCompletionResult completion, AgentDecision.FinalAnswer answer) {
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,output_json,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms)
                VALUES (?,?,'FINAL_ANSWER',?::jsonb,?,?,?,?)
                """, run.id(), sequence, jsonString(answer),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()));
        jdbc.update("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'ASSISTANT',?,?::jsonb,?::jsonb)
                """, run.sessionId(), run.id(), answer.answer(),
                answer.citations().toString(), answer.inferences().toString());
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='SUCCEEDED',steps_used=steps_used+1,
                  input_tokens_used=input_tokens_used+?,output_tokens_used=output_tokens_used+?,
                  token_usage_estimated=?,model_provider=?,model_name=?,
                  finished_at=now(),lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                truncate(completion.provider(), 80), truncate(completion.model(), 120),
                run.projectId(), run.id(), run.version()));
    }

    @Transactional
    public void recordToolResult(
            AgentRunView run,
            ChatCompletionResult completion,
            AgentDecision.CallTool call,
            JsonNode result) {
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,tool_name,input_json,output_json,reason,
                  prompt_tokens,completion_tokens,token_usage_estimated,latency_ms)
                VALUES (?,?,'TOOL_CALL_COMPLETED',?,?::jsonb,?::jsonb,?,?,?,?,?)
                """, run.id(), sequence, call.tool(), call.arguments().toString(),
                result.toString(), truncate(call.reason(), 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()));
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='QUEUED',steps_used=steps_used+1,
                  tool_calls_used=tool_calls_used+1,input_tokens_used=input_tokens_used+?,
                  output_tokens_used=output_tokens_used+?,token_usage_estimated=?,
                  model_provider=?,model_name=?,lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                truncate(completion.provider(), 80), truncate(completion.model(), 120),
                run.projectId(), run.id(), run.version()));
    }

    private RowMapper<AgentSessionView> sessionMapper() {
        return (rs, row) -> new AgentSessionView(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("creator_id", UUID.class),
                rs.getString("title"),
                rs.getString("status"),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private RowMapper<AgentRunView> runMapper() {
        return (rs, row) -> new AgentRunView(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("requester_id", UUID.class),
                rs.getObject("parent_run_id", UUID.class),
                rs.getString("role"),
                rs.getInt("depth"),
                rs.getString("goal"),
                AgentRunStatus.valueOf(rs.getString("status")),
                rs.getInt("max_steps"),
                rs.getInt("max_tool_calls"),
                rs.getInt("max_children"),
                rs.getInt("max_input_tokens"),
                rs.getInt("max_output_tokens"),
                rs.getInt("steps_used"),
                rs.getInt("tool_calls_used"),
                rs.getInt("children_used"),
                rs.getInt("input_tokens_used"),
                rs.getInt("output_tokens_used"),
                rs.getBoolean("token_usage_estimated"),
                rs.getBoolean("scheduled"),
                rs.getBoolean("correction_attempted"),
                rs.getInt("retry_count"),
                rs.getString("error_code"),
                rs.getInt("version"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class));
    }

    private RowMapper<ClaimedAgentRun> claimedMapper() {
        return (rs, row) -> new ClaimedAgentRun(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("requester_id", UUID.class),
                rs.getObject("parent_run_id", UUID.class),
                rs.getString("role"),
                rs.getInt("depth"),
                rs.getString("goal"),
                AgentRunStatus.valueOf(rs.getString("previous_status")),
                rs.getBoolean("scheduled"),
                rs.getBoolean("correction_attempted"),
                rs.getInt("version"));
    }

    private RowMapper<AgentStepView> stepMapper() {
        return (rs, row) -> new AgentStepView(
                rs.getObject("id", UUID.class),
                rs.getInt("sequence_no"),
                AgentStepType.valueOf(rs.getString("type")),
                rs.getString("tool_name"),
                parse(rs.getString("input_json")),
                parse(rs.getString("output_json")),
                rs.getString("reason"),
                integer(rs, "prompt_tokens"),
                integer(rs, "completion_tokens"),
                rs.getBoolean("token_usage_estimated"),
                integer(rs, "latency_ms"),
                rs.getString("error_code"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private RowMapper<AgentMessageView> messageMapper() {
        return (rs, row) -> new AgentMessageView(
                rs.getObject("id", UUID.class),
                rs.getObject("session_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("role"),
                rs.getString("content"),
                parse(rs.getString("citations_json")),
                parse(rs.getString("inferences_json")),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private JsonNode parse(String value) {
        if (value == null) return null;
        try {
            return json.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 持久化 JSON 无法读取", exception);
        }
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private void appendErrorStep(AgentRunView run, String errorCode) {
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,error_code)
                VALUES (?,?,'ERROR',?)
                """, run.id(), nextSequence(run.id()), errorCode);
    }

    private int nextSequence(UUID runId) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(max(sequence_no),0)+1 FROM agent_step WHERE run_id=?
                """, Integer.class, runId);
        return value == null ? 1 : value;
    }

    private String jsonString(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 结果无法序列化", exception);
        }
    }

    private static int tokens(Integer reported, String text) {
        if (reported != null) return reported;
        int codePoints = text == null ? 0 : text.codePointCount(0, text.length());
        return Math.max(1, (codePoints + 2) / 3);
    }

    private static int boundedLatency(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private static String truncate(String value, int maximum) {
        if (value == null) return null;
        int count = value.codePointCount(0, value.length());
        return count <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    private static void requireRunUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Agent 运行已被其他 worker 修改");
        }
    }
}
