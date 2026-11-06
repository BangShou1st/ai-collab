package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.infrastructure.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 父子块与章节递归扩展器 (Parent-Child Expander)。
 *
 * <p>核心能力：
 * <ul>
 *   <li>当子块被命中且需要更完整的上下文时，依据子块的 parentHeading / parentSnippet / parentCharFrom 等元数据或 parentChunkId，有界扩展父级上下文。</li>
 *   <li>严格保持版本一致性：校验 bodySnapshotId 与 documentId，严禁跨版本混读。</li>
 *   <li>严格保护原始证据契约：保留原始证据的 chunkId、documentId、heading、content 与坐标不变。</li>
 * </ul>
 */
@Component
public class ParentChildExpander {

    private static final Logger log = LoggerFactory.getLogger(ParentChildExpander.class);
    public static final int DEFAULT_MAX_PARENT_CHARS = 2000;

    private final ParentChunkReader parentChunkReader;
    private final int maxParentChars;

    public ParentChildExpander() {
        this((ParentChunkReader) null, DEFAULT_MAX_PARENT_CHARS);
    }

    public ParentChildExpander(ParentChunkReader parentChunkReader) {
        this(parentChunkReader, DEFAULT_MAX_PARENT_CHARS);
    }

    public ParentChildExpander(ParentChunkReader parentChunkReader, int maxParentChars) {
        this.parentChunkReader = parentChunkReader;
        this.maxParentChars = maxParentChars > 0 ? maxParentChars : DEFAULT_MAX_PARENT_CHARS;
    }

    @Autowired
    public ParentChildExpander(@Autowired(required = false) DocumentRepository documentRepository) {
        this(documentRepository != null ? documentRepository::findChunkById : null, DEFAULT_MAX_PARENT_CHARS);
    }

    public List<RetrievalCandidate> expand(UUID projectId, List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<RetrievalCandidate> result = new ArrayList<>(candidates.size());
        for (RetrievalCandidate candidate : candidates) {
            result.add(expandCandidate(projectId, candidate));
        }
        return result;
    }

    private RetrievalCandidate expandCandidate(UUID projectId, RetrievalCandidate candidate) {
        Map<String, Object> metadata = candidate.metadata();
        if (metadata == null || metadata.isEmpty()) {
            return candidate;
        }

        UUID parentChunkId = extractParentChunkId(metadata);
        String childSnapshotId = extractBodySnapshotId(metadata);

        // 1. 若存在 parentChunkId 且 reader 可用，通过 parentChunkId 回读父分块
        if (parentChunkId != null && parentChunkReader != null && projectId != null) {
            Optional<DocumentSearchHit> parentHitOpt = parentChunkReader.findParentChunk(projectId, parentChunkId);
            if (parentHitOpt.isPresent()) {
                DocumentSearchHit parentHit = parentHitOpt.get();

                // 跨文档与版本一致性校验（硬约束）：严禁跨文档或跨版本混读
                if (!Objects.equals(candidate.documentId(), parentHit.documentId())) {
                    log.warn("拒绝跨文档父子块回读: candidateDoc={}, parentDoc={}", candidate.documentId(), parentHit.documentId());
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("parentExpansionRejected", "DOCUMENT_MISMATCH");
                    return candidate.withMetadata(extra);
                }

                String parentSnapshotId = extractBodySnapshotId(parentHit.metadata());
                if (childSnapshotId != null && parentSnapshotId != null && !Objects.equals(childSnapshotId, parentSnapshotId)) {
                    log.warn("拒绝跨版本父子块回读: childSnapshot={}, parentSnapshot={}", childSnapshotId, parentSnapshotId);
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("parentExpansionRejected", "VERSION_MISMATCH");
                    return candidate.withMetadata(extra);
                }

                String parentProse = parentHit.content();
                if (parentProse != null && !parentProse.isBlank()) {
                    String boundedContext = truncateProse(parentProse, maxParentChars);
                    Map<String, Object> extra = new LinkedHashMap<>();
                    extra.put("parentContext", boundedContext);
                    extra.put("parentHeading", parentHit.heading());
                    extra.put("parentChunkId", parentChunkId.toString());
                    extra.put("parentExpanded", true);
                    return candidate.withMetadata(extra);
                }
            }
        }

        // 2. 若未配置 reader 或未找到父块，检查元数据中已存的 parentSnippet / parentHeading
        Object parentSnippetObj = metadata.get("parentSnippet");
        if (parentSnippetObj instanceof String parentSnippet && !parentSnippet.isBlank()) {
            String boundedSnippet = truncateProse(parentSnippet, maxParentChars);
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("parentContext", boundedSnippet);
            Object parentHeading = metadata.get("parentHeading");
            if (parentHeading != null) {
                extra.put("parentHeading", String.valueOf(parentHeading));
            }
            extra.put("parentExpanded", true);
            return candidate.withMetadata(extra);
        }

        return candidate;
    }

    private static UUID extractParentChunkId(Map<String, Object> metadata) {
        Object val = metadata.get("parentChunkId");
        if (val instanceof UUID u) return u;
        if (val instanceof String s && !s.isBlank()) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return null;
    }

    private static String extractBodySnapshotId(Map<String, Object> metadata) {
        if (metadata == null) return null;
        Object val = metadata.get("bodySnapshotId");
        return val != null ? String.valueOf(val) : null;
    }

    private static String truncateProse(String prose, int maxChars) {
        if (prose == null || prose.length() <= maxChars) {
            return prose;
        }
        return prose.substring(0, maxChars) + "...";
    }
}
