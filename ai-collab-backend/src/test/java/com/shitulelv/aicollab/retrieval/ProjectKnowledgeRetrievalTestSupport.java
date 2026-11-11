package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import com.shitulelv.aicollab.retrieval.pipeline.ParentChildExpander;
import com.shitulelv.aicollab.retrieval.pipeline.PassThroughReranker;
import com.shitulelv.aicollab.retrieval.pipeline.ReciprocalRankFusion;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalCandidate;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipeline;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalResult;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalTrace;
import com.shitulelv.aicollab.retrieval.pipeline.SentenceWindowExpander;
import com.shitulelv.aicollab.retrieval.query.MetadataFilter;
import com.shitulelv.aicollab.retrieval.query.QueryRouteDecision;
import com.shitulelv.aicollab.retrieval.query.QueryRouter;
import com.shitulelv.aicollab.retrieval.query.StructuredQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * J0-4 测试支撑：为只需要"拿到旧版 Dense 命中"的测试构造门面。
 *
 * <p>生产 {@code KnowledgeSearchAgentTool} 固定走 {@link ProjectKnowledgeRetrieval.Mode#RAG_V2}。
 * 历史测试用 mock {@link DocumentSearchService#search} 验证候选池、来源身份与范围逻辑；
 * 本工具类用一条"legacy-backed pipeline"把这些 mock 命中接入 RAG_V2 执行路径，
 * 从而在不引入真实向量/词法检索依赖的前提下保留原有断言。
 *
 * <p>需要真实验证 RAG V2 路由/管线契约的测试请直接使用
 * {@link #facade(ProjectAccessGuard, DocumentSearchService, RetrievalPipeline, QueryRouter)}
 * 传入真实或专用的 {@link RetrievalPipeline} 与 {@link QueryRouter}。
 */
public final class ProjectKnowledgeRetrievalTestSupport {

    private ProjectKnowledgeRetrievalTestSupport() {
    }

    /**
     * 旧版 Dense 语义的门面：RAG_V2 管线适配器直接委托 {@link DocumentSearchService#search}。
     *
     * <p>路由必须是 passthrough：迁移前的 4 参工具没有注入 QueryRouter，Dense 直接使用
     * 原始 query。若这里使用生产 QueryRouter，含《文档名》等结构化片段的查询会被改写成
     * 去掉片段的 semanticQuery，而 legacy 适配器又忽略 MetadataFilter，导致 P8/P9 的冻结
     * 基线输入与迁移前不同。passthrough 只返回原查询与空过滤，保持旧 Dense 语义。</p>
     */
    public static ProjectKnowledgeRetrieval legacyBacked(ProjectAccessGuard access, DocumentSearchService search) {
        return facade(access, search, legacyPipeline(search), passthroughRouter());
    }

    /**
     * 保留原始查询、不提取任何 MetadataFilter 的测试路由。
     *
     * <p>仅用于旧版 Dense 语义对照；需要真实 RAG V2 路由行为的测试必须显式传入生产
     * {@link QueryRouter}。</p>
     */
    static QueryRouter passthroughRouter() {
        return new QueryRouter() {
            @Override
            public QueryRouteDecision route(String rawQuery) {
                String query = rawQuery == null ? "" : rawQuery;
                return QueryRouteDecision.hybrid(
                        "legacy test adapter: preserve raw query and filters",
                        StructuredQuery.of(query, List.of()));
            }
        };
    }

    public static ProjectKnowledgeRetrieval facade(
            ProjectAccessGuard access,
            DocumentSearchService search,
            RetrievalPipeline pipeline,
            QueryRouter router) {
        return new ProjectKnowledgeRetrieval(access, search, pipeline, router);
    }

    /**
     * 有界的旧版 Dense 管线适配器：保留查询/文档范围/topK 语义，忽略 MetadataFilter
     * （旧版 DENSE_LEGACY 本就不下推过滤条件），并原样保留规范化引用身份。
     */
    public static RetrievalPipeline legacyPipeline(DocumentSearchService search) {
        return new RetrievalPipeline(
                (projectId, query, documentIds, topK) -> List.of(),
                (projectId, query, documentIds, topK) -> List.of(),
                new ReciprocalRankFusion(),
                new SentenceWindowExpander(),
                new ParentChildExpander(),
                new PassThroughReranker()) {
            @Override
            public RetrievalResult execute(
                    UUID projectId, String query, List<UUID> documentIds,
                    List<MetadataFilter> filters, int topK) {
                List<DocumentSearchHit> hits = search.search(projectId, query, documentIds, topK);
                if (hits == null || hits.isEmpty()) {
                    return new RetrievalResult(List.of(), emptyTrace());
                }
                List<RetrievalCandidate> candidates = new ArrayList<>(hits.size());
                for (int index = 0; index < hits.size(); index++) {
                    candidates.add(RetrievalCandidate.fromDenseHit(hits.get(index), index + 1));
                }
                return new RetrievalResult(List.copyOf(candidates), emptyTrace());
            }
        };
    }

    private static RetrievalTrace emptyTrace() {
        return new RetrievalTrace(0, 0, 0, 0, 0, 0, 0, 0, false, "legacy-adapter",
                false, false, Map.of(), List.of());
    }
}
