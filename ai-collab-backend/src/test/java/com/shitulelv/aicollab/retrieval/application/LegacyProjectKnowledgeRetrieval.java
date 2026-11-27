package com.shitulelv.aicollab.retrieval.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipeline;
import com.shitulelv.aicollab.retrieval.query.EvidenceSufficiencyProbe;
import com.shitulelv.aicollab.retrieval.query.MetadataFilter;
import com.shitulelv.aicollab.retrieval.query.QueryRouteDecision;
import com.shitulelv.aicollab.retrieval.query.QueryRouter;
import com.shitulelv.aicollab.retrieval.query.RetrievalStrategy;
import com.shitulelv.aicollab.retrieval.query.StrategyAwareRetrievalExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 项目知识检索统一门面（J0-4）。
 *
 * <p>唯一职责边界：项目成员权限、READY/撤权过滤、查询嵌入快照入口、候选池上界与
 * 规范引用身份全部由本门面持有。四类调用方（Agent 知识检索工具、知识问答、流式问答、
 * 任务规划）只表达"检索意图"，不再各自决定权限、模式或候选身份。</p>
 *
 * <p>检索算法、排序、pgvector SQL 与引用格式均不在此层改变：
 * <ul>
 *   <li>所有业务调用方均通过 {@link RetrievalIntent} 表达业务意图，统一经 {@link QueryRouter} +
 *       {@link StrategyAwareRetrievalExecutor} 执行（支持策略路由、多子查询与文档范围收窄）；</li>
 *   <li>仅当配置 {@code retrieval.force-legacy-dense=true}、QA 配置显式降级 {@code DENSE_LEGACY}
 *       或历史代码显式传入 {@link Mode#DENSE_LEGACY} 时，才退回旧版 Dense；</li>
 *   <li>{@link Mode#RAG_V2} 在注入 {@link StrategyAwareRetrievalExecutor} 后同样委托策略感知执行器，
 *       仅在纯单元测试 4 参构造（无 {@code StrategyAwareRetrievalExecutor} Bean）时保留管线直连以兼容测试桩。</li>
 * </ul>
 *
 * <p>返回的 {@link DocumentSearchHit} 即规范引用身份（documentId/chunkId/filename/heading/
 * content/similarity/contentHash/metadata）：门面只透传，不生成、不重排、不改写 ID。</p>
 */
public class LegacyProjectKnowledgeRetrieval {

    public static final String RETRIEVAL_CORE_LEGACY_DENSE = "LEGACY_DENSE";
    public static final String RETRIEVAL_CORE_RAG_V2_PIPELINE = "RAG_V2_PIPELINE";
    public static final String RETRIEVAL_CORE_RAG_V2_STRATEGY_AWARE = "RAG_V2_STRATEGY_AWARE";

    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;

    private final ProjectAccessGuard access;
    private final DocumentSearchService documentSearch;
    private final RetrievalPipeline retrievalPipeline;
    private final QueryRouter queryRouter;
    private final StrategyAwareRetrievalExecutor strategyAwareExecutor;

    @Value("${retrieval.force-legacy-dense:false}")
    private boolean forceLegacyDense;

    @Autowired(required = false)
    private RetrievalQaProperties retrievalQaProperties;

    public LegacyProjectKnowledgeRetrieval(
            ProjectAccessGuard access,
            DocumentSearchService documentSearch,
            RetrievalPipeline retrievalPipeline,
            QueryRouter queryRouter) {
        this(access, documentSearch, retrievalPipeline, queryRouter, null);
    }

    @Autowired
    public LegacyProjectKnowledgeRetrieval(
            ProjectAccessGuard access,
            DocumentSearchService documentSearch,
            RetrievalPipeline retrievalPipeline,
            QueryRouter queryRouter,
            StrategyAwareRetrievalExecutor strategyAwareExecutor) {
        this.access = access;
        this.documentSearch = documentSearch;
        this.retrievalPipeline = retrievalPipeline;
        this.queryRouter = queryRouter;
        this.strategyAwareExecutor = strategyAwareExecutor;
    }

    void setForceLegacyDense(boolean forceLegacyDense) {
        this.forceLegacyDense = forceLegacyDense;
    }

    void setRetrievalQaProperties(RetrievalQaProperties retrievalQaProperties) {
        this.retrievalQaProperties = retrievalQaProperties;
    }

    /** 业务检索意图（调用方只表达业务意图，不硬编码底层检索模式）。 */
    public enum RetrievalIntent {
        /** 用户知识问答：策略感知，注重证据充分性与回退。 */
        KNOWLEDGE_QA,
        /** Agent 调研：策略感知，注重跨文档覆盖与证据引用。 */
        AGENT_RESEARCH,
        /** 任务规划初始资料：指定来源与严格证据筛选。 */
        PLANNING_SOURCE,
        /** 任务规划详情轮次工具检索：同项目策略感知检索。 */
        PLANNING_TOOL_LOOKUP
    }

    /** 兼容历史 {@link Request} 调用方的检索模式枚举。 */
    public enum Mode {
        DENSE_LEGACY,
        RAG_V2,
        STRATEGY_AWARE
    }

    /**
     * 基于业务意图的统一检索请求。documentIds 为 null 或空表示全项目检索。
     */
    public record IntentRequest(
            UUID projectId,
            UUID userId,
            String query,
            List<UUID> documentIds,
            int topK,
            RetrievalIntent intent) {
    }

    /**
     * 检索请求（兼容旧调用方）。documentIds 为 null 或空表示全项目检索。
     */
    public record Request(
            UUID projectId,
            UUID userId,
            String query,
            List<UUID> documentIds,
            int topK,
            Mode mode) {
    }

    /**
     * 检索结果。hits 是规范引用身份的唯一事实来源；effectiveQuery/filters/appliedStrategy 仅用于诊断，
     * retrievalCore 取值为 {@link #RETRIEVAL_CORE_LEGACY_DENSE}、
     * {@link #RETRIEVAL_CORE_RAG_V2_PIPELINE} 或
     * {@link #RETRIEVAL_CORE_RAG_V2_STRATEGY_AWARE}。
     */
    public record Result(
            List<DocumentSearchHit> hits,
            String effectiveQuery,
            List<MetadataFilter> filters,
            String retrievalCore,
            RetrievalStrategy appliedStrategy) {
        public Result {
            hits = hits == null ? List.of() : List.copyOf(hits);
            filters = filters == null ? List.of() : List.copyOf(filters);
        }

        public Result(
                List<DocumentSearchHit> hits,
                String effectiveQuery,
                List<MetadataFilter> filters,
                String retrievalCore) {
            this(hits, effectiveQuery, filters, retrievalCore, null);
        }
    }

    /** 基于业务意图执行知识检索。 */
    public Result retrieve(IntentRequest request) {
        return retrieve(request, null);
    }

    /**
     * 基于业务意图执行知识检索，并支持注入证据充分性探针。
     *
     * <p>权限先于任何检索动作：{@link ProjectAccessGuard#requireMember} 在路由、嵌入与
     * 管线执行之前调用；非项目成员一律失败关闭。</p>
     */
    public Result retrieve(IntentRequest request, EvidenceSufficiencyProbe evidenceProbe) {
        validateIntentRequest(request);
        access.requireMember(request.projectId(), request.userId());
        List<UUID> documentIds = normalizeDocumentIds(request.documentIds());
        if (isEmergencyLegacyDense(request.intent())) {
            return retrieveLegacyDense(
                    request.projectId(), request.query(), documentIds, request.topK());
        }
        if (strategyAwareExecutor == null) {
            return retrieveRagV2Pipeline(
                    request.projectId(), request.userId(), request.query(), documentIds, request.topK());
        }
        return switch (request.intent()) {
            case KNOWLEDGE_QA, AGENT_RESEARCH, PLANNING_SOURCE, PLANNING_TOOL_LOOKUP ->
                    retrieveStrategyAware(
                            request.projectId(), request.userId(), request.query(), documentIds,
                            request.topK(), evidenceProbe);
        };
    }

    /**
     * 执行一次项目内知识检索（兼容旧 Request 调用方）。
     *
     * <p>权限先于任何检索动作：{@link ProjectAccessGuard#requireMember} 在路由、嵌入与
     * 管线执行之前调用；非项目成员一律失败关闭。</p>
     */
    public Result retrieve(Request request) {
        return retrieve(request, null);
    }

    /**
     * 执行一次项目内知识检索，并为策略感知路径注入证据充分性探针。
     *
     * <p>在生产注入 {@link StrategyAwareRetrievalExecutor} 后，{@link Mode#RAG_V2} 与
     * {@link Mode#STRATEGY_AWARE} 均统一走完整策略感知路由，避免丢弃多子查询与策略决策。</p>
     */
    public Result retrieve(Request request, EvidenceSufficiencyProbe evidenceProbe) {
        validateRequest(request);
        access.requireMember(request.projectId(), request.userId());
        List<UUID> documentIds = normalizeDocumentIds(request.documentIds());
        if (isGlobalLegacyForced() || request.mode() == Mode.DENSE_LEGACY) {
            return retrieveLegacyDense(
                    request.projectId(), request.query(), documentIds, request.topK());
        }
        return switch (request.mode()) {
            case DENSE_LEGACY -> retrieveLegacyDense(
                    request.projectId(), request.query(), documentIds, request.topK());
            case RAG_V2 -> strategyAwareExecutor != null
                    ? retrieveStrategyAware(
                            request.projectId(), request.userId(), request.query(), documentIds,
                            request.topK(), evidenceProbe)
                    : retrieveRagV2Pipeline(
                            request.projectId(), request.userId(), request.query(), documentIds, request.topK());
            case STRATEGY_AWARE -> retrieveStrategyAware(
                    request.projectId(), request.userId(), request.query(), documentIds,
                    request.topK(), evidenceProbe);
        };
    }

    /**
     * 解析用户请求中的显式来源范围，并在解析前强制项目成员权限。
     *
     * <p>解析算法仍由 {@link DocumentSearchService} 持有（含中文来源正则），J0-4 不做改写。</p>
     */
    public DocumentSearchService.UserDocumentScope resolveUserDocumentScope(
            UUID projectId, UUID userId, String userGoal) {
        validateIdentifiers(projectId, userId);
        access.requireMember(projectId, userId);
        // TODO(J1): 中文来源正则与自然语言范围解析迁出；依赖前端文档选择器与显式 documentIds。
        return documentSearch.resolveUserDocumentScope(projectId, userGoal);
    }

    private boolean isGlobalLegacyForced() {
        return forceLegacyDense
                || (retrievalQaProperties != null && retrievalQaProperties.forceLegacyDense());
    }

    private boolean isEmergencyLegacyDense(RetrievalIntent intent) {
        if (isGlobalLegacyForced()) {
            return true;
        }
        return intent == RetrievalIntent.KNOWLEDGE_QA
                && retrievalQaProperties != null
                && retrievalQaProperties.mode() == Mode.DENSE_LEGACY;
    }

    private Result retrieveLegacyDense(
            UUID projectId, String query, List<UUID> documentIds, int topK) {
        List<DocumentSearchHit> hits = documentSearch.search(
                projectId, query, documentIds, topK);
        return new Result(hits, query, List.of(), RETRIEVAL_CORE_LEGACY_DENSE);
    }

    private Result retrieveRagV2Pipeline(
            UUID projectId, UUID userId, String query, List<UUID> documentIds, int topK) {
        QueryRouteDecision decision = queryRouter.route(query, List.of(), userId, projectId);
        List<MetadataFilter> filters = List.of();
        String effectiveQuery = query;
        if (decision != null && decision.structuredQuery() != null) {
            filters = decision.structuredQuery().filters();
            if (!decision.structuredQuery().semanticQuery().isBlank()) {
                effectiveQuery = decision.structuredQuery().semanticQuery();
            }
        }
        List<UUID> effectiveDocumentIds = narrowDocumentScope(
                documentIds, decision == null ? List.of() : decision.documentIds());
        List<DocumentSearchHit> hits = retrievalPipeline
                .execute(projectId, effectiveQuery, effectiveDocumentIds, filters, topK)
                .toDocumentSearchHits();
        return new Result(hits, effectiveQuery, filters, RETRIEVAL_CORE_RAG_V2_PIPELINE,
                decision == null ? null : decision.strategy());
    }

    /**
     * 策略感知检索：QueryRouter 决定策略，StrategyAwareRetrievalExecutor 单点执行。
     *
     * <p>QueryPlan 给出的文档范围只允许在调用方范围内收窄；计划中的无效 ID 被丢弃，
     * 真正的项目/READY/撤权过滤仍由 RetrievalPipeline 强制。</p>
     */
    private Result retrieveStrategyAware(
            UUID projectId, UUID userId,
            String query,
            List<UUID> documentIds,
            int topK,
            EvidenceSufficiencyProbe evidenceProbe) {
        if (strategyAwareExecutor == null) {
            throw new IllegalStateException(
                    "retrieval.qa-mode=STRATEGY_AWARE 需要 StrategyAwareRetrievalExecutor Bean");
        }
        QueryRouteDecision decision = queryRouter.route(query, documentIds, userId, projectId);
        List<MetadataFilter> filters = List.of();
        String effectiveQuery = query;
        if (decision != null && decision.structuredQuery() != null) {
            filters = decision.structuredQuery().filters();
            if (!decision.structuredQuery().semanticQuery().isBlank()) {
                effectiveQuery = decision.structuredQuery().semanticQuery();
            }
        }
        List<UUID> effectiveDocumentIds = narrowDocumentScope(documentIds,
                decision == null ? List.of() : decision.documentIds());
        List<DocumentSearchHit> hits = strategyAwareExecutor.execute(
                projectId, decision, effectiveQuery, effectiveDocumentIds,
                filters, topK, evidenceProbe);
        return new Result(hits, effectiveQuery, filters, RETRIEVAL_CORE_RAG_V2_STRATEGY_AWARE,
                decision == null ? null : decision.strategy());
    }

    private static List<UUID> narrowDocumentScope(
            List<UUID> requestedDocumentIds, List<UUID> plannedDocumentIds) {
        if (plannedDocumentIds == null || plannedDocumentIds.isEmpty()) {
            return requestedDocumentIds;
        }
        if (requestedDocumentIds == null || requestedDocumentIds.isEmpty()) {
            return List.copyOf(new LinkedHashSet<>(plannedDocumentIds));
        }
        Set<UUID> requested = new LinkedHashSet<>(requestedDocumentIds);
        List<UUID> narrowed = plannedDocumentIds.stream()
                .filter(requested::contains)
                .distinct()
                .toList();
        return narrowed.isEmpty() ? requestedDocumentIds : narrowed;
    }

    private static void validateIntentRequest(IntentRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索请求不能为空");
        }
        validateIdentifiers(request.projectId(), request.userId());
        if (request.query() == null || request.query().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索内容不能为空");
        }
        if (request.intent() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索意图不能为空");
        }
        if (request.topK() < MIN_TOP_K || request.topK() > MAX_TOP_K) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "topK 必须在 1 到 20 之间");
        }
    }

    private static void validateRequest(Request request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索请求不能为空");
        }
        validateIdentifiers(request.projectId(), request.userId());
        if (request.query() == null || request.query().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索内容不能为空");
        }
        if (request.mode() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索模式不能为空");
        }
        if (request.topK() < MIN_TOP_K || request.topK() > MAX_TOP_K) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "topK 必须在 1 到 20 之间");
        }
    }

    private static void validateIdentifiers(UUID projectId, UUID userId) {
        if (projectId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "项目 ID 不能为空");
        }
        if (userId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "用户 ID 不能为空");
        }
    }

    private static List<UUID> normalizeDocumentIds(List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        return documentIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }
}
