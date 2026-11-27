package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.parser.DocumentParser;
import com.shitulelv.aicollab.document.infrastructure.parser.ParsedDocument;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * R3-2 存量正文补齐前置任务：
 * 对 {@code status='READY'} 但缺少 {@code document_body} 的存量文档补写 {@code document_body(_chunk)}，
 * 绝不写 {@code document_chunk}，绝不触发向量化。
 */
@Service
public class LegacyDocumentBodyBackfillService {
    public static final String LEGACY_SOURCE_MISSING_ERROR = "legacy_source_missing";
    static final int DEFAULT_BATCH_SIZE = 200;
    static final int MAX_SOURCE_BYTES = 20 * 1024 * 1024;

    private static final Logger log = LoggerFactory.getLogger(LegacyDocumentBodyBackfillService.class);

    private static final String STATS_SQL = """
            SELECT count(*) AS missing_body,
                   count(*) FILTER (WHERE d.object_key IS NOT NULL AND d.object_key <> '') AS has_object_key
            FROM project_document d
            WHERE d.status = 'READY'
              AND NOT EXISTS (SELECT 1 FROM document_body b WHERE b.document_id = d.id)
            """;

    private static final String CANDIDATE_FIRST_PAGE_SQL = """
            SELECT d.id, d.project_id, d.object_key, d.original_filename, d.mime_type
            FROM project_document d
            WHERE d.status = 'READY'
              AND NOT EXISTS (SELECT 1 FROM document_body b WHERE b.document_id = d.id)
            ORDER BY d.id ASC
            LIMIT ?
            """;

    private static final String CANDIDATE_NEXT_PAGE_SQL = """
            SELECT d.id, d.project_id, d.object_key, d.original_filename, d.mime_type
            FROM project_document d
            WHERE d.status = 'READY'
              AND NOT EXISTS (SELECT 1 FROM document_body b WHERE b.document_id = d.id)
              AND d.id > ?
            ORDER BY d.id ASC
            LIMIT ?
            """;

    private static final String MARK_MISSING_SOURCE_SQL = """
            UPDATE project_document
            SET status = 'FAILED',
                error_message = ?,
                processing_token = NULL,
                processing_heartbeat_at = NULL,
                updated_at = now()
            WHERE id = ?
              AND status = 'READY'
              AND NOT EXISTS (SELECT 1 FROM document_body b WHERE b.document_id = project_document.id)
            """;

    private final JdbcTemplate jdbc;
    private final DocumentStorageGateway storage;
    private final DocumentParser parser;
    private final DocumentChunker chunker;
    private final DocumentContentService contentService;
    private final boolean enableSentenceIndexing;

    @Autowired
    public LegacyDocumentBodyBackfillService(
            JdbcTemplate jdbc,
            DocumentStorageGateway storage,
            DocumentParser parser,
            DocumentChunker chunker,
            DocumentContentService contentService,
            @Value("${rag.indexing.enable-sentence-indexing:true}") boolean enableSentenceIndexing) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.parser = parser;
        this.chunker = chunker;
        this.contentService = contentService;
        this.enableSentenceIndexing = enableSentenceIndexing;
    }

    public BackfillReport run(boolean dryRun) {
        return run(dryRun, DEFAULT_BATCH_SIZE);
    }

    BackfillReport run(boolean dryRun, int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        ReadonlyStats stats = queryReadonlyStats();
        if (stats.missingBodyCount() == 0) {
            return new BackfillReport(dryRun, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        }

        long existingSourceCount = 0L;
        long missingSourceCount = 0L;
        long backfilledCount = 0L;
        long skippedExistingCount = 0L;
        long markedFailedCount = 0L;
        long failedOtherCount = 0L;

        UUID afterId = null;
        while (true) {
            List<CandidateDocument> batch = fetchBatch(afterId, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (CandidateDocument candidate : batch) {
                ProcessOutcome outcome = processOne(candidate, dryRun);
                switch (outcome) {
                    case DRY_RUN_EXISTS -> existingSourceCount++;
                    case DRY_RUN_MISSING -> missingSourceCount++;
                    case BACKFILLED -> {
                        existingSourceCount++;
                        backfilledCount++;
                    }
                    case SKIPPED_EXISTING -> skippedExistingCount++;
                    case MARKED_MISSING_FAILED -> {
                        missingSourceCount++;
                        markedFailedCount++;
                    }
                    case FAILED_OTHER -> failedOtherCount++;
                }
            }
            afterId = batch.get(batch.size() - 1).id();
            if (batch.size() < batchSize) {
                break;
            }
        }

        log.info("存量正文补齐完成: dryRun={}, missingBody={}, hasObjectKey={}, existingSource={}, "
                        + "missingSource={}, backfilled={}, skipped={}, markedFailed={}, failedOther={}",
                dryRun, stats.missingBodyCount(), stats.hasObjectKeyCount(), existingSourceCount,
                missingSourceCount, backfilledCount, skippedExistingCount, markedFailedCount, failedOtherCount);

        return new BackfillReport(
                dryRun,
                stats.missingBodyCount(),
                stats.hasObjectKeyCount(),
                existingSourceCount,
                backfilledCount,
                skippedExistingCount,
                missingSourceCount,
                markedFailedCount,
                failedOtherCount);
    }

    ReadonlyStats queryReadonlyStats() {
        List<ReadonlyStats> rows = jdbc.query(
                STATS_SQL,
                (rs, rowNum) -> new ReadonlyStats(
                        rs.getLong("missing_body"),
                        rs.getLong("has_object_key")));
        return rows.isEmpty() ? new ReadonlyStats(0L, 0L) : rows.get(0);
    }

    private List<CandidateDocument> fetchBatch(UUID afterId, int batchSize) {
        if (afterId == null) {
            return jdbc.query(
                    CANDIDATE_FIRST_PAGE_SQL,
                    (rs, rowNum) -> new CandidateDocument(
                            rs.getObject("id", UUID.class),
                            rs.getObject("project_id", UUID.class),
                            rs.getString("object_key"),
                            rs.getString("original_filename"),
                            rs.getString("mime_type")),
                    batchSize);
        }
        return jdbc.query(
                CANDIDATE_NEXT_PAGE_SQL,
                (rs, rowNum) -> new CandidateDocument(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_id", UUID.class),
                        rs.getString("object_key"),
                        rs.getString("original_filename"),
                        rs.getString("mime_type")),
                afterId,
                batchSize);
    }

    private ProcessOutcome processOne(CandidateDocument candidate, boolean dryRun) {
        String objectKey = candidate.objectKey();
        if (objectKey == null || objectKey.isBlank()) {
            return handleMissingSource(candidate, dryRun);
        }

        byte[] sourceBytes;
        try (InputStream stream = storage.open(objectKey)) {
            if (dryRun) {
                return ProcessOutcome.DRY_RUN_EXISTS;
            }
            sourceBytes = readSourceBytes(stream);
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == ErrorCode.DOCUMENT_STORAGE_OBJECT_NOT_FOUND) {
                return handleMissingSource(candidate, dryRun);
            }
            log.warn("读取存量文档原件失败（不标为 missing）: documentId={}, errorCode={}",
                    candidate.id(), ex.getErrorCode().name());
            return ProcessOutcome.FAILED_OTHER;
        } catch (IOException | RuntimeException ex) {
            log.warn("读取存量文档原件异常（不标为 missing）: documentId={}, exception={}",
                    candidate.id(), ex.getClass().getSimpleName());
            return ProcessOutcome.FAILED_OTHER;
        }

        try {
            ParsedDocument parsed = parser.parse(sourceBytes, candidate.originalFilename(), candidate.mimeType());
            List<DocumentChunk> chunks = chunker.split(parsed.text(), parsed.pageBoundaries(), enableSentenceIndexing);
            if (chunks.isEmpty()) {
                log.warn("解析存量文档结果为空，跳过补齐: documentId={}", candidate.id());
                return ProcessOutcome.FAILED_OTHER;
            }
            boolean written = contentService.saveBackfilledParsed(
                    candidate.projectId(), candidate.id(), sourceBytes, chunks);
            return written ? ProcessOutcome.BACKFILLED : ProcessOutcome.SKIPPED_EXISTING;
        } catch (BusinessException ex) {
            log.warn("解析存量文档失败: documentId={}, errorCode={}",
                    candidate.id(), ex.getErrorCode().name());
            return ProcessOutcome.FAILED_OTHER;
        } catch (RuntimeException ex) {
            log.warn("补齐存量正文异常: documentId={}, exception={}",
                    candidate.id(), ex.getClass().getSimpleName());
            return ProcessOutcome.FAILED_OTHER;
        }
    }

    private ProcessOutcome handleMissingSource(CandidateDocument candidate, boolean dryRun) {
        if (dryRun) {
            return ProcessOutcome.DRY_RUN_MISSING;
        }
        int updated = jdbc.update(MARK_MISSING_SOURCE_SQL, LEGACY_SOURCE_MISSING_ERROR, candidate.id());
        if (updated > 0) {
            log.info("存量文档原件已缺失，已标记 FAILED(legacy_source_missing): documentId={}", candidate.id());
            return ProcessOutcome.MARKED_MISSING_FAILED;
        }
        return ProcessOutcome.SKIPPED_EXISTING;
    }

    private static byte[] readSourceBytes(InputStream stream) throws IOException {
        byte[] bytes = stream.readNBytes(MAX_SOURCE_BYTES + 1);
        if (bytes.length > MAX_SOURCE_BYTES) {
            throw new BusinessException(ErrorCode.DOCUMENT_TOO_LARGE);
        }
        return bytes;
    }

    public record ReadonlyStats(long missingBodyCount, long hasObjectKeyCount) {
    }

    public record BackfillReport(
            boolean dryRun,
            long missingBodyCount,
            long hasObjectKeyCount,
            long existingSourceCount,
            long backfilledCount,
            long skippedExistingCount,
            long missingSourceCount,
            long markedFailedCount,
            long failedOtherCount) {
    }

    private record CandidateDocument(
            UUID id,
            UUID projectId,
            String objectKey,
            String originalFilename,
            String mimeType) {
    }

    private enum ProcessOutcome {
        DRY_RUN_EXISTS,
        DRY_RUN_MISSING,
        BACKFILLED,
        SKIPPED_EXISTING,
        MARKED_MISSING_FAILED,
        FAILED_OTHER
    }
}
