package com.shitulelv.aicollab.knowledge.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.knowledge.api.dto.CreateKnowledgeSessionRequest;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeFeedbackService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeQuestionApplicationService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeSessionApplicationService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeStreamQuestionService;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeAnswerView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeFeedbackView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeSessionDetailView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeSessionView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/knowledge/sessions")
public class KnowledgeController {
    private final KnowledgeSessionApplicationService sessions;
    private final KnowledgeQuestionApplicationService questions;
    private final KnowledgeStreamQuestionService streamQuestions;
    private final KnowledgeFeedbackService feedback;

    public KnowledgeController(
            KnowledgeSessionApplicationService sessions,
            KnowledgeQuestionApplicationService questions,
            KnowledgeStreamQuestionService streamQuestions,
            KnowledgeFeedbackService feedback) {
        this.sessions = sessions;
        this.questions = questions;
        this.streamQuestions = streamQuestions;
        this.feedback = feedback;
    }

    @GetMapping
    public ApiResponse<List<KnowledgeSessionView>> list(
            @PathVariable UUID projectId, @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(sessions.list(projectId, userId(jwt)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<KnowledgeSessionView>> create(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(201)
                .body(ApiResponse.success(sessions.create(projectId, null, userId(jwt))));
    }

    @GetMapping("/{sessionId}")
    public ApiResponse<KnowledgeSessionDetailView> detail(
            @PathVariable UUID projectId,
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(sessions.detail(projectId, sessionId, userId(jwt)));
    }

    @PatchMapping("/{sessionId}")
    public ApiResponse<KnowledgeSessionView> rename(
            @PathVariable UUID projectId,
            @PathVariable UUID sessionId,
            @RequestBody CreateKnowledgeSessionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(sessions.rename(projectId, sessionId, request, userId(jwt)));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID projectId,
            @PathVariable UUID sessionId,
            @AuthenticationPrincipal Jwt jwt) {
        sessions.delete(projectId, sessionId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{sessionId}/questions")
    public ApiResponse<KnowledgeAnswerView> ask(
            @PathVariable UUID projectId,
            @PathVariable UUID sessionId,
            @RequestBody KnowledgeQuestionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(questions.ask(projectId, sessionId, request, userId(jwt)));
    }

    @PostMapping(value = "/{sessionId}/questions/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(
            @PathVariable UUID projectId,
            @PathVariable UUID sessionId,
            @RequestBody KnowledgeQuestionRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        SseEmitter emitter = new SseEmitter(300_000L);
        streamQuestions.askStream(projectId, sessionId, request, userId(jwt), emitter);
        return emitter;
    }

    @PostMapping("/messages/{messageId}/feedback")
    public ApiResponse<KnowledgeFeedbackView> submitFeedback(
            @PathVariable UUID projectId,
            @PathVariable UUID messageId,
            @RequestBody java.util.Map<String, Boolean> body,
            @AuthenticationPrincipal Jwt jwt) {
        Boolean helpful = body.get("helpful");
        if (helpful == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "helpful 字段不能为空");
        }
        return ApiResponse.success(feedback.submit(projectId, messageId, userId(jwt), helpful));
    }

    @DeleteMapping("/messages/{messageId}/feedback")
    public ResponseEntity<Void> removeFeedback(
            @PathVariable UUID projectId,
            @PathVariable UUID messageId,
            @AuthenticationPrincipal Jwt jwt) {
        feedback.remove(projectId, messageId, userId(jwt));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/messages/{messageId}/feedback")
    public ApiResponse<KnowledgeFeedbackView> getFeedback(
            @PathVariable UUID projectId,
            @PathVariable UUID messageId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(feedback.getFeedback(projectId, messageId, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
