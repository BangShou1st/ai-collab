package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import com.shitulelv.aicollab.retrieval.query.RetrievalStrategy;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * P3 测试支撑：为 mock 命中的 Agent/规划测试构造门面。
 *
 * <p>P3 起 {@code QueryRouter} 与旧版管线随查询理解/检索执行迁 Python（契约 §2.1 行 1、
 * P2 §12 D2/D4），门面固定 {@code strategy=HYBRID} + 原始查询。Agent 工具测试以
 * {@code DocumentSearchService.search} 为桩点：门面子类绕过 remote 检索与复核，
 * 直接把 mock 命中按候选顺序回传（语义与旧 legacyBacked 一致：mock 决定命中，
 * 门面只透传身份）。复核与可见集合语义由 {@code P2RemoteProjectKnowledgeSearchTest}
 * 用独立 mock 覆盖；入站校验（成员/topK/非空查询）在本支撑内逐条复刻。</p>
 */
public final class ProjectKnowledgeRetrievalTestSupport {

    private ProjectKnowledgeRetrievalTestSupport() {
    }

    public static ProjectKnowledgeRetrieval legacyBacked(ProjectAccessGuard access, DocumentSearchService search) {
        return new ProjectKnowledgeRetrieval(access, search, null) {
            @Override
            public Result retrieve(ProjectKnowledgeRetrieval.IntentRequest request) {
                validateIntentRequest(request, access);
                List<UUID> documentIds = request.documentIds() == null
                        ? List.of()
                        : request.documentIds().stream().filter(Objects::nonNull).toList();
                List<DocumentSearchHit> hits = search.search(
                        request.projectId(), request.query(), documentIds, request.topK());
                return new Result(hits, request.query(), List.of(),
                        ProjectKnowledgeRetrieval.RETRIEVAL_CORE_PYTHON, RetrievalStrategy.HYBRID);
            }
        };
    }

    private static void validateIntentRequest(
            ProjectKnowledgeRetrieval.IntentRequest request, ProjectAccessGuard access) {
        if (request == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索请求不能为空");
        }
        if (request.projectId() == null || request.userId() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索身份不能为空");
        }
        if (request.query() == null || request.query().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索内容不能为空");
        }
        if (request.intent() == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "检索意图不能为空");
        }
        if (request.topK() < 1 || request.topK() > 20) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "topK 必须在 1 到 20 之间");
        }
        access.requireMember(request.projectId(), request.userId());
    }
}
