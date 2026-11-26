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
            InternalAiErrorCode code = errorCodeOf(failure);
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

    /**
     * 失败 → 错误码：FORBIDDEN → 403 {@code LEASE_FORBIDDEN}；参数越界 → 503 {@code AI_PROVIDER_PARAMS_INVALID}
     * （retryable=false，契约 R3 §11）；其余 UNAVAILABLE → 503 {@code AI_PROVIDER_UNAVAILABLE}（retryable=true）。
     * 响应体只用错误码的固定 message，不带原因标签、参数值或任何凭据。
     */
    static InternalAiErrorCode errorCodeOf(CredentialLeaseException failure) {
        if (failure.kind() == CredentialLeaseException.Kind.FORBIDDEN) {
            return InternalAiErrorCode.LEASE_FORBIDDEN;
        }
        if (CredentialLeaseException.REASON_PROVIDER_PARAMS_INVALID.equals(failure.reason())) {
            return InternalAiErrorCode.AI_PROVIDER_PARAMS_INVALID;
        }
        return InternalAiErrorCode.AI_PROVIDER_UNAVAILABLE;
    }

    private static CredentialLeaseService.Request parse(LeaseRequestBody body) {
        if (body == null) {
            throw CredentialLeaseException.forbidden("body_invalid");
        }
        // userId/projectId 在 JSON 里可以为 null：只有带 taskId 的系统级 EMBEDDING 租借允许缺省（P2 契约 R1-3），
        // 其余用途缺省时由 CredentialLeaseService 按 uid/pid 不匹配拒绝（403）。
        return new CredentialLeaseService.Request(
                LeasePurpose.parse(body.purpose()).orElse(null),
                optionalUuid(body.userId(), "userId"),
                optionalUuid(body.projectId(), "projectId"),
                optionalUuid(body.taskId(), "taskId"),
                optionalUuid(body.runId(), "runId"));
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
