package com.shitulelv.aicollab.retrieval.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 统一多路混合检索管线 (Hybrid Retrieval Pipeline)。
 *
 * <p>执行流程：
 * <ol>
 *   <li>多路检索：同时召回 pgvector 向量检索与 PostgreSQL pg_trgm 词项检索结果。</li>
 *   <li>RRF 融合：基于 Chunk ID 进行稳定互惠排名融合 (Reciprocal Rank Fusion)。</li>
 *   <li>句子窗口扩展：若开启，恢复前后句上下文并对重叠窗口自动去重合并。</li>
 *   <li>父子块回读：若开启，递归扩展父级上下文并严格执行版本一致性校验。</li>
 *   <li>重排器处理：执行语义启发式二次重排，异常时平滑降级并标记。</li>
 *   <li>生成全链路 Trace 诊断追踪。</li>
 * </ol>
 */
@Service
public class RetrievalPipeline {

    private final Retriever denseRetriever;
    private final Retriever lexicalRetriever;
    private final FusionStrategy fusionStrategy;
    private final SentenceWindowExpander sentenceWindowExpander;
    private final ParentChildExpander parentChildExpander;
    private final Reranker reranker;

    @Autowired
    public RetrievalPipeline(
            DenseRetriever denseRetriever,
            LexicalRetriever lexicalRetriever,
            FusionStrategy fusionStrategy,
            SentenceWindowExpander sentenceWindowExpander,
            ParentChildExpander parentChildExpander,
            SemanticHeuristicReranker reranker) {
        this((Retriever) denseRetriever, lexicalRetriever, fusionStrategy, sentenceWindowExpander, parentChildExpander, reranker);
    }

    public RetrievalPipeline(
            Retriever denseRetriever,
            Retriever lexicalRetriever,
            FusionStrategy fusionStrategy,
            SentenceWindowExpander sentenceWindowExpander,
            ParentChildExpander parentChildExpander,
            Reranker reranker) {
        this.denseRetriever = denseRetriever;
        this.lexicalRetriever = lexicalRetriever;
        this.fusionStrategy = fusionStrategy != null ? fusionStrategy : new ReciprocalRankFusion();
        this.sentenceWindowExpander = sentenceWindowExpander != null ? sentenceWindowExpander : new SentenceWindowExpander();
        this.parentChildExpander = parentChildExpander != null ? parentChildExpander : new ParentChildExpander();
        this.reranker = reranker != null ? reranker : new SemanticHeuristicReranker();
    }

    public RetrievalResult execute(UUID projectId, String query, List<UUID> documentIds) {
        return execute(projectId, query, documentIds, PipelineConfig.defaultConfig());
    }

    public RetrievalResult execute(UUID projectId, String query, List<UUID> documentIds, int topK) {
        PipelineConfig config = new PipelineConfig(
                Math.max(20, topK * 2),
                Math.max(20, topK * 2),
                Math.max(20, topK * 2),
                topK,
                true,
                1,
                true
        );
        return execute(projectId, query, documentIds, config);
    }

    public RetrievalResult execute(UUID projectId, String query, List<UUID> documentIds, PipelineConfig config) {
        long startTotal = System.currentTimeMillis();
        List<RetrievalTrace.DropRecord> dropReasons = new ArrayList<>();

        if (query == null || query.isBlank()) {
            RetrievalTrace emptyTrace = new RetrievalTrace(
                    0, 0, 0, 0, 0, 0, 0, 0,
                    false, reranker.name(), false, false,
                    Map.of(), List.of()
            );
            return new RetrievalResult(List.of(), emptyTrace);
        }

        // 1. Dense 检索
        long startDense = System.currentTimeMillis();
        List<RetrievalCandidate> denseCandidates = denseRetriever != null
                ? denseRetriever.retrieve(projectId, query, documentIds, config.denseTopK())
                : List.of();
        long denseDuration = System.currentTimeMillis() - startDense;

        // 2. Lexical 检索
        long startLexical = System.currentTimeMillis();
        List<RetrievalCandidate> lexicalCandidates = lexicalRetriever != null
                ? lexicalRetriever.retrieve(projectId, query, documentIds, config.lexicalTopK())
                : List.of();
        long lexicalDuration = System.currentTimeMillis() - startLexical;

        // 收集所有被召回的 Chunk ID 用于跟踪截断
        Set<UUID> allRecalledIds = new LinkedHashSet<>();
        for (RetrievalCandidate c : denseCandidates) allRecalledIds.add(c.id());
        for (RetrievalCandidate c : lexicalCandidates) allRecalledIds.add(c.id());

        // 3. RRF 融合
        List<RetrievalCandidate> fused = fusionStrategy.fuse(denseCandidates, lexicalCandidates, config.rrfTopK());
        Map<UUID, Double> rrfScores = new LinkedHashMap<>();
        Set<UUID> fusedIds = new HashSet<>();
        for (RetrievalCandidate c : fused) {
            fusedIds.add(c.id());
            rrfScores.put(c.id(), c.rrfScore());
        }

        for (UUID id : allRecalledIds) {
            if (!fusedIds.contains(id)) {
                dropReasons.add(new RetrievalTrace.DropRecord(id, "RRF_TOP_K_TRUNCATED"));
            }
        }

        // 4. 句子窗口扩展与父子块回读
        List<RetrievalCandidate> candidates = fused;
        boolean swExpanded = false;
        if (config.enableSentenceWindow() && sentenceWindowExpander != null) {
            candidates = sentenceWindowExpander.expand(projectId, candidates, config.sentenceWindowSize());
            swExpanded = true;
        }

        boolean parentExpanded = false;
        if (config.enableParentChildExpansion() && parentChildExpander != null) {
            candidates = parentChildExpander.expand(projectId, candidates);
            parentExpanded = true;
        }

        // 5. Reranker 重排与不可变证据契约保护
        long startRerank = System.currentTimeMillis();
        boolean rerankerDegraded = false;
        String activeRerankerName = reranker.name();
        List<RetrievalCandidate> finalCandidates;

        try {
            finalCandidates = reranker.rerank(query, candidates, config.finalTopN());
            if (finalCandidates.stream().anyMatch(c -> Boolean.TRUE.equals(c.metadata().get("rerankerDegraded")))) {
                rerankerDegraded = true;
                activeRerankerName = "degraded";
            }
        } catch (Throwable t) {
            rerankerDegraded = true;
            activeRerankerName = "degraded";
            finalCandidates = candidates.stream().limit(config.finalTopN()).toList();
        }
        long rerankDuration = System.currentTimeMillis() - startRerank;

        Set<UUID> finalIds = new HashSet<>();
        for (RetrievalCandidate c : finalCandidates) finalIds.add(c.id());
        for (UUID fid : fusedIds) {
            if (!finalIds.contains(fid)) {
                dropReasons.add(new RetrievalTrace.DropRecord(fid, "RERANK_TOP_N_TRUNCATED"));
            }
        }

        long totalDuration = System.currentTimeMillis() - startTotal;

        RetrievalTrace trace = new RetrievalTrace(
                denseDuration,
                lexicalDuration,
                rerankDuration,
                totalDuration,
                denseCandidates.size(),
                lexicalCandidates.size(),
                fused.size(),
                finalCandidates.size(),
                rerankerDegraded,
                activeRerankerName,
                swExpanded,
                parentExpanded,
                Collections.unmodifiableMap(rrfScores),
                Collections.unmodifiableList(dropReasons)
        );

        return new RetrievalResult(Collections.unmodifiableList(finalCandidates), trace);
    }

    public record PipelineConfig(
            int denseTopK,
            int lexicalTopK,
            int rrfTopK,
            int finalTopN,
            boolean enableSentenceWindow,
            int sentenceWindowSize,
            boolean enableParentChildExpansion
    ) {
        public static PipelineConfig defaultConfig() {
            return new PipelineConfig(20, 20, 20, 10, true, 1, true);
        }
    }
}
