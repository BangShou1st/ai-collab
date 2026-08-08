package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.*;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentStepType;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
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
                """, runMapper(), runId, requesterId, goal, scheduled,
                skillCode, pageContextJson, projectId, sessionId);
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
                .orElseThrow(() -> new com.shitulelv.aicollab.common.exception.BusinessException(
                        com.shitulelv.aicollab.common.exception.ErrorCode.AGENT_RUN_NOT_CANCELABLE));
        if (current.status() == AgentRunStatus.CANCELED) {
            return AgentRunStatus.CANCELED;
        }
        if (current.status() != AgentRunStatus.QUEUED
                && current.status() != AgentRunStatus.RUNNING
                && current.status() != AgentRunStatus.WAITING_FOR_APPROVAL
                && current.status() != AgentRunStatus.WAITING_FOR_USER_INPUT
                && current.status() != AgentRunStatus.FAILED_RETRYABLE) {
            throw new com.shitulelv.aicollab.common.exception.BusinessException(
                    com.shitulelv.aicollab.common.exception.ErrorCode.AGENT_RUN_NOT_CANCELABLE);
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
        boolean changed = jdbc.update("""
                UPDATE agent_run
                SET status=?,error_code=?,lease_owner=NULL,lease_expires_at=NULL,
                    finished_at=CASE WHEN ? IN ('SUCCEEDED','FAILED','CANCELED','BUDGET_EXCEEDED')
                                     THEN now() ELSE finished_at END,
                    updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status=?
                """, target.name(), errorCode, target.name(),
                projectId, runId, version, expected.name()) == 1;
        if (changed && target == AgentRunStatus.CANCELED) {
            findRun(projectId, runId).ifPresent(
                    run -> resumeParent(run, "CANCELED", "AGENT_RUN_CANCELED"));
        }
        return changed;
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
        resumeParent(run, "BUDGET_EXCEEDED", "AGENT_BUDGET_EXCEEDED");
    }

    @Transactional
    public void recordBudgetExceeded(AgentRunView run, ChatCompletionResult completion) {
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms,error_code)
                VALUES (?,?,'ERROR','Model response exceeded remaining budget',?,?,?,?,?,
                  'AGENT_BUDGET_EXCEEDED')
                """, run.id(), sequence, completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()));
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='BUDGET_EXCEEDED',
                  steps_used=LEAST(max_steps,steps_used+1),
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  token_usage_estimated=?,error_code='AGENT_BUDGET_EXCEEDED',
                  finished_at=now(),lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                run.projectId(), run.id(), run.version()));
        resumeParent(run, "BUDGET_EXCEEDED", "AGENT_BUDGET_EXCEEDED");
    }

    @Transactional
    public void recordDecisionFailure(
            AgentRunView run, ChatCompletionResult completion, String errorCode, String reason) {
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms,error_code)
                VALUES (?,?,'ERROR',?,?,?,?,?,?)
                """, run.id(), sequence, truncate(reason, 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()), errorCode);
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='FAILED',steps_used=LEAST(max_steps,steps_used+1),
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  token_usage_estimated=?,error_code=?,finished_at=now(),
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                errorCode, run.projectId(), run.id(), run.version()));
        resumeParent(run, "FAILED", errorCode);
    }

    @Transactional
    public void recordFailure(AgentRunView run, String errorCode, boolean retryable) {
        appendErrorStep(run, errorCode);
        // 如果可重试但已达到最大重试次数（10），直接标记为 FAILED 避免死循环
        boolean finalFailure = !retryable || run.retryCount() >= 9;
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status=?,error_code=?,
                  retry_count=LEAST(retry_count+CASE WHEN ? THEN 1 ELSE 0 END, 10),
                  retry_after=CASE WHEN ? AND NOT ? THEN now()+interval '30 seconds' ELSE NULL END,
                  finished_at=CASE WHEN ? THEN NULL ELSE now() END,
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, finalFailure ? "FAILED" : "FAILED_RETRYABLE", errorCode,
                retryable, retryable, finalFailure, finalFailure,
                run.projectId(), run.id(), run.version()));
        if (finalFailure) resumeParent(run, "FAILED", errorCode);
    }

    /**
     * 记录取消状态。状态为 CANCELED，error_code 为 RUN_CANCELLED。
     * 不复用 recordFailure 方法，确保状态一致。
     */
    @Transactional
    public void recordCanceled(AgentRunView run) {
        int updated = jdbc.update("""
                UPDATE agent_run SET status='CANCELED', error_code='RUN_CANCELLED',
                  finished_at=now(), lease_owner=NULL, lease_expires_at=NULL,
                  updated_at=now(), version=version+1
                WHERE project_id=? AND id=? AND status='RUNNING'
                """, run.projectId(), run.id());
        if (updated == 0) {
            AgentRunStatus status = findRun(run.projectId(), run.id())
                    .map(AgentRunView::status)
                    .orElseThrow(() -> new IllegalStateException("Agent 运行不存在"));
            if (status == AgentRunStatus.CANCELED) return;
            throw new IllegalStateException("Agent 运行已进入不可取消状态: " + status);
        }
        appendErrorStep(run, "RUN_CANCELLED");
        resumeParent(run, "CANCELED", "AGENT_RUN_CANCELED");
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
        resumeParent(run, "SUCCEEDED", answer.answer());
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

    @Transactional
    public AgentRunView recordDelegation(
            AgentRunView run, ChatCompletionResult completion, AgentDecision.Delegate delegate) {
        if (run.depth() != 0 || run.childrenUsed() >= run.maxChildren()) {
            throw new IllegalStateException("Agent 子运行预算已耗尽");
        }
        UUID childId = UUID.randomUUID();
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,input_json,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms)
                VALUES (?,?,'DELEGATION_REQUESTED',
                  jsonb_build_object('role',?,'objective',?),?,?,?,?,?)
                """, run.id(), sequence, delegate.role(), delegate.objective(),
                delegate.objective(), completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                boundedLatency(completion.latencyMs()));
        int promptTokens = tokens(completion.promptTokens(), "");
        int outputTokens = tokens(completion.completionTokens(), completion.content());
        int childSteps = run.maxSteps() - run.stepsUsed() - 1;
        int childTools = run.maxToolCalls() - run.toolCallsUsed();
        int childInputs = run.maxInputTokens() - run.inputTokensUsed() - promptTokens;
        int childOutputs = run.maxOutputTokens() - run.outputTokensUsed() - outputTokens;
        if (childSteps < 1 || childInputs < 1 || childOutputs < 1) {
            throw new IllegalStateException("Agent 没有可分配给子运行的剩余预算");
        }
        AgentRunView child = jdbc.queryForObject("""
                INSERT INTO agent_run(
                  id,session_id,project_id,requester_id,parent_run_id,role,depth,goal,status,
                  max_steps,max_tool_calls,max_children,max_input_tokens,max_output_tokens)
                VALUES (?,?,?,?,?,?,1,?,'QUEUED',?,?,0,?,?)
                RETURNING *
                """, runMapper(), childId, run.sessionId(), run.projectId(), run.requesterId(),
                run.id(), delegate.role(), delegate.objective(),
                childSteps, childTools, childInputs, childOutputs);
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='CREATED',steps_used=steps_used+1,
                  children_used=children_used+1,input_tokens_used=input_tokens_used+?,
                  output_tokens_used=output_tokens_used+?,lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, promptTokens, outputTokens,
                run.projectId(), run.id(), run.version()));
        return child;
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
                rs.getString("plan_json"),
                rs.getString("page_context_json"),
                rs.getString("skill_code"),
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

    private void resumeParent(AgentRunView child, String status, String content) {
        if (child.parentRunId() == null) return;
        AgentRunView usage = findRun(child.projectId(), child.id()).orElse(child);
        int sequence = nextSequence(child.parentRunId());
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json,reason)
                VALUES (?,?,'DELEGATION_COMPLETED',
                  jsonb_build_object('childRunId',?,'status',?,'content',?),?)
                """, child.parentRunId(), sequence, child.id(), status, content,
                "Specialist child run completed");
        jdbc.update("""
                UPDATE agent_run SET status='QUEUED',
                  steps_used=LEAST(max_steps,steps_used+?),
                  tool_calls_used=LEAST(max_tool_calls,tool_calls_used+?),
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  token_usage_estimated=token_usage_estimated OR ?,
                  updated_at=now(),version=version+1
                WHERE id=? AND status='CREATED'
                """, usage.stepsUsed(), usage.toolCallsUsed(), usage.inputTokensUsed(),
                usage.outputTokensUsed(), usage.tokenUsageEstimated(), child.parentRunId());
    }

    /**
     * 更新 Run 的计划。
     */
    @Transactional
    public void updatePlan(UUID projectId, UUID runId, int version, String planJson) {
        jdbc.update("""
                UPDATE agent_run SET plan_json=?::jsonb,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=?
                """, planJson, projectId, runId, version);
    }

    /**
     * 重新排队 Run。
     */
    @Transactional
    public void requeueRun(AgentRunView run) {
        jdbc.update("""
                UPDATE agent_run SET status='QUEUED',
                  lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version());
    }

    /**
     * 记录模型轮次。同时更新 Run 的 token 预算计数和版本。
     */
    @Transactional
    public AgentRunView recordModelTurn(AgentRunView run, ModelTurnResult turn) {
        int inputTokens = estimateInputTokens(turn);
        int outputTokens = turn.usage() != null && turn.usage().outputTokens() != null
                ? turn.usage().outputTokens() : 0;
        boolean estimated = turn.usage() == null || turn.usage().inputTokens() == null;

        // 先验证版本和状态，避免版本冲突时留下孤立 Step
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET
                  steps_used=steps_used+1,
                  input_tokens_used=LEAST(max_input_tokens, input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens, output_tokens_used+?),
                  token_usage_estimated=token_usage_estimated OR ?,
                  updated_at=now(), version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, inputTokens, outputTokens, estimated,
                run.projectId(), run.id(), run.version()));

        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms,model_provider,model_name)
                VALUES (?,?,'MODEL_TURN',?,?,?,?,?,?,?)
                """, run.id(), sequence,
                truncate(turn.content(), 2000),
                inputTokens,
                turn.usage() != null ? turn.usage().outputTokens() : null,
                estimated,
                boundedLatency(turn.latencyMs()),
                truncate(turn.provider(), 80),
                truncate(turn.model(), 120));

        // 返回更新后的 Run
        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 记录工具结果。同时更新 Run 的 tool_calls_used 和版本。
     */
    @Transactional
    public AgentRunView recordToolResult(AgentRunView run, String toolName,
                                  JsonNode arguments, JsonNode result, boolean isError) {
        // 先验证版本和状态，避免版本冲突时留下孤立 Step
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET
                  tool_calls_used=tool_calls_used+1,
                  steps_used=steps_used+1,
                  updated_at=now(), version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version()));

        int sequence = nextSequence(run.id());
        // 7 columns, type hardcoded = 6 bind params. ::jsonb on input_json and output_json.
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,tool_name,input_json,output_json,reason)
                VALUES (?,?,'TOOL_CALL_COMPLETED',?,?::jsonb,?::jsonb,?)
                """, run.id(), sequence, toolName,
                arguments.toString(),
                result.toString(),
                isError ? "TOOL_ERROR" : "TOOL_SUCCESS");

        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 记录最终答案。使用 ObjectMapper 序列化 JSONB，确保中文、引号、换行等正确保存。
     * 先检查版本，再写入数据，避免版本冲突时留下孤立数据。
     */
    @Transactional
    public AgentRunView recordFinal(AgentRunView run, String content, List<AgentCitation> citations) {
        // 先验证版本和状态，避免版本冲突时留下孤立 Step/Message
        int updated = jdbc.update("""
                UPDATE agent_run SET status='SUCCEEDED',
                  steps_used=steps_used+1,
                  finished_at=now(),lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version());
        requireRunUpdate(updated);

        int sequence = nextSequence(run.id());

        // 使用 ObjectMapper 序列化为 JSON 对象，确保 JSONB 正确
        String outputJson;
        try {
            ObjectNode outputNode = json.createObjectNode();
            outputNode.put("answer", content);
            outputNode.set("citations", json.valueToTree(citations));
            outputNode.set("inferences", json.createArrayNode());
            outputJson = json.writeValueAsString(outputNode);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent 结果无法序列化", e);
        }

        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,output_json)
                VALUES (?,?,'FINAL_ANSWER',?::jsonb)
                """, run.id(), sequence, outputJson);
        jdbc.update("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'ASSISTANT',?,'[]'::jsonb,'[]'::jsonb)
                """, run.sessionId(), run.id(), content);

        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 记录等待用户输入状态。
     */
    @Transactional
    public AgentRunView recordWaitingForInput(AgentRunView run, String question) {
        // 先验证版本和状态
        int updated = jdbc.update("""
                UPDATE agent_run SET status='WAITING_FOR_USER_INPUT',
                  finished_at=NULL,lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version());
        requireRunUpdate(updated);

        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,output_json,reason)
                VALUES (?,?,'FINAL_ANSWER',?::jsonb,'WAITING_FOR_USER_INPUT')
                """, run.id(), sequence, jsonString(Map.of("question", question)));
        jdbc.update("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'ASSISTANT',?,'[]'::jsonb,'[]'::jsonb)
                """, run.sessionId(), run.id(), question);

        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 继续等待用户输入的 Run。
     */
    @Transactional
    public AgentRunView continueRun(AgentRunView run, String userResponse) {
        // 先验证版本和状态
        int updated = jdbc.update("""
                UPDATE agent_run SET status='QUEUED',
                  finished_at=NULL,lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='WAITING_FOR_USER_INPUT'
                """, run.projectId(), run.id(), run.version());
        requireRunUpdate(updated);

        // 记录用户回复
        jdbc.update("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'USER',?,'[]'::jsonb,'[]'::jsonb)
                """, run.sessionId(), run.id(), userResponse);

        return findRun(run.projectId(), run.id()).orElse(run);
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

    private static int estimateInputTokens(ModelTurnResult turn) {
        if (turn.usage() != null && turn.usage().inputTokens() != null) {
            return turn.usage().inputTokens();
        }
        return Math.max(1, (turn.content() == null ? 0 : turn.content().length()) / 3);
    }

    private record CancelState(AgentRunStatus status, boolean requested) {
    }
}
