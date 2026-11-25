package com.shitulelv.aicollab.knowledge.api.controller;

import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeFeedbackService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeQuestionApplicationService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeSessionApplicationService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeStreamQuestionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
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

class KnowledgeControllerTest {
    @Test
    void askStreamSetsAntiBufferingHeadersOnSseResponse() {
        KnowledgeStreamQuestionService streamQuestions = mock(KnowledgeStreamQuestionService.class);
        KnowledgeController controller = new KnowledgeController(
                mock(KnowledgeSessionApplicationService.class),
                mock(KnowledgeQuestionApplicationService.class),
                streamQuestions,
                mock(KnowledgeFeedbackService.class));
        UUID projectId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(userId.toString());
        KnowledgeQuestionRequest request = new KnowledgeQuestionRequest("总结风险", List.of());

        ResponseEntity<SseEmitter> response = controller.askStream(projectId, sessionId, request, jwt);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(response.getHeaders().getCacheControl()).contains("no-cache").contains("no-transform");
        verify(streamQuestions).askStream(eq(projectId), eq(sessionId), eq(request), eq(userId), any(SseEmitter.class));
    }
}
