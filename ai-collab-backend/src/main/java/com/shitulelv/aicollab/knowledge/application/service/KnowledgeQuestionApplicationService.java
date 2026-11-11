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
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.model.ValidatedKnowledgeAnswer;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeCitationValidator;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgePromptProvider;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import com.shitulelv.aicollab.retrieval.query.QueryUnderstandingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class KnowledgeQuestionApplicationService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeQuestionApplicationService.class);
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
    private final KnowledgePromptProvider promptProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shitulelv.aicollab.system.application.service.SystemConfigService systemConfigService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QueryUnderstandingService queryUnderstandingService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TokenCounter tokenCounter;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.shitulelv.aicollab.knowledge.domain.service.KnowledgeOutputSafetyGuard outputSafetyGuard;

    public KnowledgeQuestionApplicationService(
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
            KnowledgePromptProvider promptProvider) {
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
        this.promptProvider = promptProvider;
    }

    public KnowledgeAnswerView ask(
            UUID projectId, UUID sessionId, KnowledgeQuestionRequest request, UUID userId) {
        String question = validateQuestion(request);
        List<UUID> documentIds = validateDocumentIds(request);
        access.requireMember(projectId, userId);
        if (repository.findOwn(projectId, sessionId, userId).isEmpty()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_SESSION_NOT_FOUND);
        }
        if (systemConfigService != null && !systemConfigService.isChatEnabled()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "AI 知识问答服务当前未开启");
        }
        rateLimiter.check(userId);

        long totalStarted = System.nanoTime();
        long retrievalStarted = System.nanoTime();

        // 1. 查询理解与多轮对话消歧（通过 QueryUnderstandingService 驱动的结构化轮次与无正则消歧）
        KnowledgeConversationContext conversation = KnowledgeConversationContext.from(
                repository.recentContext(projectId, sessionId, userId), question);
        String retrievalQuery = conversation.retrievalQuery();
        String promptContext = conversation.prompt();

        // 2. 统一检索门面：生产行为固定为 DENSE_LEGACY（此前 RAG V2 分支因字段从未注入而恒不执行）
        List<DocumentSearchHit> candidates = retrieval.retrieve(new ProjectKnowledgeRetrieval.Request(
                projectId, userId, retrievalQuery, documentIds, TOP_K,
                ProjectKnowledgeRetrieval.Mode.DENSE_LEGACY)).hits();
        KnowledgeContext context = assembleEvidenceContext(candidates);

        long retrievalMs = elapsedMs(retrievalStarted);

        double highestSimilarity = candidates.isEmpty() ? 0d : candidates.getFirst().similarity();
        if (context.sources().isEmpty()) {
            KnowledgeAnswerView view = persistence.saveExchange(
                    projectId, sessionId, userId, question,
                    KnowledgeCitationValidator.INSUFFICIENT_ANSWER, true, null, List.of());
            log.info(
                    "Knowledge QA completed candidateCount={} retainedSourceCount=0 highestSimilarity={} retrievalLatencyMs={} chatLatencyMs=0 totalLatencyMs={}",
                    candidates.size(), highestSimilarity, retrievalMs, elapsedMs(totalStarted));
            return view;
        }

        // 4. 构建提示词与出站请求（唯一 Prompt 来源：KnowledgePromptProvider，缺 Bean 即启动失败）
        String effectiveSystemPrompt = promptProvider.systemPrompt();
        String effectiveUserPayload =
                promptProvider.buildUserPayload(question, context.promptSources(), promptContext);

        UUID requestId = UUID.randomUUID();
        long chatStarted = System.nanoTime();
        ChatCompletionResult completion;
        try {
            completion = chat.complete(new ChatCompletionCommand(
                    projectId, effectiveSystemPrompt, effectiveUserPayload,
                    ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT, null,
                    List.of(), userId),
                    new com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata(sessionId.toString()));
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.AI_PROVIDER_UNAVAILABLE) {
                aiLogs.failure(
                        requestId, userId, projectId,
                        exception.getErrorCode(), elapsedMs(chatStarted));
            }
            throw exception;
        }

        KnowledgeAnswerView[] result = new KnowledgeAnswerView[1];
        visibility.whileVisible(projectId, context.sources(), () -> {
            ValidatedKnowledgeAnswer validated =
                    citationValidator.validate(completion.content(), context.sources(), projectId);
            // 有界输出校验：回答复述系统提示词片段属于注入攻击的典型目标行为，
            // 用确定性 k-gram 检测兜底（不依赖提示词约束）。
            String answerForUser = validated.answer();
            if (outputSafetyGuard != null) {
                var safety = outputSafetyGuard.inspect(effectiveSystemPrompt, answerForUser);
                if (safety.leaked()) {
                    log.warn(
                            "Knowledge QA blocked system prompt leakage requestId={} matchedSampleLength={}",
                            requestId, safety.matchedSample().codePointCount(0, safety.matchedSample().length()));
                    answerForUser = com.shitulelv.aicollab.knowledge.domain.service.KnowledgeOutputSafetyGuard.BLOCKED_ANSWER;
                }
            }
            if (validated.invalidOutput()) {
                aiLogs.invalidOutput(requestId, userId, projectId, completion);
            } else {
                aiLogs.success(requestId, userId, projectId, completion);
            }
            if (validated.invalidCitationCount() > 0) {
                log.warn(
                        "Knowledge QA removed invalid citations requestId={} invalidCitationCount={} validCitationCount={}",
                        requestId, validated.invalidCitationCount(), validated.citedSources().size());
            }
            KnowledgeAnswerView view = persistence.saveExchange(
                    projectId, sessionId, userId, question, answerForUser,
                    validated.insufficientEvidence(), completion, validated.citedSources());
            log.info(
                    "Knowledge QA completed candidateCount={} retainedSourceCount={} highestSimilarity={} retrievalLatencyMs={} chatLatencyMs={} totalLatencyMs={} requestId={}",
                    candidates.size(), context.sources().size(), highestSimilarity, retrievalMs,
                    completion.latencyMs(), elapsedMs(totalStarted), requestId);
            result[0] = view;
        });
        return result[0];
    }

    /**
     * 证据装配共享入口（优先 TokenCounter 精确预算）：检索回退判定与最终装配使用同一实现，
     * 保证"回退判定看到的证据"与"进入提示词的证据"完全一致。
     */
    private KnowledgeContext assembleEvidenceContext(List<DocumentSearchHit> candidates) {
        if (tokenCounter != null) {
            return contextBuilder.buildWithTokenBudget(candidates, 5, 2500, tokenCounter, null, null).context();
        }
        return contextBuilder.buildBalanced(candidates);
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
