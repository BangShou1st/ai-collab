package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.*;
import com.shitulelv.aicollab.agent.application.runtime.*;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.*;
import com.shitulelv.aicollab.agent.domain.tool.*;
import com.shitulelv.aicollab.agent.infrastructure.repository.*;
import com.shitulelv.aicollab.agent.infrastructure.tool.*;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.infrastructure.ai.model.*;
import com.shitulelv.aicollab.infrastructure.ai.turn.*;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.project.domain.policy.ProjectAccessGuard;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P8: reuse the frozen corpus, real Ollama, pgvector and production search from P6.
 * Only model turns, context assembly, event delivery, approval and memory collaborators
 * are substituted. Real tool registry/executor, composer, runtime guards, database access
 * guard and persistence execute. Scripted answers copy ONLY actual tool quotes, so this
 * proves transport/identity contracts, never autonomous reasoning or answer quality.
 * Explicit local embedding opt-in (JUnit's condition is not inherited); no external model client.
 */
@Timeout(value = 12, unit = TimeUnit.MINUTES)
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="AI_REAL_OLLAMA_RETRIEVAL",matches="true")
class P8AgentRagCrossLayerPostgresTest extends P6RagQualityBaselinePostgresTest {
    @Autowired ProjectAccessGuard access;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private UUID followupSession;

    @Override
    protected void observeQuestion(JsonNode question, Map<String, Object> measured) throws Exception {
        String id = question.path("id").asText();
        String query = question.path("query").asText();
        // Preserve the frozen Q11/Q12 conversation and explicit user scope; no model rewrite.
        String goal = "Q12".equals(id)
                ? "仅根据《任务预算规则》，任务运行期间可以临时增加预算额度吗？\n追问：" + query : query;
        var recorder = new AgentRunEventRecorder(jdbc, json);
        var repository = new AgentRepository(jdbc, json, recorder);
        var events = mock(AgentEventService.class);
        ReflectionTestUtils.setField(recorder, "events", events);
        var assembler = mock(AgentContextAssembler.class);
        when(assembler.assemble(any(), any(), any())).thenAnswer(inv -> {
            AgentRunView run = inv.getArgument(0);
            return new AgentExecutionContext(run.id(), run.sessionId(), run.projectId(), run.requesterId(),
                    "OWNER", false, AgentPageContext.empty(), AgentRuntimeLimits.forSkill(run.skillCode()),
                    run.depth(), List.of());
        });
        var model = mock(RoutingAgentModelExecutor.class);
        when(model.resolveRequest(any())).thenReturn(resolved());
        var knowledge = new KnowledgeSearchAgentTool(access, search, new KnowledgeContextBuilder(), json);
        var registry = new AgentToolRegistry(List.of(knowledge));
        var executor = new AgentToolCallExecutor(repository, registry,
                new AgentCancellationService(repository), new AgentLoopGuard(), mock(AgentApprovalService.class),
                model, new AgentToolResultSanitizer(json), json, events, new AgentToolScheduler());
        var coordinator = new AgentRuntimeCoordinator(repository, assembler, new AgentSkillRegistry(),
                new AgentPlanService(repository, json), registry, new AgentCancellationService(repository), model,
                new AgentConvergencePolicy(), json, events,
                new AgentContextProperties(true, 20_000, 4_000, 2_000, Map.of()),
                new AgentModelMessageComposer(repository, mock(AgentMemoryService.class), json), executor,
                new AgentContextSummarizer(repository, model, json));
        UUID session = "Q12".equals(id) ? followupSession
                : repository.createSession(project, user, "P8 " + id).id();
        if ("Q11".equals(id)) followupSession = session;
        assertThat(session).isNotNull();
        var created = repository.createRun(project, session, user, goal, false, null, null);
        var calls = new AtomicInteger();
        var auxiliaryCalls = new AtomicInteger();
        when(model.callModelWithoutTools(any(), any(), any())).thenAnswer(inv -> {
            assertThat(auxiliaryCalls.incrementAndGet()).isLessThanOrEqualTo(1);
            // Explicitly exercise the production EMPTY-summary fallback; no hidden Mockito null response.
            return new ModelTurnResult("", List.of(), ModelFinishReason.STOP,
                    new ModelUsage(null, null), "SCRIPTED", "p8-empty-summary", 0L);
        });
        var observations = new ArrayList<JsonNode>();
        var scriptedAnswer = new StringBuilder();
        when(model.callModel(any(), any(), any(), anyBoolean(), any())).thenAnswer(inv -> {
            assertThat(calls.incrementAndGet()).as("bounded scripted turns").isLessThanOrEqualTo(2);
            if (calls.get() == 1) {
                var args = json.createObjectNode().put("query", query);
                if (!question.path("scope").isEmpty()) {
                    var ids = args.putArray("documentIds");
                    question.path("scope").forEach(name -> ids.add(documentIds.get(name.asText()).toString()));
                }
                return new ModelTurnResult("", List.of(new ModelToolCall("p8-" + id,
                        "search_project_knowledge", args)), ModelFinishReason.TOOL_CALLS,
                        new ModelUsage(null, null), "SCRIPTED", "p8-no-network", 0L);
            }
            List<ModelMessage> messages = inv.getArgument(1);
            for (ModelMessage message : messages) {
                if (message instanceof ModelMessage.ToolResult tool) observations.add(tool.result());
            }
            assertThat(observations).as("actual tool feedback reached scripted model").isNotEmpty();
            JsonNode result = observations.getLast();
            if (question.path("abstain").asBoolean()) {
                scriptedAnswer.append("资料不足，不能确认问题所述年限或运行期间增加预算的例外。以下仅为相关摘录：\n");
            }
            int index = 0;
            for (JsonNode source : result.path("data").path("sources")) {
                scriptedAnswer.append(source.path("quote").asText()).append(" [S")
                        .append(++index).append("]\n");
            }
            if (scriptedAnswer.isEmpty()) scriptedAnswer.append("资料不足，未取得可用摘录。");
            return new ModelTurnResult(scriptedAnswer.toString(), List.of(), ModelFinishReason.STOP,
                    new ModelUsage(null, null), "SCRIPTED", "p8-no-network", 0L);
        });
        long started = System.nanoTime();
        var recovery = new AgentRecoveryJob(repository);
        for (int round = 0; round < 8; round++) {
            if (repository.findRun(project, created.id()).orElseThrow().status().terminal()) break;
            var claim = recovery.claim("p8-worker", Duration.ofMinutes(2)).orElseThrow();
            assertThat(claim.id()).isEqualTo(created.id());
            coordinator.advance(repository.findRun(project, claim.id()).orElseThrow());
        }
        var run = repository.findRun(project, created.id()).orElseThrow();
        var invocation = jdbc.queryForMap("SELECT status,result_json::text AS result FROM agent_tool_invocation WHERE run_id=?",
                run.id());
        JsonNode toolResult = json.readTree((String) invocation.get("result"));
        var saved = jdbc.queryForMap("SELECT content,citations_json::text AS citations FROM agent_message "
                + "WHERE run_id=? AND role='ASSISTANT' ORDER BY created_at DESC LIMIT 1", run.id());
        JsonNode citations = json.readTree((String) saved.get("citations"));
        var sources = toolResult.path("data").path("sources");
        assertThat(observations.getLast().path("data").path("sources"))
                .as("tool result survives sanitizer and model message composition intact").isEqualTo(sources);
        var identities = new ArrayList<Map<String, Object>>();
        Set<String> availableChunks = new LinkedHashSet<>();
        for (JsonNode source : sources) {
            String chunk = source.path("chunkId").asText();
            String doc = source.path("documentId").asText();
            availableChunks.add(chunk);
            var actual = jdbc.queryForMap("SELECT c.content,c.content_hash,c.document_id,d.original_filename "
                    + "FROM document_chunk c JOIN project_document d ON d.id=c.document_id "
                    + "WHERE c.id=? AND c.document_id=? AND c.project_id=? AND d.status='READY'",
                    UUID.fromString(chunk), UUID.fromString(doc), project);
            String quote = source.path("quote").asText();
            assertThat((String) actual.get("content")).startsWith(quote.replaceAll("…$", ""));
            assertThat(source.path("filename").asText()).isEqualTo(actual.get("original_filename"));
            assertThat(observations.toString()).contains(chunk, doc);
            identities.add(Map.of("documentId", doc, "chunkId", chunk, "contentHash", actual.get("content_hash"),
                    "quote", quote, "filename", source.path("filename").asText()));
            if (!question.path("scope").isEmpty()) {
                List<String> allowed = new ArrayList<>();
                question.path("scope").forEach(name -> allowed.add(documentIds.get(name.asText()).toString()));
                assertThat(doc).isIn(allowed);
            }
        }
        Set<String> persistedChunks = new LinkedHashSet<>();
        for (JsonNode citation : citations) {
            assertThat(citation.isObject()).isTrue();
            String chunk = citation.path("chunkId").asText();
            persistedChunks.add(chunk);
            assertThat(chunk).isIn(availableChunks);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM document_chunk c WHERE c.id=? AND c.document_id=? AND c.project_id=?",
                    Integer.class, UUID.fromString(chunk), UUID.fromString(citation.path("documentId").asText()), project)).isEqualTo(1);
        }
        assertThat(persistedChunks).containsExactlyInAnyOrderElementsOf(availableChunks);
        assertThat(saved.get("content")).isEqualTo(scriptedAnswer.toString());
        assertThat(run.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(invocation.get("status")).isEqualTo("SUCCEEDED");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(run.toolCallsUsed()).isEqualTo(1);
        assertThat(run.stepsUsed()).isLessThanOrEqualTo(8);
        // Real DB membership check: an unknown requester cannot execute the actual search tool.
        assertThatThrownBy(() -> knowledge.execute(new AgentToolContext(run.id(), project,
                UUID.randomUUID(), "OWNER", false, 0), json.createObjectNode().put("query", query)))
                .isInstanceOf(BusinessException.class);
        var fabricated = json.createObjectNode();
        fabricated.putArray("citations").addObject().put("documentId", UUID.randomUUID().toString())
                .put("chunkId", UUID.randomUUID().toString());
        assertThat(repository.citationsStillValid(project, fabricated)).isFalse();
        if ("Q09".equals(id)) {
            assertThat(identities.stream().map(item -> item.get("filename")).toList())
                    .contains("合同模板甲.md", "合同模板乙.md");
        }
        // User include scope cannot be expanded by a model-provided document outside it.
        if ("Q10".equals(id)) {
            var args = json.createObjectNode().put("query", query);
            args.putArray("documentIds").add(documentIds.get("合同模板乙.md").toString());
            var rejectedExpansion = knowledge.execute(new AgentToolContext(run.id(), project, user, "OWNER",
                    false, 0, null, goal), args);
            assertThat(rejectedExpansion.data().path("sources")).isEmpty();
        }
        var row = new LinkedHashMap<String, Object>();
        row.put("id", id);
        row.put("sessionId", session);
        row.put("runId", run.id());
        row.put("goal", goal);
        row.put("status", run.status());
        row.put("modelBoundary", "SCRIPTED: copies actual observed quotes, no autonomous semantic evaluation");
        row.put("mainModelCalls", calls.get());
        row.put("auxiliaryModelCalls", auxiliaryCalls.get());
        row.put("modelCalls", calls.get() + auxiliaryCalls.get());
        row.put("summaryBoundary", "SCRIPTED EMPTY fallback; summary semantics NOT_MEASURED");
        row.put("toolCalls", run.toolCallsUsed());
        row.put("steps", run.stepsUsed());
        row.put("elapsedMs", (System.nanoTime() - started) / 1_000_000);
        row.put("actualModelTokens", "NOT_MEASURED: no real chat request; runtime estimates are not usage");
        row.put("toolResult", toolResult);
        row.put("modelObservations", observations);
        row.put("sourceIdentityChecks", identities);
        row.put("persistedAnswer", saved.get("content"));
        row.put("persistedCitations", citations);
        row.put("identityAndPersistence", "PASS");
        row.put("nonMemberDenied", true);
        row.put("fabricatedIdentityRejected", true);
        row.put("semanticAnswerQuality", "NOT_MEASURED");
        List<Map<String, Object>> factTransport = new ArrayList<>();
        for (Object item : (List<?>) measured.get("facts")) {
            Map<?, ?> fact = (Map<?, ?>) item;
            String marker = (String) fact.get("marker");
            String document = (String) fact.get("document");
            boolean present = false;
            for (JsonNode source : sources) {
                if (document.equals(source.path("filename").asText())
                        && source.path("quote").asText().contains(marker)) present = true;
            }
            factTransport.add(Map.of("document", document, "marker", marker,
                    "baselineAgentStage", fact.get("agentStage"), "actualToolQuoteRetained", present));
            assertThat(present).as("same real index: measured quote retention agrees with actual tool result")
                    .isEqualTo("RETAINED".equals(fact.get("agentStage")));
        }
        row.put("factTransport", factTransport);
        results.add(row);
        Path output = Path.of("target/p8-agent-rag-e2e-quality-01/integration-results.json");
        Files.createDirectories(output.getParent());
        json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), results);
    }

    private static RoutingAgentModelExecutor.ResolvedRequest resolved() {
        OffsetDateTime now = OffsetDateTime.now();
        var provider = new UserAiProvider(UUID.randomUUID(), UUID.randomUUID(), "P8 scripted",
                ModelProviderType.OPENAI_COMPATIBLE, "https://example.invalid", "/v1/chat/completions",
                "not-a-credential", "p8-scripted", true, 0.0, 1024,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.NATIVE_TOOLS), true, now, now, null);
        return RoutingAgentModelExecutor.ResolvedRequest.of(provider, provider.toModelConfiguration());
    }
}
