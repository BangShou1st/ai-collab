package com.shitulelv.aicollab.agent.infrastructure.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentCitation;
import com.shitulelv.aicollab.agent.domain.model.AgentDecision;
import com.shitulelv.aicollab.agent.domain.model.AgentDelegationAdmission;
import com.shitulelv.aicollab.agent.domain.model.AgentDelegationGuard;
import com.shitulelv.aicollab.agent.domain.model.AgentResourcePolicy;
import com.shitulelv.aicollab.agent.domain.model.DelegatedResearchCoverage;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResultSanitizer;
import com.shitulelv.aicollab.common.exception.AgentDelegationNotAdmittedException;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 委派受理与子运行终态回收的持久化（P1 编排核心）。
 *
 * <p>从 {@link AgentRunEventRecorder} 拆出（纯搬移 + 注入重组）：</p>
 * <ul>
 *   <li>两条委派受理路径：{@link #recordDelegation}（旧 DELEGATE 决策协议路径）、
 *       {@link #documentResearchDelegationResult}（document_research 只读委派路径）；</li>
 *   <li>{@code resumeParent} 子运行终态回收（原私有方法，现为本类入口，由宿主在各终态
 *       写路径调用）与 P0 委派台账的写入/收口调用；</li>
 *   <li>V82 并行 fan-out 语义：受理时 {@code children_pending+1}、父运行保持 RUNNING
 *       并立即释放租约；子终态时 {@code children_pending-1}（CAS 防负），归零时父运行
 *       重新排队并发布 {@code AgentDelegationWakeEvent} 事件唤醒（§3.4）。</li>
 * </ul>
 *
 * <p>低层共享助手（序列号、运行查找、证据投影、批次消费标记、事件与指标）仍留在宿主
 * {@code AgentRunEventRecorder} 上，经构造注入的宿主引用以包内可见性访问——
 * 保证拆分不产生第二份实现，避免两侧规则漂移。</p>
 *
 * <h2>部分失败与重复委派的交互（设计文档 §3.2 第 4 点的确认）</h2>
 * <p>fan-out 中部分子失败<b>不整体失败</b>：父 prompt 注入按"成功列表 + 失败列表（含
 * 失败原因）"分组，父可对失败项重新委派。但 P0 台账判重语义保持不变：<b>失败
 * （FAILED/BUDGET_EXCEEDED）记录是非 CANCELED，会拦截同归一化哈希的再次委派</b>——
 * 父要对失败目标重试，必须以实质不同的 objective 表述（归一化把大小写/标点/空白变体
 * 折叠为同一键，表面改写无法绕过）。设计文档 §3.2 第 4 点的 retry_of（同 hash 允许
 * 一次重试）语义<b>未实现</b>：放开它会改变 P0 既有回归
 * （{@code AgentDelegationLedgerPostgresTest} 的重复拒绝断言），本轮不做。</p>
 */
public class AgentDelegationRecorder {

    // ==== 结构化回传协议（设计文档 §3.3）的字段上限 ====
    /** summary 上限（字符）。 */
    static final int STRUCTURED_SUMMARY_CHARS = 2000;
    /** findings 条数上限。 */
    static final int STRUCTURED_FINDINGS_MAX = 10;
    /** 单条 finding 上限（字符）。 */
    static final int STRUCTURED_FINDING_CHARS = 500;
    /** openQuestions 条数上限。 */
    static final int STRUCTURED_OPEN_QUESTIONS_MAX = 5;
    /** evidenceDigest 条数上限。 */
    static final int STRUCTURED_EVIDENCE_MAX = 8;
    /** 单条 evidenceDigest 上限（字符）。 */
    static final int STRUCTURED_EVIDENCE_CHARS = 800;
    /** DELEGATION_COMPLETED 载荷总上限（字符，设计文档 §3.3：20000 放宽到 40000）。 */
    static final int STRUCTURED_PAYLOAD_MAX = 40000;

    private final AgentRunEventRecorder host;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    /** 委派防重台账（V81）：受理判重与子运行终态收口共用同一仓储。 */
    private final AgentDelegationLedgerRepository delegationLedger;
    /** 落库前清洗 evidenceDigest（防提示注入/敏感信息），与工具结果同一清洗边界。 */
    private final AgentToolResultSanitizer sanitizer;

    public AgentDelegationRecorder(AgentRunEventRecorder host, JdbcTemplate jdbc,
            ObjectMapper json, AgentDelegationLedgerRepository delegationLedger) {
        this.host = host;
        this.jdbc = jdbc;
        this.json = json;
        this.delegationLedger = delegationLedger;
        this.sanitizer = new AgentToolResultSanitizer(json);
    }

    // ========== 旧路径：DELEGATE 决策协议委派（V22 时代） ==========

    @Transactional
    public AgentRunView recordDelegation(
            AgentRunView run, ChatCompletionResult completion, AgentDecision.Delegate delegate) {
        if (run.depth() != 0 || run.childrenUsed() >= run.maxChildren()) {
            throw new IllegalStateException("Agent 子运行预算已耗尽");
        }
        admitInFlightLimit(run);
        // 委派防重台账（V81）：同一会话下同一 objective（归一化哈希）已存在非 CANCELED
        // 记录时拒绝受理——不创建子运行、不落委派步骤，异常由调用方按可预期拒绝处理。
        String objectiveHash = AgentDelegationGuard.objectiveHash(delegate.objective());
        if (AgentDelegationGuard.isDuplicateObjective(
                delegationLedger.countActiveByObjective(run.sessionId(), objectiveHash))) {
            throw new AgentDelegationNotAdmittedException(
                    AgentDelegationAdmission.RejectionReason.DUPLICATE_OBJECTIVE.code(),
                    AgentDelegationAdmission.RejectionReason.DUPLICATE_OBJECTIVE.message());
        }
        UUID childId = UUID.randomUUID();
        int sequence = host.nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(
                  run_id,sequence_no,type,input_json,reason,prompt_tokens,completion_tokens,
                  token_usage_estimated,latency_ms)
                VALUES (?,?,'DELEGATION_REQUESTED',
                  jsonb_build_object('role',?,'objective',?),?,?,?,?,?)
                """, run.id(), sequence, delegate.role(), delegate.objective(),
                delegate.objective(), completion.promptTokens(), completion.completionTokens(),
                completion.promptTokens() == null || completion.completionTokens() == null,
                AgentRunEventRecorder.boundedLatency(completion.latencyMs()));
        int promptTokens = AgentRunEventRecorder.tokens(completion.promptTokens(), "");
        int outputTokens = AgentRunEventRecorder.tokens(completion.completionTokens(), completion.content());
        // v1 兼容：从父剩余额度切出（旧 DELEGATE 决策协议路径）。
        // v2：子运行使用自身独立执行额度，累计输入/输出为 NULL（不限额），
        // 不出现"父剩余多少、子只能多少"的切分，也不因父累计 token 用得多而拒绝。
        boolean independentChildBudget = !run.enforcesCumulativeTokenLimits();
        int childSteps = independentChildBudget
                ? AgentResourcePolicy.V2_CHILD_MAX_STEPS
                : run.maxSteps() - run.stepsUsed() - 1;
        int childTools = independentChildBudget
                ? AgentResourcePolicy.V2_CHILD_MAX_TOOL_CALLS
                : run.maxToolCalls() - run.toolCallsUsed();
        Integer childInputs = independentChildBudget
                ? null : run.maxInputTokens() - run.inputTokensUsed() - promptTokens;
        Integer childOutputs = independentChildBudget
                ? null : run.maxOutputTokens() - run.outputTokensUsed() - outputTokens;
        if (childSteps < 1 || (childInputs != null && childInputs < 1) || (childOutputs != null && childOutputs < 1)) {
            throw new IllegalStateException("Agent 没有可分配给子运行的剩余预算");
        }
        AgentRunView child = jdbc.queryForObject("""
                INSERT INTO agent_run(
                  id,session_id,project_id,requester_id,parent_run_id,role,depth,goal,status,
                  max_steps,max_tool_calls,max_children,max_input_tokens,max_output_tokens,budget_semantics,context_policy_version)
                VALUES (?,?,?,?,?,?,1,?,'QUEUED',?,?,0,?,?,(SELECT budget_semantics FROM agent_run WHERE id=?),
                  (SELECT context_policy_version FROM agent_run WHERE id=?))
                RETURNING *
                """, AgentRunMappers.runMapper(), childId, run.sessionId(), run.projectId(), run.requesterId(),
                run.id(), delegate.role(), delegate.objective(),
                childSteps, childTools, childInputs, childOutputs, run.id(), run.id());
        // 委派防重台账（V81）：与子运行创建同一事务登记 RUNNING 记录，
        // 子运行终态收口时经 resumeParent 同步台账状态
        delegationLedger.insertRunning(run.sessionId(), run.id(), child.id(), objectiveHash);
        // M2 双轨制：委派子运行以 QUEUED 创建，事务提交后发入队通知（tick 兜底）
        host.notifyRunQueued(child.id(), "DELEGATION_CHILD_CREATED");
        // V82 并行 fan-out：受理即登记待决子计数（+1 与 children_used 同一事务），
        // 父运行转 CREATED（本路径的历史语义：等子终态经 resumeParent 重新排队）
        AgentRunEventRecorder.requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='CREATED',children_pending=children_pending+1,
                  children_used=children_used+1,
                  steps_used=steps_used+CASE WHEN budget_semantics='COMBINED' THEN 1 ELSE 0 END,
                  input_tokens_used=LEAST(max_input_tokens,input_tokens_used+?),
                  output_tokens_used=LEAST(max_output_tokens,output_tokens_used+?),
                  input_tokens_actual=input_tokens_actual+?,
                  output_tokens_actual=output_tokens_actual+?,
                  lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, promptTokens, outputTokens, promptTokens, outputTokens,
                run.projectId(), run.id(), run.version()));
        return child;
    }

    // ========== 新路径：document_research 只读委派（V62） ==========

    /**
     * 受理一次只读文档研究委派：创建 depth=1 的子运行、落 DELEGATION_REQUESTED step、
     * 委派工具结果落库——全部在同一事务内完成。
     * 与旧 {@link #recordDelegation} 的差异：本方法不绑定一次模型轮次 completion
     * （委派发生在工具执行阶段），预算按策略解析。
     *
     * <p>V82 并行 fan-out（设计文档 §3.1）：同一批次允许多个委派调用（由
     * {@code AgentToolCallExecutor} 逐个调用本方法受理），每个受理
     * {@code children_pending+1}、{@code children_used+1}；父运行<b>不再转 QUEUED
     * 挂起</b>，保持 RUNNING 但 {@code lease_expires_at=now()} 立即释放租约——
     * "等待子运行"由 children_pending &gt; 0 表达，claimNext 排除这类运行。</p>
     *
     * <p>幂等：同一 invocation（run + invocationId）重复执行返回既有结果，
     * 不再切预算、不再创建第二个子运行——崩溃恢复与重复接管安全。</p>
     *
     * <p>暂停/取消：父运行暂停意图先落库时拒绝受理（AGENT_RUN_PAUSED），由调用方按控制结果处理。</p>
     */
    @Transactional
    public JsonNode documentResearchDelegationResult(
            AgentRunView run, String invocationId, String objective) {
        AgentLeaseScope.verify(jdbc, run.projectId(), run.id(), false);
        if (run.depth() != 0) {
            throw new BusinessException(ErrorCode.AGENT_TOOL_NOT_ALLOWED, "子运行不能再委派（最多一层）");
        }
        // 幂等：同调用身份已有委派结果直接返回（恢复路径的重复执行）；
        // 或该运行已有同一 objective 的待决委派时返回既有子运行
        var existing = jdbc.query("""
                SELECT i.result_json::text FROM agent_tool_invocation i
                WHERE i.run_id=? AND i.invocation_id::text=? AND i.status='SUCCEEDED'
                """, (rs, row) -> rs.getString(1), run.id(), invocationId).stream().findFirst();
        if (existing.isEmpty()) {
            existing = jdbc.query("""
                    SELECT jsonb_build_object('status','DELEGATED','childRunId',c.id::text)::text
                    FROM agent_step s
                    JOIN agent_run c ON c.parent_run_id=s.run_id AND c.role='KNOWLEDGE_RESEARCHER'
                    WHERE s.run_id=? AND s.type='DELEGATION_REQUESTED'
                      AND s.input_json->>'invocationId'=?
                    """, (rs, row) -> rs.getString(1), run.id(), invocationId).stream().findFirst();
        }
        if (existing.isPresent()) {
            try { return json.readTree(existing.get()); }
            catch (JsonProcessingException failure) { throw new IllegalStateException(failure); }
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pause_requested_at IS NOT NULL FROM agent_run WHERE id=?", Boolean.class, run.id()))) {
            throw new BusinessException(ErrorCode.AGENT_RUN_PAUSED, "Agent 运行已请求暂停，不启动委派");
        }
        if (run.childrenUsed() >= run.maxChildren()) {
            throw new AgentDelegationNotAdmittedException(
                    AgentDelegationAdmission.RejectionReason.CHILDREN_EXHAUSTED.code(),
                    AgentDelegationAdmission.RejectionReason.CHILDREN_EXHAUSTED.message());
        }
        // V82 并行 fan-out：待决子计数达到 max_children 时同样拒绝（并行上限；
        // children_used 是累计上限，children_pending 是在途上限，两者独立校验）
        admitInFlightLimit(run);
        // 委派防重台账（V81）：同一会话下同一 objective（归一化哈希）已存在非 CANCELED
        // 记录时拒绝受理——"换个说法"的重复委派（大小写/标点/空白变体）同样命中。
        // 幂等重放已在上方按 invocation 身份提前返回，不会走到这里。
        // 注意与部分失败重试的交互：失败（非 CANCELED）的台账记录同样拦截同哈希再委派，
        // 失败重试需以实质不同的 objective 表述（见类注释）。
        String objectiveHash = AgentDelegationGuard.objectiveHash(objective);
        if (AgentDelegationGuard.isDuplicateObjective(
                delegationLedger.countActiveByObjective(run.sessionId(), objectiveHash))) {
            throw new AgentDelegationNotAdmittedException(
                    AgentDelegationAdmission.RejectionReason.DUPLICATE_OBJECTIVE.code(),
                    AgentDelegationAdmission.RejectionReason.DUPLICATE_OBJECTIVE.message());
        }

        // 子运行预算：v1 从父运行剩余额度切出（硬上限保证委派不放大总资源）；
        // v2 使用子运行自己的独立有限执行额度（不从父剩余切分，累计 token 不设上限），
        // 父仍须能发起委派并完成自身综合收尾——这一检查不能变成"父还剩几次工具，子只能读几页"。
        String parentSemantics = jdbc.queryForObject(
                "SELECT budget_semantics FROM agent_run WHERE id=?", String.class, run.id());
        boolean combined = "COMBINED".equals(parentSemantics);
        var facts = new com.shitulelv.aicollab.agent.domain.model.AgentDelegationAdmission.Facts(
                run.maxSteps(), run.stepsUsed(), run.maxToolCalls(), run.toolCallsUsed(),
                run.maxInputTokens(), run.inputTokensUsed(), run.maxOutputTokens(), run.outputTokensUsed(),
                run.childrenUsed(), run.maxChildren(), combined, run.contextPolicyVersion());
        // 剩余执行额度连"发起委派 + 父综合收尾"都容纳不了：明确拒绝受理
        // （可预期拒绝类型的异常，不先启动再注定失败），由调用方有边界地保留父运行、
        // 不把它判成失败。
        var rejection = com.shitulelv.aicollab.agent.domain.model.AgentDelegationAdmission.reject(facts);
        if (rejection != null) {
            throw new AgentDelegationNotAdmittedException(rejection.code(), rejection.message());
        }
        var childBudget = com.shitulelv.aicollab.agent.domain.model.AgentDelegationAdmission.split(facts);
        int childSteps = childBudget.steps();
        int childToolCalls = childBudget.toolCalls();
        Integer childInput = childBudget.inputTokens();
        Integer childOutput = childBudget.outputTokens();

        int sequence = host.nextSequence(run.id());
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,input_json,reason)
                VALUES (?,?,'DELEGATION_REQUESTED',?::jsonb,'DOCUMENT_RESEARCH_DELEGATED')
                """, run.id(), sequence,
                host.jsonString(Map.of("invocationId", invocationId, "role", "KNOWLEDGE_RESEARCHER",
                        "objective", AgentRunEventRecorder.truncate(objective, 3800))));

        UUID childId = UUID.randomUUID();
        // 子运行继承父运行的预算语义（V63）与资源策略版本（V64）：v2 父下子运行同样是
        // v2，累计输入/输出写 NULL（只统计、不限额），执行额度用自己的独立上界。
        AgentRunView child = jdbc.queryForObject("""
                INSERT INTO agent_run(
                  id,session_id,project_id,requester_id,parent_run_id,role,depth,goal,status,
                  max_steps,max_tool_calls,max_children,max_input_tokens,max_output_tokens,budget_semantics,context_policy_version)
                VALUES (?,?,?,?,?,?,1,?,'QUEUED',?,?,0,?,?,(SELECT budget_semantics FROM agent_run WHERE id=?),
                  (SELECT context_policy_version FROM agent_run WHERE id=?))
                RETURNING *
                """, AgentRunMappers.runMapper(), childId, run.sessionId(), run.projectId(), run.requesterId(),
                run.id(), "KNOWLEDGE_RESEARCHER", AgentRunEventRecorder.truncate(objective, 3800),
                childSteps, childToolCalls, childInput, childOutput, run.id(), run.id());
        // 委派防重台账（V81）：与子运行创建同一事务登记 RUNNING 记录，
        // 子运行终态收口时经 resumeParent 同步台账状态
        delegationLedger.insertRunning(run.sessionId(), run.id(), child.id(), objectiveHash);
        // M2 双轨制：委派子运行以 QUEUED 创建，事务提交后发入队通知（tick 兜底）
        host.notifyRunQueued(child.id(), "DELEGATION_CHILD_CREATED");

        ObjectNode result = json.createObjectNode();
        result.put("status", "DELEGATED");
        result.put("childRunId", child.id().toString());
        result.put("message", "研究任务已受理。子 Agent 完成后本运行自动继续，届时综合其发现回答用户；本轮不要重复委派。");

        // 父运行（V82）：登记 children_pending/children_used 与委派自身的工具消耗
        // （委派是一次真实的工具受理，与普通 recordToolResult 同一配额语义，不出现免费
        // 调用；推进步按预算语义分支），保持 RUNNING 但 lease_expires_at=now() 立即释放
        // 租约——等待子运行由 children_pending>0 表达，claimNext 不领取这类运行；
        // 不预扣子运行将要消耗的额度（子消耗在回收时并入）。
        // 委派工具结果在执行层身份行（invocation）上落 SUCCEEDED，恢复路径据此幂等复用。
        AgentRunEventRecorder.requireRunUpdate(jdbc.update("""
                UPDATE agent_run SET status='RUNNING',children_pending=children_pending+1,
                  children_used=children_used+1,
                  tool_calls_used=LEAST(max_tool_calls,tool_calls_used+1),
                  steps_used=steps_used+CASE WHEN budget_semantics='COMBINED' THEN 1 ELSE 0 END,
                  lease_owner=NULL,lease_expires_at=now(),
                  updated_at=now(),version=version+1
                WHERE project_id=? AND id=? AND version=? AND status='RUNNING'
                """, run.projectId(), run.id(), run.version()));
        host.invocationMetrics(jdbc.queryForList("""
                UPDATE agent_tool_invocation SET status='SUCCEEDED',
                  result_json=?::jsonb,updated_at=now()
                WHERE run_id=? AND invocation_id=? AND status='PENDING'
                RETURNING invocation_id,tool_call_id,execution_safety,status
                """, host.jsonString(result), run.id(), UUID.fromString(invocationId)));
        // 委派批次全部完成：标记本运行最近模型轮次已消费，恢复路径不再重放委派调用
        host.markBatchHandled(run.id());
        return result;
    }

    /**
     * 在途委派上限校验（V82）：children_pending 已达 max_children 时拒绝新受理。
     * 受理事务内读取数据库实时值（不信任调用方的运行视图快照），
     * 与 childrenUsed 累计上限（调用方已校验）相互独立。
     */
    private void admitInFlightLimit(AgentRunView run) {
        Integer pending = jdbc.queryForObject(
                "SELECT children_pending FROM agent_run WHERE id=?", Integer.class, run.id());
        if (pending != null && pending >= run.maxChildren()) {
            throw new AgentDelegationNotAdmittedException(
                    AgentDelegationAdmission.RejectionReason.IN_FLIGHT_LIMIT.code(),
                    AgentDelegationAdmission.RejectionReason.IN_FLIGHT_LIMIT.message());
        }
    }

    // ========== 子运行终态回收 ==========

    /**
     * 子运行终态回收：唤醒等待中的父运行，回收已发生的消耗并移交结构化结果。
     *
     * <p>消息隔离（R1）：子运行自身不向会话写 ASSISTANT 消息——它的产出只以
     * DELEGATION_COMPLETED step（本方法）持久化，由父运行综合后以父身份落一份最终回答。</p>
     *
     * <p>用量回收（R5）：已发生消耗的回收独立于能否唤醒父运行——父处于 RUNNING/PAUSED
     * 等任何状态时用量照样累加（幂等键 DELEGATION_COMPLETED step 防重复回收）。</p>
     *
     * <p>来源身份（R7）：回收本运行成功工具结果中的有效 citations，随 DELEGATION_COMPLETED
     * 持久化，父运行综合时能把这些来源投影到自己的最终回答，不依赖模型重新编造引用 ID。</p>
     *
     * <p>覆盖事实：同时从子运行持久化工具结果推导最小结构化覆盖，由
     * {@link DelegatedResearchCoverage} 提取——不由模型填写、不追加统计用工具调用、
     * 不回传工具原文。</p>
     *
     * <p>V82 并行 fan-out 回收语义：每个子终态 {@code children_pending-1}（GREATEST
     * CAS 防负，幂等键保证每子至多回收一次）；<b>归零时</b>父运行重新排队
     * （CREATED/QUEUED 维持既有语义；V82 等待态父运行为 RUNNING 且租约已释放，
     * 一并转入 QUEUED；带暂停意图的转入 PAUSED 确认暂停；活跃租约中的父运行不打断），
     * 并在事务提交后发布 {@code AgentDelegationWakeEvent} 即时唤醒（§3.4，
     * 唤醒是优化非正确性依赖，调度 tick 兜底）。</p>
     */
    void resumeParent(AgentRunView child, String status, String content) {
        if (child.parentRunId() == null) return;
        // 幂等：同一子运行只回收一次。恢复/重复收口重放时直接返回，不重复写 step、不重复累计用量。
        boolean alreadyCollected = jdbc.queryForObject("""
                SELECT count(*) FROM agent_step
                WHERE run_id=? AND type='DELEGATION_COMPLETED' AND output_json->>'childRunId'=?
                """, Integer.class, child.parentRunId(), child.id().toString()) > 0;
        if (alreadyCollected) return;
        // 委派防重台账（V81）：子运行到达终态即收口台账，释放该 objective 的判重状态语义
        // （终态记录与 RUNNING 记录一样阻止同哈希重复委派，只有用户取消放行再次委派）。
        // 只更新仍为 RUNNING 的记录，重复收口 no-op。
        delegationLedger.closeForChild(child.id(), ledgerTerminalStatus(status));
        AgentRunView usage = host.findRun(child.projectId(), child.id()).orElse(child);
        // 子研究运行的可验证来源身份：本运行成功工具结果中真实存在的 chunk/document 投影
        java.util.LinkedHashMap<UUID, AgentCitation> childCitations =
                host.collectEvidence(child.id(), child.projectId());
        int sequence = host.nextSequence(child.parentRunId());
        var completed = buildCollectionPayload(child, status, content, childCitations, usage);
        jdbc.update("""
                INSERT INTO agent_step(run_id,sequence_no,type,output_json,reason)
                VALUES (?,?,'DELEGATION_COMPLETED',?::jsonb,?)
                """, child.parentRunId(), sequence, host.jsonString(completed),
                "Specialist child run completed");
        // 已发生消耗的回收独立于唤醒。v1 沿用"并入父运行共享额度"的既有语义；
        // v2 父子执行额度独立：子消耗只做<b>真实统计</b>（actual 与 used 如实累计一次），
        // <b>不扣减父自身的步骤/工具执行额度</b>——否则子研究会把父综合的额度吃掉
        // （设计要求：子消耗回收只用于真实统计，不扣减父自身步骤/工具额度）。
        AgentRunView parentView = host.findRun(child.projectId(), child.parentRunId()).orElse(null);
        boolean independentChildBudget = parentView != null && !parentView.enforcesCumulativeTokenLimits();
        if (independentChildBudget) {
            // v2：只累计 token 用量事实，不动父自身的 steps_used / tool_calls_used。
            // token 仍走 NULL 安全的封顶函数：v2 正常情况下 max_* 为 NULL（不封顶、只统计），
            // 但若该行仍带非 NULL 上限（兼容场景），必须继续保持 used <= max 的行约束，
            // 不能因放宽执行额度而写出违反 ck_agent_run_budgets 的行。
            jdbc.update("""
                    UPDATE agent_run SET
                      input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),
                      output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),
                      input_tokens_actual=input_tokens_actual+?,
                      output_tokens_actual=output_tokens_actual+?,
                      token_usage_estimated=token_usage_estimated OR ?,
                      updated_at=now()
                    WHERE id=?
                    """, usage.inputTokensUsed(), usage.outputTokensUsed(),
                    usage.inputTokensActual(), usage.outputTokensActual(),
                    usage.tokenUsageEstimated(), child.parentRunId());
        } else {
            jdbc.update("""
                    UPDATE agent_run SET
                      steps_used=LEAST(max_steps,steps_used+?),
                      tool_calls_used=LEAST(max_tool_calls,tool_calls_used+?),
                      input_tokens_used=agent_capped_add(input_tokens_used, max_input_tokens, ?),
                      output_tokens_used=agent_capped_add(output_tokens_used, max_output_tokens, ?),
                      input_tokens_actual=input_tokens_actual+?,
                      output_tokens_actual=output_tokens_actual+?,
                      token_usage_estimated=token_usage_estimated OR ?,
                      updated_at=now()
                    WHERE id=?
                    """, usage.stepsUsed(), usage.toolCallsUsed(), usage.inputTokensUsed(),
                    usage.outputTokensUsed(), usage.inputTokensActual(), usage.outputTokensActual(),
                    usage.tokenUsageEstimated(), child.parentRunId());
        }
        // V82：待决子计数 -1（CAS 防负）；归零时父运行重新排队（部分失败语义：
        // 只有最后一个终态的子运行触发恢复，此前父保持等待态）。
        String requeuedStatus = decrementChildrenPendingAndRequeue(child.parentRunId());
        // 父事件（P0 单点收口扩展）：成功子写 DELEGATION_COMPLETED、失败/取消/预算超限
        // 子写 DELEGATION_FAILED，载荷与 step 一致（含 childRunId/status/content）。
        emitParentEvent(child, status, completed);
        if (requeuedStatus != null) {
            // 事件唤醒（§3.4）：事务提交后发布，监听器立即提交一次 claim 尝试；
            // 发布失败/事件丢失无害——调度 tick 兜底。
            host.publishDelegationWake(child.parentRunId());
            // M2 双轨制：父运行转回 QUEUED 时补一条 MQ 入队通知（进程内事件只有本实例
            // 收到，多实例下靠 MQ 让任一空闲实例承接）；PAUSED 不发。
            if ("QUEUED".equals(requeuedStatus)) {
                host.notifyRunQueued(child.parentRunId(), "DELEGATION_PARENT_RESUMED");
            }
        }
    }

    /**
     * 待决子计数 -1 并在归零时重新排队父运行。
     *
     * <p>递减用 {@code GREATEST(children_pending-1, 0)} 防负（计数漂移时兜底为 0，
     * 不写出违反 ck_agent_run_budgets 的行）；幂等由 DELEGATION_COMPLETED step 的
     * 单次回收保证。重新排队条件：</p>
     * <ul>
     *   <li>仅当递减后 pending=0（最后一个子运行终态）——更早的终态不唤醒父运行；</li>
     *   <li>状态限定 CREATED/QUEUED/RUNNING：CREATED/QUEUED 是旧路径等待语义；
     *       V82 等待态父运行处于 RUNNING 且租约已释放；PAUSED 不被自动解除；</li>
     *   <li>带暂停意图的 RUNNING 父运行转 PAUSED（确认暂停，等价于 worker 在动作边界
     *       的确认路径——等待态父运行没有在途 worker，回收是它的收口点）；</li>
     *   <li>仍持有活跃租约的 RUNNING 父运行不打断（正常推进中，无需唤醒）。</li>
     * </ul>
     *
     * @return 重新排队后的父运行状态（"QUEUED"/"PAUSED"）；未发生重新排队时返回 null
     *         （决定是否发布事件唤醒与 MQ 入队通知）
     */
    private String decrementChildrenPendingAndRequeue(UUID parentRunId) {
        Integer pendingAfter = jdbc.queryForObject("""
                UPDATE agent_run SET children_pending=GREATEST(children_pending-1,0),
                  updated_at=now(),version=version+1
                WHERE id=?
                RETURNING children_pending
                """, Integer.class, parentRunId);
        if (pendingAfter == null || pendingAfter > 0) {
            return null;
        }
        return jdbc.query("""
                UPDATE agent_run SET
                  status=CASE WHEN pause_requested_at IS NOT NULL THEN 'PAUSED' ELSE 'QUEUED' END,
                  lease_owner=NULL,lease_expires_at=NULL,
                  updated_at=now(),version=version+1
                WHERE id=? AND children_pending=0
                  AND status IN ('CREATED','QUEUED','RUNNING')
                  AND (lease_expires_at IS NULL OR lease_expires_at <= now())
                RETURNING status
                """, (rs, rowNum) -> rs.getString(1), parentRunId).stream().findFirst().orElse(null);
    }

    /** 父事件类型：SUCCEEDED → DELEGATION_COMPLETED；其余终态 → DELEGATION_FAILED。 */
    private void emitParentEvent(AgentRunView child, String status, ObjectNode payload) {
        String eventType = "SUCCEEDED".equals(status) ? "DELEGATION_COMPLETED" : "DELEGATION_FAILED";
        host.event(child.projectId(), child.parentRunId(), eventType, payload);
    }

    /**
     * 构建 DELEGATION_COMPLETED step 载荷（设计文档 §3.3 结构化回传协议）。
     *
     * <p>子运行收敛输出为结构化 JSON（含 summary/findings 等字段）时，按分字段落库：
     * summary ≤2000、findings ≤10 条且每条 ≤500、openQuestions ≤5 条、
     * evidenceDigest ≤8 条且每条 ≤800（落库前过 {@link AgentToolResultSanitizer}），
     * 总载荷 ≤40000 字符（超限按 evidenceDigest → openQuestions → findings →
     * summary 的顺序降级裁剪）。</p>
     *
     * <p><b>向后兼容</b>：无 findings 字段（旧格式/纯文本/错误码占位）按既有逻辑降级，
     * content 字段始终保留——既有回填（预算部分回答）与父注入降级逻辑不受影响。</p>
     */
    private ObjectNode buildCollectionPayload(AgentRunView child, String status, String content,
            LinkedHashMap<UUID, AgentCitation> childCitations, AgentRunView usage) {
        var completed = json.createObjectNode()
                .put("childRunId", child.id().toString())
                .put("status", status)
                .put("content", content);
        completed.set("citations", json.valueToTree(childCitations.values().stream().limit(50).toList()));
        completed.set("coverage",
                DelegatedResearchCoverage.extract(json, host.persistedToolResults(child.id()), status));
        completed.put("usage", json.createObjectNode()
                .put("inputTokensUsed", usage.inputTokensUsed())
                .put("outputTokensUsed", usage.outputTokensUsed())
                .put("inputTokensActual", usage.inputTokensActual())
                .put("outputTokensActual", usage.outputTokensActual())
                .put("stepsUsed", usage.stepsUsed()));
        applyStructuredReturn(completed, content);
        enforcePayloadBudget(completed);
        return completed;
    }

    /** 解析子运行的结构化回传；非对象或缺 summary/findings 时返回 null（旧格式降级）。 */
    private JsonNode parseStructuredReturn(String content) {
        if (content == null || content.isBlank()) return null;
        String trimmed = content.strip();
        if (!trimmed.startsWith("{")) return null;
        try {
            JsonNode node = json.readTree(trimmed);
            if (node.isObject() && (node.hasNonNull("summary") || node.has("findings"))) {
                return node;
            }
        } catch (JsonProcessingException malformed) {
            // 非 JSON 的最终回答按旧格式降级
        }
        return null;
    }

    /** 结构化字段的抽取与限幅（各字段上限见类常量；content 字段改写为可读的 summary）。 */
    private void applyStructuredReturn(ObjectNode completed, String content) {
        JsonNode structured = parseStructuredReturn(content);
        if (structured == null) return;
        String summary = AgentRunEventRecorder.truncate(
                structured.path("summary").asText(""), STRUCTURED_SUMMARY_CHARS);
        completed.put("summary", summary);
        if (!summary.isBlank()) {
            // content 保留为可读结论（旧消费者/回填路径继续有稳定字段）
            completed.put("content", summary);
        }
        ArrayNode findings = json.createArrayNode();
        for (JsonNode finding : structured.path("findings")) {
            if (findings.size() >= STRUCTURED_FINDINGS_MAX) break;
            String text = finding.isTextual() ? finding.asText() : finding.toString();
            if (text.isBlank()) continue;
            findings.add(AgentRunEventRecorder.truncate(text, STRUCTURED_FINDING_CHARS));
        }
        if (!findings.isEmpty()) completed.set("findings", findings);
        ArrayNode openQuestions = json.createArrayNode();
        for (JsonNode question : structured.path("openQuestions")) {
            if (openQuestions.size() >= STRUCTURED_OPEN_QUESTIONS_MAX) break;
            String text = question.isTextual() ? question.asText() : question.toString();
            if (text.isBlank()) continue;
            openQuestions.add(AgentRunEventRecorder.truncate(text, STRUCTURED_FINDING_CHARS));
        }
        if (!openQuestions.isEmpty()) completed.set("openQuestions", openQuestions);
        ArrayNode evidence = json.createArrayNode();
        for (JsonNode excerpt : structured.path("evidenceDigest")) {
            if (evidence.size() >= STRUCTURED_EVIDENCE_MAX) break;
            String text = excerpt.isTextual() ? excerpt.asText() : excerpt.toString();
            if (text.isBlank()) continue;
            // 落库前过 sanitizer（防提示注入/敏感信息），再限幅到单条上限
            JsonNode wrapped = sanitizer.sanitize(json.createObjectNode().put("text", text));
            evidence.add(AgentRunEventRecorder.truncate(
                    wrapped.path("text").asText(text), STRUCTURED_EVIDENCE_CHARS));
        }
        if (!evidence.isEmpty()) completed.set("evidenceDigest", evidence);
    }

    /**
     * 总载荷上限（40000 字符）的确定性降级：按 evidenceDigest → openQuestions →
     * findings（裁半）→ summary（裁半）→ citations（裁 20 条）顺序逐级裁剪，
     * 保证结构完整（不产出非法 JSON）且有界。
     */
    private void enforcePayloadBudget(ObjectNode payload) {
        if (host.jsonString(payload).length() <= STRUCTURED_PAYLOAD_MAX) return;
        payload.remove("evidenceDigest");
        if (host.jsonString(payload).length() <= STRUCTURED_PAYLOAD_MAX) return;
        payload.remove("openQuestions");
        if (host.jsonString(payload).length() <= STRUCTURED_PAYLOAD_MAX) return;
        ArrayNode findings = (ArrayNode) payload.get("findings");
        if (findings != null && findings.size() > STRUCTURED_FINDINGS_MAX / 2) {
            ArrayNode reduced = json.createArrayNode();
            for (int i = 0; i < findings.size() && i < STRUCTURED_FINDINGS_MAX / 2; i++) reduced.add(findings.get(i));
            payload.set("findings", reduced);
        }
        if (host.jsonString(payload).length() <= STRUCTURED_PAYLOAD_MAX) return;
        ArrayNode citations = (ArrayNode) payload.get("citations");
        if (citations != null && citations.size() > 20) {
            ArrayNode reduced = json.createArrayNode();
            for (int i = 0; i < citations.size() && i < 20; i++) reduced.add(citations.get(i));
            payload.set("citations", reduced);
        }
        if (host.jsonString(payload).length() <= STRUCTURED_PAYLOAD_MAX) return;
        String summary = payload.path("summary").asText(null);
        if (summary != null && summary.length() > STRUCTURED_SUMMARY_CHARS / 2) {
            payload.put("summary", AgentRunEventRecorder.truncate(summary, STRUCTURED_SUMMARY_CHARS / 2));
            if (payload.path("content").asText("").equals(summary) || summary.startsWith(payload.path("content").asText(""))) {
                payload.put("content", payload.path("summary").asText());
            }
        }
        if (host.jsonString(payload).length() <= STRUCTURED_PAYLOAD_MAX) return;
        payload.put("content", AgentRunEventRecorder.truncate(
                payload.path("content").asText(""), STRUCTURED_SUMMARY_CHARS / 2));
    }

    /**
     * 子运行终态 → 台账终态的映射（台账 CHECK 允许 RUNNING/COMPLETED/FAILED/CANCELED）：
     * SUCCEEDED→COMPLETED；FAILED/BUDGET_EXCEEDED 及未知兜底→FAILED（预算超限是失败的一种
     * 收口形态）；CANCELED→CANCELED（用户取消的委派不阻止同 objective 再次委派）。
     */
    private static String ledgerTerminalStatus(String runStatus) {
        return switch (runStatus == null ? "" : runStatus) {
            case "SUCCEEDED" -> "COMPLETED";
            case "CANCELED" -> "CANCELED";
            // FAILED / BUDGET_EXCEEDED / 其他（RUNNING 不是终态，防御性兜底为 FAILED）
            default -> "FAILED";
        };
    }

}
