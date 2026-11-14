package com.shitulelv.aicollab.retrieval.pipeline;

import com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria;
import com.shitulelv.aicollab.retrieval.query.MetadataFilter;
import com.shitulelv.aicollab.retrieval.rerank.RerankMode;
import com.shitulelv.aicollab.retrieval.rerank.RerankModeRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 *   <li>重排器处理：根据 RerankerType 激活对应重排器（PassThrough, SemanticHeuristic、NeuralModel 或显式配置的 ConfiguredRerank），异常时平滑降级并标记。</li>
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
    private final RerankModeRouter rerankModeRouter;
    private final ConfiguredReranker configuredReranker;
    private final boolean configuredRerankEnabled;
    private final boolean inlineRerankTop8;
    private final RerankMode rerankMode;

    private static final int INLINE_RERANK_WINDOW = 8;

    @Autowired
    public RetrievalPipeline(
            DenseRetriever denseRetriever,
            LexicalRetriever lexicalRetriever,
            FusionStrategy fusionStrategy,
            SentenceWindowExpander sentenceWindowExpander,
            ParentChildExpander parentChildExpander,
            SemanticHeuristicReranker heuristicReranker,
            @Autowired(required = false) PassThroughReranker passThroughReranker,
            @Autowired(required = false) NeuralModelReranker neuralModelReranker,
            @Autowired(required = false) RerankModeRouter rerankModeRouter,
            @Autowired(required = false) ConfiguredReranker configuredReranker,
            @Value("${retrieval.configured-rerank.enabled:false}") boolean configuredRerankEnabled,
            @Value("${retrieval.inline-rerank-top8:false}") boolean inlineRerankTop8,
            @Value("${rerank.mode:java}") String rerankMode) {
        this(denseRetriever, lexicalRetriever, fusionStrategy, sentenceWindowExpander,
                parentChildExpander, heuristicReranker, passThroughReranker, neuralModelReranker,
                rerankModeRouter, configuredReranker, configuredRerankEnabled, inlineRerankTop8,
                RerankMode.from(rerankMode));
    }

    RetrievalPipeline(
            DenseRetriever denseRetriever,
            LexicalRetriever lexicalRetriever,
            FusionStrategy fusionStrategy,
            SentenceWindowExpander sentenceWindowExpander,
            ParentChildExpander parentChildExpander,
            SemanticHeuristicReranker heuristicReranker,
            PassThroughReranker passThroughReranker,
            NeuralModelReranker neuralModelReranker,
            RerankModeRouter rerankModeRouter,
            ConfiguredReranker configuredReranker,
            boolean configuredRerankEnabled,
            boolean inlineRerankTop8,
            RerankMode rerankMode) {
        this.denseRetriever = denseRetriever;
        this.lexicalRetriever = lexicalRetriever;
        this.fusionStrategy = fusionStrategy != null ? fusionStrategy : new ReciprocalRankFusion();
        this.sentenceWindowExpander = sentenceWindowExpander != null ? sentenceWindowExpander : new SentenceWindowExpander();
        this.parentChildExpander = parentChildExpander != null ? parentChildExpander : new ParentChildExpander();
        this.defaultReranker = heuristicReranker != null ? heuristicReranker : new SemanticHeuristicReranker();
        this.rerankModeRouter = rerankModeRouter;
        this.configuredReranker = configuredReranker;
        this.configuredRerankEnabled = configuredRerankEnabled;
        this.inlineRerankTop8 = inlineRerankTop8;
        this.rerankMode = rerankMode != null ? rerankMode : RerankMode.JAVA;

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
        if (configuredReranker != null) {
            rerankers.put(RerankerType.CONFIGURED_RERANK, configuredReranker);
        }
    }

    /** 兼容既有直接构造调用；生产装配使用带 ConfiguredReranker 的构造器。 */
    public RetrievalPipeline(
            DenseRetriever denseRetriever,
            LexicalRetriever lexicalRetriever,
            FusionStrategy fusionStrategy,
            SentenceWindowExpander sentenceWindowExpander,
            ParentChildExpander parentChildExpander,
            SemanticHeuristicReranker heuristicReranker,
            PassThroughReranker passThroughReranker,
            NeuralModelReranker neuralModelReranker,
            RerankModeRouter rerankModeRouter) {
        this(denseRetriever, lexicalRetriever, fusionStrategy, sentenceWindowExpander,
                parentChildExpander, heuristicReranker, passThroughReranker, neuralModelReranker,
                rerankModeRouter, null, false, false, RerankMode.JAVA);
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
        this.rerankModeRouter = null;
        this.configuredReranker = null;
        this.configuredRerankEnabled = false;
        this.inlineRerankTop8 = false;
        this.rerankMode = RerankMode.JAVA;

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

    /**
     * 多查询分解合并执行（COMPLEX 路由修复，RAG_V2_QUALITY_REGRESSION 复测直接阻断项）。
     *
     * <p>历史缺陷一：各子查询独立走完整管线后仅按 {@code putIfAbsent} 顺序拼接，
     * 子查询切分会把原始查询下排名 1–5 的证据挤到合并列表第 9 位之后，最终被来源名额淘汰。
     * 历史缺陷二：对全部查询做标准 RRF 求和时，跨子查询共现的候选会<b>扰动</b>原始查询本已
     * 正确的排序（实测：G02-B-4DOCS 的两个 fullRank=1/4 事实被挤出 5 源名额，QA 51/54）；
     * 而消融实验（fullDefault，原始查询单跑）证明原始查询过完整管线即达 52/54 基线。</p>
     *
     * <p>修复语义（主查询保序 + 子查询增召回）：列表<b>首个查询为主查询</b>（调用方传入原始查询）。
     * 每个查询独立走完整管线（Dense/Lexical → RRF → 窗口/父子 → 重排）；合并顺序为主查询
     * 结果的原始排名序置前，子查询独有候选（主查询未召回）按跨查询 RRF 之和降序追加——
     * 子查询只增召回、不扰动主排名。融合池按查询数有界扩展（topK + 3×(n-1)，上限 24）。
     * 主查询无召回时回退为按 RRF 之和的全局融合。准入语义不变：admissionScore 仍由
     * 类型化评分契约在转换层生成。仅传一个查询时等价于单查询执行。</p>
     */
    public RetrievalResult executeMultiQuery(
            UUID projectId, List<String> queries, List<UUID> documentIds,
            List<MetadataFilter> filters, int topK) {
        List<String> effectiveQueries = queries == null ? List.of()
                : queries.stream().filter(q -> q != null && !q.isBlank()).distinct().toList();
        if (effectiveQueries.isEmpty()) {
            return execute(projectId, "", documentIds, filters, topK);
        }
        if (effectiveQueries.size() == 1) {
            return execute(projectId, effectiveQueries.getFirst(), documentIds, filters, topK);
        }

        long startTotal = System.currentTimeMillis();
        Map<UUID, Double> fusedScore = new LinkedHashMap<>();
        Map<UUID, RetrievalCandidate> bestInstanceByRrf = new LinkedHashMap<>();
        List<RetrievalCandidate> primaryCandidates = List.of();
        long denseMs = 0, lexicalMs = 0, rerankMs = 0;
        int denseCount = 0, lexicalCount = 0;
        boolean rerankerDegraded = false;
        boolean swExpanded = false, parentExpanded = false;
        String rerankerName = null;
        List<RetrievalTrace.DropRecord> dropReasons = new ArrayList<>();

        for (int queryIndex = 0; queryIndex < effectiveQueries.size(); queryIndex++) {
            String query = effectiveQueries.get(queryIndex);
            RetrievalResult subResult = execute(projectId, query, documentIds, filters, topK);
            var trace = subResult.trace();
            denseMs += trace.denseDurationMs();
            lexicalMs += trace.lexicalDurationMs();
            rerankMs += trace.rerankDurationMs();
            denseCount += trace.denseRecallCount();
            lexicalCount += trace.lexicalRecallCount();
            rerankerDegraded |= trace.rerankerDegraded();
            swExpanded |= trace.sentenceWindowExpanded();
            parentExpanded |= trace.parentExpanded();
            if (rerankerName == null) {
                rerankerName = trace.rerankerName();
            }
            if (queryIndex == 0) {
                primaryCandidates = subResult.candidates();
            }
            for (RetrievalCandidate candidate : subResult.candidates()) {
                double rrf = candidate.rrfScore() != null ? candidate.rrfScore() : 0.0;
                fusedScore.merge(candidate.id(), rrf, Double::sum);
                RetrievalCandidate best = bestInstanceByRrf.get(candidate.id());
                double bestRrf = best != null && best.rrfScore() != null ? best.rrfScore() : 0.0;
                if (best == null || rrf > bestRrf) {
                    bestInstanceByRrf.put(candidate.id(), candidate);
                }
            }
        }

        // 合并：主查询排名序置前（保序），子查询独有候选按 RRF 之和降序追加，平分按 chunkId 稳定收敛。
        // 主查询无召回时 consolidated 为空，全部候选按 RRF 之和的全局融合回退。
        List<RetrievalCandidate> consolidated = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (RetrievalCandidate candidate : primaryCandidates) {
            if (seen.add(candidate.id())) {
                consolidated.add(candidate);
            }
        }
        List<RetrievalCandidate> extras = new ArrayList<>();
        for (RetrievalCandidate candidate : bestInstanceByRrf.values()) {
            if (seen.add(candidate.id())) {
                extras.add(candidate);
            }
        }
        extras.sort((a, b) -> {
            int cmp = Double.compare(fusedScore.getOrDefault(b.id(), 0.0), fusedScore.getOrDefault(a.id(), 0.0));
            return cmp != 0 ? cmp : a.id().compareTo(b.id());
        });
        consolidated.addAll(extras);

        // 融合池随查询数有界扩展（子查询召回的增量候选必须能进入装配选择，但总量有绝对上限）。
        int fusedTopN = Math.min(topK + (effectiveQueries.size() - 1) * 3, 24);
        List<RetrievalCandidate> finalCandidates = consolidated;
        if (fusedTopN > 0 && consolidated.size() > fusedTopN) {
            finalCandidates = new ArrayList<>(consolidated.subList(0, fusedTopN));
            for (int index = fusedTopN; index < consolidated.size(); index++) {
                dropReasons.add(new RetrievalTrace.DropRecord(consolidated.get(index).id(), "MULTI_QUERY_FUSION_TRUNCATED"));
            }
        }

        Map<UUID, Double> rrfScores = new LinkedHashMap<>();
        for (RetrievalCandidate candidate : finalCandidates) {
            rrfScores.put(candidate.id(), fusedScore.getOrDefault(candidate.id(), 0.0));
        }

        RetrievalTrace trace = new RetrievalTrace(
                denseMs, lexicalMs, rerankMs, System.currentTimeMillis() - startTotal,
                denseCount, lexicalCount, consolidated.size(), finalCandidates.size(),
                rerankerDegraded, rerankerName != null ? rerankerName : "degraded",
                swExpanded, parentExpanded,
                Collections.unmodifiableMap(rrfScores), Collections.unmodifiableList(dropReasons));
        return new RetrievalResult(Collections.unmodifiableList(finalCandidates), trace);
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

        RerankerType requestedType = config.rerankerType() != null
                ? config.rerankerType() : RerankerType.LEXICAL_HEURISTIC;
        RerankerType effectiveType = requestedType;
        if (configuredRerankEnabled && requestedType == RerankerType.LEXICAL_HEURISTIC
                && configuredReranker != null) {
            effectiveType = RerankerType.CONFIGURED_RERANK;
        }
        Reranker activeReranker = selectReranker(effectiveType);
        if (effectiveType == RerankerType.CONFIGURED_RERANK && configuredReranker != null) {
            activeReranker = configuredReranker.forProject(projectId);
        }
        // W3：rerank.mode 作用在检索管线层（PASS_THROUGH 的"不重排"语义不得被包装改写）。
        if (rerankModeRouter != null && effectiveType != RerankerType.PASS_THROUGH) {
            activeReranker = rerankModeRouter.wrap(activeReranker, projectId);
        }
        boolean usesConfiguredReranker = effectiveType == RerankerType.CONFIGURED_RERANK
                && configuredReranker != null;
        boolean usesPythonReranker = rerankModeRouter != null
                && rerankMode == RerankMode.PYTHON
                && effectiveType != RerankerType.PASS_THROUGH;
        boolean useInlineWindow = inlineRerankTop8
                && rerankMode != RerankMode.SHADOW
                && (usesConfiguredReranker || usesPythonReranker);

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

        // 2. Lexical 检索（透传 criteria 元数据过滤）；DENSE 策略下整体跳过（不执行词法 SQL）
        long startLexical = System.currentTimeMillis();
        List<RetrievalCandidate> lexicalCandidates;
        if (!config.lexicalEnabled()) {
            lexicalCandidates = List.of();
        } else if (lexicalRetriever != null) {
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
            if (useInlineWindow && candidates.size() > INLINE_RERANK_WINDOW) {
                List<RetrievalCandidate> head = new ArrayList<>(
                        candidates.subList(0, INLINE_RERANK_WINDOW));
                List<RetrievalCandidate> tail = new ArrayList<>(
                        candidates.subList(INLINE_RERANK_WINDOW, candidates.size()));
                List<RetrievalCandidate> rerankedHead = activeReranker.rerank(query, head, head.size());
                if (rerankedHead == null) {
                    rerankedHead = head;
                }
                rerankedHead = truncate(new ArrayList<>(rerankedHead), head.size());
                List<RetrievalCandidate> combined = new ArrayList<>(
                        rerankedHead.size() + tail.size());
                combined.addAll(rerankedHead);
                combined.addAll(tail);
                finalCandidates = truncate(combined, config.finalTopN());
            } else {
                finalCandidates = activeReranker.rerank(query, candidates, config.finalTopN());
            }
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

    private static List<RetrievalCandidate> truncate(List<RetrievalCandidate> candidates, int topN) {
        if (topN > 0 && candidates.size() > topN) {
            return new ArrayList<>(candidates.subList(0, topN));
        }
        return candidates;
    }

    public record PipelineConfig(
            int denseTopK,
            int lexicalTopK,
            int rrfTopK,
            int finalTopN,
            boolean enableSentenceWindow,
            int sentenceWindowSize,
            boolean enableParentChildExpansion,
            boolean lexicalEnabled,
            RerankerType rerankerType,
            List<MetadataFilter> filters,
            DocumentFilterCriteria criteria
    ) {
        /** 兼容旧 10 参形态：词法通道默认开启。 */
        public PipelineConfig(int denseTopK, int lexicalTopK, int rrfTopK, int finalTopN,
                boolean enableSentenceWindow, int sentenceWindowSize, boolean enableParentChildExpansion,
                RerankerType rerankerType, List<MetadataFilter> filters, DocumentFilterCriteria criteria) {
            this(denseTopK, lexicalTopK, rrfTopK, finalTopN, enableSentenceWindow, sentenceWindowSize,
                    enableParentChildExpansion, true, rerankerType, filters, criteria);
        }
        public static PipelineConfig defaultConfig() {
            return new PipelineConfig(20, 20, 20, 10, true, 1, true, RerankerType.LEXICAL_HEURISTIC, List.of(), null);
        }

        /** Dense-only 配置（阶段 3 策略路径）：跳过词法 SQL、关闭窗口/父子扩展、透传排序。 */
        public static PipelineConfig denseOnlyConfig() {
            return new PipelineConfig(20, 20, 20, 10, false, 1, false, false, RerankerType.PASS_THROUGH, List.of(), null);
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
            return new PipelineConfig(denseTopK, lexicalTopK, rrfTopK, finalTopN, enableSentenceWindow, sentenceWindowSize, enableParentChildExpansion, lexicalEnabled, type, filters, criteria);
        }

        public PipelineConfig withFilters(List<MetadataFilter> newFilters) {
            return new PipelineConfig(denseTopK, lexicalTopK, rrfTopK, finalTopN, enableSentenceWindow, sentenceWindowSize, enableParentChildExpansion, lexicalEnabled, rerankerType, newFilters, criteria);
        }

        public PipelineConfig withCriteria(DocumentFilterCriteria newCriteria) {
            return new PipelineConfig(denseTopK, lexicalTopK, rrfTopK, finalTopN, enableSentenceWindow, sentenceWindowSize, enableParentChildExpansion, lexicalEnabled, rerankerType, filters, newCriteria);
        }
    }
}
