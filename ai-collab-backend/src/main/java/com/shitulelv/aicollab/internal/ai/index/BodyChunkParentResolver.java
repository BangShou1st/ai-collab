package com.shitulelv.aicollab.internal.ai.index;

import com.shitulelv.aicollab.document.domain.model.DocumentChunk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 解析 document_body_chunk 行的 parentChunkNo（契约 docs/design/p2-rag-contract-2026-11-27.md §4.3）。
 * <ol>
 *   <li>新数据：metadata.parentChunkId 等于某一行的行 id（saveParsed 已改为写切块器 id）→ 该行 chunk_no；</li>
 *   <li>存量数据：CHILD 取 charFrom/charThrough 等于本行 parentCharFrom/parentCharThrough 的 PARENT 行；
 *       SENTENCE 取 chunk_no 小于本行、字符区间包含本行区间的最近一个 STANDARD 或 CHILD 行；</li>
 *   <li>都匹配不到为 null，并计入 unresolved（调用方记 document_body_parent_unresolved_total），不报错。</li>
 * </ol>
 * 只有 CHILD / SENTENCE 需要父块；STANDARD / PARENT 的 parentChunkNo 恒为 null，不计入 unresolved。
 * 纯函数，不访问数据库。输入必须是整篇文档的全部行（分页前解析）。
 */
public final class BodyChunkParentResolver {

    public record Row(UUID id, int chunkNo, Map<String, Object> metadata) {
        public Row {
            metadata = metadata == null ? Map.of() : metadata;
        }

        String chunkType() {
            Object type = metadata.get("chunkType");
            return type instanceof String s && !s.isBlank() ? s : DocumentChunk.TYPE_STANDARD;
        }

        Integer intMeta(String key) {
            return metadata.get(key) instanceof Number n ? n.intValue() : null;
        }
    }

    public record Result(Map<Integer, Integer> parentByChunkNo, int unresolved) {
        public Integer parentOf(int chunkNo) {
            return parentByChunkNo.get(chunkNo);
        }
    }

    private BodyChunkParentResolver() {
    }

    public static String chunkType(Map<String, Object> metadata) {
        return new Row(null, 0, metadata).chunkType();
    }

    public static Result resolve(List<Row> rows) {
        Map<UUID, Integer> chunkNoById = new HashMap<>();
        for (Row row : rows) {
            if (row.id() != null) chunkNoById.put(row.id(), row.chunkNo());
        }
        Map<Integer, Integer> parents = new HashMap<>();
        int unresolved = 0;
        for (Row row : rows) {
            String type = row.chunkType();
            if (!DocumentChunk.TYPE_CHILD.equals(type) && !DocumentChunk.TYPE_SENTENCE.equals(type)) continue;
            Integer parent = byId(row, chunkNoById);
            if (parent == null) {
                parent = DocumentChunk.TYPE_CHILD.equals(type) ? childByOffsets(row, rows) : sentenceByOffsets(row, rows);
            }
            if (parent == null) unresolved++;
            else parents.put(row.chunkNo(), parent);
        }
        return new Result(Map.copyOf(parents), unresolved);
    }

    private static Integer byId(Row row, Map<UUID, Integer> chunkNoById) {
        Object raw = row.metadata().get("parentChunkId");
        if (!(raw instanceof String s)) return null;
        try {
            Integer parent = chunkNoById.get(UUID.fromString(s));
            return parent != null && parent != row.chunkNo() ? parent : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static Integer childByOffsets(Row child, List<Row> rows) {
        Integer from = child.intMeta("parentCharFrom");
        Integer through = child.intMeta("parentCharThrough");
        if (from == null || through == null) return null;
        for (Row candidate : rows) {
            if (DocumentChunk.TYPE_PARENT.equals(candidate.chunkType())
                    && from.equals(candidate.intMeta("charFrom"))
                    && through.equals(candidate.intMeta("charThrough"))) {
                return candidate.chunkNo();
            }
        }
        return null;
    }

    private static Integer sentenceByOffsets(Row sentence, List<Row> rows) {
        Integer from = sentence.intMeta("charFrom");
        Integer through = sentence.intMeta("charThrough");
        if (from == null || through == null) return null;
        Integer best = null;
        for (Row candidate : rows) {
            String type = candidate.chunkType();
            if (!DocumentChunk.TYPE_STANDARD.equals(type) && !DocumentChunk.TYPE_CHILD.equals(type)) continue;
            if (candidate.chunkNo() >= sentence.chunkNo()) continue;
            Integer cFrom = candidate.intMeta("charFrom");
            Integer cThrough = candidate.intMeta("charThrough");
            if (cFrom == null || cThrough == null || cFrom > from || cThrough < through) continue;
            if (best == null || candidate.chunkNo() > best) best = candidate.chunkNo();
        }
        return best;
    }
}
