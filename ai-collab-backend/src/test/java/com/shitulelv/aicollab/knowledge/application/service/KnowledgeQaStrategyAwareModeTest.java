package com.shitulelv.aicollab.knowledge.application.service;

import com.shitulelv.aicollab.infrastructure.ai.AiCallLogService;
import com.shitulelv.aicollab.infrastructure.ai.ChatModelGateway;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeAnswerView;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeCitationValidator;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgePromptAssembler;
import com.shitulelv.aicollab.knowledge.infrastructure.entity.KnowledgeSessionEntity;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import com.shitulelv.aicollab.retrieval.application.RetrievalQaProperties;
import com.shitulelv.aicollab.retrieval.query.EvidenceSufficiencyProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeQaStrategyAwareModeTest {

    private ProjectAccessGuard access;
    private KnowledgeRepository repository;
    private KnowledgeRateLimiter rateLimiter;
    private ProjectKnowledgeRetrieval retrieval;
    private KnowledgeContextBuilder contextBuilder;
    private ChatModelGateway chat;
    private AiCallLogService aiLogs;
    private KnowledgeCitationValidator citationValidator;
    private KnowledgePersistenceService persistence;
    private KnowledgeVisibilityGuard visibility;
    private KnowledgePromptAssembler promptAssembler;

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
        retrieval = mock(ProjectKnowledgeRetrieval.class);
        contextBuilder = mock(KnowledgeContextBuilder.class);
        chat = mock(ChatModelGateway.class);
        aiLogs = mock(AiCallLogService.class);
        citationValidator = mock(KnowledgeCitationValidator.class);
        persistence = mock(KnowledgePersistenceService.class);
        visibility = mock(KnowledgeVisibilityGuard.class);
        promptAssembler = mock(KnowledgePromptAssembler.class);

        questionService = new KnowledgeQuestionApplicationService(
                access, repository, rateLimiter, retrieval, contextBuilder, chat, aiLogs,
                citationValidator, persistence, visibility, promptAssembler);
        streamQuestionService = new KnowledgeStreamQuestionService(
                access, repository, rateLimiter, retrieval, contextBuilder, chat, aiLogs,
                citationValidator, persistence, visibility, promptAssembler);
        RetrievalQaProperties strategyAware = new RetrievalQaProperties("STRATEGY_AWARE");
        ReflectionTestUtils.setField(questionService, "retrievalQaProperties", strategyAware);
        ReflectionTestUtils.setField(streamQuestionService, "retrievalQaProperties", strategyAware);

        when(repository.findOwn(projectId, sessionId, userId))
                .thenReturn(Optional.of(mock(KnowledgeSessionEntity.class)));
        when(repository.recentContext(projectId, sessionId, userId)).thenReturn(List.of());
        when(contextBuilder.buildBalanced(anyList())).thenReturn(new KnowledgeContext(List.of(), ""));
        when(persistence.saveExchange(
                any(), any(), any(), anyString(), anyString(), anyBoolean(), any(), anyList()))
                .thenReturn(new KnowledgeAnswerView(
                        UUID.randomUUID(), "insufficient", true, null, List.of()));
    }

    @Test
    void questionServiceUsesConfiguredStrategyAwareModeAndProbe() {
        when(retrieval.retrieve(any(ProjectKnowledgeRetrieval.Request.class),
                any(EvidenceSufficiencyProbe.class)))
                .thenReturn(new ProjectKnowledgeRetrieval.Result(
                        List.of(), "问题", List.of(),
                        ProjectKnowledgeRetrieval.RETRIEVAL_CORE_RAG_V2_STRATEGY_AWARE));

        questionService.ask(projectId, sessionId,
                new KnowledgeQuestionRequest("项目保留策略是什么？", null), userId);

        ArgumentCaptor<ProjectKnowledgeRetrieval.Request> requestCaptor =
                ArgumentCaptor.forClass(ProjectKnowledgeRetrieval.Request.class);
        verify(retrieval).retrieve(requestCaptor.capture(), any(EvidenceSufficiencyProbe.class));
        assertThat(requestCaptor.getValue().mode())
                .isEqualTo(ProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE);
        verify(retrieval, never()).retrieve(any(ProjectKnowledgeRetrieval.Request.class));
    }

    @Test
    void streamServiceUsesConfiguredStrategyAwareModeAndProbe() {
        when(retrieval.retrieve(any(ProjectKnowledgeRetrieval.Request.class),
                any(EvidenceSufficiencyProbe.class)))
                .thenReturn(new ProjectKnowledgeRetrieval.Result(
                        List.of(), "问题", List.of(),
                        ProjectKnowledgeRetrieval.RETRIEVAL_CORE_RAG_V2_STRATEGY_AWARE));
        SseEmitter emitter = mock(SseEmitter.class);

        streamQuestionService.askStream(projectId, sessionId,
                new KnowledgeQuestionRequest("项目保留策略是什么？", null), userId, emitter);

        ArgumentCaptor<ProjectKnowledgeRetrieval.Request> requestCaptor =
                ArgumentCaptor.forClass(ProjectKnowledgeRetrieval.Request.class);
        verify(retrieval, timeout(3_000))
                .retrieve(requestCaptor.capture(), any(EvidenceSufficiencyProbe.class));
        assertThat(requestCaptor.getValue().mode())
                .isEqualTo(ProjectKnowledgeRetrieval.Mode.STRATEGY_AWARE);
        verify(persistence, timeout(3_000)).saveExchange(
                any(), any(), any(), anyString(), anyString(), anyBoolean(), any(), anyList());
        verify(retrieval, never()).retrieve(any(ProjectKnowledgeRetrieval.Request.class));
    }
}
