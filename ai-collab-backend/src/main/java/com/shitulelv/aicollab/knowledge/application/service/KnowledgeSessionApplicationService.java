package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.knowledge.api.dto.CreateKnowledgeSessionRequest;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeCitationView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeMessageView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeSessionDetailView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeSessionView;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeCitationEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeSessionEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class KnowledgeSessionApplicationService {
    private static final String DEFAULT_TITLE = "新会话";
    private final ProjectAccessGuard access;
    private final KnowledgeRepository repository;

    public KnowledgeSessionApplicationService(
            ProjectAccessGuard access, KnowledgeRepository repository) {
        this.access = access;
        this.repository = repository;
    }

    @Transactional
    public KnowledgeSessionView create(
            UUID projectId, CreateKnowledgeSessionRequest request, UUID userId) {
        access.requireMember(projectId, userId);
        String title = normalizeTitle(request == null ? null : request.title());
        KnowledgeSessionEntity entity = new KnowledgeSessionEntity();
        entity.setId(UUID.randomUUID());
        entity.setProjectId(projectId);
        entity.setUserId(userId);
        entity.setTitle(title);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        repository.createSession(entity);
        return KnowledgeSessionView.from(entity);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeSessionView> list(UUID projectId, UUID userId) {
        access.requireMember(projectId, userId);
        return repository.listOwn(projectId, userId).stream()
                .map(KnowledgeSessionView::from).toList();
    }

    @Transactional(readOnly = true)
    public KnowledgeSessionDetailView detail(UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        KnowledgeSessionEntity session = requireOwn(projectId, sessionId, userId);
        var messageEntities = repository.listMessages(projectId, sessionId, userId);
        Map<UUID, List<KnowledgeCitationEntity>> citations = repository
                .listCitations(projectId, sessionId, userId).stream()
                .collect(Collectors.groupingBy(KnowledgeCitationEntity::getMessageId));
        List<KnowledgeMessageView> messages = messageEntities.stream()
                .map(message -> new KnowledgeMessageView(
                        message.getId(),
                        message.getRole(),
                        message.getContent(),
                        message.isInsufficientEvidence(),
                        message.getModelName(),
                        citations.getOrDefault(message.getId(), List.of()).stream()
                                .map(this::citationView).toList(),
                        message.getCreatedAt()))
                .toList();
        return new KnowledgeSessionDetailView(KnowledgeSessionView.from(session), messages);
    }

    @Transactional
    public void delete(UUID projectId, UUID sessionId, UUID userId) {
        access.requireMember(projectId, userId);
        if (!repository.deleteOwn(projectId, sessionId, userId)) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_SESSION_NOT_FOUND);
        }
    }

    private KnowledgeSessionEntity requireOwn(UUID projectId, UUID sessionId, UUID userId) {
        return repository.findOwn(projectId, sessionId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_SESSION_NOT_FOUND));
    }

    private static String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.strip();
        if (normalized.isBlank()) return DEFAULT_TITLE;
        if (normalized.codePointCount(0, normalized.length()) > 120) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "会话标题不能超过 120 个字符");
        }
        return normalized;
    }

    private KnowledgeCitationView citationView(KnowledgeCitationEntity citation) {
        return new KnowledgeCitationView(
                citation.getDocumentId(), citation.getFilename(), citation.getChunkId(),
                citation.getHeading(), citation.getQuote(), citation.getSimilarity(),
                citation.getRank());
    }
}
