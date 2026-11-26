package com.shitulelv.aicollab.retrieval.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** P2 契约 §4.4 的请求 / 响应形状。字段名与 JSON 完全一致（camelCase）。 */
public final class KnowledgeSearchModels {

    private KnowledgeSearchModels() {
    }

    public enum Intent { KNOWLEDGE_QA, AGENT_RESEARCH, PLANNING_SOURCE, PLANNING_TOOL_LOOKUP }

    public enum Rerank { HEURISTIC, CONFIGURED }

    public record Query(String text, String role) {
        public static Query primary(String text) {
            return new Query(text, "PRIMARY");
        }

        public static Query sub(String text) {
            return new Query(text, "SUB");
        }
    }

    public record HeadingFilter(String exact, String contains) {
        public static HeadingFilter none() {
            return new HeadingFilter(null, null);
        }
    }

    public record Expand(boolean sentenceWindow, boolean parent) {
    }

    public record Request(
            UUID requestId,
            UUID projectId,
            UUID userId,
            Intent intent,
            String strategy,
            List<Query> queries,
            String originalQuery,
            List<UUID> visibleDocumentIds,
            int topK,
            int candidatePool,
            HeadingFilter headingFilter,
            Rerank rerank,
            Expand expand,
            int deadlineMs) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChunkRef(Integer chunkNo, String contentHash) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Context(String text, List<ChunkRef> chunkRefs) {
    }

    /** {@code final} 是 Java 关键字，组件名用 finalScore，JSON 仍为 {@code final}（P2-R1 R1-2：准入分）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scores(Double dense, Double lexical, Double rrf, Double rerank,
            @com.fasterxml.jackson.annotation.JsonProperty("final") Double finalScore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hit(
            Integer rank,
            UUID documentId,
            Integer chunkNo,
            String contentHash,
            String chunkType,
            Integer parentChunkNo,
            String heading,
            String content,
            UUID bodySnapshotId,
            Map<String, Object> sourceRef,
            Context context,
            Scores scores) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Response(UUID requestId, UUID generationId, String strategy, List<Hit> hits,
            Map<String, Object> diagnostics) {
    }
}
