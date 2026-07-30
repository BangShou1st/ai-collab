package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeFeedbackView;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeFeedbackEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.mapper.KnowledgeFeedbackMapper;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class KnowledgeFeedbackService {
    private final ProjectAccessGuard access;
    private final KnowledgeFeedbackMapper feedbackMapper;
    private final JdbcTemplate jdbc;

    public KnowledgeFeedbackService(
            ProjectAccessGuard access,
            KnowledgeFeedbackMapper feedbackMapper,
            JdbcTemplate jdbc) {
        this.access = access;
        this.feedbackMapper = feedbackMapper;
        this.jdbc = jdbc;
    }

    @Transactional
    public KnowledgeFeedbackView submit(
            UUID projectId, UUID messageId, UUID userId, boolean helpful) {
        access.requireMember(projectId, userId);
        requireOwnAssistantMessage(projectId, messageId, userId);
        feedbackMapper.upsert(messageId, userId, helpful);
        return getFeedback(projectId, messageId, userId);
    }

    @Transactional
    public KnowledgeFeedbackView remove(UUID projectId, UUID messageId, UUID userId) {
        access.requireMember(projectId, userId);
        requireOwnAssistantMessage(projectId, messageId, userId);
        feedbackMapper.deleteByMessageAndUser(messageId, userId);
        return getFeedback(projectId, messageId, userId);
    }

    public KnowledgeFeedbackView getFeedback(
            UUID projectId, UUID messageId, UUID userId) {
        access.requireMember(projectId, userId);
        requireOwnAssistantMessage(projectId, messageId, userId);
        KnowledgeFeedbackEntity entity =
                feedbackMapper.findByMessageAndUser(messageId, userId);
        Boolean myFeedback = entity == null ? null : entity.isHelpful();
        int helpfulCount = feedbackMapper.countHelpful(messageId);
        int unhelpfulCount = feedbackMapper.countUnhelpful(messageId);
        return new KnowledgeFeedbackView(myFeedback, helpfulCount, unhelpfulCount);
    }

    private void requireOwnAssistantMessage(UUID projectId, UUID messageId, UUID userId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_message m " +
                "JOIN knowledge_session s ON s.id = m.session_id " +
                "WHERE m.id = ? AND s.project_id = ? AND s.user_id = ? AND m.role = 'ASSISTANT'",
                Integer.class, messageId, projectId, userId);
        if (count == null || count == 0) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_SESSION_NOT_FOUND, "消息不存在");
        }
    }
}
