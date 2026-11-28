package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.infrastructure.entity.DocumentEntity;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.capability.RerankCapabilityResolver;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeAnswerView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeStreamEvent;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeMessageEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.remote.KnowledgeQaModels;
import com.shitulelv.aicollab.knowledge.infrastructure.remote.KnowledgeQaStreamClient;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeHitVerifier;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeSearchClient;
import com.shitulelv.aicollab.retrieval.remote.KnowledgeSearchModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import jakarta.annotation.PreDestroy;

/**
 * P3 问答中继（契约 §2.2 方案 A，Q1=A + Q2=1 + Q9=a + Q10=a + Q13=a）：
 * 前端协议、入站校验、限流、开关、会话属主与落库全部留在 Java；生成、路由、消歧、
 * 装配与输出安全预检全部在 worker。本类是原两段问答编排服务（同步 ask + 流式 askStream）
 * 的 P3 替代实现，其 Java 编排体已按契约 §7 删除。
 *
 * <p>finalize 处理顺序（§3.3/§5.1）：</p>
 * <ol>
 *   <li>全文一致性：finalize.answer 与转发的 token 拼接逐字节比较，不等 →
 *       {@code error(INTERNAL_ERROR)} 且不落库（§8.2.5）；</li>
 *   <li>引用复核：citations 转 {@link KnowledgeSearchModels.Hit} 交给
 *       {@link KnowledgeHitVerifier}（visible 集合 + P2 §6 六条），全部被拒 →
 *       按证据不足落库；</li>
 *   <li>落库：{@link KnowledgeVisibilityGuard#whileVisible} 包住复核后写库 +
 *       {@code insertScoped} 写时复核兜底撤权竞态；promptKey/Version 取 finalize；</li>
 *   <li>流式路径随后发 {@code citations} + {@code done(messageId)}（done 前必落库，
 *       §1.3 时序不变）。</li>
 * </ol>
 *
 * <p>已登记的语义边界（P3 汇报）：转发 token 不再包 {@code whileVisible}（可见集合在
 * finalize 前未知；落库侧防护完整保留）；泄漏终检命中 → worker error(CONTENT_BLOCKED)
 * 终止且不落库；断线/取消不落库（§8.2.2）；线程池容量沿用现状 2/2/32（契约 §2.2 只要求复核）。</p>
 */
@Service
public class KnowledgeQaRelayService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeQaRelayService.class);

    private static final int TOP_K = 8;
    private static final int QA_DEADLINE_MS = 90_000;
    private static final DateTimeFormatter HISTORY_TIME =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ProjectAccessGuard access;
    private final KnowledgeRepository repository;
    private final KnowledgeRateLimiter rateLimiter;
    private final DocumentRepository documents;
    private final RerankCapabilityResolver rerank;
    private final KnowledgeHitVerifier verifier;
    private final KnowledgePersistenceService persistence;
    private final KnowledgeVisibilityGuard visibility;
    private final KnowledgeQaStreamClient client;

    @Autowired(required = false)
    private com.shitulelv.aicollab.system.application.service.SystemConfigService systemConfigService;

    private final ExecutorService streamExecutor = new ThreadPoolExecutor(2, 2, 0,
            TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(32), r -> {
        Thread t = new Thread(r, "knowledge-qa-relay-" + UUID.randomUUID());
        t.setDaemon(true);
        return t;
    });

    public KnowledgeQaRelayService(
            ProjectAccessGuard access,
            KnowledgeRepository repository,
            KnowledgeRateLimiter rateLimiter,
            DocumentRepository documents,
            RerankCapabilityResolver rerank,
            KnowledgeHitVerifier verifier,
            KnowledgePersistenceService persistence,
            KnowledgeVisibilityGuard visibility,
            KnowledgeQaStreamClient client) {
        this.access = access;
        this.repository = repository;
        this.rateLimiter = rateLimiter;
        this.documents = documents;
        this.rerank = rerank;
        this.verifier = verifier;
        this.persistence = persistence;
        this.visibility = visibility;
        this.client = client;
    }

    // ---------------------------------------------------------------- 同步问答

    public KnowledgeAnswerView ask(UUID projectId, UUID sessionId,
            KnowledgeQuestionRequest request, UUID userId) {
        String question = validateQuestion(request);
        List<UUID> documentIds = validateDocumentIds(request);
        validateInbound(projectId, sessionId, userId);

        RelayInbound inbound = buildInbound(projectId, sessionId, userId, question, documentIds);
        StringBuilder forwarded = new StringBuilder();
        KnowledgeQaModels.Finalize finalize = client.stream(
                inbound.request(), event -> {
                    if (event.isToken() && event.text() != null) {
                        forwarded.append(event.text());
                    }
                    return true;
                });
        return processFinalize(projectId, sessionId, userId, question, inbound,
                finalize, forwarded.toString(), null);
    }

    // ---------------------------------------------------------------- 流式问答

    public void askStream(UUID projectId, UUID sessionId,
            KnowledgeQuestionRequest request, UUID userId, SseEmitter emitter) {
        String question = validateQuestion(request);
        List<UUID> documentIds = validateDocumentIds(request);
        validateInbound(projectId, sessionId, userId);

        AtomicBoolean terminal = new AtomicBoolean(false);
        AtomicBoolean disconnected = new AtomicBoolean(false);
        AtomicReference<Future<?>> pending = new AtomicReference<>();
        Runnable disconnect = () -> {
            disconnected.set(true);
            if (!terminal.get() && pending.get() != null) {
                pending.get().cancel(true);
            }
        };
        emitter.onCompletion(disconnect);
        emitter.onTimeout(disconnect);
        emitter.onError(ignored -> disconnect.run());

        Future<?> task;
        try {
            task = streamExecutor.submit(() -> runStream(projectId, sessionId, userId, question,
                    documentIds, emitter, terminal, disconnected));
        } catch (RejectedExecutionException busy) {
            sendErrorAndComplete(emitter, terminal, ErrorCode.AI_PROVIDER_UNAVAILABLE);
            return;
        }
        pending.set(task);
        if (disconnected.get() && !terminal.get()) {
            task.cancel(true);
        }
    }

    private void runStream(UUID projectId, UUID sessionId, UUID userId, String question,
            List<UUID> documentIds, SseEmitter emitter, AtomicBoolean terminal,
            AtomicBoolean disconnected) {
        try {
            RelayInbound inbound = buildInbound(projectId, sessionId, userId, question, documentIds);
            StringBuilder forwarded = new StringBuilder();
            ReentrantLock forwardLock = new ReentrantLock();

            KnowledgeQaModels.Finalize finalize = client.stream(inbound.request(), event -> {
                if (disconnected.get()) {
                    return false;
                }
                if (event.isToken() && event.text() != null && !event.text().isEmpty()) {
                    forwardLock.lock();
                    try {
                        forwarded.append(event.text());
                        return sendSse(emitter, KnowledgeStreamEvent.token(event.text()));
                    } finally {
                        forwardLock.unlock();
                    }
                }
                return true;
            });
            if (disconnected.get() || terminal.get()) {
                return;
            }
            KnowledgeAnswerView view = processFinalize(projectId, sessionId, userId, question,
                    inbound, finalize, forwarded.toString(), new StreamSink(emitter, terminal));
            log.info(
                    "Knowledge QA relay stream completed requestId={} sessionId={} messageId={} citations={}",
                    inbound.request().requestId(), sessionId,
                    view == null ? "-" : view.messageId(),
                    view == null || view.citations() == null ? 0 : view.citations().size());
        } catch (BusinessException error) {
            log.warn("Knowledge QA relay stream failed requestId={} error={}",
                    error.getErrorCode(), error.getMessage());
            sendErrorAndComplete(emitter, terminal, error.getErrorCode(), error.getMessage());
        } catch (RuntimeException error) {
            log.warn("Knowledge QA relay stream failed unexpectedly", error);
            sendErrorAndComplete(emitter, terminal, ErrorCode.INTERNAL_ERROR);
        }
    }

    /** finalize 处理的流式出口：发 citations + done（terminal CAS 保证至多一次）。 */
    private record StreamSink(SseEmitter emitter, AtomicBoolean terminal) {
        void citations(List<com.shitulelv.aicollab.knowledge.application.view.KnowledgeCitationView> views) {
            sendSse(emitter, KnowledgeStreamEvent.citations(views));
        }

        void done(UUID messageId) {
            sendDoneAndComplete(emitter, terminal, messageId.toString());
        }
    }

    // ---------------------------------------------------------------- 入站与请求构造

    private void validateInbound(UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        if (repository.findOwn(projectId, sessionId, userId).isEmpty()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_SESSION_NOT_FOUND);
        }
        if (systemConfigService != null && !systemConfigService.isChatEnabled()) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE, "AI 知识问答服务当前未开启");
        }
        rateLimiter.check(userId);
    }

    private RelayInbound buildInbound(UUID projectId, UUID sessionId, UUID userId,
            String question, List<UUID> documentIds) {
        // 可见集合（口径与 RemoteProjectKnowledgeSearch 一致：requested 交项目可见范围）
        Map<UUID, String> visible = new LinkedHashMap<>();
        List<DocumentEntity> entities = documents.listVisibleForRetrieval(
                projectId, documentIds, List.of(),
                new com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria(),
                KnowledgeSearchClient.MAX_VISIBLE_DOCUMENTS + 1);
        for (DocumentEntity document : entities) {
            visible.put(document.getId(), document.getOriginalFilename());
        }
        if (visible.size() > KnowledgeSearchClient.MAX_VISIBLE_DOCUMENTS) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "可见文档数超过上限 " + KnowledgeSearchClient.MAX_VISIBLE_DOCUMENTS);
        }

        List<KnowledgeMessageEntity> recent = repository.recentContext(projectId, sessionId, userId);
        List<KnowledgeQaModels.HistoryEntry> history = new ArrayList<>();
        for (KnowledgeMessageEntity message : recent) {
            history.add(new KnowledgeQaModels.HistoryEntry(
                    message.getRole(), message.getContent(), message.isInsufficientEvidence(),
                    message.getCreatedAt() == null ? null : HISTORY_TIME.format(message.getCreatedAt())));
        }

        Map<String, String> documentNames = new HashMap<>();
        visible.forEach((id, name) -> documentNames.put(id.toString(), name));
        UUID requestId = UUID.randomUUID();
        KnowledgeQaModels.Request request = new KnowledgeQaModels.Request(
                1, requestId, projectId, userId, sessionId, question,
                List.copyOf(documentIds), List.copyOf(visible.keySet()), documentNames,
                TOP_K, List.copyOf(history),
                rerank.hasPublishedBinding(projectId)
                        ? KnowledgeSearchModels.Rerank.CONFIGURED.name()
                        : KnowledgeSearchModels.Rerank.HEURISTIC.name(),
                QA_DEADLINE_MS);
        return new RelayInbound(request, visible);
    }

    private record RelayInbound(KnowledgeQaModels.Request request, Map<UUID, String> visible) {
    }

    // ---------------------------------------------------------------- finalize 处理

    private KnowledgeAnswerView processFinalize(
            UUID projectId, UUID sessionId, UUID userId, String question,
            RelayInbound inbound, KnowledgeQaModels.Finalize finalize,
            String forwardedText, StreamSink sink) {
        // 1. 全文一致性（§8.2.5）：不等 → 丢弃不落库
        if (!forwardedText.equals(finalize.answer())) {
            log.warn("Knowledge QA finalize mismatch requestId={} forwardedLen={} answerLen={}",
                    finalize.requestId(), forwardedText.length(),
                    finalize.answer() == null ? 0 : finalize.answer().length());
            if (sink != null) {
                sendErrorAndComplete(sink.emitter(), sink.terminal(), ErrorCode.INTERNAL_ERROR);
                return null;
            }
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "回答全文一致性校验失败");
        }

        // 2. 引用复核（P2 §6 六条 + visible 集合）
        List<KnowledgeSearchModels.Hit> hits = new ArrayList<>();
        if (finalize.citations() != null) {
            for (KnowledgeQaModels.Finalize.Citation citation : finalize.citations()) {
                if (citation.documentId() == null || citation.chunkNo() == null
                        || citation.rank() == null) {
                    continue;
                }
                hits.add(new KnowledgeSearchModels.Hit(citation.rank(), citation.documentId(),
                        citation.chunkNo(), citation.contentHash(), null, null, null, null,
                        citation.bodySnapshotId(), Map.of(), null, null));
            }
        }
        KnowledgeHitVerifier.Outcome outcome = verifier.verify(
                projectId, inbound.visible().keySet(), hits);

        List<KnowledgeSource> sources = new ArrayList<>();
        for (KnowledgeHitVerifier.VerifiedHit verified : outcome.accepted()) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("chunkNo", verified.chunkNo());
            metadata.put("bodySnapshotId", verified.bodySnapshotId());
            if (verified.pageNumber() != null) {
                metadata.put("pageNumber", verified.pageNumber());
            }
            Double similarity = citationSimilarity(finalize, verified);
            sources.add(new KnowledgeSource(
                    verified.rowId(), verified.documentId(),
                    inbound.visible().getOrDefault(verified.documentId(), ""),
                    verified.heading(), verified.content(), verified.contentHash(),
                    similarity == null ? 0d : similarity, verified.hit().rank() == null
                            ? sources.size() + 1 : verified.hit().rank(),
                    metadata));
        }
        boolean insufficient = finalize.insufficientEvidence() || outcome.insufficientEvidence();
        String answer = insufficient
                ? KnowledgeQaModels.INSUFFICIENT_ANSWER
                : finalize.answer();

        ChatCompletionResult completion = completionOf(finalize);
        KnowledgeQaModels.Finalize.Prompt prompt = finalize.prompt();
        KnowledgeAnswerView[] result = new KnowledgeAnswerView[1];
        Runnable persist = () -> result[0] = persistence.saveExchange(
                projectId, sessionId, userId, question, answer, insufficient, completion,
                sources,
                prompt == null ? null : prompt.key(),
                prompt == null ? null : prompt.version());
        if (sources.isEmpty()) {
            persist.run();
        } else {
            visibility.whileVisible(projectId, sources, persist);
        }
        KnowledgeAnswerView answerView = result[0];
        if (sink != null && answerView != null) {
            sink.citations(answerView.citations());
            sink.done(answerView.messageId());
        }
        return answerView;
    }

    private Double citationSimilarity(KnowledgeQaModels.Finalize finalize,
            KnowledgeHitVerifier.VerifiedHit verified) {
        if (finalize.citations() == null) {
            return null;
        }
        for (KnowledgeQaModels.Finalize.Citation citation : finalize.citations()) {
            if (verified.documentId().equals(citation.documentId())
                    && verified.chunkNo() == citation.chunkNo()) {
                return citation.similarity();
            }
        }
        return null;
    }

    private static ChatCompletionResult completionOf(KnowledgeQaModels.Finalize finalize) {
        KnowledgeQaModels.Finalize.Model model = finalize.model();
        if (model == null) {
            return null;
        }
        return new ChatCompletionResult(
                finalize.answer(),
                model.provider(),
                model.name(),
                model.promptTokens(),
                model.completionTokens(),
                model.latencyMs() == null ? 0L : model.latencyMs());
    }

    // ---------------------------------------------------------------- 校验与 SSE 工具

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

    private static boolean sendSse(SseEmitter emitter, KnowledgeStreamEvent event) {
        try {
            emitter.send(SseEmitter.event().name(event.type()).data(event));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.debug("Failed to send SSE event: {}", e.getMessage());
            return false;
        }
    }

    private static void sendErrorAndComplete(
            SseEmitter emitter, AtomicBoolean terminal, ErrorCode errorCode) {
        sendErrorAndComplete(emitter, terminal, errorCode, errorCode.message());
    }

    private static void sendErrorAndComplete(
            SseEmitter emitter, AtomicBoolean terminal, ErrorCode errorCode, String customMessage) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        sendSse(emitter, KnowledgeStreamEvent.error(errorCode.name(), customMessage));
        emitter.complete();
    }

    private static void sendDoneAndComplete(
            SseEmitter emitter, AtomicBoolean terminal, String messageId) {
        if (!terminal.compareAndSet(false, true)) {
            return;
        }
        sendSse(emitter, KnowledgeStreamEvent.done(messageId));
        emitter.complete();
    }

    @PreDestroy
    void shutdownExecutor() {
        streamExecutor.shutdownNow();
    }
}
