package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.view.*;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 会话与运行的查询/领取仓储。
 *
 * <p>运行过程中的事件与状态写路径已拆分到 {@link AgentRunEventRecorder}；
 * 行映射共用 {@link AgentRunMappers}，保证查询与写入两侧的字段清单一致。</p>
 */
@Repository
public class AgentRepository {
    public boolean atomicEventsEnabled() { return recorder.atomicEventsEnabled(); }
    public long activeElapsedMillis(AgentRunView run) {
        return jdbc.queryForObject("SELECT active_elapsed_ms+CASE WHEN claim_started_at IS NULL THEN 0 ELSE GREATEST(0,(extract(epoch FROM(now()-claim_started_at))*1000)::bigint) END FROM agent_run WHERE project_id=? AND id=?",Long.class,run.projectId(),run.id());
    }
    public JsonNode modelSnapshot(AgentRunView run) {
        var values=jdbc.queryForList("SELECT model_configuration_snapshot::text FROM agent_run WHERE project_id=? AND id=?",String.class,run.projectId(),run.id());
        return values.isEmpty() ? null : parse(values.getFirst());
    }
    public UUID invocationId(AgentRunView run, com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall call) {
        var ids=jdbc.queryForList("SELECT invocation_id FROM agent_tool_invocation WHERE run_id=? AND tool_call_id=? AND turn_sequence=(SELECT max(sequence_no) FROM agent_step WHERE run_id=? AND type='MODEL_TURN')",UUID.class,run.id(),call.id(),run.id());
        return ids.size()==1 ? ids.getFirst() : null;
    }
    public Map<String,Integer> recoveryCounters(AgentRunView run) {
        Map<String,Integer> counters=new java.util.HashMap<>(Map.of("MODEL_RETRY",0,"FORMAT_REPAIR",0,"PARAMETER_CORRECTION",0,"TOOL_RETRY",0));
        jdbc.query("SELECT kind,attempts FROM agent_recovery_counter WHERE run_id=?",(org.springframework.jdbc.core.RowCallbackHandler)rs -> counters.put(rs.getString(1),rs.getInt(2)),run.id());
        return counters;
    }
    @Transactional public boolean consumeRecovery(AgentRunView run, String kind, int maximum) {
        AgentLeaseScope.verify(jdbc,run.projectId(),run.id(),false);
        return jdbc.queryForList("INSERT INTO agent_recovery_counter(run_id,kind,attempts) VALUES (?,?,1) ON CONFLICT(run_id,kind) DO UPDATE SET attempts=agent_recovery_counter.attempts+1 WHERE agent_recovery_counter.attempts<? RETURNING attempts",Integer.class,run.id(),kind,maximum).size()==1;
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AgentRunEventRecorder recorder;

    public AgentRepository(JdbcTemplate jdbc, ObjectMapper json, AgentRunEventRecorder recorder) {
        this.jdbc = jdbc;
        this.json = json;
        this.recorder = recorder;
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

    public Optional<AgentRunView> findLatestRun(UUID projectId, UUID sessionId) {
        return jdbc.query("SELECT * FROM agent_run WHERE project_id=? AND session_id=? ORDER BY created_at DESC, id DESC LIMIT 1",
                AgentRunMappers.runMapper(), projectId, sessionId).stream().findFirst();
    }

    public List<AgentSessionSummaryView> listSessionSummaries(UUID projectId, int limit) {
        return jdbc.query("""
                SELECT s.id, s.project_id, s.creator_id, COALESCE(u.display_name, u.username, s.creator_id::text) AS creator_name,
                       s.title, s.status, s.version, s.created_at, s.updated_at,
                       (SELECT r.id FROM agent_run r WHERE r.project_id=s.project_id AND r.session_id=s.id ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS latest_run_id,
                       (SELECT r.status FROM agent_run r WHERE r.project_id=s.project_id AND r.session_id=s.id ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS latest_run_status,
                       (SELECT r.updated_at FROM agent_run r WHERE r.project_id=s.project_id AND r.session_id=s.id ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS latest_activity_at
                FROM agent_session s LEFT JOIN app_user u ON u.id=s.creator_id
                WHERE s.project_id=? ORDER BY s.updated_at DESC, s.id DESC LIMIT ?
                """, (rs, row) -> new AgentSessionSummaryView(
                        rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                        rs.getObject("creator_id", UUID.class), rs.getString("creator_name"),
                        rs.getString("title"), rs.getString("status"), rs.getInt("version"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getObject("latest_run_id", UUID.class), rs.getString("latest_run_status"),
                        rs.getObject("latest_activity_at", OffsetDateTime.class)), projectId, limit);
    }

    public Optional<AgentSessionView> renameSession(
            UUID projectId, UUID sessionId, UUID creatorId, String title) {
        return jdbc.query("""
                UPDATE agent_session
                SET title=?,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND creator_id=?
                RETURNING id,project_id,creator_id,title,status,version,created_at,updated_at
                """, sessionMapper(), title, projectId, sessionId, creatorId)
                .stream().findFirst();
    }

    public boolean deleteSession(UUID projectId, UUID sessionId, UUID creatorId) {
        return jdbc.update("""
                DELETE FROM agent_session
                WHERE project_id=? AND id=? AND creator_id=?
                """, projectId, sessionId, creatorId) == 1;
    }

    @Transactional
    public AgentRunView createRun(
            UUID projectId, UUID sessionId, UUID requesterId, String goal, boolean scheduled,
            String skillCode, String pageContextJson) {
        UUID runId = UUID.randomUUID();
        AgentRunView run = jdbc.queryForObject("""
                INSERT INTO agent_run(
                  id,session_id,project_id,requester_id,goal,status,scheduled,skill_code,page_context_json)
                SELECT ?,s.id,s.project_id,?,?, 'QUEUED',?,?,?::jsonb
                FROM agent_session s
                WHERE s.project_id=? AND s.id=?
                RETURNING *
                """, AgentRunMappers.runMapper(), runId, requesterId, goal, scheduled,
                skillCode, pageContextJson, projectId, sessionId);
        if (run == null) {
            throw new IllegalArgumentException("Agent 会话不存在");
        }
        UUID messageId = jdbc.queryForObject("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'USER',?,'[]'::jsonb,'[]'::jsonb)
                RETURNING id
                """, UUID.class, sessionId, runId, goal);
        jdbc.update("UPDATE agent_session SET updated_at=now(),version=version+1 WHERE project_id=? AND id=?",
                projectId, sessionId);
        AgentWorkingState.appendUser(jdbc,json,sessionId,goal,messageId);
        return run;
    }

    public Optional<AgentRunView> findRun(UUID projectId, UUID runId) {
        return jdbc.query("SELECT * FROM agent_run WHERE project_id=? AND id=?",
                AgentRunMappers.runMapper(), projectId, runId).stream().findFirst();
    }

    public boolean isCancelRequested(UUID projectId, UUID runId) {
        Boolean requested = jdbc.query("""
                SELECT cancel_requested_at IS NOT NULL
                FROM agent_run WHERE project_id=? AND id=?
                """, (rs, row) -> rs.getBoolean(1), projectId, runId)
                .stream().findFirst().orElse(false);
        return Boolean.TRUE.equals(requested);
    }

    @Transactional
    public AgentRunStatus requestCancel(UUID projectId, UUID runId) {
        CancelState current = jdbc.query("""
                SELECT status,cancel_requested_at IS NOT NULL AS cancel_requested
                FROM agent_run
                WHERE project_id=? AND id=?
                FOR UPDATE
                """, (rs, row) -> new CancelState(
                        AgentRunStatus.valueOf(rs.getString("status")),
                        rs.getBoolean("cancel_requested")), projectId, runId)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_CANCELABLE));
        if (current.status() == AgentRunStatus.CANCELED) {
            return AgentRunStatus.CANCELED;
        }
        if (current.status() != AgentRunStatus.QUEUED
                && current.status() != AgentRunStatus.RUNNING
                && current.status() != AgentRunStatus.WAITING_FOR_APPROVAL
                && current.status() != AgentRunStatus.WAITING_FOR_USER_INPUT
                && current.status() != AgentRunStatus.FAILED_RETRYABLE) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_CANCELABLE);
        }
        if (current.status() == AgentRunStatus.RUNNING && current.requested()) {
            return AgentRunStatus.RUNNING;
        }
        AgentRunStatus target = current.status() == AgentRunStatus.RUNNING
                ? AgentRunStatus.RUNNING : AgentRunStatus.CANCELED;
        // WAITING_FOR_USER_INPUT 可以直接取消
        if (current.status() == AgentRunStatus.WAITING_FOR_USER_INPUT) {
            target = AgentRunStatus.CANCELED;
        }
        int updated = jdbc.update("""
                UPDATE agent_run
                SET cancel_requested_at=COALESCE(cancel_requested_at,now()),
                    status=?,
                    finished_at=CASE WHEN ?='CANCELED' THEN now() ELSE finished_at END,
                    lease_owner=CASE WHEN ?='CANCELED' THEN NULL ELSE lease_owner END,
                    lease_expires_at=CASE WHEN ?='CANCELED' THEN NULL ELSE lease_expires_at END,
                    updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND status=?
                """, target.name(), target.name(), target.name(), target.name(),
                projectId, runId, current.status().name());
        requireRunUpdate(updated);
        return target;
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

    public Optional<com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult> pendingModelTurn(AgentRunView run) {
        return jdbc.query("""
                SELECT output_json::text FROM agent_step WHERE run_id=? AND type='MODEL_TURN'
                  AND jsonb_array_length(COALESCE(output_json->'toolCalls','[]'::jsonb))>0
                  AND NOT COALESCE((output_json->>'batchHandled')::boolean,false)
                ORDER BY sequence_no DESC LIMIT 1
                """, (rs, row) -> {
            try { return json.readValue(rs.getString(1), com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult.class); }
            catch (Exception failure) { throw new IllegalStateException("持久化模型轮次无法恢复", failure); }
        }, run.id()).stream().findFirst();
    }

    public Optional<JsonNode> knownInvocationResult(AgentRunView run, com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall call) {
        return jdbc.query("""
                SELECT i.arguments_json::text,CASE WHEN i.proposal_id IS NOT NULL
                  THEN jsonb_build_object('status','SUCCEEDED','effect','PROPOSAL_PENDING','proposalId',i.proposal_id,'revision',a.revision)::text
                  ELSE i.result_json::text END AS result, i.tool_name FROM agent_tool_invocation i LEFT JOIN agent_approval a ON a.id=i.proposal_id
                WHERE i.run_id=? AND i.tool_call_id=? AND (i.status<>'PENDING' OR i.proposal_id IS NOT NULL)
                  AND i.turn_sequence=(SELECT max(sequence_no) FROM agent_step WHERE run_id=? AND type='MODEL_TURN')
                """, (rs, row) -> {
            try {
                if (!call.name().equals(rs.getString("tool_name")) || !json.readTree(rs.getString(1)).equals(call.arguments()))
                    throw new IllegalStateException("调用身份与参数冲突");
                return json.readTree(rs.getString(2));
            } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
        }, run.id(), call.id(), run.id()).stream().findFirst();
    }

    public JsonNode workingState(UUID projectId, UUID sessionId) {
        return jdbc.query("SELECT working_state::text FROM agent_session WHERE project_id=? AND id=?", (rs,row) -> {
            try { return json.readTree(rs.getString(1)); } catch (Exception failed) { throw new IllegalStateException(failed); }
        }, projectId, sessionId).stream().findFirst().orElseGet(json::createObjectNode);
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
                SET active_elapsed_ms=r.active_elapsed_ms+CASE WHEN c.status='RUNNING' AND r.claim_started_at IS NOT NULL
                    THEN GREATEST(0,(extract(epoch FROM(LEAST(r.lease_expires_at,now())-r.claim_started_at))*1000)::bigint) ELSE 0 END,
                    status='RUNNING',lease_owner=?,lease_expires_at=?,claim_version=r.version+1,claim_started_at=now(),
                    started_at=COALESCE(started_at,?),updated_at=?,version=version+1
                FROM candidate c
                WHERE r.id=c.id
                RETURNING r.id,r.session_id,r.project_id,r.requester_id,r.parent_run_id,
                  r.role,r.depth,r.goal,c.status AS previous_status,r.scheduled,
                  r.correction_attempted,r.version
                """, AgentRunMappers.claimedMapper(), now, now, workerId, expires, now, now)
                .stream().findFirst();
    }

    // ---- 以下为运行事件与状态写路径，委托给 AgentRunEventRecorder ----

    public boolean updateStatus(
            UUID projectId, UUID runId, int version,
            AgentRunStatus expected, AgentRunStatus target, String errorCode) {
        return recorder.updateStatus(projectId, runId, version, expected, target, errorCode);
    }

    public void recordBudgetExceeded(AgentRunView run) {
        recorder.recordBudgetExceeded(run);
    }

    public void recordBudgetExceeded(AgentRunView run, com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult completion) {
        recorder.recordBudgetExceeded(run, completion);
    }

    public void recordDecisionFailure(
            AgentRunView run, com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult completion,
            String errorCode, String reason) {
        recorder.recordDecisionFailure(run, completion, errorCode, reason);
    }

    public void recordFailure(AgentRunView run, String errorCode, boolean retryable) {
        recorder.recordFailure(run, errorCode, retryable);
    }

    public void recordCanceled(AgentRunView run) {
        recorder.recordCanceled(run);
    }

    public void recordInvalidDecision(
            AgentRunView run, com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult completion, String reason) {
        recorder.recordInvalidDecision(run, completion, reason);
    }

    public void recordFinal(
            AgentRunView run, com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult completion,
            com.shitulelv.aicollab.agent.domain.model.AgentDecision.FinalAnswer answer) {
        recorder.recordFinal(run, completion, answer);
    }

    public void recordToolResult(
            AgentRunView run,
            com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult completion,
            com.shitulelv.aicollab.agent.domain.model.AgentDecision.CallTool call,
            JsonNode result) {
        recorder.recordToolResult(run, completion, call, result);
    }

    public AgentRunView recordDelegation(
            AgentRunView run, com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult completion,
            com.shitulelv.aicollab.agent.domain.model.AgentDecision.Delegate delegate) {
        return recorder.recordDelegation(run, completion, delegate);
    }

    /**
     * 更新 Run 的计划。
     */
    @Transactional
    public void updatePlan(UUID projectId, UUID runId, int version, String planJson) {
        recorder.updatePlan(projectId, runId, version, planJson);
    }

    /**
     * 重新排队 Run。
     */
    @Transactional
    public void requeueRun(AgentRunView run) {
        recorder.requeueRun(run);
    }

    /**
     * 记录模型轮次。同时更新 Run 的 token 预算计数和版本。
     */
    @Transactional
    public AgentRunView recordModelTurn(AgentRunView run, com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult turn) {
        return recorder.recordModelTurn(run, turn);
    }

    /**
     * 记录工具结果。同时更新 Run 的 tool_calls_used 和版本。
     */
    @Transactional
    public AgentRunView recordToolResult(AgentRunView run, String toolName,
                                  JsonNode arguments, JsonNode result, boolean isError) {
        return recorder.recordToolResult(run, toolName, arguments, result, isError);
    }

    /**
     * 记录最终答案。使用 ObjectMapper 序列化 JSONB，确保中文、引号、换行等正确保存。
     * 先检查版本，再写入数据，避免版本冲突时留下孤立数据。
     */
    @Transactional
    public AgentRunView recordFinal(AgentRunView run, String content, List<AgentCitation> citations) {
        return recorder.recordFinal(run, content, citations);
    }

    /**
     * 记录等待用户输入状态。
     */
    @Transactional
    public AgentRunView recordWaitingForInput(AgentRunView run, String question) {
        return recorder.recordWaitingForInput(run, question);
    }

    /**
     * 继续等待用户输入的 Run。
     */
    @Transactional
    public AgentRunView continueRun(AgentRunView run, String userResponse) {
        return recorder.continueRun(run, userResponse);
    }

    /**
     * 加载最近的对话历史（用于上下文）。
     * 返回最近 limit 条消息。
     */
    public List<AgentMessageView> listRecentMessages(UUID sessionId, int limit) {
        return jdbc.query("""
                SELECT m.* FROM agent_message m
                WHERE m.session_id=?
                ORDER BY m.created_at DESC, m.id DESC
                LIMIT ?
                """, messageMapper(), sessionId, limit);
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
    public boolean citationsStillValid(UUID projectId, JsonNode result) {
        if (result==null) return false;
        for (JsonNode citation : result.path("citations")) {
            try {
                int found=jdbc.queryForObject("SELECT count(*) FROM document_chunk c JOIN project_document d ON d.id=c.document_id WHERE c.id=? AND d.id=? AND d.project_id=? AND d.status='READY'",Integer.class,
                        UUID.fromString(citation.path("chunkId").asText()),UUID.fromString(citation.path("documentId").asText()),projectId);
                if (found!=1) return false;
            } catch (IllegalArgumentException malformed) { return false; }
        }
        return true;
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static void requireRunUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Agent 运行已被其他 worker 修改");
        }
    }

    private record CancelState(AgentRunStatus status, boolean requested) {
    }
}
