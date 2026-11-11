package com.shitulelv.aicollab.retrieval.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.pipeline.RetrievalPipeline;
import com.shitulelv.aicollab.retrieval.query.MetadataFilter;
import com.shitulelv.aicollab.retrieval.query.QueryRouteDecision;
import com.shitulelv.aicollab.retrieval.query.QueryRouter;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
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
 *   <li>{@link Mode#DENSE_LEGACY} 委托 {@link DocumentSearchService#search}：显式 documentIds
 *       必须属于当前项目且全部 READY，查询向量经 {@code EmbeddingBatch} 快照生成，仓储层
 *       继续过滤 READY/access_blocked。</li>
 *   <li>{@link Mode#RAG_V2} 委托 {@link QueryRouter} 做结构化解析/语义查询清洗，再将
 *       过滤条件下推给 {@link RetrievalPipeline}。</li>
 * </ul>
 *
 * <p>返回的 {@link DocumentSearchHit} 即规范引用身份（documentId/chunkId/filename/heading/
 * content/similarity/contentHash/metadata）：门面只透传，不生成、不重排、不改写 ID。</p>
 *
 * <p>所有依赖都通过构造器强制注入，不存在 {@code @Autowired(required = false)}、
 * {@code ObjectProvider} 或静默降级；任一依赖缺 bean 时 Spring 启动失败。</p>
 */
@Service
public class ProjectKnowledgeRetrieval {

    public static final String RETRIEVAL_CORE_LEGACY_DENSE = "LEGACY_DENSE";
    public static final String RETRIEVAL_CORE_RAG_V2_PIPELINE = "RAG_V2_PIPELINE";

    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;

    private final ProjectAccessGuard access;
    private final DocumentSearchService documentSearch;
    private final RetrievalPipeline retrievalPipeline;
    private final QueryRouter queryRouter;

    public ProjectKnowledgeRetrieval(
            ProjectAccessGuard access,
            DocumentSearchService documentSearch,
            RetrievalPipeline retrievalPipeline,
            QueryRouter queryRouter) {
        this.access = access;
        this.documentSearch = documentSearch;
        this.retrievalPipeline = retrievalPipeline;
        this.queryRouter = queryRouter;
    }

    /** 检索模式：旧版 Dense 直连，或 RAG V2 混合管线。 */
    public enum Mode {
        DENSE_LEGACY,
        RAG_V2
    }

    /**
     * 检索请求。documentIds 为 null 或空表示全项目检索。
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
     * 检索结果。hits 是规范引用身份的唯一事实来源；effectiveQuery/filters 仅用于诊断，
     * retrievalCore 取值固定为 {@link #RETRIEVAL_CORE_LEGACY_DENSE} 或
     * {@link #RETRIEVAL_CORE_RAG_V2_PIPELINE}。
     */
    public record Result(
            List<DocumentSearchHit> hits,
            String effectiveQuery,
            List<MetadataFilter> filters,
            String retrievalCore) {
        public Result {
            hits = hits == null ? List.of() : List.copyOf(hits);
            filters = filters == null ? List.of() : List.copyOf(filters);
        }
    }

    /**
     * 执行一次项目内知识检索。
     *
     * <p>权限先于任何检索动作：{@link ProjectAccessGuard#requireMember} 在路由、嵌入与
     * 管线执行之前调用；非项目成员一律失败关闭。</p>
     */
    public Result retrieve(Request request) {
        validateRequest(request);
        access.requireMember(request.projectId(), request.userId());
        List<UUID> documentIds = normalizeDocumentIds(request.documentIds());
        return request.mode() == Mode.RAG_V2
                ? retrieveRagV2(request, documentIds)
                : retrieveLegacyDense(request, documentIds);
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

    private Result retrieveLegacyDense(Request request, List<UUID> documentIds) {
        List<DocumentSearchHit> hits = documentSearch.search(
                request.projectId(), request.query(), documentIds, request.topK());
        return new Result(hits, request.query(), List.of(), RETRIEVAL_CORE_LEGACY_DENSE);
    }

    private Result retrieveRagV2(Request request, List<UUID> documentIds) {
        QueryRouteDecision decision = queryRouter.route(request.query());
        List<MetadataFilter> filters = List.of();
        String effectiveQuery = request.query();
        if (decision != null && decision.structuredQuery() != null) {
            filters = decision.structuredQuery().filters();
            if (!decision.structuredQuery().semanticQuery().isBlank()) {
                effectiveQuery = decision.structuredQuery().semanticQuery();
            }
        }
        List<DocumentSearchHit> hits = retrievalPipeline
                .execute(request.projectId(), effectiveQuery, documentIds, filters, request.topK())
                .toDocumentSearchHits();
        return new Result(hits, effectiveQuery, filters, RETRIEVAL_CORE_RAG_V2_PIPELINE);
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
