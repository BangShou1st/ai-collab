package com.shitulelv.aicollab.internal.ai.credential;

import com.shitulelv.aicollab.internal.ai.InternalAiClaims;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorCode;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * {@code POST /internal/ai/credentials/lease}（契约 §2、R1 §9.4）。
 *
 * <p>响应固定 {@code Cache-Control: no-store}。请求体按 camelCase 读取；{@code taskId/runId}
 * 两个键总会出现，值可以为 null。任何字段格式问题都按 403 {@code LEASE_FORBIDDEN} 处理（不区分原因）。</p>
 */
@RestController
public class CredentialLeaseController {

    private static final Logger log = LoggerFactory.getLogger(CredentialLeaseController.class);

    private final CredentialLeaseService service;

    public CredentialLeaseController(CredentialLeaseService service) {
        this.service = service;
    }

    @PostMapping("/internal/ai/credentials/lease")
    public ResponseEntity<Object> lease(@RequestBody(required = false) LeaseRequestBody body,
            HttpServletRequest httpRequest) {
        try {
            CredentialLeaseService.Request request = parse(body);
            CredentialLeaseService.Grant grant = service.lease(request, InternalAiClaims.from(httpRequest));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                    .body(new LeaseResponse(grant.leaseId(), grant.protocol(), grant.providerType(),
                            grant.baseUrl(), grant.apiPath(), grant.model(), grant.apiKey(), grant.params(),
                            grant.capabilities(),
                            grant.expiresAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
        } catch (CredentialLeaseException failure) {
            log.warn("credential lease rejected: kind={} reason={}", failure.kind(), failure.reason());
            InternalAiErrorCode code = failure.kind() == CredentialLeaseException.Kind.FORBIDDEN
                    ? InternalAiErrorCode.LEASE_FORBIDDEN : InternalAiErrorCode.AI_PROVIDER_UNAVAILABLE;
            return ResponseEntity.status(code.httpStatus()).cacheControl(CacheControl.noStore())
                    .body(InternalAiErrorResponse.of(code));
        }
    }

    /** 请求体不是合法 JSON：同样按 403 处理（契约 §2.2 不区分原因）。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> unreadable() {
        log.warn("credential lease rejected: kind=FORBIDDEN reason=body_unreadable");
        return ResponseEntity.status(InternalAiErrorCode.LEASE_FORBIDDEN.httpStatus())
                .cacheControl(CacheControl.noStore())
                .body(InternalAiErrorResponse.of(InternalAiErrorCode.LEASE_FORBIDDEN));
    }

    private static CredentialLeaseService.Request parse(LeaseRequestBody body) {
        if (body == null) {
            throw CredentialLeaseException.forbidden("body_invalid");
        }
        return new CredentialLeaseService.Request(
                LeasePurpose.parse(body.purpose()).orElse(null),
                requiredUuid(body.userId(), "userId"),
                requiredUuid(body.projectId(), "projectId"),
                optionalUuid(body.taskId(), "taskId"),
                optionalUuid(body.runId(), "runId"));
    }

    private static UUID requiredUuid(String raw, String field) {
        UUID value = optionalUuid(raw, field);
        if (value == null) {
            throw CredentialLeaseException.forbidden(field + "_missing");
        }
        return value;
    }

    private static UUID optionalUuid(String raw, String field) {
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException invalid) {
            throw CredentialLeaseException.forbidden(field + "_invalid");
        }
    }

    /** 请求体（camelCase；R1 §9.4：taskId/runId 两个键总会出现，值可为 null）。 */
    record LeaseRequestBody(String purpose, String userId, String projectId, String taskId, String runId) {
    }

    /** 响应体（camelCase）。{@link #toString()} 不含 apiKey 明文。 */
    record LeaseResponse(
            UUID leaseId,
            String protocol,
            String providerType,
            String baseUrl,
            String apiPath,
            String model,
            String apiKey,
            java.util.Map<String, Object> params,
            java.util.List<String> capabilities,
            String expiresAt) {

        @Override
        public String toString() {
            return "LeaseResponse[leaseId=" + leaseId + ", model=" + model + ", apiKey=***]";
        }
    }
}
