package com.shitulelv.aicollab.internal.ai.index;

import com.shitulelv.aicollab.internal.ai.InternalAiClaims;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorCode;
import com.shitulelv.aicollab.internal.ai.InternalAiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * {@code GET /internal/ai/documents/{docId}/body?cursor=&limit=}（P2 契约 §4.3）。
 *
 * <p>scope {@code index:document:{docId}} 由 {@code InternalAiTokenFilter} 校验；这里只校验令牌 pid 与
 * 文档项目一致。失败信封与其他内部端点一致，日志只记文档 id 与原因类别，不记正文。</p>
 */
@RestController
public class DocumentBodyController {

    private static final Logger log = LoggerFactory.getLogger(DocumentBodyController.class);

    private final DocumentBodyReadService service;

    public DocumentBodyController(DocumentBodyReadService service) {
        this.service = service;
    }

    @GetMapping("/internal/ai/documents/{docId}/body")
    public ResponseEntity<Object> body(@PathVariable("docId") String docId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        UUID documentId;
        try {
            documentId = UUID.fromString(docId);
        } catch (IllegalArgumentException invalid) {
            return error(InternalAiErrorCode.NOT_FOUND);
        }
        try {
            DocumentBodyReadService.Page page = service.read(documentId,
                    InternalAiClaims.from(request).projectId(), cursor, limit);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(page);
        } catch (DocumentBodyReadService.BodyReadException failure) {
            log.warn("document body read rejected: documentId={} kind={}", documentId, failure.kind());
            return error(switch (failure.kind()) {
                case NOT_FOUND -> InternalAiErrorCode.NOT_FOUND;
                case SNAPSHOT_STALE -> InternalAiErrorCode.INDEX_SNAPSHOT_STALE;
                case INVALID -> InternalAiErrorCode.INVALID_REQUEST;
            });
        }
    }

    private static ResponseEntity<Object> error(InternalAiErrorCode code) {
        return ResponseEntity.status(code.httpStatus()).cacheControl(CacheControl.noStore())
                .body(InternalAiErrorResponse.of(code));
    }
}
