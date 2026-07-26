package com.shitulelv.aicollab.knowledge.infrastructure.repository;

import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeCitationEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeMessageEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeSessionEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.mapper.KnowledgeCitationMapper;
import com.shitulelv.aicollab.knowledge.infrastructure.mapper.KnowledgeMessageMapper;
import com.shitulelv.aicollab.knowledge.infrastructure.mapper.KnowledgeSessionMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class KnowledgeRepository {
    private final KnowledgeSessionMapper sessions;
    private final KnowledgeMessageMapper messages;
    private final KnowledgeCitationMapper citations;

    public KnowledgeRepository(
            KnowledgeSessionMapper sessions,
            KnowledgeMessageMapper messages,
            KnowledgeCitationMapper citations) {
        this.sessions = sessions;
        this.messages = messages;
        this.citations = citations;
    }

    public void createSession(KnowledgeSessionEntity entity) {
        if (sessions.insert(entity) != 1) {
            throw new IllegalStateException("问答会话保存失败");
        }
    }
    public List<KnowledgeSessionEntity> listOwn(UUID projectId, UUID userId) {
        return sessions.listOwn(projectId, userId);
    }
    public Optional<KnowledgeSessionEntity> findOwn(UUID projectId, UUID sessionId, UUID userId) {
        return sessions.findOwn(projectId, sessionId, userId);
    }
    public Optional<KnowledgeSessionEntity> lockOwn(UUID projectId, UUID sessionId, UUID userId) {
        return sessions.lockOwn(projectId, sessionId, userId);
    }
    public boolean deleteOwn(UUID projectId, UUID sessionId, UUID userId) {
        return sessions.deleteOwn(projectId, sessionId, userId) == 1;
    }
    public List<KnowledgeMessageEntity> listMessages(UUID projectId, UUID sessionId, UUID userId) {
        return messages.listOwn(projectId, sessionId, userId);
    }
    public List<KnowledgeCitationEntity> listCitations(UUID projectId, UUID sessionId, UUID userId) {
        return citations.listOwn(projectId, sessionId, userId);
    }
    public void insertMessage(KnowledgeMessageEntity entity) {
        if (messages.insert(entity) != 1) throw new IllegalStateException("问答消息保存失败");
    }
    public void insertCitation(
            UUID projectId, UUID messageId, UUID chunkId, UUID documentId,
            int rank, double similarity, String quote) {
        if (citations.insertScoped(
                UUID.randomUUID(), projectId, messageId, chunkId, documentId,
                rank, similarity, quote) != 1) {
            throw new IllegalStateException("问答引用保存失败");
        }
    }
    public boolean touch(UUID projectId, UUID sessionId, UUID userId, OffsetDateTime updatedAt) {
        return sessions.touch(projectId, sessionId, userId, updatedAt) == 1;
    }
}
