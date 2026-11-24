package com.shitulelv.aicollab.planning.infrastructure.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.planning.application.AttemptSourceRegistry;
import com.shitulelv.aicollab.planning.domain.PlanSource;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchProjectKnowledgePlanningToolTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void dynamicLookupRegistersHitsAndExposesSourceRefs() {
        ProjectKnowledgeRetrieval retrieval = mock(ProjectKnowledgeRetrieval.class);
        UUID projectId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID existingDoc = UUID.randomUUID();
        UUID existingChunk = UUID.randomUUID();
        UUID foundDoc = UUID.randomUUID();
        UUID foundChunk = UUID.randomUUID();

        when(retrieval.retrieve(any(ProjectKnowledgeRetrieval.IntentRequest.class)))
                .thenReturn(new ProjectKnowledgeRetrieval.Result(
                        List.of(new DocumentSearchHit(
                                foundChunk, foundDoc, "ops-runbook.md", "Rollback",
                                "Use clean single-stack rebuild.", "hash-found", 0.91d)),
                        "rollback runbook",
                        List.of(),
                        "STRATEGY_AWARE"));

        SearchProjectKnowledgePlanningTool tool = new SearchProjectKnowledgePlanningTool(retrieval, json);
        AttemptSourceRegistry registry = new AttemptSourceRegistry(
                List.of(new PlanSource("S1", existingDoc, "seed.md", existingChunk, "Seed", 0.95d, "seed text", "hash-seed")),
                12, 6000);

        JsonNode out = tool.execute(
                json.createObjectNode().put("query", "rollback runbook").put("limit", 4),
                projectId, userId, registry);

        ArgumentCaptor<ProjectKnowledgeRetrieval.IntentRequest> captor =
                ArgumentCaptor.forClass(ProjectKnowledgeRetrieval.IntentRequest.class);
        verify(retrieval).retrieve(captor.capture());
        assertThat(captor.getValue().intent()).isEqualTo(ProjectKnowledgeRetrieval.RetrievalIntent.PLANNING_TOOL_LOOKUP);
        assertThat(captor.getValue().documentIds()).isEmpty();
        assertThat(captor.getValue().topK()).isEqualTo(4);

        assertThat(out.path("returned").asInt()).isEqualTo(1);
        assertThat(out.path("registeredSourceCount").asInt()).isEqualTo(1);
        JsonNode hit = out.path("hits").get(0);
        assertThat(hit.path("sourceRef").asText()).isEqualTo("S2");
        assertThat(hit.path("documentId").asText()).isEqualTo(foundDoc.toString());
        assertThat(hit.path("chunkId").asText()).isEqualTo(foundChunk.toString());
        assertThat(out.path("fieldGuide").asText()).contains("sourceRef");
        assertThat(registry.snapshot()).hasSize(2);
    }

    @Test
    void legacyThreeArgExecuteLeavesSourceRefUnassigned() {
        ProjectKnowledgeRetrieval retrieval = mock(ProjectKnowledgeRetrieval.class);
        when(retrieval.retrieve(any(ProjectKnowledgeRetrieval.IntentRequest.class)))
                .thenReturn(new ProjectKnowledgeRetrieval.Result(
                        List.of(new DocumentSearchHit(
                                UUID.randomUUID(), UUID.randomUUID(), "spec.md", "S1",
                                "content", "hash-1", 0.8d)),
                        "spec",
                        List.of(),
                        "RAG_V2"));

        SearchProjectKnowledgePlanningTool tool = new SearchProjectKnowledgePlanningTool(retrieval, json);
        JsonNode out = tool.execute(json.createObjectNode().put("query", "spec"), UUID.randomUUID(), UUID.randomUUID());

        assertThat(out.path("registeredSourceCount").asInt()).isZero();
        assertThat(out.path("hits").get(0).has("sourceRef")).isFalse();
    }

    @Test
    void nonMemberProjectNotFoundPropagatesFailClosed() {
        ProjectKnowledgeRetrieval retrieval = mock(ProjectKnowledgeRetrieval.class);
        when(retrieval.retrieve(any(ProjectKnowledgeRetrieval.IntentRequest.class)))
                .thenThrow(new BusinessException(ErrorCode.PROJECT_NOT_FOUND));

        SearchProjectKnowledgePlanningTool tool = new SearchProjectKnowledgePlanningTool(retrieval, json);
        assertThatThrownBy(() -> tool.execute(
                json.createObjectNode().put("query", "secret"), UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROJECT_NOT_FOUND);
    }
}
