package com.shitulelv.aicollab.internal.ai.index;

import com.shitulelv.aicollab.document.domain.model.DocumentChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 契约 §4.3 / §9.3 第 5 条：parentChunkNo 解析规则（新数据按 id、存量按偏移、解析不到为 null 不报错）。 */
class BodyChunkParentResolverTest {

    @Test
    void newDataResolvesParentByChunkerIdForChildAndSentence() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        var rows = List.of(
                row(parentId, 0, Map.of("chunkType", DocumentChunk.TYPE_PARENT, "charFrom", 0, "charThrough", 2000)),
                row(childId, 1, Map.of("chunkType", DocumentChunk.TYPE_CHILD, "charFrom", 0, "charThrough", 900,
                        "parentChunkId", parentId.toString())),
                row(UUID.randomUUID(), 2, Map.of("chunkType", DocumentChunk.TYPE_SENTENCE, "charFrom", 10,
                        "charThrough", 80, "parentChunkId", childId.toString())));

        var result = BodyChunkParentResolver.resolve(rows);

        assertThat(result.parentOf(1)).isEqualTo(0);
        assertThat(result.parentOf(2)).isEqualTo(1);
        assertThat(result.parentOf(0)).isNull();
        assertThat(result.unresolved()).isZero();
    }

    @Test
    void legacyChildFallsBackToParentOffsetsWhenRowIdsDoNotMatch() {
        var rows = List.of(
                row(UUID.randomUUID(), 0, Map.of("chunkType", DocumentChunk.TYPE_PARENT, "charFrom", 100,
                        "charThrough", 3000)),
                row(UUID.randomUUID(), 1, Map.of("chunkType", DocumentChunk.TYPE_CHILD, "charFrom", 100,
                        "charThrough", 1000, "parentCharFrom", 100, "parentCharThrough", 3000,
                        "parentChunkId", UUID.randomUUID().toString())));

        var result = BodyChunkParentResolver.resolve(rows);

        assertThat(result.parentOf(1)).isEqualTo(0);
        assertThat(result.unresolved()).isZero();
    }

    @Test
    void legacySentencePicksNearestPrecedingContainingStandardOrChild() {
        var rows = List.of(
                row(UUID.randomUUID(), 0, Map.of("chunkType", DocumentChunk.TYPE_STANDARD, "charFrom", 0,
                        "charThrough", 500)),
                row(UUID.randomUUID(), 1, Map.of("chunkType", DocumentChunk.TYPE_PARENT, "charFrom", 0,
                        "charThrough", 500)),
                row(UUID.randomUUID(), 2, Map.of("chunkType", DocumentChunk.TYPE_CHILD, "charFrom", 0,
                        "charThrough", 300, "parentCharFrom", 0, "parentCharThrough", 500)),
                row(UUID.randomUUID(), 3, Map.of("chunkType", DocumentChunk.TYPE_SENTENCE, "charFrom", 20,
                        "charThrough", 60, "parentChunkId", UUID.randomUUID().toString())),
                // 后面的行即便包含区间也不能当父块
                row(UUID.randomUUID(), 4, Map.of("chunkType", DocumentChunk.TYPE_CHILD, "charFrom", 0,
                        "charThrough", 500, "parentCharFrom", 0, "parentCharThrough", 500)));

        var result = BodyChunkParentResolver.resolve(rows);

        assertThat(result.parentOf(3)).isEqualTo(2);
        assertThat(result.parentOf(2)).isEqualTo(1);
        assertThat(result.parentOf(4)).isEqualTo(1);
        assertThat(result.unresolved()).isZero();
    }

    @Test
    void unresolvableParentIsNullAndCountedWithoutThrowing() {
        var rows = List.of(
                row(UUID.randomUUID(), 0, Map.of("chunkType", DocumentChunk.TYPE_CHILD, "charFrom", 0,
                        "charThrough", 100, "parentCharFrom", 0, "parentCharThrough", 999,
                        "parentChunkId", "not-a-uuid")),
                row(UUID.randomUUID(), 1, Map.of("chunkType", DocumentChunk.TYPE_SENTENCE, "charFrom", 5000,
                        "charThrough", 5100)));

        var result = BodyChunkParentResolver.resolve(rows);

        assertThat(result.parentOf(0)).isNull();
        assertThat(result.parentOf(1)).isNull();
        assertThat(result.unresolved()).isEqualTo(2);
    }

    @Test
    void standardAndMissingChunkTypeNeverCountAsUnresolved() {
        var rows = List.of(row(UUID.randomUUID(), 0, Map.of()), row(UUID.randomUUID(), 1, null));

        var result = BodyChunkParentResolver.resolve(rows);

        assertThat(result.parentByChunkNo()).isEmpty();
        assertThat(result.unresolved()).isZero();
        assertThat(BodyChunkParentResolver.chunkType(Map.of())).isEqualTo(DocumentChunk.TYPE_STANDARD);
    }

    private static BodyChunkParentResolver.Row row(UUID id, int chunkNo, Map<String, Object> metadata) {
        return new BodyChunkParentResolver.Row(id, chunkNo, metadata);
    }
}
