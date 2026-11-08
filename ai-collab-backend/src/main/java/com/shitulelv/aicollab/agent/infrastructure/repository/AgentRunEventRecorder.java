package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.DelegatedResearchCoverage;
import com.shitulelv.aicollab.agent.domain.model.TokenEstimator;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.common.exception.AgentDelegationNotAdmittedException;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Agent 运行过程中的事件与状态持久化。
 *
 * <p>从 {@link AgentRepository} 拆出：会话/运行的查询保留在 AgentRepository，
 * 所有"记录一次执行事件并推进运行状态"的写路径集中在这里，
 * 共享 appendStep、预算推进、父运行恢复等规则。</p>
 */
@Repository
public class AgentRunEventRecorder {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shitulelv.aicollab.agent.infrastructure.diagnostics.AgentRuntimeMetrics metrics;
    /**
     * 推进步记账（V63 预算语义兼容标记）：COMBINED（旧语义，既有行默认）下工具结果
     * 逐项占用推进步；SEPARATED（新语义，新根运行默认）下一次模型轮计一次推进、
     * 最终回答落库保留一次收口，工具结果只计 tool_calls_used——持久化步骤/事件/invocation
     * 不变，只改计数语义。子运行继承父运行语义，旧运行恢复/暂停续跑保持 COMBINED。
     * 分支表达式内联在各 UPDATE 的 steps_used 赋值处。
     */

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    /** 委派防重台账（V81）：受理判重与子运行终态收口共用同一仓储。 */
    private final AgentDelegationLedgerRepository delegationLedger;
    /**
     * 委派受理与子终态回收（P1 从本类拆出，纯搬移 + 注入重组）：两条委派受理路径、
     * resumeParent、台账写入/收口与 V82 children_pending 等待语义集中在该协作类，
     * 共享的低层助手仍由本类以包内可见性提供，避免两份实现漂移。
     */
    private final AgentDelegationRecorder delegation;
    /** 委派唤醒事件发布器（V82 §3.4）：事务提交后发布 AgentDelegationWakeEvent。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher wakePublisher;
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private com.shitulelv.aicollab.agent.application.runtime.AgentEventService events;
    public boolean atomicEventsEnabled() { return events != null; }
    public void setEvents(com.shitulelv.aicollab.agent.application.runtime.AgentEventService events) { this.events = events; }
    /** 测试装配入口：注入事件发布器（生产由 Spring 完成字段的 @Autowired 注入）。 */
    public void setWakePublisher(org.springframework.context.ApplicationEventPublisher wakePublisher) { this.wakePublisher = wakePublisher; }
    public static boolean ownsEvent(com.shitulelv.aicollab.agent.domain.model.AgentEventType type) {
        return java.util.Set.of("MODEL_COMPLETED","TOOL_CALL_COMPLETED","TOOL_CALL_FAILED","RUN_SUCCEEDED","RUN_FAILED","RUN_CANCELED","RUN_BUDGET_EXCEEDED","WAITING_FOR_USER_INPUT","APPROVAL_REQUESTED","APPROVAL_UPDATED","RUN_PAUSE_REQUESTED","RUN_PAUSED","RUN_RESUMED").contains(type.name());
    }
    private void event(AgentRunView run, String type, JsonNode payload) {
        event(run.projectId(), run.id(), type, payload);
    }
    void event(java.util.UUID projectId, java.util.UUID runId, String type, JsonNode payload) {
        if (events != null) events.append(projectId, runId, com.shitulelv.aicollab.agent.domain.model.AgentEventType.valueOf(type), payload);
    }

    public AgentRunEventRecorder(JdbcTemplate jdbc, ObjectMapper json) {
        this(jdbc, json, new AgentDelegationLedgerRepository(jdbc));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AgentRunEventRecorder(JdbcTemplate jdbc, ObjectMapper json,
            AgentDelegationLedgerRepository delegationLedger) {
        this.jdbc = jdbc;
        this.json = json;
        this.delegationLedger = delegationLedger;
        this.delegation = new AgentDelegationRecorder(this, jdbc, json, delegationLedger);
    }

    /**
     * 子终态事务提交后发布委派唤醒事件（设计文档 §3.4）：事务活跃时注册 afterCommit
     * 同步（项目惯例，与 AgentEventService 同款），无事务时直接发布（测试路径）。
     * 发布器缺失（单测手工构造）时静默跳过——唤醒是优化非正确性依赖，tick 兜底。
     */
    void publishDelegationWake(java.util.UUID parentRunId) {
        if (wakePublisher == null) return;
        var wakeEvent = new com.shitulelv.aicollab.agent.application.runtime.AgentDelegationWakeEvent(parentRunId);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    wakePublisher.publishEvent(wakeEvent);
                }
            });
        } else {
            wakePublisher.publishEvent(wakeEvent);
        }
    }

    /**
     * 会话摘要尝试的持久化标记：每次尝试先落一行（reason=CONTEXT_SUMMARY），
     * 同一运行的尝试次数据此校验，服务重启不能绕过上限。
     *
     * <p>与 {@link #beginModelCall} 相同，本方法同时是摘要出站请求的<b>持久化准入边界</b>：
     * 暂停意图先落库或运行已离开 RUNNING（含过期 claim 被租约拦截）时，不创建请求身份、
     * 不发模型请求（抛 AGENT_RUN_PAUSED / AGENT_RUN_CANCELED，由调用方按控制结果处理）。</p>
     */
    @Transactional
    public UUID beginSummaryAttempt(AgentRunView run) {
        var beginInfo = json.createObjectNode()
                .put("purpose", "CONTEXT_SUMMARY")
                .put("status", "ATTEMPTED");
        return insertSummaryCallStep(run, "context_summary", "CONTEXT_SUMMARY", beginInfo);
    }

    /**
     * 摘要重压缩调用的持久化标记（reason=CONTEXT_SUMMARY_RECOMPRESS）：
     * 每次实际模型请求都有独立调用身份与结算状态；重试/恢复不得绕过
     * CONTEXT_SUMMARY 的每运行尝试上限，也不得重复结算。
     * 准入边界同 {@link #beginSummaryAttempt}：重压缩是新的出站请求，
     * 暂停意图先落库时同样不创建身份、不发出。
     */
    @Transactional
    public UUID beginSummaryRecompressAttempt(AgentRunView run) {
        var beginInfo = json.createObjectNode()
                .put("purpose", "CONTEXT_SUMMARY_RECOMPRESS")
                .put("status", "ATTEMPTED");
        return insertSummaryCallStep(run, "context_summary", "CONTEXT_SUMMARY_RECOMPRESS", beginInfo);
    }

    /**
     * 运行研究轨迹窗口压缩（RUN_CONTEXT scope）的持久化请求身份。
     *
     * <p>与主会话摘要（{@code CONTEXT_SUMMARY}）共用生成/校验/记账规则与同一准入边界，
     * 但作用域不同：本 scope 压缩的是<b>本运行自己的研究轨迹</b>（模型轮与工具结果），
     * 不读写 {@code agent_session.working_state.summary}，因此子运行只生成自己的
     * RUN_CONTEXT、不触发也不改写主会话摘要。</p>
     *
     * <p>身份先落库再出站，成功提交才推进覆盖；每个
     * {@code scope + 源边界 + 目标修订/摘要版本} 至多一个有效周期。</p>
     */
    @Transactional
    public UUID beginRunContextAttempt(AgentRunView run, int cycle, int fromSequence, int throughSequence, int goalRevision) {
        var beginInfo = json.createObjectNode()
                .put("purpose", "RUN_CONTEXT_SUMMARY")
                .put("status", "ATTEMPTED")
                .put("scope", "RUN_CONTEXT")
                .put("runId", run.id().toString())
                .put("cycle", cycle)
                .put("goalRevision", goalRevision)
                .put("sourceFromSequence", fromSequence)
                .put("sourceThroughSequence", throughSequence);
        return insertSummaryCallStep(run, "run_context_summary", "RUN_CONTEXT_SUMMARY", beginInfo);
    }

    /** 兼容入口：未显式提供目标修订时按 0 记录（发布时跳过目标修订冲突检查）。 */
    @Transactional
    public UUID beginRunContextAttempt(AgentRunView run, int cycle, int fromSequence, int throughSequence) {
        return beginRunContextAttempt(run, cycle, fromSequence, throughSequence, 0);
    }

    /** 兼容入口：不做目标修订冲突检查的发布（仍受 claim epoch 与取消 fencing 约束）。 */
    @Transactional
    public boolean completeRunContextAttempt(UUID attemptId, String outcome, String model,
            UsageSettlement usage, String note, JsonNode committedSummary) {
        return completeRunContextAttempt(attemptId, outcome, model, usage, note, committedSummary, null);
    }

    /**
     * 完成一次 RUN_CONTEXT 压缩尝试（C4）：实际用量结算与有效摘要发布<b>分开判定、
     * 同一短事务内原子完成</b>。
     *
     * <p>发布前在同一事务内核对 fencing 事实（D2 并发安全）：</p>
     * <ul>
     *   <li><b>claim epoch 与租约有效期</b>：当前线程持有该运行租约时经
     *       {@link AgentLeaseScope} 校验，失去租约或租约已过期的旧 worker 不得发布有效状态
     *       （仅比对 epoch 不够——租约过期未换 epoch 时旧 worker 同样没有发布权）；</li>
     *   <li><b>取消</b>：cancel_requested_at 已落库的返回不得成为当前有效摘要；</li>
     *   <li><b>状态</b>：已离开 RUNNING 的运行不再发布；</li>
     *   <li><b>目标修订</b>：expectedGoalRevision 与会话工作状态当前 goalRevision 冲突
     *       （目标更正发生在生成期间）时不得发布——目标修订使用真实业务修订事实，
     *       不把任意 run.version 当成目标修订。</li>
     * </ul>
     *
     * <p><b>并发安全（D2 + E1）</b>：普通 SELECT 在 READ COMMITTED 下不能阻止另一事务在
     * 检查与发布之间提交取消/目标修订/接管。发布路径改为短事务行锁 + 原子条件方案：</p>
     * <ul>
     *   <li>检查 SELECT 对 {@code agent_run} 行加 {@code FOR UPDATE}（与 requestPause /
     *       cancel / claim 接管的行锁串行化，锁序均为"先运行行"）；</li>
     *   <li><b>E1</b>：目标修订的真实存储行是 {@code agent_session}，其写入路径
     *       （{@code AgentWorkingState.appendUser} 的 {@code SELECT ... FOR UPDATE}）
     *       只锁会话行、不碰运行行，因此仅锁运行行<b>不足以</b>与目标修订串行化。
     *       这里在运行行之后显式对会话行加 {@code FOR UPDATE}（锁序固定为
     *       "运行行 → 会话行"，与 {@code AgentPlanningOperationService} 的
     *       {@code FOR UPDATE OF r} + 会话读取同序，不新增反向锁序），并在<b>取得该保护之后</b>
     *       重新读取真实 {@code goalRevision} 作为判定依据——READ COMMITTED 下加锁读
     *       看到的必然是已提交的最新修订，语句级旧快照不再能骗过校验；</li>
     *   <li>发布 UPDATE 以 <b>条件谓词</b>（租约有效 + epoch + 未取消 + RUNNING +
     *       目标修订一致 + ATTEMPTED）重新约束——即使检查后事实变化，UPDATE 影响零行，
     *       返回退回 FENCED 结算，不推进有效覆盖。</li>
     * </ul>
     *
     * <p>短事务只含读锁与一次 UPDATE，<b>不跨模型 HTTP 持锁</b>（模型调用已在本方法之外完成）。</p>
     *
     * <p>被 fenced 的返回<b>不能</b>成为当前有效摘要，但已发生用量仍如实幂等结算。
     * 同一 attempt 重复完成既不重复结算，也不能改写已发布的摘要：发布与结算是
     * agent_step 行上同一次 ATTEMPTED → 终态转换，重复调用转换零行即幂等返回。
     * 已受理的暂停按契约允许摘要完成保存，不自动解除暂停。</p>
     *
     * @return 是否<b>真实发布</b>了有效摘要（只有真正推进覆盖的提交才为 true）：
     *         调用方据此判断"提交事实"，不能只因调用了本方法就声称成功
     */
    @Transactional
    public boolean completeRunContextAttempt(UUID attemptId, String outcome, String model,
            UsageSettlement usage, String note, JsonNode committedSummary, Integer expectedGoalRevision) {
        boolean wantPublish = committedSummary != null && "COMMITTED".equals(outcome);
        String effectiveOutcome = outcome;
        String effectiveNote = note;
        String fenceReason = null;
        if (wantPublish) {
            // 检查 SELECT 带 FOR UPDATE：对运行行加短事务行锁，与 requestPause/cancel/
            // claim 接管（同样先取运行行锁）串行化——取消/暂停意图要么先于本次检查提交
            // （本事务读得到，直接 FENCED），要么等待本事务提交后才发生（合法的先发布后取消）。
            var rows = jdbc.queryForList("""
                    SELECT r.id AS run_id, r.session_id, r.claim_version, r.status, r.cancel_requested_at,
                           r.lease_expires_at
                    FROM agent_step st
                    JOIN agent_run r ON r.id = st.run_id
                    WHERE st.id=? AND st.reason='RUN_CONTEXT_SUMMARY'
                    FOR UPDATE OF r
                    """, attemptId);
            if (rows.isEmpty()) {
                wantPublish = false;
                effectiveOutcome = "FAILED";
                effectiveNote = appendNote(note, "RUN_CONTEXT_STEP_MISSING");
            } else {
                var row = rows.getFirst();
                Integer epoch = AgentLeaseScope.currentEpoch();
                // 租约有效期参与 fencing（D2）：租约过期未换 epoch 的旧 worker 没有发布权。
                // 无 claim 上下文（epoch==null，如 HTTP 管理命令路径）沿用旧语义不按租约拦截。
                java.time.OffsetDateTime leaseExpiresAt = toOffsetDateTime(row.get("lease_expires_at"));
                boolean epochValid = epoch == null || epoch.equals(((Number) row.get("claim_version")).intValue());
                boolean leaseValid = epoch == null || (leaseExpiresAt != null && leaseExpiresAt.isAfter(OffsetDateTime.now()));
                if (!epochValid) {
                    fenceReason = "RUN_CONTEXT_LEASE_LOST";
                } else if (!leaseValid) {
                    fenceReason = "RUN_CONTEXT_LEASE_EXPIRED";
                }
                if (fenceReason == null && row.get("cancel_requested_at") != null) {
                    fenceReason = "RUN_CONTEXT_CANCELED";
                }
                if (fenceReason == null && !"RUNNING".equals(row.get("status"))) {
                    fenceReason = "RUN_CONTEXT_LEFT_RUNNING";
                }
                if (fenceReason == null && expectedGoalRevision != null) {
                    // E1：目标修订的真实存储行是 agent_session，其写入路径
                    // （AgentWorkingState.appendUser / createRun）只锁会话行、不碰运行行。
                    // 仅锁运行行时，并发目标写入不会被本事务阻挡，而发布语句的
                    // READ COMMITTED 语句快照也不会重新读取会话——旧目标摘要仍可能发布。
                    // 因此在运行行之后显式对<b>真实会话行</b>加 FOR UPDATE，把目标修订的
                    // 写入纳入同一短事务的串行化保护；锁序固定为"运行行 → 会话行"，
                    // 与 createRun（先插入运行行、再锁会话行）一致，不引入反向锁序。
                    var lockedSession = jdbc.queryForList("""
                            SELECT COALESCE(working_state->>'goalRevision','0') AS goal_revision
                            FROM agent_session WHERE id=?
                            FOR UPDATE
                            """, row.get("session_id"));
                    int goalRevisionNow;
                    if (lockedSession.isEmpty()) {
                        goalRevisionNow = -1; // 会话行不存在：无法核对修订，按冲突处理
                    } else {
                        try {
                            // 取得行保护之后读取：加锁读在 READ COMMITTED 下必然看到最新已提交修订，
                            // 不使用语句开始时的旧快照
                            goalRevisionNow = Integer.parseInt(
                                    String.valueOf(lockedSession.getFirst().get("goal_revision")));
                        } catch (NumberFormatException malformed) {
                            goalRevisionNow = -1;
                        }
                    }
                    if (goalRevisionNow != expectedGoalRevision) {
                        fenceReason = "RUN_CONTEXT_GOAL_REVISION_CONFLICT";
                    }
                }
                if (fenceReason != null) {
                    wantPublish = false;
                    effectiveOutcome = "FENCED";
                    effectiveNote = appendNote(note, fenceReason);
                }
            }
        }
        if (!wantPublish) {
            // 只结算，不发布：失去 claim / 租约过期 / 取消 / 目标修订冲突的返回不成为
            // 当前有效摘要，但已发生用量仍如实幂等结算（ATTEMPTED → 终态只允许一次转换）
            completeSummaryCallStep(attemptId, "RUN_CONTEXT_SUMMARY", effectiveOutcome, model, usage, effectiveNote);
            return false;
        }
        // 发布与结算同一行一次转换：output_json 一次性从 ATTEMPTED 覆写为
        // 终态记账 || 已提交摘要。重复完成转换零行——不重复结算、不改写已发布摘要。
        // WHERE 再次原子核对全部 fencing 事实（D2）：即使检查 SELECT 之后、本 UPDATE
        // 之前同事务内事实被并发修改（行锁已阻止这种情况），条件不满足也转换零行，
        // 调用方得到 FENCED 结算而不是把过期返回发布成有效摘要。
        String basis = usage.combinedBasis();
        var terminal = runContextTerminalOutput(model, usage, note);
        int published = jdbc.update("""
                UPDATE agent_step st SET output_json = (?::jsonb || ?::jsonb),
                  prompt_tokens=?, completion_tokens=?, token_usage_estimated=?, usage_basis=?, latency_ms=?
                FROM agent_run r
                LEFT JOIN agent_session s ON s.id = r.session_id
                WHERE st.id=? AND st.reason='RUN_CONTEXT_SUMMARY' AND st.output_json->>'status'='ATTEMPTED'
                  AND st.run_id = r.id
                  AND r.claim_version = COALESCE(?, r.claim_version)
                  AND r.lease_expires_at > now()
                  AND r.cancel_requested_at IS NULL
                  AND r.status = 'RUNNING'
                  AND (?::int IS NULL OR COALESCE(s.working_state->>'goalRevision','0')::int = ?::int)
                """, terminal.toString(), committedSummary.toString(),
                usage.bookedInput(), usage.bookedOutput(), usage.estimated(), basis,
                usage.latencyMs() == null ? null : usage.latencyMs().intValue(), attemptId,
                AgentLeaseScope.currentEpoch(), expectedGoalRevision, expectedGoalRevision);
        if (published == 0) {
            // 检查通过但原子条件不再满足（并发取消/接管/目标修订/重复完成）：
            // 不发布、不推进覆盖，用量照常结算
            completeSummaryCallStep(attemptId, "RUN_CONTEXT_SUMMARY", "FENCED", model, usage,
                    appendNote(note, fenceReason == null ? "RUN_CONTEXT_PUBLISH_CONDITION_LOST" : fenceReason));
            return false;
        }
        bookRunUsage("UPDATE agent_run SET\n" +
                "                input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),\n" +
                "                output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),\n" +
                "                input_tokens_actual=input_tokens_actual+?,\n" +
                "                output_tokens_actual=output_tokens_actual+?,\n" +
                "                token_usage_estimated=token_usage_estimated OR ?,\n" +
                "                updated_at=now()\n" +
                "              WHERE id=(SELECT run_id FROM agent_step WHERE id=?)",
                usage, attemptId);
        // 只有真正转换成功（推进覆盖）才报告提交事实：调用方不得只因调用了本方法就声称成功
        return true;
    }

    private String appendNote(String note, String reason) {
        return note == null || note.isBlank() ? reason : note + "," + reason;
    }

    /** 时间点列的宽泛转换：驱动可能返回 OffsetDateTime / Timestamp / LocalDateTime。 */
    private static java.time.OffsetDateTime toOffsetDateTime(Object value) {
        if (value == null) return null;
        if (value instanceof java.time.OffsetDateTime odt) return odt;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().atOffset(OffsetDateTime.now().getOffset());
        if (value instanceof java.time.LocalDateTime ldt) return ldt.atOffset(OffsetDateTime.now().getOffset());
        if (value instanceof java.time.Instant instant) return instant.atOffset(OffsetDateTime.now().getOffset());
        return null;
    }

    /** 发布路径的终态记账 JSON（status 固定 COMMITTED，与摘要内容合并后落库）。 */
    private ObjectNode runContextTerminalOutput(String model, UsageSettlement usage, String note) {
        String basis = usage.combinedBasis();
        var output = json.createObjectNode()
                .put("purpose", "RUN_CONTEXT_SUMMARY")
                .put("status", "COMMITTED")
                .put("model", model == null ? "unknown" : model)
                .put("usageBasis", basis)
                .put("inputTokensBasis", usage.inputBasis())
                .put("outputTokensBasis", usage.outputBasis());
        if (note != null) output.put("note", note);
        if (usage.inputTokens() != null) output.put("inputTokens", usage.inputTokens());
        if (usage.outputTokens() != null) output.put("outputTokens", usage.outputTokens());
        return output;
    }

    /**
     * 摘要出站请求的运行行锁准入：与 requestPause 的事务串行化确定提交顺序，
     * 并复用 {@link AgentLeaseScope} 拦截过期 claim。短事务，锁不跨网络等待——
     * 身份行落库提交后模型请求才发出。
     */
    private void admitSummaryCall(AgentRunView run) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        var admission = jdbc.queryForList(
                "SELECT status,pause_requested_at FROM agent_run WHERE id=? FOR UPDATE", run.id());
        if (admission.isEmpty()) throw new IllegalStateException("Agent 运行不存在: " + run.id());
        if (admission.getFirst().get("pause_requested_at") != null) {
            throw new BusinessException(ErrorCode.AGENT_RUN_PAUSED, "Agent 运行已请求暂停，不再启动新的摘要请求");
        }
        if (!"RUNNING".equals(admission.getFirst().get("status"))) {
            throw new BusinessException(ErrorCode.AGENT_RUN_CANCELED, "Agent 运行已离开运行状态");
        }
    }

    private UUID insertSummaryCallStep(AgentRunView run, String toolName, String reason, ObjectNode beginInfo) {
        admitSummaryCall(run);
        return jdbc.queryForObject("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,tool_name,input_json,output_json,reason)
                SELECT ?,coalesce(max(sequence_no),0)+1,'MODEL_REQUEST',?,
                  ?::jsonb,?::jsonb,?
                FROM agent_step WHERE run_id=?
                RETURNING id
                """, UUID.class, run.id(), toolName, beginInfo.toString(), beginInfo.toString(), reason, run.id());
    }

    /** 单次实际模型请求的结算值与来源：来源按原始 usage 判定并显式传递，不按数字非空倒推。 */
    public record UsageSettlement(Integer inputTokens, Integer outputTokens,
            String inputBasis, String outputBasis, Long latencyMs) {
        public static final String PROVIDER = "PROVIDER";
        public static final String ESTIMATED = "ESTIMATED";
        public static final String UNKNOWN = "UNKNOWN";

        /** 原始 usage 缺失的侧用实际请求/响应证据估算；连证据都没有时显式 UNKNOWN。 */
        public static UsageSettlement fromRaw(com.shitulelv.aicollab.infrastructure.ai.turn.ModelUsage usage,
                int fallbackInput, int fallbackOutput, Long latencyMs) {
            Integer in = usage == null ? null : usage.inputTokens();
            Integer out = usage == null ? null : usage.outputTokens();
            String inBasis = in != null ? PROVIDER : (fallbackInput > 0 ? ESTIMATED : UNKNOWN);
            String outBasis = out != null ? PROVIDER : (fallbackOutput > 0 ? ESTIMATED : UNKNOWN);
            return new UsageSettlement(
                    in != null ? in : Math.max(0, fallbackInput),
                    out != null ? out : Math.max(0, fallbackOutput),
                    inBasis, outBasis, latencyMs);
        }

        /** 无任何可结算证据：显式未知，不得把默认 0 解释成真实零费用。 */
        public static UsageSettlement unknown() {
            return new UsageSettlement(null, null, UNKNOWN, UNKNOWN, null);
        }

        /** 混合来源的统一表示：双方 PROVIDER 才是 PROVIDER，双方 UNKNOWN 才是 UNKNOWN，其余 ESTIMATED。 */
        public String combinedBasis() {
            if (PROVIDER.equals(inputBasis) && PROVIDER.equals(outputBasis)) return PROVIDER;
            if (UNKNOWN.equals(inputBasis) && UNKNOWN.equals(outputBasis)) return UNKNOWN;
            return ESTIMATED;
        }

        public boolean estimated() { return !PROVIDER.equals(combinedBasis()); }

        public int bookedInput() { return inputTokens == null ? 0 : inputTokens; }

        public int bookedOutput() { return outputTokens == null ? 0 : outputTokens; }
    }

    /**
     * 完成摘要尝试并单独记账：token 计入<b>所属运行</b>的总预算（同样受 max 封顶），
     * usage 来源显式传入（见 {@link UsageSettlement}），混合来源在 output_json 里
     * 分侧记录；outcome 区分 COMMITTED / CAS_CONFLICT / EMPTY / FAILED / CANCELED /
     * DOWNSGRADED_UNQUALIFIED，note 记录降级或重压缩结算说明。
     *
     * <p>结算规则：attemptId 是 agent_step 的 ID（不是运行 ID），运行 ID 从步骤行取回；
     * 仅当步骤仍处于 ATTEMPTED 时才转换终态并记账，重复完成不会重复扣费。</p>
     *
     * <p>outcome 另含 PAUSED：重压缩因暂停意图未获准入（控制结果，首次用量照常结算）。</p>
     */
    @Transactional
    public void completeSummaryAttempt(UUID attemptId, String outcome, String model,
            UsageSettlement usage, String note) {
        completeSummaryCallStep(attemptId, "CONTEXT_SUMMARY", outcome, model, usage, note);
    }

    /** 完成摘要重压缩调用的记账（outcome：SETTLED / FAILED）。 */
    @Transactional
    public void completeSummaryRecompressAttempt(UUID attemptId, String outcome, String model,
            UsageSettlement usage, String note) {
        completeSummaryCallStep(attemptId, "CONTEXT_SUMMARY_RECOMPRESS", outcome, model, usage, note);
    }

    private void completeSummaryCallStep(UUID attemptId, String purpose, String outcome, String model,
            UsageSettlement usage, String note) {
        String basis = usage.combinedBasis();
        var output = json.createObjectNode()
                .put("purpose", purpose)
                .put("status", outcome)
                .put("model", model == null ? "unknown" : model)
                .put("usageBasis", basis)
                .put("inputTokensBasis", usage.inputBasis())
                .put("outputTokensBasis", usage.outputBasis());
        if (note != null) output.put("note", note);
        if (usage.inputTokens() != null) output.put("inputTokens", usage.inputTokens());
        if (usage.outputTokens() != null) output.put("outputTokens", usage.outputTokens());
        // ATTEMPTED → 终态只允许转换一次：转换零行说明已结算，直接跳过防止重复扣费
        int transitioned = jdbc.update("""
                UPDATE agent_step SET output_json=?::jsonb,
                  prompt_tokens=?,completion_tokens=?,token_usage_estimated=?,usage_basis=?,
                  latency_ms=?
                WHERE id=? AND output_json->>'status'='ATTEMPTED'
                """, output.toString(), usage.bookedInput(), usage.bookedOutput(), usage.estimated(),
                basis, usage.latencyMs() == null ? null : usage.latencyMs().intValue(), attemptId);
        if (transitioned == 0) return;
        // used 保持预算语义（封顶），actual 如实累计：摘要消耗可以超出剩余预算，
        // 真实值不能被 max 遮蔽
        bookRunUsage("UPDATE agent_run SET\n" +
                "                input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),\n" +
                "                output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),\n" +
                "                input_tokens_actual=input_tokens_actual+?,\n" +
                "                output_tokens_actual=output_tokens_actual+?,\n" +
                "                token_usage_estimated=token_usage_estimated OR ?,\n" +
                "                updated_at=now()\n" +
                "              WHERE id=(SELECT run_id FROM agent_step WHERE id=?)",
                usage, attemptId);
    }

    private void bookRunUsage(String sql, UsageSettlement usage, UUID attemptId) {
        jdbc.update(sql, usage.bookedInput(), usage.bookedOutput(),
                usage.bookedInput(), usage.bookedOutput(), usage.estimated(), attemptId);
    }

    @Transactional
    public void recordBudgetExceeded(AgentRunView run) {
        recordBudgetExceeded(run, null, null, null);
    }

    @Transactional
    public void recordBudgetExceeded(AgentRunView run, String originalErrorCode, String reason, String fallback) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        appendErrorStep(run, "AGENT_BUDGET_EXCEEDED");
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='BUDGET_EXCEEDED',
                  error_code='AGENT_BUDGET_EXCEEDED',finished_at=now(),
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version()));
        delegation.resumeParent(run, "BUDGET_EXCEEDED", "AGENT_BUDGET_EXCEEDED");
        var payload = json.createObjectNode()
                .put("status", "BUDGET_EXCEEDED")
                .put("errorCode", "AGENT_BUDGET_EXCEEDED");
        if (originalErrorCode != null) {
            payload.put("originalErrorCode", originalErrorCode);
        }
        if (reason != null) {
            payload.put("reason", reason);
        }
        if (fallback != null) {
            payload.put("fallback", fallback);
        }
        event(run, "RUN_BUDGET_EXCEEDED", payload);
    }

    @Transactional
    public void recordBudgetPartialAnswer(AgentRunView run, String content) {
        recordBudgetPartialAnswer(run, content, false, null, null, null);
    }

    /**
     * 收口"上一 claim 已持久化、尚未消费"的模型文本轮次并落地预算部分回答：
     * 结果消费标记与终态写入在同一事务，任一步失败整体回滚——
     * 重复接管、重复推进与旧 worker 迟到提交都不会二次消费同一轮响应。
     */
    @Transactional
    public void recordBudgetPartialAnswer(AgentRunView run, String content, boolean consumePersistedTurn) {
        recordBudgetPartialAnswer(run, content, consumePersistedTurn, null, null, null);
    }

    @Transactional
    public void recordBudgetPartialAnswer(
            AgentRunView run, String content, boolean consumePersistedTurn,
            String originalErrorCode, String reason, String fallback) {
        recordBudgetExceeded(run, originalErrorCode, reason, fallback);
        if (consumePersistedTurn) markModelTurnConsumed(run.id());
        // 子研究运行不向共享会话写 ASSISTANT 消息（与 recordFinal 同一隔离边界）
        if (run.depth() == 0) {
            jdbc.update("""
                    INSERT INTO agent_message(session_id,run_id,role,content)
                    VALUES (?,?,'ASSISTANT',?)
                    """, run.sessionId(), run.id(), content);
        } else if (content != null && !content.isBlank()) {
            // R12b 子运行预算部分回答随回收进入父综合：recordBudgetExceeded 的 resumeParent
            // 先按错误码占位写入 DELEGATION_COMPLETED，这里把证据兜底产出的部分回答回填，
            // 否则子运行已取得的工具证据对父运行不可见（委派失败等于产出全丢，
            // 真实委派实验第三次运行复现）。仅回填错误码占位，不覆盖已有产出内容。
            jdbc.update("""
                    UPDATE agent_step SET output_json = jsonb_set(output_json, '{content}', ?::jsonb)
                    WHERE run_id=? AND type='DELEGATION_COMPLETED'
                      AND output_json->>'childRunId'=?
                      AND output_json->>'content'='AGENT_BUDGET_EXCEEDED'
                    """, jsonString(content), run.parentRunId(), run.id().toString());
        }
    }

    /**
     * 标记已持久化模型轮次的结果被消费（原输出上置 {@code batchHandled}）。
     * 由终态写入同事务调用：只有终态真正提交，该轮次才不再作为"待恢复结果"出现。
     * 工具批次的消费仍走 {@link #markBatchHandled}，两者共用同一标记。
     */
    private void markModelTurnConsumed(UUID runId) {
        jdbc.update("""
                UPDATE agent_step SET output_json=jsonb_set(output_json,'{batchHandled}','true'::jsonb)
                WHERE run_id=? AND type='MODEL_TURN' AND output_json IS NOT NULL
                """, runId);
    }

    /**
     * 输出超限分支：如实结算用量并按原语义进入 BUDGET_EXCEEDED，<b>同时保存已经返回的正文</b>。
     *
     * <p>F8 修复：原先只写 ERROR 与用量，丢弃 {@code completion.content()}，
     * 随后 {@code resumeParent} 只回传错误码 —— 已经发生的模型调用返回的研究文字
     * 对父运行不可见（子运行同样丢失这一轮研究文字）。现在把已返回的无工具正文
     * 有界持久化为<b>部分产出</b>：终态仍如实为 BUDGET_EXCEEDED（不用 SUCCEEDED 粉饰完整性），
     * 真实用量（含提供商上报）如实保留，且<b>不执行</b>该响应提出的任何新工具。</p>
     */
    @Transactional
    public void recordBudgetExceeded(AgentRunView run, ChatCompletionResult completion) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        // 已返回但未及正常收口的正文：有界保存为部分回答（截断只影响落库长度，
        // 不虚构内容、不改终态语义），供父运行回收与用户查看。
        String returned = completion.content() == null ? "" : completion.content().strip();
        boolean hasReturnedText = !returned.isBlank();
        if (hasReturnedText) {
            persistPartialAnswerText(run, truncate(returned, MAX_PARTIAL_ANSWER_CHARS));
        }
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,usage_basis,latency_ms,error_code)
                VALUES (?,?,'ERROR','Model response exceeded remaining budget',?,?,?,?,?,
                  'AGENT_BUDGET_EXCEEDED')
                """, run.id(), sequence, completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                usageBasis(completion.promptTokens(), completion.completionTokens()),
                boundedLatency(completion.latencyMs()));
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='BUDGET_EXCEEDED',
                  steps_used=LEAST(max_steps,steps_used+1),
                  input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),
                  output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=?,error_code='AGENT_BUDGET_EXCEEDED',
                  finished_at=now(),lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, completion.promptTokens()==null ? com.shitulelv.aicollab.agent.application.runtime.AgentModelAccounting.estimatedInput(1) : completion.promptTokens(),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens()==null ? com.shitulelv.aicollab.agent.application.runtime.AgentModelAccounting.estimatedInput(1) : completion.promptTokens(),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                run.projectId(), run.id(), run.version()));
        // 回收：已有部分正文时把正文一并移交父运行（而不是只回传 AGENT_BUDGET_EXCEEDED 占位符）
        delegation.resumeParent(run, "BUDGET_EXCEEDED", hasReturnedText ? truncate(returned, MAX_PARTIAL_ANSWER_CHARS) : "AGENT_BUDGET_EXCEEDED");
        event(run,"RUN_BUDGET_EXCEEDED",json.createObjectNode().put("status","BUDGET_EXCEEDED")
                .put("partialAnswerSaved", hasReturnedText));
    }

    /** 部分产出正文的落库长度上限（模型可见/用户可见，不影响真实 usage 结算）。 */
    private static final int MAX_PARTIAL_ANSWER_CHARS = 20000;

    /**
     * 持久化已返回的部分回答文本：父运行（depth=0）写 ASSISTANT 消息；子运行（depth&gt;0）
     * 只回填自身 DELEGATION_COMPLETED 的 content 占位，不向共享会话写消息（R1 隔离）。
     * 与 {@link #recordBudgetPartialAnswer} 共用同一落库边界，但不改状态（状态由调用方推进）。
     */
    private void persistPartialAnswerText(AgentRunView run, String content) {
        if (content == null || content.isBlank()) return;
        if (run.depth() == 0) {
            jdbc.update("""
                    INSERT INTO agent_message(session_id,run_id,role,content)
                    VALUES (?,?,'ASSISTANT',?)
                    """, run.sessionId(), run.id(), content);
        } else if (run.parentRunId() != null) {
            // 子运行：把正文回填到 resumeParent 已写的错误码占位上（尚未写时后续 UPDATE 自然 no-op）
            jdbc.update("""
                    UPDATE agent_step SET output_json = jsonb_set(output_json, '{content}', ?::jsonb)
                    WHERE run_id=? AND type='DELEGATION_COMPLETED'
                      AND output_json->>'childRunId'=?
                      AND output_json->>'content'='AGENT_BUDGET_EXCEEDED'
                    """, jsonString(content), run.parentRunId(), run.id().toString());
        }
    }

    @Transactional
    public void recordDecisionFailure(
            AgentRunView run, ChatCompletionResult completion, String errorCode, String reason) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,usage_basis,latency_ms,error_code)
                VALUES (?,?,'ERROR',?,?,?,?,?,?,?)
                """, run.id(), sequence, truncate(reason, 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                usageBasis(completion.promptTokens(), completion.completionTokens()),
                boundedLatency(completion.latencyMs()), errorCode);
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='FAILED',steps_used=LEAST(max_steps,steps_used+1),
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=?,error_code=?,finished_at=now(),
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                errorCode, run.projectId(), run.id(), run.version()));
        delegation.resumeParent(run, "FAILED", errorCode);
    }

    @Transactional
    public void recordFailure(AgentRunView run, String errorCode, boolean retryable) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        if (hasUnconfirmedInvocation(run.id())) {
            retryable = false;
            errorCode = "TOOL_OUTCOME_UNKNOWN";
        }
        accountActiveTime(run.id());
        appendErrorStep(run, errorCode);
        // retry_count 记录可重试模型失败次数（含最终失败），最多安排两次自动重试。
        // MODEL_RETRY 记录实际安排的重试次数；最终失败不再增加该诊断计数。
        boolean modelRetry=retryable && !"FORMAT_REPAIR_REQUESTED".equals(errorCode);
        // 可重试失败是否已到"不再安排自动重试"的边界：一是重试次数，二是本运行活跃时长。
        // 时长判据必须读取<b>本运行适用的同一有效策略</b>（v2 根 45 分钟 / 子 30 分钟，
        // v1 沿用原 Skill 额度），不能保留固定的 300000ms 隐藏截停——否则统一放宽到
        // 45 分钟后仍会在 5 分钟处被这一层判停（设计 3 节明确要求的冲突消除）。
        long activeBudgetMillis = com.shitulelv.aicollab.agent.domain.model.AgentRuntimeLimits
                .forRun(run.contextPolicyVersion(), run.depth(), run.skillCode())
                .maxRunDuration().toMillis();
        boolean activeTimeExhausted = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active_elapsed_ms>=? FROM agent_run WHERE id=?", Boolean.class,
                activeBudgetMillis, run.id()));
        boolean finalFailure = !retryable || (modelRetry && run.retryCount() >= 2)
                || activeTimeExhausted;
        if (finalFailure) markBatchHandled(run.id());
        if (!finalFailure && !"FORMAT_REPAIR_REQUESTED".equals(errorCode)) jdbc.update("INSERT INTO agent_recovery_counter(run_id,kind,attempts) VALUES (?,'MODEL_RETRY',1) ON CONFLICT(run_id,kind) DO UPDATE SET attempts=agent_recovery_counter.attempts+1",run.id());
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status=?,error_code=?,
                  retry_count=LEAST(retry_count+CASE WHEN ? THEN 1 ELSE 0 END, 10),
                  retry_after=CASE WHEN ? AND NOT ? THEN now()+interval '30 seconds' ELSE NULL END,
                  finished_at=CASE WHEN ? THEN now() ELSE NULL END,
                  lease_owner=NULL,lease_expires_at=NULL,updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, finalFailure ? "FAILED" : "FAILED_RETRYABLE", errorCode,
                modelRetry, retryable, finalFailure, finalFailure,
                run.projectId(), run.id(), run.version()));
        if (finalFailure) delegation.resumeParent(run, "FAILED", errorCode);
        event(run,"RUN_FAILED",json.createObjectNode().put("status",finalFailure ? "FAILED" : "FAILED_RETRYABLE").put("errorCode",errorCode).put("retryable",!finalFailure));
    }

    /**
     * 记录取消状态。状态为 CANCELED，error_code 为 RUN_CANCELLED。
     * 不复用 recordFailure 方法，确保状态一致。
     */
    @Transactional
    public void recordCanceled(AgentRunView run) {
        if (findRun(run.projectId(), run.id()).map(AgentRunView::status).orElse(null) == AgentRunStatus.CANCELED) return;
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), true);
        accountActiveTime(run.id());
        preserveUnconfirmedDispatches(run.id());
        invocationMetrics(jdbc.queryForList("UPDATE agent_tool_invocation SET status='CANCELED',result_json='{\"status\":\"CANCELED\"}'::jsonb WHERE run_id=? AND status='PENDING' RETURNING invocation_id,tool_call_id,execution_safety,status", run.id()));
        markBatchHandled(run.id());
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
        delegation.resumeParent(run, "CANCELED", "AGENT_RUN_CANCELED");
        event(run,"RUN_CANCELED",json.createObjectNode().put("status","CANCELED"));
    }

    // ========== 主动暂停与继续（控制边界） ==========
    //
    // 暂停意图 ≠ 已暂停：RUNNING 上的意图只写 pause_requested_at（不递增 version，
    // 不使在途响应的落库 CAS 失配），由 worker 在动作边界经 recordPaused 确认；
    // 排队/重试等待中的运行没有在途 worker，原子直接转 PAUSED。
    // 恢复只做 PAUSED → QUEUED，不清零额度、重试次数与已完成记录。

    /**
     * 用户发起暂停。状态决定行为：
     * QUEUED / FAILED_RETRYABLE 原子转 PAUSED（同事务记录 RUN_PAUSED）；
     * RUNNING 只落暂停意图（同事务记录 RUN_PAUSE_REQUESTED），已带意图时幂等返回；
     * PAUSED 幂等返回；其余状态（终态、等待外部输入）明确业务错误。
     */
    @Transactional
    public AgentRunView requestPause(UUID projectId, UUID runId) {
        var rows = jdbc.queryForList("""
                SELECT status, pause_requested_at IS NOT NULL AS requested
                FROM agent_run WHERE project_id=? AND id=? FOR UPDATE
                """, projectId, runId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND);
        AgentRunStatus status = AgentRunStatus.valueOf((String) rows.getFirst().get("status"));
        boolean requested = Boolean.TRUE.equals(rows.getFirst().get("requested"));
        switch (status) {
            case PAUSED -> { /* 幂等：已暂停，无重复事件 */ }
            case QUEUED, FAILED_RETRYABLE -> {
                requireRunUpdate(jdbc.update("""
                        UPDATE agent_run SET status='PAUSED',
                          pause_requested_at=COALESCE(pause_requested_at,now()),
                          updated_at=now(),version=version+1
                        WHERE project_id=? AND id=? AND status=?
                        """, projectId, runId, status.name()));
                event(projectId, runId, "RUN_PAUSED",
                        json.createObjectNode().put("status", AgentRunStatus.PAUSED.name()));
            }
            case RUNNING -> {
                if (!requested) {
                    requireRunUpdate(jdbc.update("""
                            UPDATE agent_run SET pause_requested_at=now()
                            WHERE project_id=? AND id=? AND status='RUNNING' AND pause_requested_at IS NULL
                            """, projectId, runId));
                    event(projectId, runId, "RUN_PAUSE_REQUESTED",
                            json.createObjectNode().put("status", AgentRunStatus.RUNNING.name()));
                }
            }
            default -> throw new BusinessException(ErrorCode.AGENT_RUN_NOT_PAUSABLE);
        }
        return findRun(projectId, runId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
    }

    /**
     * 用户发起继续。仅 PAUSED → QUEUED（同事务记录 RUN_RESUMED，清除暂停意图）；
     * 已恢复到 QUEUED/RUNNING 且没有新的暂停意图时幂等返回当前运行，不二次推进；
     * 暂停尚未完成（RUNNING 带意图）不允许抢先恢复；终态不复活。
     */
    @Transactional
    public AgentRunView requestResume(UUID projectId, UUID runId) {
        var rows = jdbc.queryForList("""
                SELECT status, pause_requested_at IS NOT NULL AS requested
                FROM agent_run WHERE project_id=? AND id=? FOR UPDATE
                """, projectId, runId);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND);
        AgentRunStatus status = AgentRunStatus.valueOf((String) rows.getFirst().get("status"));
        boolean requested = Boolean.TRUE.equals(rows.getFirst().get("requested"));
        if (status == AgentRunStatus.PAUSED) {
            requireRunUpdate(jdbc.update("""
                    UPDATE agent_run SET status='QUEUED',pause_requested_at=NULL,finished_at=NULL,
                      updated_at=now(),version=version+1
                    WHERE project_id=? AND id=? AND status='PAUSED'
                    """, projectId, runId));
            event(projectId, runId, "RUN_RESUMED",
                    json.createObjectNode().put("status", AgentRunStatus.QUEUED.name()));
            return findRun(projectId, runId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        }
        if ((status == AgentRunStatus.QUEUED || status == AgentRunStatus.RUNNING) && !requested) {
            return findRun(projectId, runId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        }
        throw new BusinessException(ErrorCode.AGENT_RUN_NOT_RESUMABLE);
    }

    /**
     * worker 在动作边界确认暂停：结清本 claim 的已确认执行段（R1 语义）并释放租约，
     * 使旧 claim 失效；<b>不</b>调用 markBatchHandled——未消费的模型轮次与 PENDING
     * 工具调用全部保留，恢复后按原身份继续。已完成/已暂停的运行不被改写（幂等）。
     */
    @Transactional
    public AgentRunView recordPaused(UUID projectId, UUID runId) {
        AgentLeaseScope.verify(jdbc, projectId, runId, false);
        accountActiveTime(runId);
        int updated = jdbc.update("""
                UPDATE agent_run SET status='PAUSED',
                  lease_owner=NULL,lease_expires_at=NULL,
                  pause_requested_at=COALESCE(pause_requested_at,now()),
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND status='RUNNING'
                """, projectId, runId);
        if (updated == 0) {
            AgentRunStatus current = findRun(projectId, runId)
                    .map(AgentRunView::status)
                    .orElseThrow(() -> new IllegalStateException("Agent 运行不存在"));
            if (current == AgentRunStatus.PAUSED || current.terminal()) {
                // 完成与暂停竞争时完成先落库：运行保持其真实终态，不改成 PAUSED
                return findRun(projectId, runId).orElseThrow();
            }
            throw new IllegalStateException("Agent 运行已进入不可暂停状态: " + current);
        }
        event(projectId, runId, "RUN_PAUSED",
                json.createObjectNode().put("status", AgentRunStatus.PAUSED.name()));
        return findRun(projectId, runId).orElseThrow();
    }

    @Transactional
    public void recordInvalidDecision(
            AgentRunView run, ChatCompletionResult completion, String reason) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,usage_basis,latency_ms,error_code)
                VALUES (?,?,'ERROR',?,?,?,?,?,?,'AGENT_INVALID_DECISION')
                """, run.id(), sequence, truncate(reason, 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                usageBasis(completion.promptTokens(), completion.completionTokens()),
                boundedLatency(completion.latencyMs()));
        boolean retry = !run.correctionAttempted();
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status=?,correction_attempted=true,
                  steps_used=steps_used+1,
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=?,
                  error_code='AGENT_INVALID_DECISION',
                  lease_owner=NULL,lease_expires_at=NULL,
                  finished_at=CASE WHEN ? THEN NULL ELSE now() END,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, retry ? "QUEUED" : "FAILED",
                tokens(completion.promptTokens(), completion.content()),
                tokens(completion.completionTokens(), completion.content()),
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
                  token_usage_estimated,usage_basis,latency_ms)
                VALUES (?,?,'FINAL_ANSWER',?::jsonb,?,?,?,?,?)
                """, run.id(), sequence, jsonString(answer),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                usageBasis(completion.promptTokens(), completion.completionTokens()),
                boundedLatency(completion.latencyMs()));
        jdbc.update("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'ASSISTANT',?,?::jsonb,?::jsonb)
                """, run.sessionId(), run.id(), answer.answer(),
                answer.citations().toString(), answer.inferences().toString());
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='SUCCEEDED',steps_used=steps_used+1,
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=?,model_provider=?,model_name=?,
                  finished_at=now(),lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                truncate(completion.provider(), 80), truncate(completion.model(), 120),
                run.projectId(), run.id(), run.version()));
        delegation.resumeParent(run, "SUCCEEDED", answer.answer());
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
                  prompt_tokens,completion_tokens,token_usage_estimated,usage_basis,latency_ms)
                VALUES (?,?,'TOOL_CALL_COMPLETED',?,?::jsonb,?::jsonb,?,?,?,?,?,?)
                """, run.id(), sequence, call.tool(), call.arguments().toString(),
                result.toString(), truncate(call.reason(), 2000),
                completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                usageBasis(completion.promptTokens(), completion.completionTokens()),
                boundedLatency(completion.latencyMs()));
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='QUEUED',tool_calls_used=tool_calls_used+1,
                  steps_used=steps_used+CASE WHEN budget_semantics='COMBINED' THEN 1 ELSE 0 END,
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=?,
                  model_provider=?,model_name=?,lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                tokens(completion.promptTokens(), ""),
                tokens(completion.completionTokens(), completion.content()),
                completion.promptTokens() == null || completion.completionTokens() == null,
                truncate(completion.provider(), 80), truncate(completion.model(), 120),
                run.projectId(), run.id(), run.version()));
    }

    /** 旧路径委派受理（V22 时代 DELEGATE 决策协议）：已拆出到 {@link AgentDelegationRecorder}。 */
    @Transactional
    public AgentRunView recordDelegation(
            AgentRunView run, ChatCompletionResult completion, AgentDecision.Delegate delegate) {
        return delegation.recordDelegation(run, completion, delegate);
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
                    run -> delegation.resumeParent(run, "CANCELED", "AGENT_RUN_CANCELED"));
        }
        return changed;
    }

    /**
     * 更新 Run 的计划。
     */
    @Transactional
    public void updatePlan(UUID projectId, UUID runId, int version, String planJson) {
        AgentLeaseScope.verify(jdbc, projectId, runId, false);
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
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        if (hasUnconfirmedInvocation(run.id())) {
            recordFailure(run, "TOOL_OUTCOME_UNKNOWN", false);
            return;
        }
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='QUEUED',
                  lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version()));
    }

    /**
     * 批次因工具额度无法受理（新增调用超出剩余额度）：整批跳过（PENDING 调用 SKIPPED、
     * 轮次标记已消费，恢复不重放），并按<b>请求的批次规模</b>消耗工具额度（LEAST 封顶）——
     * 该批次的调用请求就是本次运行的额度终点，防止"拒绝→重新排队→再拒绝"空转；
     * 运行重新排队，下一次准入由收敛策略决定无工具总结（有可信证据且收尾可负担）
     * 或预算终止。落一条 ERROR 步骤记录拒绝原因（不占推进步），诊断可见。
     */
    @Transactional
    public void consumeToolBatchQuotaAndRequeue(AgentRunView run, int requestedCalls) {
        jdbc.update("""
                UPDATE agent_run SET tool_calls_used=LEAST(max_tool_calls,tool_calls_used+?)
                WHERE project_id=? AND id=? AND status='RUNNING'
                """, requestedCalls, run.projectId(), run.id());
        appendErrorStep(run, "TOOL_BUDGET_BATCH_REJECTED");
        requeueRun(run);
    }

    /**
     * 记录模型轮次。同时更新 Run 的 token 预算计数和版本。
     */
    @Transactional
    public AgentRunView recordModelTurn(AgentRunView run, ModelTurnResult turn) {
        return recordModelTurn(run, turn, null);
    }

    /**
     * 记录模型轮次并持久化本轮的可信执行模式（NATIVE_TOOLS / LEGACY_READ_ONLY）。
     * source_mode 用于恢复待处理调用时按来源轮次校验写工具权限；历史数据可为空。
     */
    @Transactional
    public AgentRunView recordModelTurn(AgentRunView run, ModelTurnResult turn, String sourceMode) {
        return recordModelTurn(run, turn, sourceMode, null);
    }

    /**
     * 带<b>调用身份</b>的记录版本：modelCallId 随 MODEL_COMPLETED 事件透出，
     * 前端据此把流式正文预览与持久化的完整轮次关联；null 保留历史行为（事件无该字段）。
     */
    @Transactional
    public AgentRunView recordModelTurn(
            AgentRunView run, ModelTurnResult turn, String sourceMode, String modelCallId) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        markProgress(run.id());
        int inputTokens = estimateInputTokens(turn);
        int outputTokens = tokens(turn.usage() == null ? null : turn.usage().outputTokens(), jsonString(turn));
        boolean estimated = turn.usage() == null || turn.usage().inputTokens() == null || turn.usage().outputTokens() == null;

        // 先验证版本和状态，避免版本冲突时留下孤立 Step；
        // used 封顶保持预算语义，actual 如实累计——真实输入可以超出 max_input_tokens
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET
                  steps_used=steps_used+1,
                  input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),
                  output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=token_usage_estimated OR ?,
                  updated_at=now(), version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, inputTokens, outputTokens, inputTokens, outputTokens, estimated,
                run.projectId(), run.id(), run.version()));

        int sequence = nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,usage_basis,latency_ms,model_provider,model_name)
                VALUES (?,?,'MODEL_TURN',?,?,?,?,?,?,?,?)
                """, run.id(), sequence,
                truncate(turn.content(), 2000),
                inputTokens,
                turn.usage() != null ? turn.usage().outputTokens() : null,
                estimated,
                // 模型轮次必然有内容/工具调用证据：无上报时按证据估算，不标 UNKNOWN
                turn.usage() == null ? "ESTIMATED"
                        : usageBasis(turn.usage().inputTokens(), turn.usage().outputTokens()),
                boundedLatency(turn.latencyMs()),
                truncate(turn.provider(), 80),
                truncate(turn.model(), 120));
        jdbc.update("UPDATE agent_step SET output_json=?::jsonb WHERE run_id=? AND sequence_no=?", jsonString(turn), run.id(), sequence);
        for (int ordinal = 0; ordinal < turn.toolCalls().size(); ordinal++) {
            var call = turn.toolCalls().get(ordinal);
            UUID invocationId = UUID.nameUUIDFromBytes((run.id() + ":" + sequence + ":" + ordinal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("""
                    INSERT INTO agent_tool_invocation(run_id,turn_sequence,ordinal,invocation_id,tool_call_id,tool_name,arguments_json,status,source_mode)
                    VALUES (?,?,?,?,?,?,?::jsonb,'PENDING',?)
                    """, run.id(), sequence, ordinal, invocationId, call.id(), call.name(), call.arguments().toString(), sourceMode);
        }

        // 轮次正文随事件透出（与 reason 列同一 2000 码点上限），前端按序渲染过渡说明；
        // provider/model 为本轮实际响应的模型身份，事件重放与刷新恢复共用。
        // modelCallId 把预览与完整结果关联；历史事件与恢复路径缺该字段时按旧规则处理
        var completedPayload = json.createObjectNode().put("stepSequence",sequence).put("finishReason",turn.finishReason().name()).put("toolCallCount",turn.toolCalls().size())
                .put("content",truncate(turn.content(),2000))
                .put("provider",truncate(turn.provider(),80))
                .put("model",truncate(turn.model(),120));
        if (modelCallId != null) completedPayload.put("modelCallId", modelCallId);
        event(run,"MODEL_COMPLETED",completedPayload);
        // 返回更新后的 Run
        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 记录工具结果。同时更新 Run 的 tool_calls_used 和版本。
     */
    @Transactional
    public AgentRunView recordToolResult(AgentRunView run, String toolName,
                                  JsonNode arguments, JsonNode result, boolean isError) {
        return recordToolResult(run, toolName, arguments, result, isError, false);
    }

    /** Local exceptions are not remote outcomes; classify only after serializing with dispatch. */
    @Transactional
    public AgentRunView recordToolExecutionFailure(AgentRunView run, String toolName,
            JsonNode arguments, JsonNode failure) {
        if (!arguments.hasNonNull("toolCallId")) throw new IllegalStateException("Execution failure requires invocation identity");
        return recordToolResult(run, toolName, arguments, failure, true, true);
    }

    private AgentRunView recordToolResult(AgentRunView run, String toolName,
            JsonNode arguments, JsonNode result, boolean isError, boolean uncertainFailure) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        // Worker scopes already lock the run. Keep the same run -> invocation lock order
        // for callers without a scope too; dispatch uses this order in its own transaction.
        if (AgentLeaseScope.currentEpoch() == null && jdbc.queryForList(
                "SELECT id FROM agent_run WHERE project_id=? AND id=? FOR UPDATE", UUID.class, run.projectId(), run.id()).size() != 1)
            throw new IllegalStateException("Agent run does not exist");
        if (arguments.hasNonNull("toolCallId")) {
            var states = jdbc.queryForList("""
                    SELECT status,tool_name,arguments_json::text AS arguments_json,dispatch_started_at,execution_safety
                    FROM agent_tool_invocation WHERE run_id=? AND tool_call_id=?
                      AND turn_sequence=(SELECT max(sequence_no) FROM agent_step WHERE run_id=? AND type='MODEL_TURN')
                    FOR UPDATE
                    """, run.id(), arguments.path("toolCallId").asText(), run.id());
            if (states.size() != 1) throw new IllegalStateException("Invocation does not exist in current model turn");
            var state = states.getFirst();
            if (!"PENDING".equals(state.get("status")))
                throw new IllegalStateException("Settled invocation cannot be overwritten");
            if (!toolName.equals(state.get("tool_name"))
                    || !arguments.path("arguments").equals(parseJsonOrNull(state.get("arguments_json").toString())))
                throw new IllegalStateException("Invocation identity does not match result");
            if (uncertainFailure && state.get("dispatch_started_at") != null && !"SAFE_READ".equals(state.get("execution_safety")))
                result = json.createObjectNode().put("status", "UNKNOWN").put("retryable", false)
                        .put("error", "TOOL_OUTCOME_UNKNOWN")
                        .put("message", "工具可能已执行，请人工核对；系统不会自动重发");
        }
        markProgress(run.id());
        // 先验证版本和状态，避免版本冲突时留下孤立 Step。
        // 工具结果只计工具额度；推进步按预算语义分支（COMBINED 旧语义逐项占用，SEPARATED 不占）
        requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET
                  tool_calls_used=tool_calls_used+1,
                  steps_used=steps_used+CASE WHEN budget_semantics='COMBINED' THEN 1 ELSE 0 END,
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
        if (arguments.hasNonNull("toolCallId")) {
            var changedInvocations = jdbc.queryForList("""
                    UPDATE agent_tool_invocation SET status=?,result_json=?::jsonb,updated_at=now()
                    WHERE run_id=? AND tool_call_id=? AND tool_name=? AND arguments_json=?::jsonb
                      AND status='PENDING'
                      AND turn_sequence=(SELECT max(sequence_no) FROM agent_step WHERE run_id=? AND type='MODEL_TURN')
                    RETURNING invocation_id,tool_call_id,execution_safety,status
                    """, isError ? result.path("status").asText("FAILED") : "SUCCEEDED", result.toString(),
                    run.id(), arguments.path("toolCallId").asText(), toolName, arguments.path("arguments").toString(), run.id());
            requireRunUpdate(changedInvocations.size());
            invocationMetrics(changedInvocations);
        }
        event(run,isError ? "TOOL_CALL_FAILED" : "TOOL_CALL_COMPLETED",json.createObjectNode()
                .put("callId",arguments.path("toolCallId").asText()).put("toolName",toolName)
                .put("invocationId",jdbc.queryForList("SELECT invocation_id::text FROM agent_tool_invocation WHERE run_id=? AND tool_call_id=? AND turn_sequence=(SELECT max(sequence_no) FROM agent_step WHERE run_id=? AND type='MODEL_TURN')",String.class,run.id(),arguments.path("toolCallId").asText(),run.id()).stream().findFirst().orElse(""))
                .put("status",result.path("status").asText(isError ? "FAILED" : "SUCCEEDED"))
                .put("effect",result.path("effect").asText())
                .put("errorCode",result.path("error").isObject() ? result.path("error").path("code").asText() : result.path("error").asText())
                .put("stepSequence",sequence)
                .set("taskFacts", !isError && "list_tasks".equals(toolName)
                        ? result.path("data").path("taskFacts") : json.createArrayNode()));

        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 记录最终答案。使用 ObjectMapper 序列化 JSONB，确保中文、引号、换行等正确保存。
     * 先检查版本，再写入数据，避免版本冲突时留下孤立数据。
     */
    @Transactional
    public AgentRunView recordFinal(AgentRunView run, String content, List<AgentCitation> citations) {
        return recordFinal(run, content, citations, false);
    }

    /**
     * 收口"上一 claim 已持久化、尚未消费"的模型文本轮次并记录最终答案：
     * 结果消费标记与终态、步骤、消息、事件在同一事务内完成。重复接管不会二次消费或重复记账。
     */
    @Transactional
    public AgentRunView recordFinal(AgentRunView run, String content, List<AgentCitation> citations,
            boolean consumePersistedTurn) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        if (hasUnconfirmedInvocation(run.id()))
            throw new IllegalStateException("Unconfirmed tool outcome cannot become run success");
        accountActiveTime(run.id());
        markBatchHandled(run.id());
        if (consumePersistedTurn) markModelTurnConsumed(run.id());
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

        // 使用 ObjectMapper 序列化为 JSON 对象，确保 JSONB 正确。
        // citations 参数是协调器移交的结构化来源（已完成委派子运行的持久引用投影），
        // 与本运行自己的持久证据合并——两者都来自持久化工具结果，不接受模型编造的 ID。
        var evidence = collectEvidence(run.id(), run.projectId());
        if (citations != null && !citations.isEmpty()) {
            // A child may become stale after delegation completes. Revalidate against the parent's
            // persisted handoff and current document identity; never trust handed text. A child
            // failure does not invalidate sources from its earlier successful tool reads.
            var delegated = collectDelegatedEvidence(run.id(), run.projectId(), citations);
            for (AgentCitation handed : citations.stream().limit(50).toList()) {
                if (handed == null) continue;
                AgentCitation persisted = delegated.get(handed.chunkId());
                if (persisted != null && persisted.documentId().equals(handed.documentId())) {
                    evidence.putIfAbsent(persisted.chunkId(), persisted);
                }
            }
        }
        // Source cards describe material actually read this run, never model-invented identifiers.
        var availableCitations=evidence.values().stream().limit(50).toList();
        String outputJson;
        try {
            ObjectNode outputNode = json.createObjectNode();
            outputNode.put("answer", content);
            outputNode.set("citations", json.valueToTree(availableCitations));
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
        // 子研究运行不向共享会话写 ASSISTANT 消息（R1 隔离）：其产出由父运行读取
        // DELEGATION_COMPLETED 后综合落一份最终回答；中间结果只持久化在子运行自身。
        boolean childRun = run.depth() > 0;
        if (!childRun) {
            jdbc.update("""
                    INSERT INTO agent_message(
                      session_id,run_id,role,content,citations_json,inferences_json)
                    VALUES (?,?,'ASSISTANT',?,?::jsonb,'[]'::jsonb)
                    """, run.sessionId(), run.id(), content, json.valueToTree(availableCitations).toString());
        }
        // 委派子运行成功收口：唤醒等待中的父运行（非父运行 no-op）
        delegation.resumeParent(run, "SUCCEEDED", content);
        event(run,"RUN_SUCCEEDED",json.createObjectNode().put("status","SUCCEEDED"));

        return findRun(run.projectId(), run.id()).orElse(run);
    }

    /**
     * 记录等待用户输入状态。
     */
    @Transactional
    public AgentRunView recordWaitingForInput(AgentRunView run, String question) {
        return recordWaitingForInput(run, question, false);
    }

    /** 等待输入 + 原子消费已持久化文本轮次（恢复路径专用，见 {@link #recordFinal(AgentRunView, String, List, boolean)}）。 */
    @Transactional
    public AgentRunView recordWaitingForInput(AgentRunView run, String question, boolean consumePersistedTurn) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        accountActiveTime(run.id());
        if (consumePersistedTurn) markModelTurnConsumed(run.id());
        // 子研究运行不推进主会话工作状态（R9 隔离）：question() 只面向用户会话的父运行
        if (run.depth() == 0) AgentWorkingState.question(jdbc,json,run.sessionId(),question);
        markBatchHandled(run.id());
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
        // 子研究运行不提问、不进主会话（R1/R9 隔离）：等待输入语义只属于面向用户的父运行
        if (run.depth() == 0) {
            jdbc.update("""
                    INSERT INTO agent_message(
                      session_id,run_id,role,content,citations_json,inferences_json)
                    VALUES (?,?,'ASSISTANT',?,'[]'::jsonb,'[]'::jsonb)
                    """, run.sessionId(), run.id(), question);
        } else {
            jdbc.update("UPDATE agent_session SET working_state=jsonb_set(working_state,'{pendingQuestion}','null'::jsonb,true) WHERE project_id=? AND id=?", run.projectId(), run.sessionId());
        }
        event(run,"WAITING_FOR_USER_INPUT",json.createObjectNode().put("question",question));

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

        // 记录用户回复并取得真实消息 ID，同事务内关联到工作状态
        UUID messageId = jdbc.queryForObject("""
                INSERT INTO agent_message(
                  session_id,run_id,role,content,citations_json,inferences_json)
                VALUES (?,?,'USER',?,'[]'::jsonb,'[]'::jsonb)
                RETURNING id
                """, UUID.class, run.sessionId(), run.id(), userResponse);
        AgentWorkingState.appendUser(jdbc,json,run.sessionId(),userResponse,messageId);

        return findRun(run.projectId(), run.id()).orElse(run);
    }

    Optional<AgentRunView> findRun(UUID projectId, UUID runId) {
        return jdbc.query("SELECT * FROM agent_run WHERE project_id=? AND id=?",
                AgentRunMappers.runMapper(), projectId, runId).stream().findFirst();
    }

    void invocationMetrics(java.util.List<java.util.Map<String,Object>> rows) {
        if (metrics == null) return;
        for (var row : rows) metrics.invocation(
                java.util.Objects.toString(row.get("execution_safety"), "UNCONFIRMABLE_SIDE_EFFECT"),
                row.get("status").toString(),
                com.shitulelv.aicollab.agent.infrastructure.diagnostics.AgentDiagnosticScope.elapsed(row.get("tool_call_id").toString()));
    }

    void markBatchHandled(UUID runId) {
        preserveUnconfirmedDispatches(runId);
        jdbc.update("UPDATE agent_step SET output_json=jsonb_set(output_json,'{batchHandled}','true'::jsonb) WHERE run_id=? AND type='MODEL_TURN' AND output_json IS NOT NULL", runId);
        invocationMetrics(jdbc.queryForList("UPDATE agent_tool_invocation SET status='SKIPPED',result_json='{\"status\":\"SKIPPED\",\"error\":\"BATCH_ENDED\"}'::jsonb,updated_at=now() WHERE run_id=? AND status='PENDING' RETURNING invocation_id,tool_call_id,execution_safety,status", runId));
    }

    private boolean hasUnconfirmedInvocation(UUID runId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent_tool_invocation WHERE run_id=? AND
                  (status='UNKNOWN' OR (status='PENDING' AND dispatch_started_at IS NOT NULL AND execution_safety<>'SAFE_READ')))
                """, Boolean.class, runId));
    }

    /** Cancellation/batch termination is not evidence that a dispatched operation had no effect. */
    public void preserveUnconfirmedDispatches(UUID runId) {
        var rows = jdbc.queryForList("""
                UPDATE agent_tool_invocation SET status='UNKNOWN',
                  result_json='{"status":"UNKNOWN","retryable":false,"error":"TOOL_OUTCOME_UNKNOWN","message":"工具可能已执行，请人工核对；系统不会自动重发"}'::jsonb,
                  updated_at=now()
                WHERE run_id=? AND status='PENDING' AND dispatch_started_at IS NOT NULL AND execution_safety<>'SAFE_READ'
                RETURNING invocation_id,tool_call_id,tool_name,execution_safety,status
                """, runId);
        invocationMetrics(rows);
        for (var row : rows) {
            UUID project = jdbc.queryForObject("SELECT project_id FROM agent_run WHERE id=?", UUID.class, runId);
            event(project, runId, "TOOL_CALL_FAILED", json.createObjectNode()
                    .put("invocationId", row.get("invocation_id").toString())
                    .put("callId", row.get("tool_call_id").toString()).put("toolName", row.get("tool_name").toString())
                    .put("status", "UNKNOWN").put("retryable", false).put("errorCode", "TOOL_OUTCOME_UNKNOWN"));
        }
    }
    private void accountActiveTime(UUID runId) {
        jdbc.update("UPDATE agent_run SET active_elapsed_ms=active_elapsed_ms+CASE WHEN claim_started_at IS NULL THEN 0 ELSE GREATEST(0,(extract(epoch FROM(now()-claim_started_at))*1000)::bigint) END,claim_started_at=NULL,last_progress_at=NULL WHERE id=?",runId);
    }

    /**
     * 确认一次执行进度：本 claim 的已执行时长上界推进到当前时刻。
     * 只在 worker 真实推进运行状态的落库事务内调用（模型轮次、工具结果、委派）；
     * 供过期接管按"最后一次确认进度"结算上一个 claim 的执行时长，
     * 不把等待租约/离线的空闲时间计成执行时间。
     */
    private void markProgress(UUID runId) {
        jdbc.update("UPDATE agent_run SET last_progress_at=now() WHERE id=?", runId);
    }

    private void appendErrorStep(AgentRunView run, String errorCode) {
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,error_code)
                VALUES (?,?,'ERROR',?)
                """, run.id(), nextSequence(run.id()), errorCode);
    }

    int nextSequence(UUID runId) {
        Integer value = jdbc.queryForObject("""
                SELECT COALESCE(max(sequence_no),0)+1 FROM agent_step WHERE run_id=?
                """, Integer.class, runId);
        return value == null ? 1 : value;
    }

    String jsonString(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 结果无法序列化", exception);
        }
    }

    /**
     * token 结算 fallback：provider 上报优先；缺失侧改用 CJK 感知估算
     * （预算治理 v3，设计文档 §4.3，替换原 codePoint/3——对中文系统性低估约 2 倍）。
     * 只换估算公式，usageBasis 的 PROVIDER/ESTIMATED/UNKNOWN 分类与记账语义不变。
     */
    static int tokens(Integer reported, String text) {
        if (reported != null) return reported;
        return Math.max(1, TokenEstimator.estimate(text));
    }

    /** usage 来源：提供商上报 / 按证据估算 / 无任何证据（不得把未知冒充上报或精确值）。 */
    private static String usageBasis(Integer inputTokens, Integer outputTokens) {
        if (inputTokens != null && outputTokens != null) return "PROVIDER";
        if (inputTokens == null && outputTokens == null) return "UNKNOWN";
        return "ESTIMATED";
    }

    /**
     * 每次实际出站模型请求的持久化调用身份：请求发出前先落一行（usage_basis=UNKNOWN、
     * token 列为 NULL）。正常记账与取消补记共用同一身份一次性结算；
     * 相同内容再次真实请求提供商是新的出站请求，会得到新的身份、如实再次入账，
     * 不与同一次请求的重复结算共享去重身份。
     *
     * <p>本方法同时是模型请求的<b>持久化准入边界</b>：行锁与暂停意图/状态检查同事务，
     * 与 requestPause 的行锁串行化——暂停意图先落库则不再发起新请求（抛 AGENT_RUN_PAUSED，
     * 由协调器转入 PAUSED）；请求身份先落库则允许本次请求完成当前阶段。</p>
     */
    @Transactional
    public String beginModelCall(UUID projectId, UUID runId, String kind) {
        var admission = jdbc.queryForList(
                "SELECT status,pause_requested_at FROM agent_run WHERE id=? FOR UPDATE", runId);
        if (admission.isEmpty()) throw new IllegalStateException("Agent 运行不存在: " + runId);
        if (admission.getFirst().get("pause_requested_at") != null) {
            throw new BusinessException(ErrorCode.AGENT_RUN_PAUSED, "Agent 运行已请求暂停，不再启动新的模型请求");
        }
        if (!"RUNNING".equals(admission.getFirst().get("status"))) {
            throw new BusinessException(ErrorCode.AGENT_RUN_CANCELED, "Agent 运行已离开运行状态");
        }
        return jdbc.queryForObject("""
                INSERT INTO agent_usage_settlement(run_id,call_id,kind,usage_basis)
                VALUES (?,gen_random_uuid(),?,'UNKNOWN')
                RETURNING call_id
                """, String.class, runId, kind);
    }

    /** 未结算行判定：身份行（UNKNOWN + token 列为 NULL）。结算后 token 列非 NULL，不再匹配。 */
    private static final String UNSETTLED_PREDICATE =
            " AND usage_basis='UNKNOWN' AND input_tokens_actual IS NULL AND output_tokens_actual IS NULL";

    /**
     * 正常记账（原子）：身份行首次结算与运行用量累计、步骤写入在同一事务内完成。
     * 先以条件更新独占结算身份行（仅未结算行可占用，占用失败即拒绝累计），
     * 再执行 recordModelTurn 的运行累计与步骤写入；任一步失败则整体回滚——
     * 同一身份无论以什么顺序、被调用多少次，总额只增加一次。模型网络请求不在本事务内。
     */
    @Transactional
    public AgentRunView recordModelTurnWithSettlement(AgentRunView run, ModelTurnResult turn,
            String callId, String kind, UsageSettlement settlement) {
        return recordModelTurnWithSettlement(run, turn, callId, kind, settlement, null);
    }

    /** 带本轮可信执行模式的原子记账版本；source_mode 随工具调用清单持久化供恢复校验。 */
    @Transactional
    public AgentRunView recordModelTurnWithSettlement(AgentRunView run, ModelTurnResult turn,
            String callId, String kind, UsageSettlement settlement, String sourceMode) {
        return recordModelTurnWithSettlement(run, turn, callId, kind, settlement, sourceMode, null);
    }

    /**
     * 原子记账并持久化本轮请求<b>实际采用</b>的强制收尾意图：{@code finalizingIntent} 必须来自
     * 消息组装与 {@code needsFinalRequest} 调整之后、真正出站请求使用的值，写进该轮次的持久化
     * 响应（与响应、用量结算同一事务），接管时据此按原请求语义处理已保存结果；
     * {@code null} 保留历史行为（该轮次不携带意图元数据）。
     */
    @Transactional
    public AgentRunView recordModelTurnWithSettlement(AgentRunView run, ModelTurnResult turn,
            String callId, String kind, UsageSettlement settlement, String sourceMode,
            Boolean finalizingIntent) {
        claimIdentityOrThrow(run.id(), callId, kind, settlement);
        ModelTurnResult persisted = finalizingIntent == null ? turn : turn.withFinalizing(finalizingIntent);
        return recordModelTurn(run, persisted, sourceMode, callId);
    }

    /** 输出超限分支的原子版本：身份行结算与 recordBudgetExceeded 的运行累计同事务。 */
    @Transactional
    public void recordBudgetExceededWithSettlement(AgentRunView run, ChatCompletionResult completion,
            String callId, String kind, UsageSettlement settlement) {
        claimIdentityOrThrow(run.id(), callId, kind, settlement);
        recordBudgetExceeded(run, completion);
    }

    /**
     * 条件更新独占结算身份行：仅未结算行（UNKNOWN + token 列 NULL）可占用并写入结算值。
     */
    private int claimIdentity(UUID runId, String callId, String kind, UsageSettlement settlement) {
        return jdbc.update("""
                UPDATE agent_usage_settlement
                SET kind=?, input_tokens_actual=?, output_tokens_actual=?, usage_basis=?, latency_ms=?
                WHERE run_id=? AND call_id=?
                """ + UNSETTLED_PREDICATE,
                kind, settlement.bookedInput(), settlement.bookedOutput(), settlement.combinedBasis(),
                settlement.latencyMs() == null ? null : settlement.latencyMs().intValue(), runId, callId);
    }

    /** 占用身份行；占用失败（已结算或身份不存在）必须终止累计，由调用方在同一事务内回滚。 */
    private void claimIdentityOrThrow(UUID runId, String callId, String kind, UsageSettlement settlement) {
        if (claimIdentity(runId, callId, kind, settlement) == 1) return;
        boolean exists = jdbc.queryForObject(
                "SELECT count(*) FROM agent_usage_settlement WHERE run_id=? AND call_id=?",
                Integer.class, runId, callId) > 0;
        throw new IllegalStateException(exists
                ? "模型调用身份已结算，拒绝重复累计: " + callId
                : "模型调用身份不存在: " + callId);
    }

    /** 用量未确认判定：UNKNOWN 且两侧都无已确认数值（NULL 或 0）。被恢复收口或未知结算过的行
     *  仍可被迟到证据幂等补全；PROVIDER/ESTIMATED 属已确认结算，不再被覆盖。 */
    private static final String UNCONFIRMED_PREDICATE =
            " AND usage_basis='UNKNOWN' AND COALESCE(input_tokens_actual,0)=0 AND COALESCE(output_tokens_actual,0)=0";

    /** 恢复接管时收口未结算身份行：显式记为 0/0 + UNKNOWN（用量未确认），不虚构消耗；
     *  旧 worker 的迟到真实响应仍可按原身份补全（见 settleOrphanUsage），业务动作由租约拦截。 */
    @Transactional
    public int closeUnresolvedModelCalls(UUID runId) {
        return jdbc.update("""
                UPDATE agent_usage_settlement
                SET input_tokens_actual=0, output_tokens_actual=0, latency_ms=NULL
                WHERE run_id=?
                """ + UNSETTLED_PREDICATE, runId);
    }

    /**
     * 结算"状态机已离开 RUNNING"后仍须如实入账的模型用量：
     * 模型调用已发生但运行被取消/并发推进/业务状态校验失败时，used 封顶、actual 如实累计。
     * 结算基于 {@link #beginModelCall} 落库的调用身份，只有"用量未确认"的行会入账：
     * 重复回调、恢复与取消竞争只入账一次；恢复收口（0/0 UNKNOWN）或未知结算的行
     * 允许迟到证据幂等补全（此前入账为 0，补全后总额恰为真实值）；
     * PROVIDER/ESTIMATED 已确认结算的行拒绝覆盖。业务状态已终结不妨碍结算已发生费用。
     */
    @Transactional
    public boolean settleOrphanUsage(
            UUID projectId, UUID runId, String callId, String kind, UsageSettlement usage) {
        int settled = jdbc.update("""
                UPDATE agent_usage_settlement
                SET kind=?, input_tokens_actual=?, output_tokens_actual=?, usage_basis=?, latency_ms=?
                WHERE run_id=? AND call_id=?
                """ + UNCONFIRMED_PREDICATE,
                kind, usage.bookedInput(), usage.bookedOutput(), usage.combinedBasis(),
                usage.latencyMs() == null ? null : usage.latencyMs().intValue(), runId, callId);
        if (settled == 0) return false; // 已确认结算：不重复入账
        bookUsageToRun(projectId, runId, usage);
        return true;
    }

    private void bookUsageToRun(UUID projectId, UUID runId, UsageSettlement usage) {
        jdbc.update("""
                UPDATE agent_run SET
                  input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),
                  output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  token_usage_estimated=token_usage_estimated OR ?,
                  updated_at=now()
                WHERE project_id=? AND id=?
                """, usage.bookedInput(), usage.bookedOutput(),
                usage.bookedInput(), usage.bookedOutput(), usage.estimated(), projectId, runId);
    }

    static int boundedLatency(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    static String truncate(String value, int maximum) {
        if (value == null) return null;
        int count = value.codePointCount(0, value.length());
        return count <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }

    static void requireRunUpdate(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("Agent 运行已被其他 worker 修改");
        }
    }

    // 子运行终态回收（resumeParent）已拆出到 {@link AgentDelegationRecorder}：
    // 结构化回传、children_pending 计数与父事件收口集中在该协作类。

    /**
     * 子运行持久化的全部工具结果（成功与失败），按执行顺序返回给覆盖提取器：
     * tool_name / reason（TOOL_SUCCESS|TOOL_ERROR）/ input_json / output_json。
     * 只读取持久化事实，不调用工具、不让模型参与。
     */
    List<DelegatedResearchCoverage.PersistedToolResult> persistedToolResults(UUID runId) {
        return jdbc.query("""
                SELECT tool_name, reason, input_json::text AS input_json, output_json::text AS output_json
                FROM agent_step
                WHERE run_id=? AND type='TOOL_CALL_COMPLETED' AND tool_name IS NOT NULL
                ORDER BY sequence_no
                """, (rs, index) -> new DelegatedResearchCoverage.PersistedToolResult(
                        rs.getString("tool_name"),
                        rs.getString("reason"),
                        parseJsonOrNull(rs.getString("input_json")),
                        parseJsonOrNull(rs.getString("output_json"))),
                runId);
    }

    JsonNode parseJsonOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return json.readTree(value);
        } catch (JsonProcessingException malformed) {
            // 结构损坏的持久化结果不冒充覆盖事实，按缺失处理
            return null;
        }
    }

    /** 本运行成功工具结果中的有效来源身份投影（{@link #recordFinal} 的证据查询共用逻辑）。 */
    java.util.LinkedHashMap<UUID, AgentCitation> collectEvidence(UUID runId, UUID projectId) {
        var evidence = new java.util.LinkedHashMap<UUID, AgentCitation>();
        for (String value : jdbc.queryForList("""
                SELECT c.value::text FROM agent_step s
                CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(s.output_json->'citations')='array' THEN s.output_json->'citations' ELSE '[]'::jsonb END) c(value)
                WHERE s.run_id=? AND s.type='TOOL_CALL_COMPLETED' AND s.reason='TOOL_SUCCESS'
                  AND coalesce(s.output_json->>'status','SUCCEEDED')='SUCCEEDED'
                  AND EXISTS (
                    SELECT b.id FROM document_body_chunk b JOIN project_document d ON d.id=b.document_id
                    WHERE b.id::text=c.value->>'chunkId' AND d.id::text=c.value->>'documentId' AND d.project_id=? AND d.status<>'DELETING' AND NOT d.access_blocked
                    UNION ALL SELECT b.id FROM document_chunk b JOIN project_document d ON d.id=b.document_id
                    WHERE b.id::text=c.value->>'chunkId' AND d.id::text=c.value->>'documentId' AND d.project_id=? AND d.status='READY' AND NOT d.access_blocked)
                ORDER BY s.sequence_no
                """, String.class, runId, projectId, projectId)) {
            try { var citation = json.readValue(value, AgentCitation.class); evidence.putIfAbsent(citation.chunkId(), citation); }
            catch (JsonProcessingException malformed) { /* Only persisted, structured source identities are exposed. */ }
        }
        return evidence;
    }

    /** One bounded query, only this parent's persisted handoffs of successful tool reads; cards are available
     * sources, not proof that the final text explicitly cited or semantically used them. */
    private java.util.Map<UUID, AgentCitation> collectDelegatedEvidence(
            UUID runId, UUID projectId, List<AgentCitation> handed) {
        var evidence = new java.util.HashMap<UUID, AgentCitation>();
        String requestedIds = handed.stream().limit(50).filter(java.util.Objects::nonNull)
                .map(AgentCitation::chunkId).filter(java.util.Objects::nonNull).distinct()
                .map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}"));
        for (String value : jdbc.queryForList("""
                SELECT DISTINCT ON (c.value->>'chunkId') c.value::text
                FROM agent_step s
                CROSS JOIN LATERAL jsonb_array_elements(CASE WHEN jsonb_typeof(s.output_json->'citations')='array'
                    THEN s.output_json->'citations' ELSE '[]'::jsonb END) c(value)
                WHERE s.run_id=? AND s.type='DELEGATION_COMPLETED'
                  AND c.value->>'chunkId'=ANY(?::text[])
                  AND EXISTS (
                    SELECT b.id FROM document_body_chunk b JOIN project_document d ON d.id=b.document_id
                    WHERE b.id=ANY(?::uuid[]) AND b.id::text=c.value->>'chunkId' AND d.id::text=c.value->>'documentId'
                      AND d.project_id=? AND d.status<>'DELETING' AND NOT d.access_blocked
                    UNION ALL SELECT b.id FROM document_chunk b JOIN project_document d ON d.id=b.document_id
                    WHERE b.id=ANY(?::uuid[]) AND b.id::text=c.value->>'chunkId' AND d.id::text=c.value->>'documentId'
                      AND d.project_id=? AND d.status='READY' AND NOT d.access_blocked)
                ORDER BY c.value->>'chunkId', s.sequence_no
                LIMIT 50
                """, String.class, runId, requestedIds, requestedIds, projectId, requestedIds, projectId)) {
            try {
                var citation = json.readValue(value, AgentCitation.class);
                evidence.putIfAbsent(citation.chunkId(), citation);
            } catch (JsonProcessingException malformed) { /* Malformed identities cannot supply cards. */ }
        }
        return evidence;
    }

    private static int estimateInputTokens(ModelTurnResult turn) {
        if (turn.usage() != null && turn.usage().inputTokens() != null) {
            return turn.usage().inputTokens();
        }
        // CJK 感知 fallback 估算（预算治理 v3，设计文档 §4.3，替换原 chars/3）：
        // provider 上报优先的既有语义不变，本估算只承担缺失侧的 fallback。
        return com.shitulelv.aicollab.agent.application.runtime.AgentModelAccounting.estimatedInput(
                Math.max(1, TokenEstimator.estimate(turn.content())));
    }

    // ========== document_research 只读委派（V62） ==========

    /**
     * 受理一次只读文档研究委派（V82 起已拆出到 {@link AgentDelegationRecorder}，
     * 含并行 fan-out 的 children_pending 等待语义与结构化回传）；本方法保留为兼容入口。
     */
    @Transactional
    public JsonNode documentResearchDelegationResult(
            AgentRunView run, String invocationId, String objective) {
        return delegation.documentResearchDelegationResult(run, invocationId, objective);
    }

    /** 读取子运行的最终回答与状态（父运行综合时的证据来源；只读）。 */
    public java.util.Optional<AgentRunView> findRunById(UUID projectId, UUID runId) {
        return findRun(projectId, runId);
    }
}
