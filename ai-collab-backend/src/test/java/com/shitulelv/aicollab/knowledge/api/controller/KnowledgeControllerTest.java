package com.shitulelv.aicollab.knowledge.api.controller;

import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeFeedbackService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeQaRelayService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeSessionApplicationService;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeAnswerView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P3 后 KnowledgeController 的问答端点走 {@code KnowledgeQaRelayService}（URL 与事件形状不变）。 */
class KnowledgeControllerTest {

    private final UUID projectId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    private final KnowledgeSessionApplicationService sessions = mock(KnowledgeSessionApplicationService.class);
    private final KnowledgeQaRelayService qaRelay = mock(KnowledgeQaRelayService.class);
    private final KnowledgeFeedbackService feedback = mock(KnowledgeFeedbackService.class);

    private KnowledgeController controller;
    private Jwt jwt;

    @BeforeEach
    void setUp() {
        controller = new KnowledgeController(sessions, qaRelay, feedback);
        jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(userId.toString());
    }

    @Test
    void askRoutesThroughRelay() {
        KnowledgeAnswerView view = new KnowledgeAnswerView(UUID.randomUUID(), "回答", false, "m", List.of());
        when(qaRelay.ask(eq(projectId), eq(sessionId), any(), eq(userId))).thenReturn(view);

        assertThat(controller.ask(projectId, sessionId,
                new KnowledgeQuestionRequest("问题", List.of()), jwt).data()).isSameAs(view);
    }

    @Test
    void askStreamRoutesThroughRelayWithSameUrlContract() {
        org.springframework.http.ResponseEntity<SseEmitter> response = controller.askStream(projectId, sessionId,
                new KnowledgeQuestionRequest("问题", List.of()), jwt);

        assertThat(response).isNotNull();
        assertThat(response.getBody()).isNotNull();
        ArgumentCaptor<SseEmitter> captured = ArgumentCaptor.forClass(SseEmitter.class);
        verify(qaRelay).askStream(eq(projectId), eq(sessionId), any(), eq(userId), captured.capture());
        assertThat(captured.getValue()).isSameAs(response.getBody());
    }
}
