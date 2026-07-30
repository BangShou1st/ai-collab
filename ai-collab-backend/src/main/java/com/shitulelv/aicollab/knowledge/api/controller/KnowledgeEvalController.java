package com.shitulelv.aicollab.knowledge.api.controller;

import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeEvalRequest;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeEvalService;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeEvalRunDetailView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeEvalRunView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/knowledge/eval")
public class KnowledgeEvalController {
    private final KnowledgeEvalService eval;

    public KnowledgeEvalController(KnowledgeEvalService eval) {
        this.eval = eval;
    }

    @PostMapping("/run")
    public ResponseEntity<ApiResponse<Map<String, String>>> startRun(
            @PathVariable UUID projectId,
            @RequestBody KnowledgeEvalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        UUID runId = eval.startEval(projectId, userId(jwt), request);
        return ResponseEntity.accepted()
                .body(ApiResponse.success(Map.of("runId", runId.toString())));
    }

    @GetMapping("/runs")
    public ApiResponse<List<KnowledgeEvalRunView>> listRuns(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(eval.listRuns(projectId, userId(jwt)));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<KnowledgeEvalRunDetailView> getRun(
            @PathVariable UUID projectId,
            @PathVariable UUID runId,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(eval.getRun(projectId, runId, userId(jwt)));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }
}
