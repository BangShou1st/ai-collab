package com.shitulelv.aicollab.retrieval.application;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.DocumentScopeService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.query.MetadataFilter;
import com.shitulelv.aicollab.retrieval.query.RetrievalStrategy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 统一检索门面（P3 后）：成员校验 → 计算 visible 集合 → 调用 Python 检索 → 复核 → 回填文件名。
 *
 * <p>P3 起规则路由器随查询理解迁到 Python（契约 §2.1 行 1、Q4=a），Java 侧调用方
 * （Agent 工具 / 规划）不再本地路由：固定 {@code strategy=HYBRID} + 原始查询作为唯一主查询
 * 下发（与 E2 评测口径一致），元数据过滤解析随之不再作用于这些调用方（已知行为变化，P4 处理）。
 * 问答链路不再经过本门面（走 {@code KnowledgeQaRelayService} 中继）。</p>
 */
@Service
public class ProjectKnowledgeRetrieval {

    public static final String RETRIEVAL_CORE_PYTHON = "PYTHON";

    private static final int MIN_TOP_K = 1;
    private static final int MAX_TOP_K = 20;

    private final ProjectAccessGuard access;
    private final DocumentScopeService documentSearch;
    private final RemoteProjectKnowledgeSearch remote;

    public ProjectKnowledgeRetrieval(ProjectAccessGuard access, DocumentScopeService documentSearch,
            RemoteProjectKnowledgeSearch remote) {
        this.access = access;
        this.documentSearch = documentSearch;
        this.remote = remote;
    }

    public enum RetrievalIntent {
        KNOWLEDGE_QA, AGENT_RESEARCH, PLANNING_SOURCE, PLANNING_TOOL_LOOKUP
    }

    /** An absent or empty document list represents an unrestricted project scope. */
    public record IntentRequest(UUID projectId, UUID userId, String query, List<UUID> documentIds,
            int topK, RetrievalIntent intent) {
    }

    public record Result(List<DocumentSearchHit> hits, String effectiveQuery, List<MetadataFilter> filters,
            String retrievalCore, RetrievalStrategy appliedStrategy) {
        public Result {
            hits = hits == null ? List.of() : List.copyOf(hits);
            filters = filters == null ? List.of() : List.copyOf(filters);
        }

        public Result(List<DocumentSearchHit> hits, String effectiveQuery, List<MetadataFilter> filters,
                String retrievalCore) {
            this(hits, effectiveQuery, filters, retrievalCore, null);
        }
    }

    public Result retrieve(IntentRequest request) {
        validateIntentRequest(request);
        access.requireMember(request.projectId(), request.userId());
        List<UUID> documentIds = normalizeDocumentIds(request.documentIds());
        if (request.documentIds() != null && !request.documentIds().isEmpty() && documentIds.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        List<DocumentSearchHit> hits = remote.execute(request, documentIds, request.query());
        return new Result(hits, request.query(), List.of(), RETRIEVAL_CORE_PYTHON,
                RetrievalStrategy.HYBRID);
    }

    /**
     * 解析用户请求中的显式来源范围，并在解析前强制项目成员权限。
     *
     * <p>解析算法仍由 {@link DocumentScopeService} 持有（含中文来源正则），J0-4 不做改写。</p>
     */
    public DocumentScopeService.UserDocumentScope resolveUserDocumentScope(
            UUID projectId, UUID userId, String userGoal) {
        validateIdentifiers(projectId, userId);
        access.requireMember(projectId, userId);
        // TODO(J1): 中文来源正则与自然语言范围解析迁出；依赖前端文档选择器与显式 documentIds。
        return documentSearch.resolveUserDocumentScope(projectId, userGoal);
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
