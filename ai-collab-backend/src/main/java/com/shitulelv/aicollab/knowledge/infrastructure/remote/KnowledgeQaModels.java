package com.shitulelv.aicollab.knowledge.infrastructure.remote;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P3 契约 §3.3：{@code POST /v1/knowledge/qa:stream} 的出站请求与入站事件模型。
 *
 * <p>请求形状由 Python 侧 {@code contract/knowledge_qa.py}（extra=forbid）冻结；
 * 事件序固定为 {@code token* → finalize} 或 {@code error} 终止。字段名 camelCase
 * 由 Jackson 显式标注，不依赖全局命名策略。</p>
 */
public final class KnowledgeQaModels {

    /** 证据不足统一拒答文案（契约：文案逐字不变；原 KnowledgeCitationValidator 常量移此，§7）。 */
    public static final String INSUFFICIENT_ANSWER = "当前项目资料不足以回答该问题";

    private KnowledgeQaModels() {
    }

    /** 请求（§3.3 形状 + P3 实现追认的 rerank / documentNames 两个契约增量字段）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            int schemaVersion,
            UUID requestId,
            UUID projectId,
            UUID userId,
            UUID sessionId,
            String question,
            List<UUID> documentIds,
            List<UUID> visibleDocumentIds,
            Map<String, String> documentNames,
            int topK,
            List<HistoryEntry> history,
            String rerank,
            int deadlineMs) {
    }

    /** 历史消息条目（{@code repository.recentContext} 的快照；裁剪在 worker 执行）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryEntry(String role, String content, boolean insufficientEvidence, String createdAt) {
    }

    /** 事件：{@code token} / {@code finalize} / {@code error}（event 名来自 SSE 帧而非载荷）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(String event, String text, FinalizePayload finalizePayload, WorkerError error) {
        public static Event token(String text) {
            return new Event("token", text, null, null);
        }

        public static Event finalizeEvent(Finalize payload) {
            return new Event("finalize", null, new FinalizePayload(payload), null);
        }

        public static Event errorEvent(WorkerError error) {
            return new Event("error", null, null, error);
        }

        public boolean isToken() {
            return "token".equals(event);
        }

        public boolean isFinalize() {
            return "finalize".equals(event);
        }

        public boolean isError() {
            return "error".equals(event);
        }
    }

    /** Event 对 finalize 的包装（{@code finalize} 不能作为 Java record 组件名）。 */
    public record FinalizePayload(Finalize payload) {
    }

    /** token 事件载荷：{@code {"text": "增量"}}。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TokenPayload(String text) {
    }

    /** finalize 载荷（§3.3 草案冻结形状）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finalize(
            UUID requestId,
            String answer,
            boolean insufficientEvidence,
            List<Citation> citations,
            Retrieval retrieval,
            Prompt prompt,
            Model model) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Citation(
                UUID documentId,
                Integer chunkNo,
                String contentHash,
                UUID bodySnapshotId,
                Integer rank,
                Double similarity,
                String quote) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Retrieval(String strategy, String effectiveQuery, String routeReason, List<String> subQueries) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Prompt(String key, String version) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Model(
                String provider,
                String name,
                Long latencyMs,
                Integer promptTokens,
                Integer completionTokens) {
        }
    }

    /** error 事件载荷（终止性）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WorkerError(String code, String message) {
    }
}
