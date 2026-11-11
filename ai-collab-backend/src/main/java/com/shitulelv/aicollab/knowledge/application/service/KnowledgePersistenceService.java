package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeAnswerView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeCitationView;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeMessageEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeSessionEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class KnowledgePersistenceService {
    private final KnowledgeRepository repository;

    public KnowledgePersistenceService(KnowledgeRepository repository) {
        this.repository = repository;
    }

    /**
     * 未调用模型的一轮（例如证据不足直接返回统一拒答文案）：不产生 Prompt 身份。
     *
     * <p>Prompt 身份为 {@code null} 在此语义下是<b>正确值</b>，不是缺省填充：这一轮没有任何
     * 模型请求，也就没有"用了哪个 Prompt"。凡是走了模型的轮次必须用下面带 Prompt 身份的重载。</p>
     */
    @Transactional
    public KnowledgeAnswerView saveExchange(
            UUID projectId,
            UUID sessionId,
            UUID userId,
            String question,
            String answer,
            boolean insufficientEvidence,
            ChatCompletionResult completion,
            List<KnowledgeSource> citedSources) {
        return saveExchange(projectId, sessionId, userId, question, answer, insufficientEvidence,
                completion, citedSources, null, null);
    }

    /**
     * 保存一轮问答交换，并把本次回答所用的 PromptPack 身份写到助手消息上（V90）。
     *
     * <p>{@code promptKey}/{@code promptVersion} 只写在助手消息；用户消息永远为 {@code null}。
     * 两者按"要么都有、要么都没有"使用：缺一个视为调用方错误。</p>
     */
    @Transactional
    public KnowledgeAnswerView saveExchange(
            UUID projectId,
            UUID sessionId,
            UUID userId,
            String question,
            String answer,
            boolean insufficientEvidence,
            ChatCompletionResult completion,
            List<KnowledgeSource> citedSources,
            String promptKey,
            String promptVersion) {
        if ((promptKey == null) != (promptVersion == null)) {
            throw new IllegalArgumentException("promptKey 与 promptVersion 必须同时提供或同时为空");
        }
        KnowledgeSessionEntity session = repository.lockOwn(projectId, sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_SESSION_NOT_FOUND));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime userCreatedAt = later(now, session.getUpdatedAt().plusNanos(1_000));
        OffsetDateTime assistantCreatedAt = userCreatedAt.plusNanos(1_000);

        KnowledgeMessageEntity userMessage = message(
                sessionId, "USER", question, false, null, userCreatedAt);
        KnowledgeMessageEntity assistantMessage = message(
                sessionId, "ASSISTANT", answer, insufficientEvidence, completion, assistantCreatedAt);
        assistantMessage.setPromptKey(promptKey);
        assistantMessage.setPromptVersion(promptVersion);
        repository.insertMessage(userMessage);
        repository.insertMessage(assistantMessage);

        List<KnowledgeCitationView> citationViews = new ArrayList<>();
        for (KnowledgeSource source : citedSources) {
            String quote = truncateCodePoints(source.content(), 1000);
            repository.insertCitation(
                    projectId, assistantMessage.getId(), source.chunkId(), source.documentId(),
                    source.rank(), source.similarity(), quote);
            citationViews.add(new KnowledgeCitationView(
                    source.documentId(), source.originalFilename(), source.chunkId(),
                    source.heading(), quote, source.similarity(), source.rank(),
                    source.pageNumber()));
        }
        if (!repository.touch(projectId, sessionId, userId, assistantCreatedAt)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_SESSION_NOT_FOUND);
        }
        return new KnowledgeAnswerView(
                assistantMessage.getId(),
                answer,
                insufficientEvidence,
                completion == null ? null : completion.model(),
                List.copyOf(citationViews));
    }

    private static KnowledgeMessageEntity message(
            UUID sessionId,
            String role,
            String content,
            boolean insufficient,
            ChatCompletionResult completion,
            OffsetDateTime createdAt) {
        KnowledgeMessageEntity entity = new KnowledgeMessageEntity();
        entity.setId(UUID.randomUUID());
        entity.setSessionId(sessionId);
        entity.setRole(role);
        entity.setContent(content);
        entity.setInsufficientEvidence(insufficient);
        if (completion != null) {
            entity.setModelProvider(completion.provider());
            entity.setModelName(completion.model());
            entity.setLatencyMs(Math.toIntExact(Math.min(Integer.MAX_VALUE, completion.latencyMs())));
            entity.setPromptTokens(completion.promptTokens());
            entity.setCompletionTokens(completion.completionTokens());
        }
        entity.setCreatedAt(createdAt);
        return entity;
    }

    private static OffsetDateTime later(OffsetDateTime first, OffsetDateTime second) {
        return first.isAfter(second) ? first : second;
    }

    private static String truncateCodePoints(String value, int maximum) {
        int count = value.codePointCount(0, value.length());
        return count <= maximum ? value : value.substring(0, value.offsetByCodePoints(0, maximum));
    }
}
