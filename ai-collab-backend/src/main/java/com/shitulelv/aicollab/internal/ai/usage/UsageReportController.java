package com.shitulelv.aicollab.internal.ai.usage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.shitulelv.aicollab.internal.ai.InternalAiClaims;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorCode;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code POST /internal/ai/usage}（契约 §3、R1 §9.5）。
 *
 * <p>整批格式错误（非 JSON、缺 items、条数 0 或超过 100、某条不是对象）→ 400；否则整批 200，
 * 响应 {@code {"results":[{callId, accepted, reason?}]}}，每条上报都有一条结果。</p>
 */
@RestController
public class UsageReportController {

    private static final Logger log = LoggerFactory.getLogger(UsageReportController.class);

    private final UsageReportService service;

    public UsageReportController(UsageReportService service) {
        this.service = service;
    }

    @PostMapping("/internal/ai/usage")
    public ResponseEntity<Object> report(@RequestBody(required = false) UsageRequestBody body,
            HttpServletRequest request) {
        if (body == null || body.items() == null || body.items().isEmpty()
                || body.items().size() > UsageReportService.MAX_BATCH
                || body.items().stream().anyMatch(item -> item == null)) {
            log.warn("usage report rejected: reason=batch_invalid");
            return badRequest("items 必须是 1–100 条对象");
        }
        List<UsageReportService.Result> results = service.report(
                body.items().stream().map(UsageItemBody::toItem).toList(), InternalAiClaims.from(request));
        long rejected = results.stream().filter(result -> !result.accepted()).count();
        if (rejected > 0) {
            log.warn("usage report partially rejected: items={} rejected={}", results.size(), rejected);
        }
        return ResponseEntity.ok(new UsageResponse(results.stream()
                .map(result -> new UsageResult(result.callId(), result.accepted(), result.reason()))
                .toList()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> unreadable() {
        log.warn("usage report rejected: reason=body_unreadable");
        return badRequest("请求体不是合法 JSON");
    }

    private static ResponseEntity<Object> badRequest(String message) {
        return ResponseEntity.status(400).body(InternalAiErrorResponse.of(InternalAiErrorCode.INVALID_REQUEST, message));
    }

    /** 请求体（camelCase；R1 §9.5：没有 callerType 字段）。 */
    record UsageRequestBody(List<UsageItemBody> items) {
    }

    record UsageItemBody(String callId, String leaseId, String purpose, String model, Long inputTokens,
            Long outputTokens, Long latencyMs, String status, String errorCode) {

        UsageReportService.Item toItem() {
            return new UsageReportService.Item(callId, leaseId, purpose, model, inputTokens, outputTokens,
                    latencyMs, status, errorCode);
        }
    }

    record UsageResponse(List<UsageResult> results) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record UsageResult(String callId, boolean accepted, String reason) {
    }
}
