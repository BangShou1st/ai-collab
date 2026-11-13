package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.api.dto.ResolveAgentApprovalRequest;
import com.shitulelv.aicollab.agent.api.dto.AgentApprovalResponse;
import com.shitulelv.aicollab.agent.api.dto.AgentApprovalInboxResponse;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalInboxView;
import com.shitulelv.aicollab.common.api.ApiResponse;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent/approvals")
public class AgentApprovalController {
    private final AgentApprovalService approvals;

    public AgentApprovalController(AgentApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<AgentApprovalInboxResponse>> list(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime updatedSince,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            @AuthenticationPrincipal Jwt jwt) {
        AgentApprovalInboxView inbox = approvals.inbox(projectId, userId(jwt), status, updatedSince);
        List<AgentApprovalResponse> items = inbox.items().stream()
                .map(AgentApprovalResponse::from)
                .toList();
        String etag = etag(status, updatedSince, inbox);
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.noCache())
                    .build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .body(ApiResponse.success(new AgentApprovalInboxResponse(
                        items, inbox.serverTime(), inbox.maxUpdatedAt())));
    }

    @PostMapping("/{approvalId}/approve")
    public ApiResponse<AgentApprovalResponse> approve(
            @PathVariable UUID projectId, @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody ResolveAgentApprovalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(AgentApprovalResponse.from(approvals.approve(
                projectId, approvalId, userId(jwt), request.nonce(), idempotencyKey, request.expectedRevision())));
    }

    @PostMapping("/{approvalId}/reject")
    public ApiResponse<AgentApprovalResponse> reject(
            @PathVariable UUID projectId, @PathVariable UUID approvalId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @Valid @RequestBody ResolveAgentApprovalRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ApiResponse.success(AgentApprovalResponse.from(approvals.reject(
                projectId, approvalId, userId(jwt), request.nonce(),
                idempotencyKey, request.reason(), request.expectedRevision())));
    }

    private static UUID userId(Jwt jwt) {
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AUTH_UNAUTHORIZED);
        }
    }

    private static String etag(
            String status, OffsetDateTime updatedSince, AgentApprovalInboxView inbox) {
        String material = (status == null ? "" : status) + "|"
                + (updatedSince == null ? "" : updatedSince.toInstant()) + "|"
                + (inbox.maxUpdatedAt() == null ? "empty" : inbox.maxUpdatedAt().toInstant()) + "|"
                + inbox.items().stream()
                .map(item -> item.id() + ":" + item.version() + ":" + item.revision())
                .sorted()
                .reduce("", (left, right) -> left + "," + right);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest) + "\"";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static boolean matches(String ifNoneMatch, String current) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
        for (String candidate : ifNoneMatch.split(",")) {
            String value = candidate.trim();
            if ("*".equals(value) || current.equals(value)
                    || ("W/" + current).equals(value)) {
                return true;
            }
        }
        return false;
    }
}
