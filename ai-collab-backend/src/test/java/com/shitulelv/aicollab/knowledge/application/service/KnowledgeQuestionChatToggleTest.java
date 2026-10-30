package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.infrastructure.ai.AiCallLogService;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeCitationValidator;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeSessionEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.system.application.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 验证 AI Chat（知识问答）动态启停机制：
 * 禁用时拦截问答提问，启用后立即放行。
 */
class KnowledgeQuestionChatToggleTest {
    private ProjectAccessGuard access;
    private KnowledgeRepository repository;
    private KnowledgeRateLimiter rateLimiter;
    private DocumentSearchService search;
    private KnowledgeContextBuilder contextBuilder;
    private ChatModelGateway chat;
    private AiCallLogService aiLogs;
    private KnowledgeCitationValidator citationValidator;
    private KnowledgePersistenceService persistence;
    private KnowledgeVisibilityGuard visibility;
    private SystemConfigService systemConfigService;

    private KnowledgeQuestionApplicationService questionService;
    private KnowledgeStreamQuestionService streamQuestionService;

    private final UUID projectId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        access = mock(ProjectAccessGuard.class);
        repository = mock(KnowledgeRepository.class);
        rateLimiter = mock(KnowledgeRateLimiter.class);
        search = mock(DocumentSearchService.class);
        contextBuilder = mock(KnowledgeContextBuilder.class);
        chat = mock(ChatModelGateway.class);
        aiLogs = mock(AiCallLogService.class);
        citationValidator = mock(KnowledgeCitationValidator.class);
        persistence = mock(KnowledgePersistenceService.class);
        visibility = mock(KnowledgeVisibilityGuard.class);
        systemConfigService = mock(SystemConfigService.class);

        questionService = new KnowledgeQuestionApplicationService(
                access, repository, rateLimiter, search, contextBuilder, chat, aiLogs,
                citationValidator, persistence, visibility
        );
        ReflectionTestUtils.setField(questionService, "systemConfigService", systemConfigService);

        streamQuestionService = new KnowledgeStreamQuestionService(
                access, repository, rateLimiter, search, contextBuilder, chat, aiLogs,
                citationValidator, persistence, visibility
        );
        ReflectionTestUtils.setField(streamQuestionService, "systemConfigService", systemConfigService);

        when(repository.findOwn(projectId, sessionId, userId))
                .thenReturn(Optional.of(mock(KnowledgeSessionEntity.class)));
    }

    @Test
    void askRejectsWhenChatDisabled() {
        when(systemConfigService.isChatEnabled()).thenReturn(false);

        KnowledgeQuestionRequest request = new KnowledgeQuestionRequest("什么是 AI Collab？", null);

        assertThatThrownBy(() -> questionService.ask(projectId, sessionId, request, userId))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                    assertThat(be.getMessage()).contains("AI 知识问答服务当前未开启");
                });
    }

    @Test
    void streamAskRejectsWhenChatDisabled() {
        when(systemConfigService.isChatEnabled()).thenReturn(false);

        KnowledgeQuestionRequest request = new KnowledgeQuestionRequest("什么是 AI Collab？", null);
        SseEmitter emitter = mock(SseEmitter.class);

        streamQuestionService.askStream(projectId, sessionId, request, userId, emitter);

        // Verify emitter completed due to error
        verify(emitter).complete();
    }
}
