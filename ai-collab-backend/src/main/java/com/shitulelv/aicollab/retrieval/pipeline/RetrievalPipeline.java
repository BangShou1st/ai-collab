package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria;
import com.shitulelv.aicollab.retrieval.query.MetadataFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 统一多路混合检索管线 (Hybrid Retrieval Pipeline)。
 *
 * <p>执行流程：
 * <ol>
 *   <li>多路检索：同时召回 pgvector 向量检索与 PostgreSQL pg_trgm 词项检索结果，并在召回层下推元数据硬过滤。</li>
 *   <li>RRF 融合：基于 Chunk ID 进行稳定互惠排名融合 (Reciprocal Rank Fusion)。</li>
 *   <li>句子窗口扩展：若开启，恢复前后句上下文并对重叠窗口自动去重合并。</li>
 *   <li>父子块回读：若开启，递归扩展父级上下文并严格执行版本一致性校验。</li>
 *   <li>重排器处理：根据 RerankerType 激活对应重排器（PassThrough, SemanticHeuristic 或 NeuralModel），异常时平滑降级并标记。</li>
 *   <li>生成全链路 Trace 诊断追踪。</li>
 * </ol>
 */
@Service
public class RetrievalPipeline {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPipeline.class);

    private final Retriever denseRetriever;
    private final Retriever lexicalRetriever;
    private final FusionStrategy fusionStrategy;
    private final SentenceWindowExpander sentenceWindowExpander;
    private final ParentChildExpander parentChildExpander;
    private final Reranker defaultReranker;
    private final Map<RerankerType, Reranker> rerankers = new EnumMap<>(RerankerType.class);

    @Autowired
    public RetrievalPipeline(
            DenseRetriever denseRetriever,
            LexicalRetriever lexicalRetriever,
            FusionStrategy fusionStrategy,
            SentenceWindowExpander sentenceWindowExpander,
            ParentChildExpander parentChildExpander,
            SemanticHeuristicReranker heuristicReranker,
            @Autowired(required = false) PassThroughReranker passThroughReranker,
            @Autowired(required = false) NeuralModelReranker neuralModelReranker) {
        this.denseRetriever = denseRetriever;
        this.lexicalRetriever = lexicalRetriever;
        this.fusionStrategy = fusionStrategy != null ? fusionStrategy : new ReciprocalRankFusion();
        this.sentenceWindowExpander = sentenceWindowExpander != null ? sentenceWindowExpander : new SentenceWindowExpander();
        this.parentChildExpander = parentChildExpander != null ? parentChildExpander : new ParentChildExpander();
        this.defaultReranker = heuristicReranker != null ? heuristicReranker : new SemanticHeuristicReranker();

        rerankers.put(RerankerType.LEXICAL_HEURISTIC, this.defaultReranker);
        if (passThroughReranker != null) {
            rerankers.put(RerankerType.PASS_THROUGH, passThroughReranker);
        } else {
            rerankers.put(RerankerType.PASS_THROUGH, new PassThroughReranker());
        }
        if (neuralModelReranker != null) {
            rerankers.put(RerankerType.NEURAL_MODEL, neuralModelReranker);
        } else {
            rerankers.put(RerankerType.NEURAL_MODEL, new NeuralModelReranker());
        }
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
        this.defaultReranker = reranker != null ? reranker : new SemanticHeuristicReranker();

        rerankers.put(RerankerType.LEXICAL_HEURISTIC, this.defaultReranker);
        rerankers.put(RerankerType.PASS_THROUGH, new PassThroughReranker());
        rerankers.put(RerankerType.NEURAL_MODEL, new NeuralModelReranker());
    }

    public Reranker selectReranker(RerankerType type) {
        if (type == null) {
            return defaultReranker;
        }
        Reranker selected = rerankers.get(type);
        return selected != null ? selected : defaultReranker;
    }

    public RetrievalResult execute(UUID projectId, String query, List<UUID> documentIds) {
        return execute(projectId, query, documentIds, List.of(), PipelineConfig.defaultConfig());
    }

    public RetrievalResult execute(UUID projectId, String query, List<UUID> documentIds, int topK) {
        return execute(projectId, query, documentIds, List.of(), topK);
    }

    public RetrievalResult execute(UUID projectId, String query, List<UUID> documentIds, List<MetadataFilter> filters, int topK) {
        PipelineConfig config = new PipelineConfig(
                Math.max(20, topK * 2),
                Math.max(20, topK * 2),
                Math.max(20, topK * 2),
                topK,
                true,
                1,
                true,
                RerankerType.LEXICAL_HEURISTIC,
                filters,
                null
        );
        return execute(projectId, query, documentIds, filters, config);
    }

    public RetrievalResult execute(UUID projectId, String query, List<UUID> documentIds, PipelineConfig config) {
        return execute(projectId, query, documentIds, List.of(), config);
    }

    public RetrievalResult execute(UUID projectId, String query, List<UUID> documentIds, List<MetadataFilter> filters, PipelineConfig config) {
        if (config == null) {
            config = PipelineConfig.defaultConfig();
        }
        List<MetadataFilter> combinedFilters = new ArrayList<>();
        if (config.filters() != null) {
            combinedFilters.addAll(config.filters());
        }
        if (filters != null && !filters.isEmpty()) {
            for (MetadataFilter f : filters) {
                if (!combinedFilters.contains(f)) {
                    combinedFilters.add(f);
                }
            }
        }

        DocumentFilterCriteria criteria = config.criteria();
        if (criteria == null && !combinedFilters.isEmpty()) {
            criteria = DocumentFilterCriteria.fromFilters(combinedFilters);
        }

        return executeInternal(projectId, query, documentIds, combinedFilters, criteria, config);
    }

    public RetrievalResult execute(UUID projectId, String query, List<UUID> documentIds, DocumentFilterCriteria criteria, PipelineConfig config) {
        if (config == null) {
            config = PipelineConfig.defaultConfig();
        }
        return executeInternal(projectId, query, documentIds, config.filters(), criteria, config);
    }

    private RetrievalResult executeInternal(
            UUID projectId,
            String query,
            List<UUID> documentIds,
            List<MetadataFilter> filters,
            DocumentFilterCriteria criteria,
            PipelineConfig config) {

        long startTotal = System.currentTimeMillis();
        List<RetrievalTrace.DropRecord> dropReasons = new ArrayList<>();

        Reranker activeReranker = selectReranker(config.rerankerType());

        if (query == null || query.isBlank()) {
            RetrievalTrace emptyTrace = new RetrievalTrace(
                    0, 0, 0, 0, 0, 0, 0, 0,
                    false, activeReranker.name(), false, false,
                    Map.of(), List.of()
            );
            return new RetrievalResult(List.of(), emptyTrace);
        }

        // 1. Dense 检索（透传 criteria 元数据过滤）
        long startDense = System.currentTimeMillis();
        List<RetrievalCandidate> denseCandidates;
        if (denseRetriever != null) {
            try {
                if (criteria != null) {
                    denseCandidates = denseRetriever.retrieve(projectId, query, documentIds, config.denseTopK(), criteria);
                } else {
                    denseCandidates = denseRetriever.retrieve(projectId, query, documentIds, config.denseTopK());
                }
            } catch (Exception e) {
                log.warn("Dense 检索异常或未配置嵌入模型，平滑降级为词项检索: project={}, query={}, err={}", projectId, query, e.getMessage());
                denseCandidates = List.of();
            }
        } else {
            denseCandidates = List.of();
        }
        long denseDuration = System.currentTimeMillis() - startDense;

        // 2. Lexical 检索（透传 criteria 元数据过滤）
        long startLexical = System.currentTimeMillis();
        List<RetrievalCandidate> lexicalCandidates;
        if (lexicalRetriever != null) {
            try {
                if (criteria != null) {
                    lexicalCandidates = lexicalRetriever.retrieve(projectId, query, documentIds, config.lexicalTopK(), criteria);
                } else {
                    lexicalCandidates = lexicalRetriever.retrieve(projectId, query, documentIds, config.lexicalTopK());
                }
            } catch (Exception e) {
                log.warn("Lexical 检索异常，平滑降级: project={}, query={}, err={}", projectId, query, e.getMessage());
                lexicalCandidates = List.of();
            }
        } else {
            lexicalCandidates = List.of();
        }
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
        String activeRerankerName = activeReranker.name();
        List<RetrievalCandidate> finalCandidates;

        try {
            finalCandidates = activeReranker.rerank(query, candidates, config.finalTopN());
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
            boolean enableParentChildExpansion,
            RerankerType rerankerType,
            List<MetadataFilter> filters,
            DocumentFilterCriteria criteria
    ) {
        public static PipelineConfig defaultConfig() {
            return new PipelineConfig(20, 20, 20, 10, true, 1, true, RerankerType.LEXICAL_HEURISTIC, List.of(), null);
        }

        public PipelineConfig(
                int denseTopK,
                int lexicalTopK,
                int rrfTopK,
                int finalTopN,
                boolean enableSentenceWindow,
                int sentenceWindowSize,
                boolean enableParentChildExpansion
        ) {
            this(denseTopK, lexicalTopK, rrfTopK, finalTopN, enableSentenceWindow, sentenceWindowSize, enableParentChildExpansion, RerankerType.LEXICAL_HEURISTIC, List.of(), null);
        }

        public PipelineConfig withRerankerType(RerankerType type) {
            return new PipelineConfig(denseTopK, lexicalTopK, rrfTopK, finalTopN, enableSentenceWindow, sentenceWindowSize, enableParentChildExpansion, type, filters, criteria);
        }

        public PipelineConfig withFilters(List<MetadataFilter> newFilters) {
            return new PipelineConfig(denseTopK, lexicalTopK, rrfTopK, finalTopN, enableSentenceWindow, sentenceWindowSize, enableParentChildExpansion, rerankerType, newFilters, criteria);
        }

        public PipelineConfig withCriteria(DocumentFilterCriteria newCriteria) {
            return new PipelineConfig(denseTopK, lexicalTopK, rrfTopK, finalTopN, enableSentenceWindow, sentenceWindowSize, enableParentChildExpansion, rerankerType, filters, newCriteria);
        }
    }
}
