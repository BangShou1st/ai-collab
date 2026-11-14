package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.common.ai.token.TokenCounter;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.infrastructure.ai.AiCallLogService;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeAnswerView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeStreamEvent;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.model.ValidatedKnowledgeAnswer;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeCitationValidator;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgePromptAssembler;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import com.shitulelv.aicollab.retrieval.application.RetrievalQaProperties;
import com.shitulelv.aicollab.retrieval.query.QueryUnderstandingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;

@Service
public class KnowledgeStreamQuestionService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeStreamQuestionService.class);
    private static final int TOP_K = 8;

    private final ProjectAccessGuard access;
    private final KnowledgeRepository repository;
    private final KnowledgeRateLimiter rateLimiter;
    private final ProjectKnowledgeRetrieval retrieval;
    private final KnowledgeContextBuilder contextBuilder;
    private final ChatModelGateway chat;
    private final AiCallLogService aiLogs;
    private final KnowledgeCitationValidator citationValidator;
    private final KnowledgePersistenceService persistence;
    private final KnowledgeVisibilityGuard visibility;
    private final KnowledgePromptAssembler promptAssembler;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shitulelv.aicollab.system.application.service.SystemConfigService systemConfigService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QueryUnderstandingService queryUnderstandingService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TokenCounter tokenCounter;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shitulelv.aicollab.knowledge.domain.service.KnowledgeOutputSafetyGuard outputSafetyGuard;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RetrievalQaProperties retrievalQaProperties;

    private final ExecutorService streamExecutor = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32), r -> {
        Thread t = new Thread(r, "knowledge-stream-" + UUID.randomUUID());
        t.setDaemon(true);
        return t;
    });

    public KnowledgeStreamQuestionService(
            ProjectAccessGuard access,
            KnowledgeRepository repository,
            KnowledgeRateLimiter rateLimiter,
            ProjectKnowledgeRetrieval retrieval,
            KnowledgeContextBuilder contextBuilder,
            ChatModelGateway chat,
            AiCallLogService aiLogs,
            KnowledgeCitationValidator citationValidator,
            KnowledgePersistenceService persistence,
            KnowledgeVisibilityGuard visibility,
            KnowledgePromptAssembler promptAssembler) {
        this.access = access;
        this.repository = repository;
        this.rateLimiter = rateLimiter;
        this.retrieval = retrieval;
        this.contextBuilder = contextBuilder;
        this.chat = chat;
        this.aiLogs = aiLogs;
        this.citationValidator = citationValidator;
        this.persistence = persistence;
        this.visibility = visibility;
        this.promptAssembler = promptAssembler;
    }

    public void askStream(
            UUID projectId, UUID sessionId, KnowledgeQuestionRequest request,
            UUID userId, SseEmitter emitter) {
        String question = validateQuestion(request);
        List<UUID> documentIds = validateDocumentIds(request);
        AtomicBoolean terminal = new AtomicBoolean(false);
        access.requireMember(projectId, userId);
        if (repository.findOwn(projectId, sessionId, userId).isEmpty()) {
            sendErrorAndComplete(emitter, terminal, ErrorCode.KNOWLEDGE_SESSION_NOT_FOUND);
            return;
        }
        if (systemConfigService != null && !systemConfigService.isChatEnabled()) {
            sendErrorAndComplete(emitter, terminal, ErrorCode.AI_PROVIDER_UNAVAILABLE, "AI 知识问答服务当前未开启");
            return;
        }
        rateLimiter.check(userId);
        AtomicBoolean disconnected = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> pending = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable disconnect = () -> { disconnected.set(true); if (!terminal.get() && pending.get() != null) pending.get().cancel(true); };
        emitter.onCompletion(disconnect);
        emitter.onTimeout(disconnect);
        emitter.onError(ignored -> disconnect.run());

        java.util.concurrent.Future<?> task;
        try { task = streamExecutor.submit(() -> {
          try {
            long totalStarted = System.nanoTime();
            long retrievalStarted = System.nanoTime();

            // 1. 查询理解与多轮对话消歧（通过 QueryUnderstandingService 驱动的结构化轮次与无正则消歧）
            KnowledgeConversationContext conversation = KnowledgeConversationContext.from(
                    repository.recentContext(projectId, sessionId, userId), question);
            String retrievalQuery = conversation.retrievalQuery();
            String promptContext = conversation.prompt();
            if (disconnected.get()) return;

            // 2. 统一检索门面：默认 DENSE_LEGACY；显式配置后才进入策略感知执行器。
            ProjectKnowledgeRetrieval.Mode retrievalMode = retrievalMode();
            ProjectKnowledgeRetrieval.Request retrievalRequest = new ProjectKnowledgeRetrieval.Request(
                    projectId, userId, retrievalQuery, documentIds, TOP_K,
                    retrievalMode);
            List<DocumentSearchHit> candidates = retrievalMode == ProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE
                    ? retrieval.retrieve(retrievalRequest, this::hasUsableEvidence).hits()
                    : retrieval.retrieve(retrievalRequest).hits();
            KnowledgeContext context = assembleEvidenceContext(candidates);

            long retrievalMs = elapsedMs(retrievalStarted);

            double highestSimilarity = candidates.isEmpty() ? 0d : candidates.getFirst().similarity();

            if (context.sources().isEmpty()) {
                if (disconnected.get()) return;
                KnowledgeAnswerView view = persistence.saveExchange(
                        projectId, sessionId, userId, question,
                        KnowledgeCitationValidator.INSUFFICIENT_ANSWER, true, null, List.of());
                sendDoneAndComplete(emitter, terminal, view.messageId().toString());
                log.info("Knowledge stream QA completed (no sources) candidateCount={} retrievalLatencyMs={}",
                        candidates.size(), retrievalMs);
                return;
            }

            UUID requestId = UUID.randomUUID();
            long chatStarted = System.nanoTime();

            KnowledgePromptAssembler.RenderedPrompt systemPrompt = promptAssembler.system();
            String effectiveSystemPrompt = systemPrompt.text();
            String effectiveUserPayload =
                    promptAssembler.userPayload(question, context.promptSources(), promptContext);

            // 有界输出校验（流式）：SSE 已发送内容无法撤回，因此必须在<b>发送前</b>做系统提示词泄漏预检。
            // 实现方式：维护未发送缓冲，仅发送留出 (K_GRAM-1) 尾码点后可确认安全的前缀；一旦命中指纹，
            // 立即中止流并下发安全错误事件（不持久化未完成的交换）。尾部残余在完成回调中全量复检。
            java.util.Set<String> promptFingerprints = outputSafetyGuard != null
                    ? outputSafetyGuard.fingerprintSet(effectiveSystemPrompt) : java.util.Set.of();
            int kGram = com.shitulelv.aicollab.knowledge.domain.service.KnowledgeOutputSafetyGuard.K_GRAM;
            StringBuilder pendingTokens = new StringBuilder();
            AtomicBoolean outputBlocked = new AtomicBoolean(false);

            chat.completeStream(
                    new ChatCompletionCommand(projectId, effectiveSystemPrompt,
                            effectiveUserPayload,
                            ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT, null,
                            List.of(), userId),
                    new com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata(sessionId.toString()),
                    token -> {
                        if (disconnected.get() || outputBlocked.get()) {
                            return;
                        }
                        pendingTokens.append(token);
                        int pendingCp = pendingTokens.codePointCount(0, pendingTokens.length());
                        int safeCp = Math.max(0, pendingCp - (kGram - 1));
                        String flushText = pendingTokens.substring(0, pendingTokens.offsetByCodePoints(0, safeCp));
                        String tail = pendingTokens.substring(pendingTokens.offsetByCodePoints(0, safeCp));
                        if (!flushText.isEmpty() && outputSafetyGuard != null
                                && outputSafetyGuard.containsAnyFingerprint(flushText, promptFingerprints)) {
                            outputBlocked.set(true);
                            log.warn("Knowledge stream QA blocked system prompt leakage mid-stream");
                            sendErrorAndComplete(emitter, terminal, ErrorCode.AI_PROVIDER_ERROR,
                                    "回答包含不应披露的内部信息，已被安全策略拦截");
                            return;
                        }
                        pendingTokens.setLength(0);
                        pendingTokens.append(tail);
                        if (!flushText.isEmpty()) {
                            visibility.whileVisible(projectId, context.sources(), () -> {
                                if (!sendSse(emitter, KnowledgeStreamEvent.token(flushText))) {
                                    disconnected.set(true);
                                    throw new BusinessException(ErrorCode.AI_MODEL_TIMEOUT);
                                }
                            });
                        }
                    },
                    completion -> {
                        if (disconnected.get() || terminal.get()) {
                            return;
                        }
                        visibility.whileVisible(projectId, context.sources(), () -> {
                            long chatLatencyMs = elapsedMs(chatStarted);
                            ValidatedKnowledgeAnswer validated =
                                    citationValidator.validate(completion.content(), context.sources(), projectId);
                            String answerForUser = validated.answer();
                            boolean leaked = false;
                            if (outputSafetyGuard != null) {
                                var safety = outputSafetyGuard.inspect(effectiveSystemPrompt, completion.content());
                                if (safety.leaked()) {
                                    leaked = true;
                                    log.warn("Knowledge stream QA blocked system prompt leakage requestId={} "
                                            + "matchedSampleLength={}", requestId,
                                            safety.matchedSample().codePointCount(0, safety.matchedSample().length()));
                                    answerForUser = com.shitulelv.aicollab.knowledge.domain.service
                                            .KnowledgeOutputSafetyGuard.BLOCKED_ANSWER;
                                }
                            }
                            if (validated.invalidOutput()) {
                                aiLogs.invalidOutput(requestId, userId, projectId, completion);
                            } else {
                                aiLogs.success(requestId, userId, projectId, completion);
                            }
                            if (validated.invalidCitationCount() > 0) {
                                log.warn("Knowledge stream QA removed invalid citations requestId={} invalidCitationCount={}",
                                        requestId, validated.invalidCitationCount());
                            }
                            KnowledgeAnswerView view = persistence.saveExchange(
                                    projectId, sessionId, userId, question, answerForUser,
                                    validated.insufficientEvidence(), completion, validated.citedSources(),
                                    systemPrompt.key(), systemPrompt.version());
                            if (leaked) {
                                // 流式边界限制：已发送的增量内容无法撤回；此处以安全错误事件明确告知客户端，
                                // 持久化记录已替换为统一拦截文案，保证历史会话不残留泄漏内容。
                                sendErrorAndComplete(emitter, terminal, ErrorCode.AI_PROVIDER_ERROR,
                                        "回答包含不应披露的内部信息，已被安全策略拦截");
                                return;
                            }
                            if (terminal.get()) {
                                return;
                            }
                            sendSse(emitter, KnowledgeStreamEvent.citations(view.citations()));
                            sendDoneAndComplete(emitter, terminal, view.messageId().toString());
                            log.info(
                                    "Knowledge stream QA completed candidateCount={} retainedSourceCount={} highestSimilarity={} retrievalLatencyMs={} chatLatencyMs={} totalLatencyMs={} requestId={}",
                                    candidates.size(), context.sources().size(), highestSimilarity,
                                    retrievalMs, chatLatencyMs, elapsedMs(totalStarted), requestId);
                        });
                    },
                    error -> {
                        log.warn("Knowledge stream QA failed requestId={} error={}",
                                requestId, error.getMessage());
                        if (error instanceof BusinessException be) {
                            sendErrorAndComplete(emitter, terminal, be.getErrorCode());
                        } else {
                            sendErrorAndComplete(emitter, terminal, ErrorCode.AI_PROVIDER_ERROR);
                        }
                    });
          } catch (BusinessException error) {
              sendErrorAndComplete(emitter, terminal, error.getErrorCode());
          } catch (RuntimeException error) {
              log.warn("Knowledge stream preparation failed", error);
              sendErrorAndComplete(emitter, terminal, ErrorCode.INTERNAL_ERROR);
          }
        }); } catch (java.util.concurrent.RejectedExecutionException busy) {
            sendErrorAndComplete(emitter,terminal,ErrorCode.AI_PROVIDER_UNAVAILABLE);
            return;
        }
        pending.set(task);
        if (disconnected.get() && !terminal.get()) task.cancel(true);
    }

    @PreDestroy
    void shutdownExecutor() {
        streamExecutor.shutdownNow();
    }

    private boolean sendSse(SseEmitter emitter, KnowledgeStreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(event));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to send SSE event: {}", e.getMessage());
            return false;
        }
    }

    private void sendErrorAndComplete(
            SseEmitter emitter, AtomicBoolean terminal, ErrorCode errorCode) {
        sendErrorAndComplete(emitter, terminal, errorCode, errorCode.message());
    }

    private void sendErrorAndComplete(
            SseEmitter emitter, AtomicBoolean terminal, ErrorCode errorCode, String customMessage) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        sendSse(emitter, KnowledgeStreamEvent.error(errorCode.name(), customMessage));
        emitter.complete();
    }

    private void sendDoneAndComplete(
            SseEmitter emitter, AtomicBoolean terminal, String messageId) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        sendSse(emitter, KnowledgeStreamEvent.done(messageId));
        emitter.complete();
    }

    /**
     * 证据装配共享入口（优先 TokenCounter 精确预算）：检索回退判定与最终装配使用同一实现，
     * 与普通 QA 完全一致，保证回退行为不因链路不同而分叉。
     */
    private KnowledgeContext assembleEvidenceContext(List<DocumentSearchHit> candidates) {
        if (tokenCounter != null) {
            return contextBuilder.buildWithTokenBudget(candidates, 5, 2500, tokenCounter, null, null).context();
        }
        return contextBuilder.buildBalanced(candidates);
    }

    private boolean hasUsableEvidence(List<DocumentSearchHit> candidates) {
        return !assembleEvidenceContext(candidates).sources().isEmpty();
    }

    private ProjectKnowledgeRetrieval.Mode retrievalMode() {
        return retrievalQaProperties == null
                ? ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY
                : retrievalQaProperties.mode();
    }

    private static String validateQuestion(KnowledgeQuestionRequest request) {
        String value = request == null || request.question() == null ? "" : request.question().strip();
        if (value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "问题不能为空");
        }
        if (value.codePointCount(0, value.length()) > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "问题不能超过 2000 个字符");
        }
        return value;
    }

    private static List<UUID> validateDocumentIds(KnowledgeQuestionRequest request) {
        List<UUID> ids = request == null || request.documentIds() == null
                ? List.of() : new ArrayList<>(request.documentIds());
        if (ids.size() > 20) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "最多选择 20 个文档");
        }
        if (ids.stream().anyMatch(id -> id == null) || new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "文档 ID 不能为空或重复");
        }
        return ids;
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }
}
