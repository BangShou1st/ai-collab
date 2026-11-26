package com.shitulelv.aicollab.internal.ai.index;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P2 契约 §4.3：正文块分页、parentChunkNo、白名单、404 / 409 语义（不依赖 Docker）。 */
class DocumentBodyReadServiceTest {

    private static final UUID DOC = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID PROJECT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OTHER_PROJECT = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID SNAPSHOT = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID PARENT_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private DocumentBodyRepository bodies;
    private SimpleMeterRegistry meters;
    private DocumentBodyReadService service;

    @BeforeEach
    void setUp() {
        bodies = mock(DocumentBodyRepository.class);
        meters = new SimpleMeterRegistry();
        service = new DocumentBodyReadService(bodies, meters);
        when(bodies.findReadableHeader(DOC)).thenReturn(Optional.of(
                new DocumentBodyRepository.Header(PROJECT, SNAPSHOT, "tika-page-clean-v2/chunk-offset-v2")));
        Map<String, Object> parentMeta = Map.of("chunkType", "PARENT", "charFrom", 0, "charThrough", 2000);
        Map<String, Object> childMeta = Map.of("chunkType", "CHILD", "charFrom", 0, "charThrough", 900,
                "parentChunkId", PARENT_ID.toString(), "parentSnippet", "secret-ish", "offsetCoordinate", "X",
                "pageNumber", 1, "isTable", true);
        Map<String, Object> orphanMeta = Map.of("chunkType", "SENTENCE", "charFrom", 9000, "charThrough", 9010);
        when(bodies.listRowMeta(DOC)).thenReturn(List.of(
                new DocumentBodyRepository.RowMeta(PARENT_ID, 0, parentMeta),
                new DocumentBodyRepository.RowMeta(UUID.randomUUID(), 1, childMeta),
                new DocumentBodyRepository.RowMeta(UUID.randomUUID(), 2, orphanMeta)));
        when(bodies.page(eq(DOC), eq(-1), anyInt())).thenReturn(List.of(
                new DocumentBodyRepository.Row(0, "h", "parent", "a".repeat(64), parentMeta),
                new DocumentBodyRepository.Row(1, "h", "child", "b".repeat(64), childMeta),
                new DocumentBodyRepository.Row(2, "h", "orphan", "c".repeat(64), orphanMeta)));
        when(bodies.page(eq(DOC), eq(1), anyInt())).thenReturn(List.of(
                new DocumentBodyRepository.Row(2, "h", "orphan", "c".repeat(64), orphanMeta)));
    }

    @Test
    void returnsChunksWithResolvedParentsAndWhitelistedMetadata() {
        DocumentBodyReadService.Page page = service.read(DOC, PROJECT, null, 200);

        assertThat(page.bodySnapshotId()).isEqualTo(SNAPSHOT);
        assertThat(page.totalChunks()).isEqualTo(3);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.chunks()).extracting(DocumentBodyReadService.ChunkView::chunkNo).containsExactly(0, 1, 2);
        assertThat(page.chunks().get(0).chunkType()).isEqualTo("PARENT");
        assertThat(page.chunks().get(0).parentChunkNo()).isNull();
        assertThat(page.chunks().get(1).parentChunkNo()).isEqualTo(0);
        assertThat(page.chunks().get(1).metadata())
                .containsOnlyKeys("charFrom", "charThrough", "pageNumber", "isTable");
        assertThat(page.chunks().get(2).parentChunkNo()).isNull();
        assertThat(meters.get(DocumentBodyReadService.METRIC_PARENT_UNRESOLVED).counter().count()).isEqualTo(1.0);
    }

    @Test
    void paginatesWithCursorBoundToSnapshot() {
        DocumentBodyReadService.Page first = service.read(DOC, PROJECT, null, 2);
        assertThat(first.chunks()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();

        DocumentBodyReadService.Page second = service.read(DOC, PROJECT, first.nextCursor(), 2);
        assertThat(second.chunks()).extracting(DocumentBodyReadService.ChunkView::chunkNo).containsExactly(2);
        assertThat(second.nextCursor()).isNull();
        // 父块解析基于整篇文档，第二页里 SENTENCE 的解析结果与第一次读取一致
        assertThat(second.chunks().getFirst().parentChunkNo()).isNull();
    }

    @Test
    void snapshotChangedDuringPagingIsStale() {
        String staleCursor = new DocumentBodyReadService.Cursor(UUID.randomUUID(), 1).encode();
        assertKind(() -> service.read(DOC, PROJECT, staleCursor, 2), DocumentBodyReadService.BodyReadException.Kind.SNAPSHOT_STALE);
    }

    @Test
    void wrongProjectOrUnreadableDocumentIsNotFound() {
        assertKind(() -> service.read(DOC, OTHER_PROJECT, null, 10), DocumentBodyReadService.BodyReadException.Kind.NOT_FOUND);
        assertKind(() -> service.read(DOC, null, null, 10), DocumentBodyReadService.BodyReadException.Kind.NOT_FOUND);
        UUID missing = UUID.randomUUID();
        when(bodies.findReadableHeader(missing)).thenReturn(Optional.empty());
        assertKind(() -> service.read(missing, PROJECT, null, 10), DocumentBodyReadService.BodyReadException.Kind.NOT_FOUND);
        verify(bodies, never()).listRowMeta(missing);
    }

    @Test
    void invalidLimitOrCursorIsRejected() {
        assertKind(() -> service.read(DOC, PROJECT, null, 0), DocumentBodyReadService.BodyReadException.Kind.INVALID);
        assertKind(() -> service.read(DOC, PROJECT, null, 201), DocumentBodyReadService.BodyReadException.Kind.INVALID);
        assertKind(() -> service.read(DOC, PROJECT, "not-base64!!", 10), DocumentBodyReadService.BodyReadException.Kind.INVALID);
        String wrongVersion = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v0:" + SNAPSHOT + ":1").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertKind(() -> service.read(DOC, PROJECT, wrongVersion, 10), DocumentBodyReadService.BodyReadException.Kind.INVALID);
    }

    @Test
    void emptyBodyReturnsZeroChunks() {
        UUID empty = UUID.randomUUID();
        when(bodies.findReadableHeader(empty)).thenReturn(Optional.of(
                new DocumentBodyRepository.Header(PROJECT, SNAPSHOT, "v")));
        when(bodies.listRowMeta(empty)).thenReturn(List.of());
        when(bodies.page(eq(empty), eq(-1), anyInt())).thenReturn(List.of());

        DocumentBodyReadService.Page page = service.read(empty, PROJECT, null, null);
        assertThat(page.totalChunks()).isZero();
        assertThat(page.chunks()).isEmpty();
        assertThat(page.nextCursor()).isNull();
    }

    private static void assertKind(Runnable attempt, DocumentBodyReadService.BodyReadException.Kind kind) {
        assertThatThrownBy(attempt::run).isInstanceOfSatisfying(DocumentBodyReadService.BodyReadException.class,
                failure -> assertThat(failure.kind()).isEqualTo(kind));
    }
}
