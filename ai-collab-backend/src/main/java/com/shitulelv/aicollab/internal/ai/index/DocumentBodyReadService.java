package com.shitulelv.aicollab.internal.ai.index;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 给 Python 的正文块分页读取（P2 契约 §4.3，方案 B）。
 *
 * <ul>
 *   <li>按 {@code chunk_no} 升序返回 {@code document_body_chunk} 行；{@code chunkType} 取 metadata，缺失为 STANDARD；</li>
 *   <li>{@code parentChunkNo} 由 {@link BodyChunkParentResolver} 对整篇文档解析，解析不到为 null 并计数
 *       {@code document_body_parent_unresolved_total}，不报错；</li>
 *   <li>metadata 只返回白名单字段；</li>
 *   <li>令牌 pid ≠ 文档项目、文档 DELETING / 封锁 / 外部来源失效、正文不存在：一律 404（不区分原因）；</li>
 *   <li>cursor 绑定 bodySnapshotId；分页过程中快照变化返回 409 {@code INDEX_SNAPSHOT_STALE}。</li>
 * </ul>
 */
@Service
public class DocumentBodyReadService {

    public static final int MAX_LIMIT = 200;
    static final String METRIC_PARENT_UNRESOLVED = "document_body_parent_unresolved_total";
    static final List<String> METADATA_WHITELIST =
            List.of("charFrom", "charThrough", "pageNumber", "pageThrough", "isTable", "isCode");

    private final DocumentBodyRepository bodies;
    private final Counter parentUnresolved;

    @Autowired
    public DocumentBodyReadService(DocumentBodyRepository bodies, ObjectProvider<MeterRegistry> meters) {
        this(bodies, meters == null ? null : meters.getIfAvailable());
    }

    DocumentBodyReadService(DocumentBodyRepository bodies, MeterRegistry meters) {
        this.bodies = Objects.requireNonNull(bodies, "bodies");
        MeterRegistry registry = meters == null ? new SimpleMeterRegistry() : meters;
        this.parentUnresolved = Counter.builder(METRIC_PARENT_UNRESOLVED)
                .description("正文块 parentChunkNo 无法解析的行数").register(registry);
    }

    /** 读取失败：NOT_FOUND → 404，SNAPSHOT_STALE → 409，INVALID → 422。 */
    public static final class BodyReadException extends RuntimeException {
        public enum Kind { NOT_FOUND, SNAPSHOT_STALE, INVALID }

        private final Kind kind;

        BodyReadException(Kind kind) {
            super(kind.name());
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    public record ChunkView(int chunkNo, String chunkType, Integer parentChunkNo, String heading, String content,
            String contentHash, Map<String, Object> metadata) {
    }

    public record Page(UUID documentId, UUID projectId, UUID bodySnapshotId, String parseVersion, int totalChunks,
            List<ChunkView> chunks, String nextCursor) {
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page read(UUID documentId, UUID tokenProjectId, String cursor, Integer limit) {
        int size = limit == null ? MAX_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw new BodyReadException(BodyReadException.Kind.INVALID);
        }
        Cursor position = Cursor.decode(cursor);
        DocumentBodyRepository.Header header = bodies.findReadableHeader(documentId)
                .orElseThrow(() -> new BodyReadException(BodyReadException.Kind.NOT_FOUND));
        if (tokenProjectId == null || !tokenProjectId.equals(header.projectId())) {
            throw new BodyReadException(BodyReadException.Kind.NOT_FOUND);
        }
        if (position != null && !position.snapshotId().equals(header.snapshotId())) {
            throw new BodyReadException(BodyReadException.Kind.SNAPSHOT_STALE);
        }

        List<DocumentBodyRepository.RowMeta> all = bodies.listRowMeta(documentId);
        BodyChunkParentResolver.Result parents = BodyChunkParentResolver.resolve(all.stream()
                .map(row -> new BodyChunkParentResolver.Row(row.id(), row.chunkNo(), row.metadata()))
                .toList());
        int afterChunkNo = position == null ? -1 : position.afterChunkNo();
        List<DocumentBodyRepository.Row> rows = bodies.page(documentId, afterChunkNo, size + 1);
        boolean more = rows.size() > size;
        List<DocumentBodyRepository.Row> pageRows = more ? rows.subList(0, size) : rows;

        List<ChunkView> chunks = new ArrayList<>(pageRows.size());
        for (DocumentBodyRepository.Row row : pageRows) {
            String type = BodyChunkParentResolver.chunkType(row.metadata());
            Integer parent = parents.parentOf(row.chunkNo());
            if (parent == null && needsParent(type)) {
                parentUnresolved.increment();
            }
            chunks.add(new ChunkView(row.chunkNo(), type, parent, row.heading(), row.content(), row.contentHash(),
                    whitelist(row.metadata())));
        }
        String next = more && !pageRows.isEmpty()
                ? new Cursor(header.snapshotId(), pageRows.getLast().chunkNo()).encode() : null;
        return new Page(documentId, header.projectId(), header.snapshotId(), header.parseVersion(), all.size(),
                List.copyOf(chunks), next);
    }

    private static boolean needsParent(String type) {
        return "CHILD".equals(type) || "SENTENCE".equals(type);
    }

    static Map<String, Object> whitelist(Map<String, Object> metadata) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (metadata == null) {
            return result;
        }
        for (String key : METADATA_WHITELIST) {
            if (metadata.containsKey(key)) {
                result.put(key, metadata.get(key));
            }
        }
        return result;
    }

    /** 不透明游标：base64url("v1:{snapshotId}:{afterChunkNo}")。格式非法按 422 处理。 */
    record Cursor(UUID snapshotId, int afterChunkNo) {
        private static final String VERSION = "v1";

        String encode() {
            String raw = VERSION + ":" + snapshotId + ":" + afterChunkNo;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String encoded) {
            if (encoded == null || encoded.isBlank()) {
                return null;
            }
            try {
                String raw = new String(Base64.getUrlDecoder().decode(encoded.trim()), StandardCharsets.UTF_8);
                String[] parts = raw.split(":");
                if (parts.length != 3 || !VERSION.equals(parts[0])) {
                    throw new IllegalArgumentException("cursor_version");
                }
                int after = Integer.parseInt(parts[2]);
                if (after < 0) {
                    throw new IllegalArgumentException("cursor_negative");
                }
                return new Cursor(UUID.fromString(parts[1]), after);
            } catch (IllegalArgumentException invalid) {
                throw new BodyReadException(BodyReadException.Kind.INVALID);
            }
        }
    }
}
