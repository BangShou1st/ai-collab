package com.shitulelv.aicollab.document.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.LegacyDocumentBodyBackfillService.BackfillReport;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.parser.DocumentParser;
import com.shitulelv.aicollab.document.infrastructure.parser.ParsedDocument;
import com.shitulelv.aicollab.document.infrastructure.storage.DocumentStorageGateway;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LegacyDocumentBodyBackfillServiceTest {

    private JdbcTemplate jdbc;
    private DocumentStorageGateway storage;
    private DocumentParser parser;
    private DocumentChunker chunker;
    private DocumentContentService contentService;
    private LegacyDocumentBodyBackfillService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        storage = mock(DocumentStorageGateway.class);
        parser = mock(DocumentParser.class);
        chunker = new DocumentChunker();
        contentService = new DocumentContentService(jdbc, new ObjectMapper(), mock(ProjectAccessGuard.class));
        service = new LegacyDocumentBodyBackfillService(jdbc, storage, parser, chunker, contentService, true);
    }

    @Test
    void returnsZeroImmediatelyWhenNoMissingBodyDocuments() {
        stubStats(0L, 0L);

        BackfillReport report = service.run(false);

        assertThat(report.missingBodyCount()).isZero();
        assertThat(report.hasObjectKeyCount()).isZero();
        assertThat(report.backfilledCount()).isZero();
        verifyNoInteractions(storage, parser);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void dryRunCountsMissingBodyAndExistingStorageObjectsWithoutWritingDatabase() {
        UUID projectId = UUID.randomUUID();
        UUID docExists = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID docMissing = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID docBlank = UUID.fromString("00000000-0000-0000-0000-000000000003");

        stubStats(3L, 2L);
        stubCandidatePages(List.of(
                new CandidateRow(docExists, projectId, "p/exists.txt", "exists.txt", "text/plain"),
                new CandidateRow(docMissing, projectId, "p/missing.txt", "missing.txt", "text/plain"),
                new CandidateRow(docBlank, projectId, "   ", "blank.txt", "text/plain")));
        when(storage.open("p/exists.txt")).thenReturn(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
        when(storage.open("p/missing.txt")).thenThrow(new BusinessException(ErrorCode.DOCUMENT_STORAGE_OBJECT_NOT_FOUND));

        BackfillReport report = service.run(true, 10);

        assertThat(report.dryRun()).isTrue();
        assertThat(report.missingBodyCount()).isEqualTo(3L);
        assertThat(report.hasObjectKeyCount()).isEqualTo(2L);
        assertThat(report.existingSourceCount()).isEqualTo(1L);
        assertThat(report.missingSourceCount()).isEqualTo(2L);
        assertThat(report.backfilledCount()).isZero();
        assertThat(report.markedFailedCount()).isZero();
        assertThat(report.failedOtherCount()).isZero();
        verifyNoInteractions(parser);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void backfillParsesAndWritesDocumentBodyOnlyWithoutTouchingDocumentChunk() {
        UUID projectId = UUID.randomUUID();
        UUID doc1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID doc2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        byte[] bytes1 = "第一篇历史文档正文。".getBytes(StandardCharsets.UTF_8);
        byte[] bytes2 = "第二篇历史文档正文。".getBytes(StandardCharsets.UTF_8);

        stubStats(2L, 2L);
        stubCandidatePages(
                List.of(new CandidateRow(doc1, projectId, "p/1.md", "1.md", "text/markdown")),
                List.of(new CandidateRow(doc2, projectId, "p/2.md", "2.md", "text/markdown")));

        when(storage.open("p/1.md")).thenReturn(new ByteArrayInputStream(bytes1));
        when(storage.open("p/2.md")).thenReturn(new ByteArrayInputStream(bytes2));
        when(parser.parse(bytes1, "1.md", "text/markdown"))
                .thenReturn(new ParsedDocument("tika", "# 标题一\n\n第一篇历史文档内容足够长用于生成正文块。"));
        when(parser.parse(bytes2, "2.md", "text/markdown"))
                .thenReturn(new ParsedDocument("tika", "# 标题二\n\n第二篇历史文档内容足够长用于生成正文块。"));

        when(jdbc.queryForList(contains("project_document WHERE project_id=? AND id=? AND status='READY' FOR UPDATE"),
                eq(UUID.class), eq(projectId), eq(doc1))).thenReturn(List.of(doc1));
        when(jdbc.queryForList(contains("project_document WHERE project_id=? AND id=? AND status='READY' FOR UPDATE"),
                eq(UUID.class), eq(projectId), eq(doc2))).thenReturn(List.of(doc2));
        when(jdbc.queryForList(eq("SELECT document_id FROM document_body WHERE document_id=?"),
                eq(UUID.class), any(UUID.class))).thenReturn(List.of());

        List<String> executedUpdateSqls = new ArrayList<>();
        when(jdbc.update(anyString(), any(Object[].class))).thenAnswer(inv -> {
            executedUpdateSqls.add(inv.getArgument(0));
            return 1;
        });

        BackfillReport report = service.run(false, 1);

        assertThat(report.dryRun()).isFalse();
        assertThat(report.backfilledCount()).isEqualTo(2L);
        assertThat(report.existingSourceCount()).isEqualTo(2L);
        assertThat(report.skippedExistingCount()).isZero();
        assertThat(report.markedFailedCount()).isZero();
        assertThat(report.failedOtherCount()).isZero();

        assertThat(executedUpdateSqls).anyMatch(sql -> sql.contains("INSERT INTO document_body("));
        assertThat(executedUpdateSqls).anyMatch(sql -> sql.contains("INSERT INTO document_body_chunk("));
        assertThat(executedUpdateSqls).noneMatch(sql -> sql.contains("document_chunk"));
        assertThat(executedUpdateSqls).noneMatch(sql -> sql.contains("UPDATE project_document"));
    }

    @Test
    void backfillIsIdempotentWhenDocumentBodyAppearsBeforeCommitOrStatusNoLongerReady() {
        UUID projectId = UUID.randomUUID();
        UUID docAlreadyBackfilled = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID docStatusChanged = UUID.fromString("00000000-0000-0000-0000-000000000012");
        byte[] bytes = "正文内容".getBytes(StandardCharsets.UTF_8);

        stubStats(2L, 2L);
        stubCandidatePages(List.of(
                new CandidateRow(docAlreadyBackfilled, projectId, "p/a.txt", "a.txt", "text/plain"),
                new CandidateRow(docStatusChanged, projectId, "p/b.txt", "b.txt", "text/plain")));
        when(storage.open("p/a.txt")).thenReturn(new ByteArrayInputStream(bytes));
        when(storage.open("p/b.txt")).thenReturn(new ByteArrayInputStream(bytes));
        when(parser.parse(any(), anyString(), anyString()))
                .thenReturn(new ParsedDocument("tika", "这是一段正文内容。"));

        when(jdbc.queryForList(contains("project_document WHERE project_id=? AND id=? AND status='READY' FOR UPDATE"),
                eq(UUID.class), eq(projectId), eq(docAlreadyBackfilled)))
                .thenReturn(List.of(docAlreadyBackfilled));
        when(jdbc.queryForList(eq("SELECT document_id FROM document_body WHERE document_id=?"),
                eq(UUID.class), eq(docAlreadyBackfilled)))
                .thenReturn(List.of(docAlreadyBackfilled));

        when(jdbc.queryForList(contains("project_document WHERE project_id=? AND id=? AND status='READY' FOR UPDATE"),
                eq(UUID.class), eq(projectId), eq(docStatusChanged)))
                .thenReturn(List.of());

        BackfillReport report = service.run(false, 10);

        assertThat(report.backfilledCount()).isZero();
        assertThat(report.skippedExistingCount()).isEqualTo(2L);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void missingSourceMarksFailedWithLegacySourceMissing() {
        UUID projectId = UUID.randomUUID();
        UUID docMissingObject = UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID docNullObjectKey = UUID.fromString("00000000-0000-0000-0000-000000000022");

        stubStats(2L, 1L);
        stubCandidatePages(List.of(
                new CandidateRow(docMissingObject, projectId, "p/gone.pdf", "gone.pdf", "application/pdf"),
                new CandidateRow(docNullObjectKey, projectId, null, "empty-key.pdf", "application/pdf")));
        when(storage.open("p/gone.pdf")).thenThrow(new BusinessException(ErrorCode.DOCUMENT_STORAGE_OBJECT_NOT_FOUND));
        when(jdbc.update(contains("SET status = 'FAILED'"), eq(LegacyDocumentBodyBackfillService.LEGACY_SOURCE_MISSING_ERROR), any(UUID.class)))
                .thenReturn(1);

        BackfillReport report = service.run(false, 10);

        assertThat(report.missingSourceCount()).isEqualTo(2L);
        assertThat(report.markedFailedCount()).isEqualTo(2L);
        assertThat(report.backfilledCount()).isZero();
        assertThat(report.failedOtherCount()).isZero();

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sqlCaptor.capture(), eq("legacy_source_missing"), eq(docMissingObject));
        verify(jdbc).update(anyString(), eq("legacy_source_missing"), eq(docNullObjectKey));
        assertThat(sqlCaptor.getValue()).contains("NOT EXISTS (SELECT 1 FROM document_body");
    }

    @Test
    void storageOutageAndParseFailureDoNotMarkLegacySourceMissing() {
        UUID projectId = UUID.randomUUID();
        UUID docOutage = UUID.fromString("00000000-0000-0000-0000-000000000031");
        UUID docEmptyParse = UUID.fromString("00000000-0000-0000-0000-000000000032");

        stubStats(2L, 2L);
        stubCandidatePages(List.of(
                new CandidateRow(docOutage, projectId, "p/outage.txt", "outage.txt", "text/plain"),
                new CandidateRow(docEmptyParse, projectId, "p/blank-parse.txt", "blank-parse.txt", "text/plain")));
        when(storage.open("p/outage.txt")).thenThrow(new BusinessException(ErrorCode.DOCUMENT_STORAGE_UNAVAILABLE));
        when(storage.open("p/blank-parse.txt")).thenReturn(new ByteArrayInputStream("   ".getBytes(StandardCharsets.UTF_8)));
        when(parser.parse(any(), eq("blank-parse.txt"), anyString()))
                .thenReturn(new ParsedDocument("tika", "   "));

        BackfillReport report = service.run(false, 10);

        assertThat(report.failedOtherCount()).isEqualTo(2L);
        assertThat(report.markedFailedCount()).isZero();
        assertThat(report.missingSourceCount()).isZero();
        verify(jdbc, never()).update(contains("SET status = 'FAILED'"), any(), any());
    }

    @SuppressWarnings("unchecked")
    private void stubStats(long missingBody, long hasObjectKey) {
        when(jdbc.query(contains("AS missing_body"), any(RowMapper.class))).thenAnswer(inv -> {
            RowMapper<LegacyDocumentBodyBackfillService.ReadonlyStats> mapper = inv.getArgument(1);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getLong("missing_body")).thenReturn(missingBody);
            when(rs.getLong("has_object_key")).thenReturn(hasObjectKey);
            return List.of(mapper.mapRow(rs, 0));
        });
    }

    @SafeVarargs
    @SuppressWarnings("unchecked")
    private void stubCandidatePages(List<CandidateRow>... pages) {
        if (pages.length == 0) {
            return;
        }
        when(jdbc.query(argThat((String sql) -> sql != null && sql.contains("ORDER BY d.id ASC") && !sql.contains("d.id > ?")),
                any(RowMapper.class), anyInt())).thenAnswer(inv -> mapCandidateRows(inv.getArgument(1), pages[0]));
        if (pages.length > 1) {
            when(jdbc.query(argThat((String sql) -> sql != null && sql.contains("d.id > ?")),
                    any(RowMapper.class), any(UUID.class), anyInt())).thenAnswer(inv -> {
                UUID afterId = inv.getArgument(2);
                RowMapper<Object> mapper = inv.getArgument(1);
                for (int i = 0; i < pages.length - 1; i++) {
                    List<CandidateRow> prev = pages[i];
                    if (!prev.isEmpty() && prev.get(prev.size() - 1).id().equals(afterId)) {
                        return mapCandidateRows(mapper, pages[i + 1]);
                    }
                }
                return List.of();
            });
        } else {
            when(jdbc.query(argThat((String sql) -> sql != null && sql.contains("d.id > ?")),
                    any(RowMapper.class), any(UUID.class), anyInt())).thenReturn(List.of());
        }
    }

    private static <T> List<T> mapCandidateRows(RowMapper<T> mapper, List<CandidateRow> rows) throws Exception {
        List<T> mapped = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            CandidateRow r = rows.get(i);
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(r.id());
            when(rs.getObject("project_id", UUID.class)).thenReturn(r.projectId());
            when(rs.getString("object_key")).thenReturn(r.objectKey());
            when(rs.getString("original_filename")).thenReturn(r.originalFilename());
            when(rs.getString("mime_type")).thenReturn(r.mimeType());
            mapped.add(mapper.mapRow(rs, i));
        }
        return mapped;
    }

    private record CandidateRow(UUID id, UUID projectId, String objectKey, String originalFilename, String mimeType) {
    }
}
