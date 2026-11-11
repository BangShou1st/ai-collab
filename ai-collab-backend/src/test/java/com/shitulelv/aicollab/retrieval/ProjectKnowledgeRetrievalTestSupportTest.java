package com.shitulelv.aicollab.retrieval;

import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * J0-4 测试支撑的语义回归：legacy-backed 门面必须保留迁移前的原始 Dense 查询。
 *
 * <p>迁移前的 4 参 KnowledgeSearchAgentTool 没有 QueryRouter，受限来源查询中的
 * 《文档名》会原样进入 DocumentSearchService.search。legacy-backed 适配器若使用
 * 生产 QueryRouter，会把书名片段改写成 MetadataFilter（随后被 legacy 适配器忽略），
 * 改变 P8/P9 冻结基线的输入。本测试钉住 passthrough 行为。</p>
 */
class ProjectKnowledgeRetrievalTestSupportTest {

    private final ProjectAccessGuard access = mock(ProjectAccessGuard.class);
    private final DocumentSearchService search = mock(DocumentSearchService.class);
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @Test
    void legacyBackedKeepsRawQueryIncludingBookTitleScope() {
        String rawQuery = "仅根据《合同模板甲》，重大变更由谁审批？";
        when(access.requireMember(projectId, userId)).thenReturn(ProjectRole.MEMBER);
        when(search.search(projectId, rawQuery, List.of(), 5)).thenReturn(List.of());

        var retrieval = ProjectKnowledgeRetrievalTestSupport.legacyBacked(access, search);
        var result = retrieval.retrieve(new ProjectKnowledgeRetrieval.Request(
                projectId, userId, rawQuery, List.of(), 5,
                ProjectKnowledgeRetrieval.Mode.RAG_V2));

        assertThat(result.hits()).isEmpty();
        assertThat(result.effectiveQuery()).isEqualTo(rawQuery);
        assertThat(result.filters()).isEmpty();
        verify(search).search(projectId, rawQuery, List.of(), 5);
    }

    @Test
    void passthroughRouterNeverExtractsMetadataFilters() {
        String rawQuery = "排除《旧版规范》，只根据《新版规范》回答";
        var decision = ProjectKnowledgeRetrievalTestSupport.passthroughRouter().route(rawQuery);

        assertThat(decision).isNotNull();
        assertThat(decision.structuredQuery()).isNotNull();
        assertThat(decision.structuredQuery().semanticQuery()).isEqualTo(rawQuery);
        assertThat(decision.structuredQuery().filters()).isEmpty();
    }
}