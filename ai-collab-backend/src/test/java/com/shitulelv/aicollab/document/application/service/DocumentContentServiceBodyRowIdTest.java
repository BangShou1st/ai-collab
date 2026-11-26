package com.shitulelv.aicollab.document.application.service;

import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 契约 §4.3 规则 1：saveParsed 的行 id 取切块器生成的 id，父块关系才对得上。 */
class DocumentContentServiceBodyRowIdTest {

    @Test
    void usesChunkerIdWhenPresent() {
        UUID id = UUID.randomUUID();
        DocumentChunk chunk = new DocumentChunk(0, "h", "c", "0".repeat(64), 1, Map.of(), null,
                DocumentChunk.TYPE_STANDARD).withId(id);

        assertThat(DocumentContentService.bodyRowId(chunk)).isEqualTo(id);
    }

    @Test
    void generatesIdOnlyWhenChunkerGaveNone() {
        DocumentChunk chunk = new DocumentChunk(0, "h", "c", "0".repeat(64), 1, Map.of(), null,
                DocumentChunk.TYPE_STANDARD);

        assertThat(chunk.id()).isNull();
        assertThat(DocumentContentService.bodyRowId(chunk)).isNotNull();
    }

    @Test
    void chunkerParentChunkIdPointsAtAnEmittedRowId() {
        String body = ("第一章\n" + "这是一段足够长的正文，用于触发父子切块。".repeat(400));
        var chunks = new DocumentChunker().split(body, true);
        var ids = chunks.stream().map(DocumentContentService::bodyRowId).toList();
        var parentRefs = chunks.stream().map(DocumentChunk::parentChunkId).filter(java.util.Objects::nonNull).toList();

        assertThat(chunks).allSatisfy(c -> assertThat(c.id()).isNotNull());
        assertThat(parentRefs).isNotEmpty();
        assertThat(ids).containsAll(parentRefs);

        // 以切块器 id 落库后，规则 1 能解析出全部父块
        var rows = chunks.stream().map(c -> new com.shitulelv.aicollab.internal.ai.index.BodyChunkParentResolver.Row(
                DocumentContentService.bodyRowId(c), c.chunkNo(), c.metadata())).toList();
        var resolved = com.shitulelv.aicollab.internal.ai.index.BodyChunkParentResolver.resolve(rows);
        assertThat(resolved.unresolved()).isZero();
        assertThat(resolved.parentByChunkNo()).hasSize(parentRefs.size());
    }
}
