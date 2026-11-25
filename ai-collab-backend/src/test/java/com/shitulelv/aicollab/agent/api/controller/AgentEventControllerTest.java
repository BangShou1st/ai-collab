package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.application.runtime.AgentEventStreamService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentEventControllerTest {
    @Test
    void setsAntiBufferingHeadersOnSseResponse() {
        AgentEventStreamService streams = mock(AgentEventStreamService.class);
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        SseEmitter emitter = new SseEmitter(0L);
        when(streams.subscribe(projectId, runId, userId, 0L)).thenReturn(emitter);
        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn(userId.toString());

        ResponseEntity<SseEmitter> response = new AgentEventController(streams)
                .events(projectId, runId, 0L, null, jwt);

        assertThat(response.getBody()).isSameAs(emitter);
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
        assertThat(response.getHeaders().getCacheControl()).contains("no-cache").contains("no-transform");
    }

    @Test
    void lastEventIdCannotMoveCursorBackwards() {
        assertThat(AgentEventController.resolveCursor(12, "8")).isEqualTo(12);
        assertThat(AgentEventController.resolveCursor(12, "15")).isEqualTo(15);
    }

    @Test
    void rejectsNegativeOrMalformedCursor() {
        assertThatThrownBy(() -> AgentEventController.resolveCursor(-1, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AGENT_EVENT_CURSOR_INVALID);
        assertThatThrownBy(() -> AgentEventController.resolveCursor(0, "not-a-number"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.AGENT_EVENT_CURSOR_INVALID);
    }
}
