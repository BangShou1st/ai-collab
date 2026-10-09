package com.shitulelv.aicollab.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.api.dto.CreateAgentSessionRequest;
import com.shitulelv.aicollab.agent.api.dto.RenameAgentSessionRequest;
import com.shitulelv.aicollab.agent.api.dto.SubmitAgentMessageRequest;
import com.shitulelv.aicollab.agent.api.dto.AgentPageContextRequest;
import com.shitulelv.aicollab.agent.application.view.*;
import com.shitulelv.aicollab.agent.domain.model.AgentPageContext;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.domain.model.AgentEventType;
import com.shitulelv.aicollab.agent.application.runtime.AgentEventService;
import com.shitulelv.aicollab.agent.domain.model.AgentSkillRegistry;
import com.shitulelv.aicollab.agent.domain.policy.AgentStateMachine;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentEventRepository;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentApprovalRepository;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AgentRunService {
    private static final int MAX_SESSIONS = 100;
    private static final int MAX_MESSAGES = 500;
    private final ProjectAccessGuard access;
    private final AgentRepository repository;
    private final AgentSkillRegistry skillRegistry;
    private final AgentStateMachine states = new AgentStateMachine();
    private final ObjectMapper json;
    private final AgentEventService events;
    private final AgentEventRepository eventRepository;
    private final AgentApprovalRepository approvalRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.shitulelv.aicollab.agent.application.runtime.AgentCancellationService cancellationSignal;

    public AgentRunService(ProjectAccessGuard access, AgentRepository repository,
                          AgentSkillRegistry skillRegistry, ObjectMapper json,
                          AgentEventService events, AgentEventRepository eventRepository,
                          AgentApprovalRepository approvalRepository) {
        this.access = access;
        this.repository = repository;
        this.skillRegistry = skillRegistry;
        this.json = json;
        this.events = events;
        this.eventRepository = eventRepository;
        this.approvalRepository = approvalRepository;
    }

    @Transactional
    public AgentSessionView createSession(
            UUID projectId, UUID userId, CreateAgentSessionRequest request) {
        access.requireMember(projectId, userId);
        return repository.createSession(projectId, userId, request.title().strip());
    }

    @Transactional(readOnly = true)
    public List<AgentSessionView> listSessions(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return repository.listSessions(projectId, MAX_SESSIONS);
    }

    @Transactional(readOnly = true)
    public List<AgentSessionSummaryView> listSessionSummaries(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return repository.listSessionSummaries(projectId, MAX_SESSIONS);
    }

    @Transactional(readOnly = true)
    public AgentRunDetailView latestRun(UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        if (repository.findSession(projectId, sessionId).isEmpty())
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND);
        return repository.findLatestRun(projectId, sessionId).map(run -> getRun(projectId, run.id(), userId)).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<com.shitulelv.aicollab.agent.application.view.AgentRunEventView> runEvents(
            UUID projectId, UUID runId, long afterSequence, UUID userId) {
        access.requireMember(projectId, userId);
        repository.findRun(projectId, runId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        return eventRepository.list(projectId, runId, Math.max(0, afterSequence), 500);
    }

    @Transactional(readOnly = true)
    public List<AgentApprovalView> runApprovals(UUID projectId, UUID runId, UUID userId) {
        access.requireMember(projectId, userId);
        repository.findRun(projectId, runId).orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        return approvalRepository.listByRun(projectId, runId);
    }

    @Transactional(readOnly = true)
    public AgentSessionView getSession(UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        return repository.findSession(projectId, sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND));
    }

    @Transactional
    public AgentSessionView renameSession(
            UUID projectId, UUID sessionId, UUID userId, RenameAgentSessionRequest request) {
        access.requireMember(projectId, userId);
        return repository.renameSession(projectId, sessionId, userId, request.title().strip())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND));
    }

    @Transactional
    public void deleteSession(UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        if (!repository.deleteSession(projectId, sessionId, userId)) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND);
        }
    }

    /** 绑定控制输入未获处理的业务提示：按权威运行状态指明正确的原入口。 */
    private static String boundInputGuidance(AgentRunStatus status) {
        String guidance = switch (status) {
            case WAITING_FOR_USER_INPUT -> "该任务正在等待你的澄清回复，请通过回复入口继续，不会开启新任务";
            case WAITING_FOR_APPROVAL -> "该任务正在等待审批处理，请在审批卡片确认或拒绝后继续";
            case FAILED_RETRYABLE -> "该任务正在等待自动重试，如需立即重试请使用重试入口";
            case QUEUED, RUNNING -> "当前任务正在执行，本次输入未按新任务提交；如需开始新任务，请先结束本次运行或新建会话";
            default -> "本次任务已结束，结果已保留；如需开始新任务，请直接发送新内容或新建会话";
        };
        return guidance + "。已保留你输入的内容。";
    }

    @Transactional
    public AgentRunView submit(
            UUID projectId, UUID sessionId, UUID userId, SubmitAgentMessageRequest request) {
        access.requireMember(projectId, userId);
        if (repository.findSession(projectId, sessionId).isEmpty()) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND);
        }

        // 暂停/暂停等待期的输入分流（后端统一入口，权威运行状态判定）：
        // 明确续跑表达恢复同一个运行（控制操作，不追加 USER 目标消息、不改 latestRequest/
        // goalRevision/有效约束）；歧义与普通新内容不得静默创建新任务改写当前工作状态。
        // 暂停请求已在途中时不允许抢先恢复或启动另一推进者。
        AgentRunView latest = repository.findLatestRun(projectId, sessionId).orElse(null);
        String boundRunId = request.pausedRunId();
        boolean boundControlInput = boundRunId != null && !boundRunId.isBlank();
        // 显式绑定暂停运行的输入始终在该运行的控制作用域中处理：绑定与权威运行不符
        // （运行不存在、不属于当前会话或当前 run 已切换）时按既有业务错误拒绝。
        if (boundControlInput && (latest == null || !latest.id().toString().equals(boundRunId))) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND,
                    "当前运行已切换，请刷新页面后重试");
        }
        if (latest != null && !latest.status().terminal()) {
            boolean paused = latest.status() == AgentRunStatus.PAUSED;
            boolean pausePending = !paused && repository.isPauseRequested(projectId, latest.id());
            boolean waitingExternal = latest.status() == AgentRunStatus.WAITING_FOR_APPROVAL
                    || latest.status() == AgentRunStatus.WAITING_FOR_USER_INPUT;
            if (paused || pausePending) {
                if (pausePending) {
                    throw new BusinessException(ErrorCode.AGENT_RUN_PAUSE_PENDING,
                            "正在暂停，当前步骤完成后保留进度；请等待暂停完成后再发送");
                }
                switch (ResumeIntentRecognizer.classify(request.content())) {
                    case RESUME -> {
                        return repository.requestResume(projectId, latest.id());
                    }
                    case AMBIGUOUS -> throw new BusinessException(ErrorCode.AGENT_RUN_PAUSED_INPUT,
                            "你的输入可能带有继续之外的新要求。当前任务已暂停：输入\"继续\"可恢复执行原任务；"
                                    + "要开始新任务，请先结束本次运行或新建会话。已保留你输入的内容。");
                    case UNRELATED -> throw new BusinessException(ErrorCode.AGENT_RUN_PAUSED_INPUT,
                            "当前任务已暂停，进度已保留。输入\"继续\"恢复执行；要开始新任务，"
                                    + "请先结束本次运行或新建会话。");
                }
            }
            // 排队/运行中的重复续跑表达：幂等返回当前运行，不创建派生任务。
            // 等待审批/澄清与自动重试等待的文本仍按原入口处理，不冒充 resume。
            if (!waitingExternal
                    && (latest.status() == AgentRunStatus.QUEUED || latest.status() == AgentRunStatus.RUNNING)
                    && ResumeIntentRecognizer.classify(request.content())
                            == ResumeIntentRecognizer.Intent.RESUME) {
                return repository.requestResume(projectId, latest.id());
            }
        }
        // 显式绑定暂停运行的输入始终留在控制入口，绝不进入普通 createRun（C1 补全）：
        // 已处理控制分支之外——终态、等待澄清/审批、重试等待以及排队/运行中的
        // 非续跑绑定文本——一律按权威状态给出明确业务提示（澄清走原 /continue、
        // 重试走既有入口、新任务请先结束本次运行或新建会话），不静默新建任务推进
        // 工作状态。唯一例外：终态上的迟到/重复"继续"幂等返回真实终态（前端展示
        // 真实状态，不当作恢复成功继续调度）。未绑定控制作用域的真正新任务仍按普通提交。
        if (latest != null && boundControlInput) {
            if (latest.status().terminal()
                    && ResumeIntentRecognizer.classify(request.content())
                            == ResumeIntentRecognizer.Intent.RESUME) {
                return latest;
            }
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_RESUMABLE,
                    boundInputGuidance(latest.status()));
        }

        // 验证 skillCode
        String skillCode = request.skillCode();
        if (skillCode != null && !skillCode.isBlank()) {
            if (!skillRegistry.exists(skillCode.trim().toUpperCase())) {
                throw new BusinessException(ErrorCode.AGENT_SKILL_NOT_FOUND);
            }
            skillCode = skillCode.trim().toUpperCase();
        }

        // 转换 pageContext
        String pageContextJson = null;
        if (request.pageContext() != null) {
            AgentPageContext pageContext = new AgentPageContext(
                    request.pageContext().route(),
                    request.pageContext().selectedTaskId(),
                    request.pageContext().selectedMilestoneId(),
                    request.pageContext().selectedDocumentId(),
                    request.pageContext().selectedPlanId(),
                    request.pageContext().filters());
            try {
                pageContextJson = json.writeValueAsString(pageContext);
            } catch (Exception e) {
                pageContextJson = null;
            }
        }

        AgentRunView run = repository.createRun(
                projectId, sessionId, userId, request.content().strip(), false,
                skillCode, pageContextJson);
        events.append(projectId, run.id(), AgentEventType.RUN_CREATED,
                json.createObjectNode().put("status", run.status().name()));
        return run;
    }

    @Transactional(readOnly = true)
    public List<AgentMessageView> listMessages(
            UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        if (repository.findSession(projectId, sessionId).isEmpty()) {
            throw new BusinessException(ErrorCode.AGENT_SESSION_NOT_FOUND);
        }
        return repository.listMessages(projectId, sessionId, MAX_MESSAGES);
    }

    @Transactional(readOnly = true)
    public AgentRunDetailView getRun(UUID projectId, UUID runId, UUID userId) {
        access.requireMember(projectId, userId);
        AgentRunView run = repository.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        com.fasterxml.jackson.databind.JsonNode plan = null;
        try { if (run.planJson() != null) plan = json.readTree(run.planJson()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Agent 计划 JSON 无法读取", exception);
        }
        UUID pendingApproval = approvalRepository.list(projectId, "PENDING").stream()
                .filter(value -> value.runId().equals(runId)).map(AgentApprovalView::id).findFirst().orElse(null);
        return new AgentRunDetailView(run, plan, repository.listSteps(projectId, runId),
                eventRepository.lastSequence(projectId, runId), pendingApproval,repository.modelSnapshot(run),repository.recoveryCounters(run),
                repository.pauseRequestedAt(projectId, runId));
    }

    /**
     * 请求暂停当前运行。权威状态与暂停意图见返回的运行详情：
     * QUEUED / 重试等待直接 PAUSED；RUNNING 落暂停意图、由 worker 在动作边界确认。
     */
    @Transactional
    public AgentRunDetailView pause(UUID projectId, UUID runId, UUID userId) {
        access.requireMember(projectId, userId);
        repository.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        AgentRunView run = repository.requestPause(projectId, runId);
        return runDetail(run);
    }

    /**
     * 恢复同一个暂停运行（内部控制能力，前端不设继续按钮；由输入续跑意图分流调用）。
     * PAUSED → QUEUED，保留原 runId、目标、约束、额度与已完成记录；不新建派生运行。
     */
    @Transactional
    public AgentRunDetailView resume(UUID projectId, UUID runId, UUID userId) {
        access.requireMember(projectId, userId);
        repository.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        AgentRunView run = repository.requestResume(projectId, runId);
        return runDetail(run);
    }

    private AgentRunDetailView runDetail(AgentRunView run) {
        return new AgentRunDetailView(run, null, repository.listSteps(run.projectId(), run.id()),
                eventRepository.lastSequence(run.projectId(), run.id()), null,
                repository.modelSnapshot(run), repository.recoveryCounters(run),
                repository.pauseRequestedAt(run.projectId(), run.id()));
    }

    @Transactional
    public void cancel(UUID projectId, UUID runId, UUID userId) {
        access.requireMember(projectId, userId);
        AgentRunView run = repository.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        AgentRunStatus status = repository.requestCancel(projectId, runId);
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { if (cancellationSignal != null) cancellationSignal.interrupt(runId); }
                });
        if (status == AgentRunStatus.CANCELED) {
            events.append(projectId, runId, AgentEventType.RUN_CANCELED,
                    json.createObjectNode().put("status", status.name()));
        }
    }

    /**
     * 重试一次运行。两种语义按状态区分：
     * <ul>
     *   <li>FAILED_RETRYABLE：运行内自动重试路径，状态机允许转回 QUEUED，原运行继续。</li>
     *   <li>FAILED / CANCELED / BUDGET_EXCEEDED（终态）：状态机不允许也不放开转回 QUEUED——
     *       原地重入会继承已耗尽的预算或取消标记。改为创建新运行（复制目标/技能/页面上下文，
     *       预算从默认值开始），旧运行记录与其历史结果、事件完整保留。retried_from_run_id
     *       的唯一约束保证重复点击/并发请求幂等返回同一新运行，业务动作不重复执行。</li>
     * </ul>
     */
    @Transactional
    public AgentRunView retry(UUID projectId, UUID runId, UUID userId) {
        access.requireMember(projectId, userId);
        AgentRunView run = repository.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));
        if (run.status() == AgentRunStatus.FAILED || run.status() == AgentRunStatus.CANCELED
                || run.status() == AgentRunStatus.BUDGET_EXCEEDED) {
            AgentRepository.RetryRunDerivation derivation = repository.createRetryRun(
                    projectId, run.sessionId(), userId, run.id(),
                    run.goal(), run.skillCode(), run.pageContextJson());
            if (derivation.created()) {
                events.append(projectId, run.id(), AgentEventType.RUN_RETRY_SCHEDULED,
                        json.createObjectNode()
                                .put("errorCode", run.errorCode())
                                .put("retriedToRunId", derivation.run().id().toString()));
                events.append(projectId, derivation.run().id(), AgentEventType.RUN_CREATED,
                        json.createObjectNode()
                                .put("status", derivation.run().status().name())
                                .put("retriedFromRunId", run.id().toString()));
            }
            return derivation.run();
        }
        states.requireTransition(run.status(), AgentRunStatus.QUEUED);
        if (!repository.updateStatus(projectId, runId, run.version(),
                run.status(), AgentRunStatus.QUEUED, null)) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        AgentRunView retried = repository.findRun(projectId, runId).orElseThrow();
        events.append(projectId, runId, AgentEventType.RUN_RETRY_SCHEDULED,
                json.createObjectNode().put("status", retried.status().name()));
        return retried;
    }

    /**
     * 继续等待用户输入的 Run。
     */
    @Transactional
    public AgentRunView continueRun(UUID projectId, UUID runId, UUID userId, String userResponse) {
        access.requireMember(projectId, userId);
        AgentRunView run = repository.findRun(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND));

        // 验证状态必须是 WAITING_FOR_USER_INPUT
        if (run.status() != AgentRunStatus.WAITING_FOR_USER_INPUT) {
            throw new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND,
                    "Agent 运行不在等待用户输入状态");
        }

        // 继续 Run
        AgentRunView continued = repository.continueRun(run, userResponse.strip());
        events.append(projectId, runId, AgentEventType.RUN_CREATED,
                json.createObjectNode().put("status", continued.status().name()));
        return continued;
    }
}
