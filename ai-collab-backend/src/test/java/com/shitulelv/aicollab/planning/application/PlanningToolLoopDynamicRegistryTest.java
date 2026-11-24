package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.planning.domain.PlanSource;
import com.shitulelv.aicollab.planning.infrastructure.tool.PlanningToolRegistry;
import com.shitulelv.aicollab.planning.infrastructure.tool.SearchProjectKnowledgePlanningTool;
import com.shitulelv.aicollab.retrieval.application.ProjectKnowledgeRetrieval;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanningToolLoopDynamicRegistryTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void dynamicallyRegistersSearchHitsIntoAttemptSourceRegistryAcrossToolTurns() {
        ProjectKnowledgeRetrieval retrieval = mock(ProjectKnowledgeRetrieval.class);
        UUID docId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        when(retrieval.retrieve(any(ProjectKnowledgeRetrieval.IntentRequest.class)))
                .thenReturn(new ProjectKnowledgeRetrieval.Result(
                        List.of(new DocumentSearchHit(
                                chunkId, docId, "release-sop.md", "Canary",
                                "Run database migration check before canary promotion.",
                                "hash-canary-1", 0.93d)),
                        "canary promotion",
                        List.of(),
                        "STRATEGY_AWARE"));

        SearchProjectKnowledgePlanningTool searchTool = new SearchProjectKnowledgePlanningTool(retrieval, json);
        PlanningToolLoop loop = new PlanningToolLoop(
                new PlanningToolRegistry(List.of(searchTool)),
                json);

        AttemptSourceRegistry sourceRegistry = new AttemptSourceRegistry(List.of(), 12, 6000);
        TaskPlanModelClient model = mock(TaskPlanModelClient.class);
        when(model.generate(anyString(), anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(new GenerationResult(
                        "<TOOL_CALL>{\"tool\":\"search_project_knowledge\",\"arguments\":{\"query\":\"canary promotion\",\"limit\":2}}</TOOL_CALL>",
                        "OPENAI_COMPATIBLE", "fixture-model", 10L, 10, 10))
                .thenAnswer(inv -> {
                    String prompt = inv.getArgument(1, String.class);
                    assertThat(prompt).contains("\"sourceRef\":\"S1\"").contains("release-sop.md");
                    return new GenerationResult(
                            "{\"milestones\":[],\"tasks\":[]}",
                            "OPENAI_COMPATIBLE", "fixture-model", 12L, 20, 15);
                });

        List<PlanningToolLoop.ToolInvocation> invocations = new ArrayList<>();
        PlanningToolLoop.Outcome outcome = loop.run(
                model,
                "system",
                "base prompt",
                "TASK_PLAN_DETAIL",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                json.createObjectNode(),
                new PlanningToolLoop.ToolBudget(),
                Set.of("search_project_knowledge"),
                invocations::add,
                sourceRegistry);

        assertThat(outcome.result().content()).isEqualTo("{\"milestones\":[],\"tasks\":[]}");
        assertThat(invocations).hasSize(1);
        assertThat(invocations.getFirst().ok()).isTrue();
        assertThat(sourceRegistry.snapshot()).hasSize(1);
        PlanSource registered = sourceRegistry.snapshot().getFirst();
        assertThat(registered.ref()).isEqualTo("S1");
        assertThat(registered.documentId()).isEqualTo(docId);
        assertThat(registered.chunkId()).isEqualTo(chunkId);
        assertThat(registered.contentHash()).isEqualTo("hash-canary-1");
    }
}
