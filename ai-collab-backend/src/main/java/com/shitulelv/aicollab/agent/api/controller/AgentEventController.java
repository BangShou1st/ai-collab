package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.application.runtime.AgentEventStreamService;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent")
public class AgentEventController {
    private final AgentEventStreamService streams;

    public AgentEventController(AgentEventStreamService streams) {
        this.streams = streams;
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            @AuthenticationPrincipal Jwt jwt) {
        return streams.subscribe(
                projectId, runId, userId(jwt), resolveCursor(afterSequence, lastEventId));
    }

    static long resolveCursor(long afterSequence, String lastEventId) {
        if (afterSequence < 0) throw invalidCursor();
        if (lastEventId == null || lastEventId.isBlank()) return afterSequence;
        try {
            long header = Long.parseLong(lastEventId);
            if (header < 0) throw invalidCursor();
            return Math.max(afterSequence, header);
        } catch (NumberFormatException failure) {
            throw invalidCursor();
        }
    }

    private static BusinessException invalidCursor() {
        return new BusinessException(ErrorCode.AGENT_EVENT_CURSOR_INVALID);
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
