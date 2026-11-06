package com.shitulelv.aicollab.retrieval.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolContext;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolResult;
import com.shitulelv.aicollab.agent.infrastructure.tool.KnowledgeSearchAgentTool;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.project.domain.model.ProjectRole;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import com.shitulelv.aicollab.retrieval.query.DocumentFilterCriteria;
import com.shitulelv.aicollab.retrieval.query.MetadataFilter;
import com.shitulelv.aicollab.retrieval.query.QueryRouter;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalPipelineContractFilterPassThroughTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void testPipelineRerankerTypeSelectionAndFilterPassThrough() {
        UUID projectId = UUID.randomUUID();
        UUID docId = UUID.randomUUID();
        UUID chunk1 = UUID.randomUUID();

        AtomicReference<DocumentFilterCriteria> capturedCriteria = new AtomicReference<>();

        Retriever mockDense = new Retriever() {
            @Override
            public List<RetrievalCandidate> retrieve(UUID proj, String query, List<UUID> docIds, int topK) {
                return retrieve(proj, query, docIds, topK, (DocumentFilterCriteria) null);
            }

            @Override
            public List<RetrievalCandidate> retrieve(UUID proj, String query, List<UUID> docIds, int topK, DocumentFilterCriteria criteria) {
                capturedCriteria.set(criteria);
                return List.of(new RetrievalCandidate(
                        chunk1, docId, "spec.pdf", "系统安全", "必须开启 HTTPS 与网关鉴权认证。", "h1",
                        Map.of("pageNumber", 1), 1, 0.95, null, null, 0.05, null, null
                ));
            }
        };

        Retriever mockLexical = (proj, query, docIds, topK) -> List.of();

        PassThroughReranker passThrough = new PassThroughReranker();

        RetrievalPipeline pipeline = new RetrievalPipeline(
                mockDense,
                mockLexical,
                new ReciprocalRankFusion(),
                new SentenceWindowExpander(),
                new ParentChildExpander(),
                passThrough
        );

        MetadataFilter filter = MetadataFilter.eq("mimeType", "application/pdf");
        RetrievalPipeline.PipelineConfig config = RetrievalPipeline.PipelineConfig.defaultConfig()
                .withRerankerType(RerankerType.PASS_THROUGH)
                .withFilters(List.of(filter));

        RetrievalResult result = pipeline.execute(projectId, "系统安全", List.of(docId), config);

        assertThat(result.candidates()).hasSize(1);
        assertThat(result.trace().rerankerName()).isEqualTo(PassThroughReranker.NAME);
        assertThat(capturedCriteria.get()).isNotNull();
        assertThat(capturedCriteria.get().getMimeType()).isEqualTo("application/pdf");
    }

    @Test
    void testKnowledgeSearchAgentToolPassesParsedMetadataFilterToPipeline() {
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        ProjectAccessGuard access = mock(ProjectAccessGuard.class);
        when(access.requireMember(any(), any())).thenReturn(ProjectRole.MEMBER);

        DocumentSearchService search = mock(DocumentSearchService.class);
        when(search.resolveUserDocumentScope(any(), any()))
                .thenReturn(DocumentSearchService.UserDocumentScope.unrestricted());

        KnowledgeContextBuilder contextBuilder = new KnowledgeContextBuilder();

        AtomicReference<List<MetadataFilter>> capturedPipelineFilters = new AtomicReference<>();
        AtomicReference<String> capturedPipelineQuery = new AtomicReference<>();

        RetrievalPipeline mockPipeline = new RetrievalPipeline(
                (p, q, ids, k) -> List.of(),
                (p, q, ids, k) -> List.of(),
                new ReciprocalRankFusion(),
                new SentenceWindowExpander(),
                new ParentChildExpander(),
                new PassThroughReranker()
        ) {
            @Override
            public RetrievalResult execute(UUID p, String query, List<UUID> documentIds, List<MetadataFilter> filters, int topK) {
                capturedPipelineQuery.set(query);
                capturedPipelineFilters.set(filters);
                return new RetrievalResult(List.of(), new RetrievalTrace(0, 0, 0, 0, 0, 0, 0, 0, false, "mock", false, false, Map.of(), List.of()));
            }
        };

        QueryRouter queryRouter = new QueryRouter();

        KnowledgeSearchAgentTool tool = new KnowledgeSearchAgentTool(
                access,
                search,
                contextBuilder,
                objectMapper,
                mockPipeline,
                queryRouter
        );

        AgentToolContext toolContext = new AgentToolContext(runId, projectId, userId, "SUPERVISOR", false, 0);
        ObjectNode args = objectMapper.createObjectNode();
        // 传入包含 《上线发布门禁.md》 的结构化查询
        args.put("query", "在《上线发布门禁.md》中 涉及的安全审计要求有哪些？");

        AgentToolResult result = tool.execute(toolContext, args);

        assertThat(result).isNotNull();
        // 验证结构化过滤条件被真实解析并传至 Pipeline，且自然语言中的书籍标记被剥离为干净的语义查询
        assertThat(capturedPipelineFilters.get()).isNotEmpty();
        assertThat(capturedPipelineFilters.get().getFirst().field()).isEqualTo("originalFilename");
        assertThat(capturedPipelineFilters.get().getFirst().value().toString()).contains("上线发布门禁.md");
        assertThat(capturedPipelineQuery.get()).contains("涉及的安全审计要求有哪些");
    }
}
