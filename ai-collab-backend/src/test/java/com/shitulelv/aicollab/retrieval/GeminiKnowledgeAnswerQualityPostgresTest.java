package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.shitulelv.aicollab.agent.application.AgentRunService;
import com.shitulelv.aicollab.agent.application.AgentWorker;
import com.shitulelv.aicollab.agent.api.dto.CreateAgentSessionRequest;
import com.shitulelv.aicollab.agent.api.dto.SubmitAgentMessageRequest;
import com.shitulelv.aicollab.agent.domain.model.AgentExecutionContext;
import com.shitulelv.aicollab.agent.domain.model.AgentSkill;
import com.shitulelv.aicollab.agent.domain.tool.AgentToolDefinition;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.agent.infrastructure.tool.AgentToolRegistry;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.GeminiModelAdapter;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher;
import com.shitulelv.aicollab.knowledge.api.dto.CreateKnowledgeSessionRequest;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeQuestionApplicationService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeSessionApplicationService;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeStreamQuestionService;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeAnswerView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeStreamEvent;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeCitationValidator;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/** Real production services, routing, prompt, citation validation and persistence; synthetic data only. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true", "embedding.enabled=false", "chat.enabled=false",
        "planning.enabled=false", "agent.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "security.jwt.access-token-minutes=30",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "storage.minio.endpoint=http://127.0.0.1:1", "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret", "storage.minio.bucket=test-bucket",
        "logging.level.com.shitulelv.aicollab.infrastructure.ai.user=INFO",
        "logging.level.com.shitulelv.aicollab.infrastructure.ai.model=INFO"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "AI_GEMINI_LIVE_TESTS", matches = "true")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GeminiKnowledgeAnswerQualityPostgresTest {
    private static final String TARGET = "GeminiKnowledgeAnswerQualityPostgresTest";
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURES = ROOT.resolve("tests/fixtures/documents/p4-rag-answer-quality-01");
    private static final Path QUESTIONS = ROOT.resolve("docs/acceptance-evidence/2026-10-22/p4-rag-answer-quality-01/questions.json");
    private static final Path OUTPUT = Path.of("target/rag-answer-quality-01/results.json");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String EMBEDDING_MODEL = "qwen3-embedding:0.6b";
    private static final String FINGERPRINT = EmbeddingFingerprints.fingerprint("OLLAMA", EMBEDDING_MODEL, 1024);
    private final AtomicInteger requests = new AtomicInteger();
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final Map<String, UUID> documents = new LinkedHashMap<>();
    private final Map<String, UUID> sessions = new LinkedHashMap<>();
    private volatile Map<String, Object> current;
    private UUID user;
    private UUID project;
    private UUID generation;
    private boolean providerBlocked;
    private int priorFailedAttempts;

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
    @BeforeAll static void requireExplicitAuthorization() {
        String selector = System.getProperty("test", "");
        Assumptions.assumeTrue(java.util.Arrays.stream(selector.split(","))
                .map(item -> item.strip().split("#")[0])
                .anyMatch(item -> item.equals(TARGET) || item.equals(GeminiKnowledgeAnswerQualityPostgresTest.class.getName())),
                "Explicit -Dtest=GeminiKnowledgeAnswerQualityPostgresTest required");
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null && !System.getenv("GEMINI_API_KEY").isBlank(),
                "GEMINI_API_KEY required; no filesystem credential reads in test");
        Assumptions.assumeTrue("gemini-3.1-flash-lite".equals(System.getenv("GEMINI_MODEL")), "Explicit approved GEMINI_MODEL required");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectEmbeddingGateway embeddings;
    @Autowired DocumentChunker chunker;
    @Autowired ModelSecretCipher cipher;
    @Autowired KnowledgeQuestionApplicationService questions;
    @Autowired KnowledgeStreamQuestionService streaming;
    @Autowired KnowledgeSessionApplicationService sessionService;
    @Autowired KnowledgeRepository knowledge;
    @MockitoSpyBean GeminiModelAdapter gemini;
    @MockitoSpyBean DocumentSearchService search;
    @MockitoBean TaskPlanRecoveryJob recoveryJob;
    @MockitoBean StringRedisTemplate redis;
    @Autowired AgentRunService agentRuns;
    @Autowired AgentRepository agentRepository;
    @Autowired AgentWorker agentWorker;
    @MockitoSpyBean AgentToolRegistry toolRegistry;

    @Test @Order(1) void productionKnowledgeAnswersAndStreams() throws Exception {
        if (Files.exists(OUTPUT)) {
            JsonNode prior = JSON.readTree(OUTPUT.toFile());
            Assumptions.assumeTrue("true".equals(System.getenv("AI_GEMINI_ACCEPTANCE_RESUME_TRANSPORT"))
                            && prior.path("generationRequests").asInt() <= 2
                            && java.util.stream.StreamSupport.stream(prior.path("rows").spliterator(), false)
                                    .noneMatch(row -> row.has("rawCompletion")),
                    "Existing real results: do not automatically repeat an acceptance round");
            Files.copy(OUTPUT, OUTPUT.resolveSibling("transport-attempt-" + prior.path("generationRequests").asInt() + ".json"));
            requests.set(prior.path("generationRequests").asInt());
            priorFailedAttempts = requests.get();
        }
        installObservers();
        seed();
        JsonNode cases = JSON.readTree(QUESTIONS.toFile());
        for (JsonNode testCase : cases) {
            if (testCase.path("id").asText().equals("Q13")) {
                indexDocument(FIXTURES.resolve("不可信资料测试.md"));
            }
            execute(testCase, false);
            if (testCase.path("id").asText().equals("Q13")) {
                // Keep the historical candidate competition unchanged for other questions.
                jdbc.update("UPDATE project_document SET status='FAILED' WHERE id=?", documents.get("不可信资料测试.md"));
            }
        }
        for (String id : List.of("Q03", "Q07")) {
            for (JsonNode testCase : cases) if (id.equals(testCase.path("id").asText())) execute(testCase, true);
        }
        save();
        assertThat(requests.get()).isLessThanOrEqualTo(24);
        assertThat(rows).hasSize(18);
        // These assertions check the experiment mechanics, never semantic acceptance by keyword.
        assertThat(rows).allSatisfy(row -> assertThat(row.get("reviewStatus")).isEqualTo("NEEDS_MANUAL_REVIEW"));
        Assumptions.assumeFalse(providerBlocked, "INCONCLUSIVE: provider failed; inspect saved evidence, not a quality pass");
    }

    @Test @Order(2) void agentRepresentativesWithinRemainingBudget() throws Exception {
        Assumptions.assumeTrue(Files.exists(OUTPUT), "Run the normal QA/stream stage first");
        JsonNode previous = JSON.readTree(OUTPUT.toFile());
        requests.set(previous.path("generationRequests").asInt());
        priorFailedAttempts = previous.path("priorFailedAttempts").asInt();
        rows.addAll(JSON.convertValue(previous.path("rows"), new TypeReference<List<Map<String, Object>>>() {}));
        Assumptions.assumeTrue(rows.size() == 18 && requests.get() <= 20, "Prior stage must be complete and within budget");
        Assumptions.assumeTrue(rows.stream().allMatch(row -> "COMPLETED_REQUIRES_REVIEW".equals(row.get("executionStatus"))),
                "Stop after an upstream provider failure");
        installObservers();
        seed();
        // Controlled read-only loop: the production registry only exposes the real knowledge search tool.
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") var definitions = (List<AgentToolDefinition>) invocation.callRealMethod();
            return definitions.stream().filter(tool -> tool.name().equals("search_project_knowledge")).toList();
        }).when(toolRegistry).definitionsFor(any(AgentExecutionContext.class), any(AgentSkill.class));
        doAnswer(invocation -> {
            ModelTurnCommand command = invocation.getArgument(2);
            reserveRequest();
            @SuppressWarnings("unchecked") var turns = (List<Map<String, Object>>) current.computeIfAbsent("turns", ignored -> new ArrayList<>());
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("requestNumber", requests.get()); turn.put("messages", command.messages());
            turn.put("tools", command.tools()); turns.add(turn);
            assertThat(command.purpose().name()).isEqualTo("AGENT");
            assertThat(command.tools()).allSatisfy(tool -> assertThat(tool.name()).isEqualTo("search_project_knowledge"));
            save();
            ModelTurnResult result = (ModelTurnResult) invocation.callRealMethod();
            turn.put("result", result);
            save();
            return result;
        }).when(gemini).turn(any(), anyString(), any());
        JsonNode cases = JSON.readTree(QUESTIONS.toFile());
        for (String id : List.of("Q03", "Q07")) {
            if (requests.get() >= 24) break;
            JsonNode annotation = null;
            for (JsonNode candidate : cases) if (id.equals(candidate.path("id").asText())) annotation = candidate;
            assertThat(annotation).isNotNull();
            current = new LinkedHashMap<>(); rows.add(current);
            current.put("id", id + "-AGENT"); current.put("mode", "REAL_AGENT_LOOP");
            current.put("annotation", annotation); current.put("reviewStatus", "NEEDS_MANUAL_REVIEW");
            current.put("restriction", "Only real read-only search tool exposed; unchanged production model prompt and runtime guards");
            var session = agentRuns.createSession(project, user, new CreateAgentSessionRequest(id));
            var run = agentRuns.submit(project, session.id(), user, new SubmitAgentMessageRequest(annotation.path("query").asText(), "PROJECT_RESEARCH", null, null));
            jdbc.update("UPDATE agent_run SET max_steps=6,max_tool_calls=2,max_children=0 WHERE id=?", run.id());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
            int initialCalls = requests.get();
            try {
                for (int round = 0; round < 12; round++) {
                    var state = agentRepository.findRun(project, run.id()).orElseThrow();
                    if (state.status().terminal()) break;
                    if (System.nanoTime() > deadline || requests.get() - initialCalls >= 3 || requests.get() >= 24) break;
                    var claimed = agentRepository.claimNext("gemini-answer-quality", OffsetDateTime.now(), Duration.ofMinutes(6)).orElse(null);
                    if (claimed == null) break;
                    agentWorker.process(claimed);
                }
                var state = agentRepository.findRun(project, run.id()).orElseThrow();
                current.put("run", state);
                current.put("steps", agentRepository.listSteps(project, run.id()));
                current.put("messages", agentRepository.listMessages(project, session.id(), 100));
                current.put("toolInvocations", jdbc.queryForList("SELECT tool_name,status,result_json FROM agent_tool_invocation WHERE run_id=? ORDER BY created_at", run.id()));
                current.put("executionStatus", state.status().name().equals("SUCCEEDED") ? "COMPLETED_REQUIRES_REVIEW" : "INCONCLUSIVE");
            } catch (BusinessException failure) {
                current.put("executionStatus", "INCONCLUSIVE"); current.put("errorCode", failure.getErrorCode().name());
                providerBlocked = true;
            } finally { save(); }
            if (providerBlocked) break;
        }
        assertThat(requests.get()).isLessThanOrEqualTo(24);
    }

    private void installObservers() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") List<DocumentSearchHit> candidates = (List<DocumentSearchHit>) invocation.callRealMethod();
            current.put("retrievalQuery", invocation.getArgument(1));
            current.put("candidates", candidates);
            var built = new KnowledgeContextBuilder().buildWithDiagnostics(candidates, 5);
            current.put("context", built.context());
            current.put("dropReasons", built.dropReasons());
            if ("REAL_AGENT_LOOP".equals(current.get("mode"))) {
                current.put("diagnosticDiverseContext", new KnowledgeContextBuilder().buildDiverse(candidates, 8));
            }
            return candidates;
        }).when(search).search(any(UUID.class), anyString(), anyList(), anyInt());
        doAnswer(invocation -> {
            beforeRequest(invocation.getArgument(2));
            ChatCompletionResult result = (ChatCompletionResult) invocation.callRealMethod();
            current.put("rawCompletion", result);
            return result;
        }).when(gemini).complete(any(), anyString(), any());
        doAnswer(invocation -> {
            beforeRequest(invocation.getArgument(2));
            Consumer<ChatCompletionResult> done = invocation.getArgument(4);
            invocation.getArguments()[4] = (Consumer<ChatCompletionResult>) result -> {
                current.put("rawCompletion", result);
                done.accept(result);
            };
            return invocation.callRealMethod();
        }).when(gemini).completeStream(any(), anyString(), any(), any(), any(), any());
    }

    private void beforeRequest(ChatCompletionCommand command) {
        reserveRequest();
        @SuppressWarnings("unchecked") List<Integer> attempts = (List<Integer>) current.computeIfAbsent("requestNumbers", ignored -> new ArrayList<Integer>());
        attempts.add(requests.get());
        assertThat(command.purpose().name()).isEqualTo("KNOWLEDGE_CHAT");
        assertThat(command.callerUserId()).isEqualTo(user);
        current.put("requestNumber", requests.get());
        current.put("systemPrompt", command.systemPrompt());
        current.put("systemPromptSha256", hash(command.systemPrompt()));
        current.put("userPrompt", command.userPrompt());
        current.put("purpose", command.purpose());
        try { save(); } catch (Exception failure) { throw new IllegalStateException("Cannot persist request ledger", failure); }
    }

    private void reserveRequest() {
        if (requests.get() >= 24) throw new IllegalStateException("External generation request budget exhausted");
        requests.incrementAndGet();
    }

    private void execute(JsonNode testCase, boolean stream) throws Exception {
        current = new LinkedHashMap<>();
        current.put("id", testCase.path("id").asText() + (stream ? "-SSE" : ""));
        current.put("mode", stream ? "STREAM_SERVICE" : "QUESTION_SERVICE");
        current.put("annotation", testCase);
        current.put("reviewStatus", "NEEDS_MANUAL_REVIEW");
        rows.add(current);
        if (providerBlocked) { current.put("executionStatus", "BLOCKED"); save(); return; }
        String id = testCase.path("id").asText();
        UUID session;
        if (testCase.has("followup") && !stream) session = sessions.get(testCase.path("followup").asText());
        else session = sessionService.create(project, new CreateKnowledgeSessionRequest(id), user).id();
        if (!stream) sessions.put(id, session);
        List<UUID> scope = new ArrayList<>();
        testCase.path("scope").forEach(name -> scope.add(documents.get(name.asText())));
        current.put("scopeDocumentIds", scope);
        var request = new KnowledgeQuestionRequest(testCase.path("query").asText(), scope);
        long started = System.nanoTime();
        try {
            if (!stream) {
                KnowledgeAnswerView answer = askWithBounded503Retry(session, request);
                current.put("response", answer);
            } else {
                CapturingEmitter emitter = new CapturingEmitter();
                streaming.askStream(project, session, request, user, emitter);
                assertThat(emitter.finished.await(150, TimeUnit.SECONDS)).as("bounded SSE completion").isTrue();
                current.put("events", emitter.events);
                assertThat(emitter.events.stream().filter(event -> "done".equals(event.type()) || "error".equals(event.type())).count()).isEqualTo(1);
                if (emitter.events.stream().anyMatch(event -> "error".equals(event.type()))) {
                    current.put("executionStatus", "INCONCLUSIVE");
                    providerBlocked = true;
                }
            }
            var messages = knowledge.listMessages(project, session, user);
            var citations = knowledge.listCitations(project, session, user);
            current.put("persistedMessages", messages);
            current.put("persistedCitations", citations);
            Object raw = current.get("rawCompletion");
            if (raw instanceof ChatCompletionResult completion) {
                @SuppressWarnings("unchecked") List<DocumentSearchHit> candidates = (List<DocumentSearchHit>) current.get("candidates");
                var context = new KnowledgeContextBuilder().build(candidates);
                var validated = new KnowledgeCitationValidator().validate(completion.content(), context.sources());
                current.put("validation", validated);
                assertThat(messages.getLast().getContent()).isEqualTo(validated.answer());
                assertThat(messages.getLast().getModelName()).isEqualTo(completion.model());
                assertThat(citations.stream().filter(citation -> citation.getMessageId().equals(messages.getLast().getId())).count()).isEqualTo(validated.citedSources().size());
                current.put("persistenceMatchesValidation", true);
            }
            current.putIfAbsent("executionStatus", "COMPLETED_REQUIRES_REVIEW");
        } catch (BusinessException failure) {
            current.put("executionStatus", "INCONCLUSIVE");
            current.put("errorCode", failure.getErrorCode().name());
            current.put("http503", is503(failure));
            // Stop after provider failures; never silently retry or substitute another model.
            providerBlocked = true;
        } catch (AssertionError | RuntimeException failure) {
            current.put("executionStatus", "FAIL");
            current.put("failureType", failure.getClass().getSimpleName());
            save();
            throw failure;
        } finally {
            current.put("latencyMs", (System.nanoTime() - started) / 1_000_000L);
            save();
        }
    }

    private KnowledgeAnswerView askWithBounded503Retry(UUID session, KnowledgeQuestionRequest request) throws InterruptedException {
        // Q01 already received one 503 in the transport preflight; at most two further attempts.
        int attempts = "Q01".equals(current.get("id")) && requests.get() == 2 ? 2 : 3;
        for (int attempt = 1; ; attempt++) {
            try { return questions.ask(project, session, request, user); }
            catch (BusinessException failure) {
                if (!is503(failure) || attempt >= attempts || requests.get() >= 24) throw failure;
                @SuppressWarnings("unchecked") List<String> failures = (List<String>) current.computeIfAbsent("retryReasons", ignored -> new ArrayList<String>());
                failures.add("HTTP_503");
                Thread.sleep(3_000L * attempt);
            }
        }
    }

    private static boolean is503(BusinessException failure) {
        return failure.getErrorCode().name().equals("AI_PROVIDER_ERROR") && failure.getMessage() != null
                && failure.getMessage().contains("HTTP 503");
    }

    private void seed() throws Exception {
        user = UUID.randomUUID(); project = UUID.randomUUID(); generation = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)", user, "answer-quality-" + user.toString().substring(0, 8), "test-only-hash", "Synthetic QA");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)", project, "Gemini synthetic answer quality", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", project, user);
        UUID embeddingConfig = UUID.randomUUID();
        jdbc.update("UPDATE system_embedding_config SET enabled=FALSE");
        jdbc.update("INSERT INTO system_embedding_config(id,provider,base_url,api_path,model_name,dimensions,batch_size,fingerprint,enabled) VALUES (?,'OLLAMA','http://127.0.0.1:11434','/v1/embeddings',?,1024,32,?,TRUE)", embeddingConfig, EMBEDDING_MODEL, FINGERPRINT);
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'ACTIVE')", generation, embeddingConfig);
        jdbc.update("UPDATE system_embedding_config SET generation_id=? WHERE id=?", generation, embeddingConfig);
        UUID provider = UUID.randomUUID();
        jdbc.update("INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,api_path,encrypted_api_key,model_name,enabled,temperature,max_output_tokens,capabilities,is_default) VALUES (?,?,?,'GEMINI','https://generativelanguage.googleapis.com','/v1beta/models/{model}:generateContent',?,?,TRUE,0.2,2048,'CHAT,STREAMING,USAGE,NATIVE_TOOLS',TRUE)", provider, user, "Authorized Gemini synthetic QA", cipher.encrypt(System.getenv("GEMINI_API_KEY")), System.getenv("GEMINI_MODEL"));
        jdbc.update("INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id) VALUES (?,'KNOWLEDGE_CHAT',?)", user, provider);
        jdbc.update("INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id) VALUES (?,'AGENT',?)", user, provider);
        try (var files = Files.list(FIXTURES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".md") && !path.getFileName().toString().equals("不可信资料测试.md")).sorted().toList()) indexDocument(file);
        }
        assertThat(documents).hasSize(13);
    }

    private void indexDocument(Path file) throws Exception {
        String filename = file.getFileName().toString();
        UUID document = UUID.randomUUID(); documents.put(filename, document);
        var chunks = chunker.split(Files.readString(file, StandardCharsets.UTF_8));
        var vectors = embeddings.embed(project, chunks.stream().map(chunk -> chunk.content()).toList(), () -> {});
        assertThat(vectors.dimension()).isEqualTo(1024);
        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,chunk_count,embedding_provider,embedding_model,embedding_dimension,embedding_fingerprint,uploaded_by,indexed_at) VALUES (?,?,?,?,'text/markdown',100,?,'READY',?,'OLLAMA',?,1024,?,?,now())", document, project, filename, filename, "synthetic/" + document, chunks.size(), EMBEDDING_MODEL, FINGERPRINT, user);
        for (int index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            jdbc.update("INSERT INTO document_chunk(id,project_id,document_id,chunk_no,heading,content,content_hash,token_estimate,metadata,embedding_provider,embedding_model,embedding_dimension,embedding_fingerprint,embedding,generation_id) VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),'OLLAMA',?,1024,?,CAST(? AS vector),?)", UUID.randomUUID(), project, document, chunk.chunkNo(), chunk.heading(), chunk.content(), chunk.contentHash(), chunk.tokenEstimate(), JSON.writeValueAsString(chunk.metadata()), EMBEDDING_MODEL, FINGERPRINT, vectors.vectors().get(index).toString(), generation);
        }
    }

    private void save() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("model", System.getenv("GEMINI_MODEL"));
        report.put("embeddingModel", EMBEDDING_MODEL); report.put("dimensions", 1024);
        report.put("fingerprint", FINGERPRINT); report.put("generationRequests", requests.get());
        report.put("requestLimit", 24); report.put("maxOutputTokens", 2048);
        report.put("priorFailedAttempts", priorFailedAttempts);
        long localRetries = rows.stream().map(row -> row.get("retryReasons"))
                .filter(List.class::isInstance).map(List.class::cast).mapToLong(List::size).sum();
        report.put("automatic503Retries", localRetries);
        report.put("retriesIncludingTransportRestarts", priorFailedAttempts + localRetries);
        report.put("transport", "Production RoutingChatModelGateway -> GeminiModelAdapter -> official Google endpoint");
        report.put("sseScope", "Production stream service and SseEmitter events; no browser or HTTP serialization assertion");
        report.put("rows", rows);
        Files.createDirectories(OUTPUT.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(OUTPUT.toFile(), report);
    }

    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    static final class CapturingEmitter extends SseEmitter {
        final List<KnowledgeStreamEvent> events = new CopyOnWriteArrayList<>();
        final CountDownLatch finished = new CountDownLatch(1);
        @Override public void send(SseEventBuilder builder) {
            for (var item : builder.build()) if (item.getData() instanceof KnowledgeStreamEvent event) events.add(event);
        }
        @Override public void complete() { finished.countDown(); }
        @Override public void completeWithError(Throwable failure) { finished.countDown(); }
    }
}
