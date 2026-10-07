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

    /**
     * 会话最新"主运行"：恢复、SSE 订阅与控制入口都以此为身份。
     * 只取 depth=0 的运行——委派子运行（depth=1）共享同一 session 但不是用户会话的
     * 控制对象，刷新/切会话不能把子运行当主运行恢复（否则父运行被孤立在等待状态）。
     */
    public Optional<AgentRunView> findLatestRun(UUID projectId, UUID sessionId) {
        return jdbc.query("SELECT * FROM agent_run WHERE project_id=? AND session_id=? AND depth=0 ORDER BY created_at DESC, id DESC LIMIT 1",
                AgentRunMappers.runMapper(), projectId, sessionId).stream().findFirst();
    }

    public List<AgentSessionSummaryView> listSessionSummaries(UUID projectId, int limit) {
        return jdbc.query("""
                SELECT s.id, s.project_id, s.creator_id, COALESCE(u.display_name, u.username, s.creator_id::text) AS creator_name,
                       s.title, s.status, s.version, s.created_at, s.updated_at,
                       (SELECT r.id FROM agent_run r WHERE r.project_id=s.project_id AND r.session_id=s.id AND r.depth=0 ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS latest_run_id,
                       (SELECT r.status FROM agent_run r WHERE r.project_id=s.project_id AND r.session_id=s.id AND r.depth=0 ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS latest_run_status,
                       (SELECT r.updated_at FROM agent_run r WHERE r.project_id=s.project_id AND r.session_id=s.id AND r.depth=0 ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS latest_activity_at
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
                  id,session_id,project_id,requester_id,goal,status,scheduled,skill_code,page_context_json,max_steps,max_tool_calls)
                SELECT ?,s.id,s.project_id,?,?, 'QUEUED',?,?,?::jsonb,?,?
                FROM agent_session s
                WHERE s.project_id=? AND s.id=?
                RETURNING *
                """, AgentRunMappers.runMapper(), runId, requesterId, goal, scheduled,
                skillCode, pageContextJson,
                "ITERATION_PLANNING".equals(skillCode)?24:12,
                "ITERATION_PLANNING".equals(skillCode)?16:8, projectId, sessionId);
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

    /** 指定运行的全部子运行（委派等待与结果回收判定使用；只读）。 */
    public List<AgentRunView> childRuns(UUID projectId, UUID parentRunId) {
        return jdbc.query("SELECT * FROM agent_run WHERE project_id=? AND parent_run_id=? ORDER BY created_at",
                AgentRunMappers.runMapper(), projectId, parentRunId);
    }

    /** 终态运行的重试派生结果：created=false 表示并发重试命中幂等边界，返回既有派生运行。 */
    public record RetryRunDerivation(AgentRunView run, boolean created) {}

    /**
     * 从终态运行派生一次新的重试运行：复制目标/技能/页面上下文，输入输出预算、步骤与
     * 取消标记全部从默认值开始，不继承已耗尽的预算或取消标记。retried_from_run_id 上的
     * 部分唯一索引保证同一旧运行至多派生一个新运行——并发重复重试时第二个请求返回既有
     * 派生运行，业务动作（新运行、用户消息、工作状态推进）不重复执行。
     */
    @Transactional
    public RetryRunDerivation createRetryRun(
            UUID projectId, UUID sessionId, UUID requesterId, UUID sourceRunId,
            String goal, String skillCode, String pageContextJson) {
        UUID runId = UUID.randomUUID();
        AgentRunView run = jdbc.query("""
                INSERT INTO agent_run(
                  id,session_id,project_id,requester_id,goal,status,scheduled,skill_code,page_context_json,
                  max_steps,max_tool_calls,retried_from_run_id)
                SELECT ?,s.id,s.project_id,?,?,'QUEUED',false,?,?::jsonb,?,?,?
                FROM agent_session s
                WHERE s.project_id=? AND s.id=?
                ON CONFLICT (retried_from_run_id) WHERE retried_from_run_id IS NOT NULL DO NOTHING
                RETURNING *
                """, AgentRunMappers.runMapper(), runId, requesterId, goal, skillCode, pageContextJson,
                "ITERATION_PLANNING".equals(skillCode)?24:12,
                "ITERATION_PLANNING".equals(skillCode)?16:8,
                sourceRunId, projectId, sessionId).stream().findFirst().orElse(null);
        if (run == null) {
            return new RetryRunDerivation(jdbc.query(
                    "SELECT * FROM agent_run WHERE project_id=? AND retried_from_run_id=?",
                    AgentRunMappers.runMapper(), projectId, sourceRunId).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Agent 会话不存在")), false);
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
        return new RetryRunDerivation(run, true);
    }

    public boolean isCancelRequested(UUID projectId, UUID runId) {
        Boolean requested = jdbc.query("""
                SELECT cancel_requested_at IS NOT NULL
                FROM agent_run WHERE project_id=? AND id=?
                """, (rs, row) -> rs.getBoolean(1), projectId, runId)
                .stream().findFirst().orElse(false);
        return Boolean.TRUE.equals(requested);
    }

    /** 暂停意图是否已落库（不区分运行状态；状态判断由调用方结合 status 完成）。 */
    public boolean isPauseRequested(UUID projectId, UUID runId) {
        Boolean requested = jdbc.query("""
                SELECT pause_requested_at IS NOT NULL
                FROM agent_run WHERE project_id=? AND id=?
                """, (rs, row) -> rs.getBoolean(1), projectId, runId)
                .stream().findFirst().orElse(false);
        return Boolean.TRUE.equals(requested);
    }

    /** 暂停意图落库时间（未请求暂停为 null）；运行详情据此展示"正在暂停/已暂停"。 */
    public OffsetDateTime pauseRequestedAt(UUID projectId, UUID runId) {
        // pause_requested_at 可空；行映射返回 null 不能进入 Optional.of（findFirst 会抛 NPE）。
        var rows = jdbc.query("""
                SELECT pause_requested_at FROM agent_run WHERE project_id=? AND id=?
                """, (rs, row) -> rs.getObject("pause_requested_at", OffsetDateTime.class), projectId, runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 暂停意图检查与安全收口（幂等）：意图已落库时由本调用结清当前 claim 的已确认
     * 执行段并转入 PAUSED（见 {@code AgentRunEventRecorder.recordPaused}）。
     * worker 的每个"新动作准入"检查点复用这一份判断，避免多处复制状态逻辑。
     */
    public boolean pauseIfRequested(AgentRunView run) {
        if (!isPauseRequested(run.projectId(), run.id())) return false;
        recorder.recordPaused(run.projectId(), run.id());
        return true;
    }

    /** 用户发起暂停（见 {@code AgentRunEventRecorder.requestPause}）。 */
    @Transactional
    public AgentRunView requestPause(UUID projectId, UUID runId) {
        return recorder.requestPause(projectId, runId);
    }

    /** 用户发起继续（见 {@code AgentRunEventRecorder.requestResume}）。 */
    @Transactional
    public AgentRunView requestResume(UUID projectId, UUID runId) {
        return recorder.requestResume(projectId, runId);
    }

    /** worker 在动作边界确认暂停（见 {@code AgentRunEventRecorder.recordPaused}）。 */
    @Transactional
    public AgentRunView recordPaused(UUID projectId, UUID runId) {
        return recorder.recordPaused(projectId, runId);
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
                && current.status() != AgentRunStatus.FAILED_RETRYABLE
                && current.status() != AgentRunStatus.PAUSED) {
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

    /**
     * 尚未消费的已持久化模型轮次（最近一轮）：
     *
     * <ul>
     *   <li>含工具调用且批次未处理的轮次——恢复工具批次；</li>
     *   <li>无工具调用但已有正文的轮次——"MODEL_TURN 已提交、后续收尾尚未提交"的窗口，
     *       接管时复用该响应继续其原本的收尾，不再次请求模型。</li>
     * </ul>
     *
     * <p>{@code batchHandled} 由终态写入在同一事务内置位（工具批次见
     * {@code AgentRunEventRecorder.markBatchHandled}，文本收尾见
     * {@code recordFinal(…,true)}/{@code recordWaitingForInput}/{@code recordBudgetPartialAnswer}），
     * 因此只会被消费一次；已消费轮次、更早轮次与其他运行的轮次都不会被再次使用。</p>
     */
    public Optional<com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult> pendingModelTurn(AgentRunView run) {
        return jdbc.query("""
                SELECT output_json::text FROM agent_step WHERE run_id=? AND type='MODEL_TURN'
                  AND output_json IS NOT NULL
                  AND (jsonb_array_length(COALESCE(output_json->'toolCalls','[]'::jsonb))>0
                       OR btrim(COALESCE(output_json->>'content',''))<>'')
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

    /** 待处理调用所属模型轮次的可信执行模式（NATIVE_TOOLS / LEGACY_READ_ONLY）；无记录返回 null。 */
    public String invocationSourceMode(AgentRunView run, com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall call) {
        var rows = jdbc.queryForList("""
                SELECT source_mode FROM agent_tool_invocation
                WHERE run_id=? AND tool_call_id=?
                  AND turn_sequence=(SELECT max(sequence_no) FROM agent_step WHERE run_id=? AND type='MODEL_TURN')
                """, run.id(), call.id(), run.id());
        return rows.isEmpty() ? null : (String) rows.getFirst().get("source_mode");
    }

    public JsonNode workingState(UUID projectId, UUID sessionId) {
        return jdbc.query("SELECT working_state::text FROM agent_session WHERE project_id=? AND id=?", (rs,row) -> {
            try { return json.readTree(rs.getString(1)); } catch (Exception failed) { throw new IllegalStateException(failed); }
        }, projectId, sessionId).stream().findFirst().orElseGet(json::createObjectNode);
    }

    /**
     * 领取下一个可执行运行。接管过期 RUNNING 时，把上一个 claim 的<b>已确认执行时长</b>
     * 累加进 active_elapsed_ms：区间为 [claim_started_at, 最后一次确认进度]；
     * 该 claim 没有确认进度时累加 0（下界为 0 的 GREATEST 保证不出现负区间）。
     *
     * <p>语义：租约有效期不等于已执行时长。租约到期、进程离线和排队重试的等待时间
     * 都不是执行时间，只有 claim 内已持久化进度之前的区间才算已确认执行；
     * 最后一次进度到进程退出这一段没有持久记录，不冒充已执行时长
     * （见 {@code AgentRuntimeJob}：租约 6 分钟是等待上界，不是执行上界）。</p>
     *
     * <p>领取顺序按 depth 降序再按 created_at：委派子运行（depth=1）先于等待它的父运行
     * 被处理，父运行在子运行完成后回收结果；正常深度 0 运行之间的顺序不变。</p>
     */
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
                  ORDER BY depth DESC,created_at,id
                  FOR UPDATE SKIP LOCKED
                  LIMIT 1
                )
                UPDATE agent_run r
                SET active_elapsed_ms=r.active_elapsed_ms+CASE WHEN c.status='RUNNING' AND r.claim_started_at IS NOT NULL
                    THEN GREATEST(0,(extract(epoch FROM(
                        GREATEST(r.claim_started_at,COALESCE(r.last_progress_at,r.claim_started_at))
                        -r.claim_started_at))*1000)::bigint)
                    ELSE 0 END,
                    status='RUNNING',lease_owner=?,lease_expires_at=?,claim_version=r.version+1,claim_started_at=now(),
                    last_progress_at=NULL,
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

    public void recordBudgetPartialAnswer(AgentRunView run, String content) {
        recorder.recordBudgetPartialAnswer(run, content);
    }

    /** 预算部分回答 + 同事务消费"上一 claim 已持久化、尚未消费"的模型文本轮次。 */
    @Transactional
    public void recordBudgetPartialAnswer(AgentRunView run, String content, boolean consumePersistedTurn) {
        recorder.recordBudgetPartialAnswer(run, content, consumePersistedTurn);
    }

    public void recordBudgetExceeded(AgentRunView run, com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult completion) {
        recorder.recordBudgetExceeded(run, completion);
    }

    /**
     * 会话摘要 CAS 提交：仅当 stateRevision 与 goalRevision 与生成时一致、且声明的
     * sourceThrough 消息真实属于本会话时才落库；提交原子递增 stateRevision（同一状态上
     * 的两个摘要提交只有第一个能成功），用 jsonb_set 只写 summary 节点，不整块覆盖。
     */
    @Transactional
    public boolean commitConversationSummary(UUID projectId, UUID sessionId,
            int expectedStateRevision, int expectedGoalRevision, JsonNode summary) {
        return jdbc.update("""
                UPDATE agent_session
                SET working_state=jsonb_set(
                      jsonb_set(working_state,'{summary}',?::jsonb,true),
                      '{stateRevision}',
                      to_jsonb((working_state->>'stateRevision')::int + 1)),
                    updated_at=now()
                WHERE project_id=? AND id=?
                  AND (working_state->>'stateRevision')=?::text
                  AND (working_state->>'goalRevision')=?::text
                  AND EXISTS (SELECT 1 FROM agent_message m
                              WHERE m.id=(?::jsonb->>'sourceThrough')::uuid
                                AND m.session_id=agent_session.id)
                """, summary.toString(), projectId, sessionId,
                String.valueOf(expectedStateRevision), String.valueOf(expectedGoalRevision),
                summary.toString()) == 1;
    }

    /** 本次运行已持久化的摘要尝试次数（用于"每运行至多一次"约束）。 */
    public int countSummaryAttempts(UUID projectId, UUID runId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_step
                WHERE run_id=? AND type='MODEL_REQUEST' AND reason='CONTEXT_SUMMARY'
                """, Integer.class, runId);
        return count == null ? 0 : count;
    }

    /** 摘要尝试开始（持久化标记）与完成记账，见 AgentRunEventRecorder。 */
    public UUID beginSummaryAttempt(AgentRunView run) {
        return recorder.beginSummaryAttempt(run);
    }

    /** 摘要重压缩调用的独立持久化标记（每次实际模型请求一个身份）。 */
    public UUID beginSummaryRecompressAttempt(AgentRunView run) {
        return recorder.beginSummaryRecompressAttempt(run);
    }

    public void completeSummaryAttempt(UUID attemptId, String outcome, String model,
            AgentRunEventRecorder.UsageSettlement usage, String note) {
        recorder.completeSummaryAttempt(attemptId, outcome, model, usage, note);
    }

    public void completeSummaryRecompressAttempt(UUID attemptId, String outcome, String model,
            AgentRunEventRecorder.UsageSettlement usage, String note) {
        recorder.completeSummaryRecompressAttempt(attemptId, outcome, model, usage, note);
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
     * 受理一次只读文档研究委派（V62）：创建 depth=1 子运行、落 DELEGATION_REQUESTED step、
     * 委派工具结果与父运行转回 QUEUED 在同一事务内完成。
     * 幂等键是本次工具调用的 invocationId；同一调用重复执行返回既有子运行，不重复落库。
     */
    @Transactional
    public JsonNode documentResearchDelegationResult(
            AgentRunView run, String invocationId, String objective) {
        return recorder.documentResearchDelegationResult(run, invocationId, objective);
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

    /** 记录模型轮次并持久化本轮可信执行模式，供恢复待处理调用时校验写工具权限。 */
    @Transactional
    public AgentRunView recordModelTurn(AgentRunView run, com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult turn, String sourceMode) {
        return recorder.recordModelTurn(run, turn, sourceMode);
    }

    /** 每次实际出站模型请求发出前落库的持久化调用身份（正常记账与取消补记共用）。 */
    @Transactional
    public String beginModelCall(UUID projectId, UUID runId, String kind) {
        return recorder.beginModelCall(projectId, runId, kind);
    }

    /** 模型调用已发生但状态机已离开 RUNNING（取消/并发推进/校验失败）时，按调用身份幂等结算用量。 */
    @Transactional
    public boolean settleOrphanUsage(UUID projectId, UUID runId, String callId, String kind,
            AgentRunEventRecorder.UsageSettlement usage) {
        return recorder.settleOrphanUsage(projectId, runId, callId, kind, usage);
    }

    /** 正常记账（原子）：身份行首次结算与 recordModelTurn 的运行累计/步骤写入同一事务。 */
    @Transactional
    public AgentRunView recordModelTurnWithSettlement(AgentRunView run,
            com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult turn, String callId, String kind,
            AgentRunEventRecorder.UsageSettlement settlement) {
        return recorder.recordModelTurnWithSettlement(run, turn, callId, kind, settlement);
    }

    /** 带本轮可信执行模式的原子记账；source_mode 随待处理调用持久化，供恢复校验。 */
    @Transactional
    public AgentRunView recordModelTurnWithSettlement(AgentRunView run,
            com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult turn, String callId, String kind,
            AgentRunEventRecorder.UsageSettlement settlement, String sourceMode) {
        return recorder.recordModelTurnWithSettlement(run, turn, callId, kind, settlement, sourceMode);
    }

    /**
     * 原子记账并持久化本轮请求实际采用的强制收尾意图（消息组装与 needsFinalRequest 调整后的值）。
     * 该元数据与响应、用量结算同一事务提交，供接管按原请求语义处理已保存结果。
     */
    @Transactional
    public AgentRunView recordModelTurnWithSettlement(AgentRunView run,
            com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult turn, String callId, String kind,
            AgentRunEventRecorder.UsageSettlement settlement, String sourceMode, Boolean finalizingIntent) {
        return recorder.recordModelTurnWithSettlement(run, turn, callId, kind, settlement, sourceMode, finalizingIntent);
    }

    /** 输出超限分支的原子记账：身份行结算与 recordBudgetExceeded 的运行累计同一事务。 */
    @Transactional
    public void recordBudgetExceededWithSettlement(AgentRunView run,
            com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult completion, String callId, String kind,
            AgentRunEventRecorder.UsageSettlement settlement) {
        recorder.recordBudgetExceededWithSettlement(run, completion, callId, kind, settlement);
    }

    /** 恢复接管时收口未结算身份行：显式未知终态（0/0 + UNKNOWN），不虚构消耗。 */
    @Transactional
    public int closeUnresolvedModelCalls(UUID runId) {
        return recorder.closeUnresolvedModelCalls(runId);
    }

    /**
     * 本运行内指定工具是否已有成功的持久化调用结果（用于核心动作是否已发生的判定，
     * 依据持久工具结果，不扫描最终回答）。
     */
    public boolean hasSuccessfulToolInvocation(UUID runId, java.util.Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) return false;
        String names = toolNames.stream()
                .map(name -> "'" + name.replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(","));
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM agent_tool_invocation WHERE run_id=? AND status='SUCCEEDED' AND tool_name IN (" + names + ")",
                Integer.class, runId);
        return count != null && count > 0;
    }

    /**
     * 恢复批次中已有持久化结果（或已绑定提案）的调用数：按原 invocation 身份（最近一个
     * 模型轮次 + tool_call_id）识别。这些调用由执行器复用、不再执行，也不应再占工具
     * 总额度的新增份额——它们的结果落库时已经推进过 tool_calls_used。仅按 tool_call_id
     * 计数；调用名与参数的一致性仍由执行器的 {@code knownInvocationResult} 逐项校验。
     */
    public int countSettledInvocations(AgentRunView run,
            java.util.List<com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall> calls) {
        if (calls == null || calls.isEmpty()) return 0;
        String ids = calls.stream()
                .map(call -> "'" + call.id().replace("'", "''") + "'")
                .collect(java.util.stream.Collectors.joining(","));
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_tool_invocation
                WHERE run_id=? AND tool_call_id IN (%s)
                  AND turn_sequence=(SELECT max(sequence_no) FROM agent_step WHERE run_id=? AND type='MODEL_TURN')
                  AND (status<>'PENDING' OR proposal_id IS NOT NULL)
                """.formatted(ids), Integer.class, run.id(), run.id());
        return count == null ? 0 : count;
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

    /** 等待输入 + 同事务消费"上一 claim 已持久化、尚未消费"的模型文本轮次（恢复路径）。 */
    @Transactional
    public AgentRunView recordWaitingForInput(AgentRunView run, String question, boolean consumePersistedTurn) {
        return recorder.recordWaitingForInput(run, question, consumePersistedTurn);
    }

    /**
     * 记录最终答案；{@code consumePersistedTurn=true} 时在同一事务内消费
     * "上一 claim 已持久化、尚未消费"的模型文本轮次（恢复收尾路径）。
     */
    @Transactional
    public AgentRunView recordFinal(AgentRunView run, String content, List<AgentCitation> citations,
            boolean consumePersistedTurn) {
        return recorder.recordFinal(run, content, citations, consumePersistedTurn);
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

    /** 每次重读均验证项目、会话和现有成员资格。按旧到新推进，不依赖最近历史窗口。 */
    public List<AgentMessageView> listSummaryCandidates(AgentRunView run, java.util.Set<UUID> excluded, int limit) {
        requireSummaryAccess(run);
        String excludedJson = json.valueToTree(excluded).toString();
        return jdbc.query("""
                SELECT m.* FROM agent_message m JOIN agent_session s ON s.id=m.session_id
                WHERE s.project_id=? AND s.id=?
                  AND NOT (?::jsonb @> to_jsonb(m.id::text))
                  AND COALESCE((s.working_state->'summary'->>'completedBefore')::timestamptz,'-infinity') <= m.created_at
                  AND length(m.content)+length(regexp_replace(m.content,U&'[^\\+010000-\\+10FFFF]','','g')) > COALESCE((SELECT max((seg->>'to')::int)
                      FROM jsonb_array_elements(COALESCE(s.working_state->'summary'->'segments','[]')) seg
                      WHERE seg->>'messageId'=m.id::text),0)
                ORDER BY m.created_at,m.id LIMIT ?
                """, messageMapper(), run.projectId(), run.sessionId(), excludedJson, limit);
    }

    public void requireSummaryAccess(AgentRunView run) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM agent_session s JOIN project_member pm ON pm.project_id=s.project_id
                WHERE s.project_id=? AND s.id=? AND pm.user_id=?
                """, Integer.class, run.projectId(), run.sessionId(), run.requesterId());
        if (count == null || count != 1) throw new BusinessException(ErrorCode.AUTH_FORBIDDEN, "会话资料已不可访问");
    }

    /** 压缩已完成的连续历史前缀；未完成偏移永不受容量限制。删除的消息明确退出覆盖。 */
    public void compactSummaryCoverage(AgentRunView run, com.fasterxml.jackson.databind.node.ObjectNode summary) {
        requireSummaryAccess(run);
        var rows = jdbc.queryForList("""
                SELECT m.id::text AS id,m.created_at,length(m.content)+length(regexp_replace(m.content,U&'[^\\+010000-\\+10FFFF]','','g')) AS size
                FROM agent_message m JOIN agent_session s ON s.id=m.session_id
                WHERE s.project_id=? AND s.id=? ORDER BY m.created_at,m.id
                """, run.projectId(),run.sessionId());
        var offsets = new java.util.HashMap<String,Integer>();
        for (JsonNode seg : summary.path("segments")) offsets.put(seg.path("messageId").asText(),seg.path("to").asInt());
        java.time.Instant boundary = summary.hasNonNull("completedBefore")
                ? java.time.Instant.parse(summary.path("completedBefore").asText()) : java.time.Instant.MIN;
        var existing = new java.util.HashSet<String>();
        var incomplete = new java.util.HashSet<String>();
        boolean prefix = true;
        for (var row : rows) {
            String id = row.get("id").toString(); existing.add(id);
            var time = ((java.sql.Timestamp)row.get("created_at")).toInstant();
            if (time.isBefore(boundary)) continue;
            boolean done = offsets.getOrDefault(id,0) >= ((Number)row.get("size")).intValue();
            if (!done) { prefix = false; incomplete.add(id); }
            // 时间相等的消息一起保留，不能将未读消息跳过。
            if (prefix) boundary = time;
        }
        if (!boundary.equals(java.time.Instant.MIN)) summary.put("completedBefore",boundary.toString());
        var segments = summary.putArray("segments");
        for (var row : rows) {
            String id = row.get("id").toString();
            var time = ((java.sql.Timestamp)row.get("created_at")).toInstant();
            if (!time.isBefore(boundary) && offsets.containsKey(id))
                segments.addObject().put("messageId",id).put("from",0).put("to",offsets.get(id));
        }
        var terminated = summary.putArray("terminatedMessageIds");
        for (JsonNode id : summary.path("uncoveredMessageIds")) if (!existing.contains(id.asText())) terminated.add(id.asText());
        var pending = summary.putArray("uncoveredMessageIds");
        for (var row : rows) if (incomplete.contains(row.get("id").toString()) && offsets.containsKey(row.get("id").toString())) pending.add(row.get("id").toString());
        summary.put("uncoveredCount",pending.size());
        summary.put("coverage",incomplete.isEmpty() ? "FULL" : "PARTIAL");
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
                int found=jdbc.queryForObject("SELECT count(*) FROM (SELECT c.id FROM document_chunk c JOIN project_document d ON d.id=c.document_id WHERE c.id=? AND d.id=? AND d.project_id=? AND d.status='READY' UNION ALL SELECT c.id FROM document_body_chunk c JOIN project_document d ON d.id=c.document_id WHERE c.id=? AND d.id=? AND d.project_id=? AND d.status<>'DELETING') sources",Integer.class,
                        UUID.fromString(citation.path("chunkId").asText()),UUID.fromString(citation.path("documentId").asText()),projectId,
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
