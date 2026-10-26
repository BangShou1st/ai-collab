package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.document.application.service.*;
import com.shitulelv.aicollab.document.application.view.DocumentSearchHit;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.document.infrastructure.parser.DocumentParser;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.embedding.*;
import com.shitulelv.aicollab.infrastructure.ai.model.*;
import com.shitulelv.aicollab.infrastructure.ai.user.*;
import com.shitulelv.aicollab.knowledge.api.dto.KnowledgeQuestionRequest;
import com.shitulelv.aicollab.knowledge.application.service.KnowledgeQuestionApplicationService;
import com.shitulelv.aicollab.knowledge.domain.model.*;
import com.shitulelv.aicollab.knowledge.domain.service.*;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Explicit local preflight opt-in, then separately authorized real mode. Default CI skips the
 * entire Spring context. Credentials stay in process memory / disposable database only.
 * Preflight responses are synthetic transport stubs, never quality measurements.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true", "embedding.enabled=false", "chat.enabled=false",
        "planning.enabled=false", "agent.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "security.jwt.access-token-minutes=30", "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=100ms", "spring.data.redis.timeout=100ms",
        "storage.minio.endpoint=http://127.0.0.1:1", "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret", "storage.minio.bucket=test-bucket"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "P10_LOCAL_PREFLIGHT", matches = "true")
@Testcontainers
class P10RealGenerationPostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURES = ROOT.resolve("tests/fixtures/documents/p9-rag-generalization-01");
    private static final P10RealGenerationGate GATE = new P10RealGenerationGate(System.getenv());
    private static final String MODEL = "qwen3-embedding:0.6b";
    private static final String FP = EmbeddingFingerprints.fingerprint("OLLAMA", MODEL, 1024);
    private static final String F4 = "F4-外部审计参考摘录-未审核来源.md";
    private static final List<String> IDS = List.of("AQ01-CURRENT", "AQ02-REPEALED", "AQ04-REGION-THRESHOLD",
            "AQ06-UNLESS", "AQ07-AND-OR", "AQ11-ABSTAIN-NONE", "AQ12-ABSTAIN-INDIRECT", "AQ14-INJECTION");
    private static final Path OUTPUT = Path.of("target/p10-real-generation-01", GATE.real() ? "real" : "preflight");
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("model.config.master-key", () -> {
            try {
                // Do not print or export .env contents. Read the one required cipher key in memory.
                for (String line : Files.readAllLines(ROOT.resolve(".env"))) {
                    if (line.startsWith("MODEL_CONFIG_MASTER_KEY=")) {
                        String value = line.substring(line.indexOf('=') + 1).strip();
                        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                                || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                        return value;
                    }
                }
            } catch (Exception ignored) { /* fail closed without printing configuration */ }
            throw new IllegalStateException("Local master key unavailable");
        });
    }

    @MockitoBean TaskPlanRecoveryJob recovery;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentParser parser;
    @Autowired DocumentChunker chunker;
    @Autowired DocumentIndexWriter writer;
    @Autowired ProjectEmbeddingGateway embeddings;
    @Autowired KnowledgeQuestionApplicationService qa;
    @Autowired UserAiProviderRepository providers;
    @Autowired UserAiProviderService routing;
    @MockitoSpyBean DocumentSearchService search;
    @MockitoSpyBean KnowledgeContextBuilder builder;
    @MockitoSpyBean RoutingChatModelGateway chat;
    @MockitoSpyBean KnowledgeCitationValidator validator;
    @MockitoSpyBean JsonHttpModelClient http;
    private UUID user, project, generation;
    private final Map<String, UUID> documents = new LinkedHashMap<>();
    private final List<Map<String, Object>> retrieval = new ArrayList<>();
    private final List<Map<String, Object>> results = new ArrayList<>();
    private final List<Map<String, Object>> calls = new ArrayList<>();
    private Map<String, Object> current;
    private KnowledgeContext context;
    private UserAiProvider selected;
    private int syntheticPosts;
    private String routeHash;

    @Test
    void measureFrozenQuestionsThroughProductionQa() throws Exception {
        Files.createDirectories(OUTPUT);
        // Refuse accidental re-execution of a real run; a new approval must be reviewed first.
        if (GATE.real() && Files.exists(OUTPUT.resolve("model-call-ledger.json")))
            throw new IllegalStateException("Existing real ledger: no automatic rerun");
        JsonNode questions = JSON.readTree(FIXTURES.resolve("p10-evaluation-question-set.json").toFile());
        for (String id : IDS) {
            var row = new LinkedHashMap<String, Object>();
            row.put("id", id);
            row.put("query", findQuestion(questions, id).path("query").asText());
            row.put("status", "NOT_RUN");
            results.add(row);
        }
        flush();
        try {
            prepareDatabase();
            observeProduction();
        } catch (Exception failure) {
            flush();
            throw failure;
        }
        Map<String, JsonNode> p9 = new LinkedHashMap<>();
        for (String file : List.of("question-manifest.json", "question-manifest-holdout.json"))
            for (JsonNode q : JSON.readTree(FIXTURES.resolve(file).toFile()).path("questions")) p9.put(q.path("id").asText(), q);
        boolean stopped = false;
        try {
            for (String id : IDS) {
                JsonNode q = findQuestion(questions, id);
                Map<String, Object> row = results.stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow();
                if (stopped) continue;
                boolean temporary = Set.of("H-G07-NONE", "H-G07-SIMILAR", "H-G07-INJECT").contains(q.path("p9Ref").asText());
                if (temporary) index(F4);
                current = new LinkedHashMap<>();
                current.put("id", id);
                current.put("query", q.path("query").asText());
                retrieval.add(current);
                UUID session = UUID.randomUUID();
                jdbc.update("INSERT INTO knowledge_session(id,project_id,user_id,title) VALUES (?,?,?,?)", session, project, user, id);
                List<UUID> scope = new ArrayList<>();
                p9.get(q.path("p9Ref").asText()).path("scope").forEach(name -> scope.add(Objects.requireNonNull(documents.get(name.asText()))));
                context = null;
                try (var cap = new AiRequestOutputCap(1024)) {
                    var view = qa.ask(project, session, new KnowledgeQuestionRequest(q.path("query").asText(), scope), user);
                    row.put("status", GATE.real() ? (row.containsKey("completion") ? "EXECUTED" : "NO_CHAT_NO_SOURCES") : "PREFLIGHT_SYNTHETIC_TRANSPORT");
                    row.put("final", view);
                    row.put("persistedMessages", jdbc.queryForList("SELECT role,content,model_name,prompt_tokens,completion_tokens FROM knowledge_message WHERE session_id=? ORDER BY created_at", session));
                    var savedCitations = jdbc.queryForList("SELECT kc.rank,kc.chunk_id,dc.document_id,kc.quote_text FROM knowledge_citation kc JOIN document_chunk dc ON dc.id=kc.chunk_id WHERE kc.message_id=? ORDER BY kc.rank", view.messageId());
                    row.put("persistedCitations", savedCitations);
                    assertThat(savedCitations).hasSize(view.citations().size());
                    assertThat(jdbc.queryForObject("SELECT content FROM knowledge_message WHERE id=?", String.class, view.messageId())).isEqualTo(view.answer());
                    if (context != null) {
                        List<Map<String, Object>> facts = availableFacts(q);
                        current.put("facts", facts);
                        if (GATE.real() && row.get("rawAnswer") instanceof String answer) {
                            var sources = context.sources().stream().map(s -> new P10AnswerQualityScorer.Source(s.rank(), s.originalFilename(), s.documentId().toString(), s.chunkId().toString(), s.content())).toList();
                            Map<String, Object> rules = JSON.convertValue(q.path("rules"), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                            row.put("mechanicalScore", P10AnswerQualityScorer.score(id, q.path("dimension").asText(), q.path("abstain").asBoolean(), facts, rules, answer, sources, P10AnswerQualityScorer.QA, null));
                            row.put("humanReview", "PENDING_INDEPENDENT_HUMAN_REVIEW");
                        }
                    }
                } catch (Exception failure) {
                    row.put("status", "FAILED");
                    row.put("errorType", failure.getClass().getSimpleName());
                    if (failure instanceof com.shitulelv.aicollab.common.exception.BusinessException business)
                        row.put("errorCode", business.getErrorCode().name());
                    stopped = true; // any failure stops the batch; never retry or switch providers
                } finally {
                    if (temporary) jdbc.update("UPDATE project_document SET status='FAILED' WHERE id=?", documents.get(F4));
                    flush();
                }
                if (GATE.real() && !stopped && !id.equals(IDS.getLast()))
                    Thread.sleep(Long.parseLong(System.getenv("P10_INTERVAL_SECONDS")) * 1000L);
            }
        } finally { flush(); }
        assertThat(results).noneMatch(row -> "FAILED".equals(row.get("status")));
        if (!GATE.real()) {
            assertThat(GATE.attempts()).isZero();
            assertThat(syntheticPosts).as("Each frozen question must reach the outbound boundary exactly once").isEqualTo(8);
        }
    }

    private void observeProduction() {
        doAnswer(inv -> {
            List<DocumentSearchHit> hits = (List<DocumentSearchHit>) inv.callRealMethod();
            assertThat(inv.<Integer>getArgument(3)).isEqualTo(8);
            current.put("candidates", hits);
            current.put("scope", inv.getArgument(2));
            return hits;
        }).when(search).search(any(), anyString(), anyList(), anyInt());
        doAnswer(inv -> {
            context = (KnowledgeContext) inv.callRealMethod();
            assertThat(context.sources()).hasSizeLessThanOrEqualTo(5);
            assertThat(context.promptSources().codePointCount(0, context.promptSources().length())).isLessThanOrEqualTo(8000);
            current.put("context", context);
            for (KnowledgeSource s : context.sources()) {
                assertThat(jdbc.queryForObject("SELECT document_id FROM document_chunk WHERE id=?", UUID.class, s.chunkId())).isEqualTo(s.documentId());
                assertThat(jdbc.queryForObject("SELECT content FROM document_chunk WHERE id=?", String.class, s.chunkId())).startsWith(s.content());
            }
            current.put("sourceIdentity", "VERIFIED_AGAINST_ISOLATED_DATABASE");
            return context;
        }).when(builder).buildBalanced(anyList());
        doAnswer(inv -> {
            ChatCompletionCommand command = inv.getArgument(0);
            assertThat(command.purpose()).isEqualTo(ModelPurpose.KNOWLEDGE_CHAT);
            assertThat(command.callerUserId()).isEqualTo(user);
            assertThat(routing.resolve(user, command.purpose()).id()).isEqualTo(selected.id());
            current.put("systemPrompt", command.systemPrompt());
            current.put("userPrompt", command.userPrompt());
            current.put("systemPromptSha256", hash(command.systemPrompt()));
            current.put("userPromptSha256", hash(command.userPrompt()));
            var completion = (com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult) inv.callRealMethod();
            resultRow().put("completion", completion);
            resultRow().put("rawAnswer", completion.content());
            return completion;
        }).when(chat).complete(any(ChatCompletionCommand.class), any(AiRequestMetadata.class));
        doAnswer(inv -> {
            var validated = (ValidatedKnowledgeAnswer) inv.callRealMethod();
            resultRow().put("validation", validated);
            return validated;
        }).when(validator).validate(anyString(), anyList());
        doAnswer(inv -> {
            JsonNode body = inv.getArgument(2);
            assertThat(body.path("generationConfig").path("maxOutputTokens").asInt()).isEqualTo(1024);
            current.put("wireBody", body); // excludes URL, headers and credentials
            if (!GATE.real()) {
                syntheticPosts++;
                return JSON.readTree("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"当前项目资料不足以回答该问题\"}]}}]}");
            }
            GATE.reserve();
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", current.get("id"));
            call.put("attempt", GATE.attempts());
            call.put("startedAt", OffsetDateTime.now().toString());
            call.put("status", "DISPATCH_RESERVED_OUTCOME_UNKNOWN");
            calls.add(call);
            flush(); // write-ahead accounting, including crashes/timeouts
            long start = System.nanoTime();
            try (var outbound = new AiOutboundObservation()) {
                try {
                    JsonNode response = (JsonNode) inv.callRealMethod();
                    resultRow().put("providerResponse", response);
                    call.put("usage", response.path("usageMetadata"));
                    call.put("status", "HTTP_RETURNED");
                    return response;
                } finally {
                    call.put("transportStarted", outbound.wasStarted());
                }
            } catch (Exception failure) {
                call.put("status", "FAILED_OR_UNKNOWN");
                throw failure;
            } finally {
                call.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
                flush();
            }
        }).when(http).post(anyString(), anyMap(), any(JsonNode.class));
        // Any non-QA network method is outside this experiment.
        doThrow(new IllegalStateException("P10 forbids model discovery calls")).when(http).get(anyString(), anyMap());
    }

    private void prepareDatabase() throws Exception {
        // Source database access is SELECT only; only one approved user's purpose-resolved row.
        String sql = "SELECT row_to_json(p) FROM user_ai_provider p JOIN app_user u ON u.id=p.user_id "
                + "WHERE u.username='p3u04_admin' AND p.id=COALESCE((SELECT provider_id FROM user_model_purpose_assignment WHERE user_id=u.id AND purpose='KNOWLEDGE_CHAT'),"
                + "(SELECT id FROM user_ai_provider WHERE user_id=u.id AND is_default))";
        Process names = new ProcessBuilder("docker", "exec", "ai-collab-postgres", "printenv", "POSTGRES_USER", "POSTGRES_DB").start();
        String[] databaseNames = new String(names.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip().split("\\R");
        if (names.waitFor() != 0 || databaseNames.length != 2) throw new IllegalStateException("Local database unavailable");
        Process process = new ProcessBuilder("docker", "exec", "-i", "ai-collab-postgres", "psql", "-X", "-q", "-t", "-A",
                "-v", "ON_ERROR_STOP=1", "-U", databaseNames[0], "-d", databaseNames[1]).start();
        process.getOutputStream().write((sql + ";\n").getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String source = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0 || source.isBlank()) throw new IllegalStateException("Selected route unavailable");
        JsonNode p = JSON.readTree(source);
        Map<String, Object> route = new LinkedHashMap<>();
        for (String field : List.of("id", "user_id", "name", "provider_type", "base_url", "api_path", "model_name",
                "enabled", "temperature", "max_output_tokens", "capabilities", "updated_at"))
            route.put(field, p.path(field).asText());
        routeHash = hash(JSON.writeValueAsString(route));
        if (GATE.real() && !routeHash.equals(System.getenv("P10_APPROVED_ROUTE_SHA256")))
            throw new IllegalStateException("Route snapshot changed after approval");
        if (!"GEMINI".equals(p.path("provider_type").asText()) || !"gemini-3.5-flash-lite".equals(p.path("model_name").asText()) || !p.path("enabled").asBoolean())
            throw new IllegalStateException("Approved route changed; re-review required");
        user = UUID.fromString(p.path("user_id").asText());
        project = UUID.randomUUID(); generation = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)", user, "p10-isolated", "test-only-hash", "P10 isolated");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)", project, "P10 isolated", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", project, user);
        selected = new UserAiProvider(UUID.fromString(p.path("id").asText()), user, p.path("name").asText(), ModelProviderType.GEMINI,
                p.path("base_url").asText(), p.path("api_path").asText(), p.path("encrypted_api_key").asText(),
                p.path("model_name").asText(), true, p.path("temperature").asDouble(), p.path("max_output_tokens").asInt(),
                Arrays.stream(p.path("capabilities").asText().split(",")).map(String::strip)
                        .filter(s -> !s.isBlank()).map(ModelCapability::valueOf)
                        .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(ModelCapability.class))),
                false, OffsetDateTime.now(), OffsetDateTime.now(), null);
        providers.save(selected);
        providers.assignPurpose(user, ModelPurpose.KNOWLEDGE_CHAT, selected.id());
        UUID config = UUID.randomUUID();
        jdbc.update("INSERT INTO system_embedding_config(id,provider,base_url,api_path,model_name,dimensions,batch_size,fingerprint,enabled) VALUES (?,'OLLAMA','http://127.0.0.1:11434','/v1/embeddings',?,1024,32,?,TRUE)", config, MODEL, FP);
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'ACTIVE')", generation, config);
        jdbc.update("UPDATE system_embedding_config SET generation_id=? WHERE id=?", generation, config);
        for (JsonNode entry : JSON.readTree(FIXTURES.resolve("corpus-manifest.json").toFile()).path("documents")) {
            String name = entry.path("filename").asText();
            assertThat(hash(Files.readString(FIXTURES.resolve(name)))).isEqualTo(entry.path("sha256").asText());
            if (!name.equals(F4)) index(name);
        }
        assertThat(documents).hasSize(22);
    }

    private void index(String filename) throws Exception {
        byte[] bytes = Files.readAllBytes(FIXTURES.resolve(filename));
        var parsed = parser.parse(bytes, filename, "text/markdown");
        var chunks = chunker.split(parsed.text());
        var vectors = embeddings.embed(project, chunks.stream().map(c -> c.content()).toList(), EmbeddingProgressListener.NONE);
        assertThat(vectors.dimension()).isEqualTo(1024);
        assertThat(vectors.fingerprint()).isEqualTo(FP);
        UUID document = UUID.randomUUID(), token = UUID.randomUUID();
        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,processing_token,uploaded_by) VALUES (?,?,?,?,'text/markdown',?,?,'INDEXING',?,?)",
                document, project, filename, filename, bytes.length, "p10/" + document, token, user);
        assertThat(writer.replaceAndComplete(project, document, token, chunks, vectors, filename)).isTrue();
        documents.put(filename, document);
    }

    private void flush() throws Exception {
        write("retrieval-context.json", retrieval);
        write("generation-results.json", results);
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("task", "P10-REAL-GENERATION-01");
        ledger.put("mode", GATE.real() ? "REAL" : "PREFLIGHT_SYNTHETIC_TRANSPORT");
        ledger.put("authorization", GATE.real());
        ledger.put("provider", "GEMINI");
        ledger.put("model", "gemini-3.5-flash-lite");
        ledger.put("routeSha256", routeHash);
        ledger.put("chatDispatchReservations", GATE.attempts());
        ledger.put("syntheticTransportResponses", syntheticPosts);
        ledger.put("externalEmbedding", 0);
        ledger.put("agent", 0);
        ledger.put("judge", 0);
        ledger.put("chatRetries", 0);
        ledger.put("actualCost", "UNAVAILABLE");
        ledger.put("calls", calls);
        ledger.put("generationVerdict", GATE.attempts() == 0 ? "REAL_GENERATION_NOT_EXECUTED" : "REAL_GENERATION_ATTEMPTED_SEE_CALL_OUTCOMES");
        write("model-call-ledger.json", ledger);
    }

    private static void write(String name, Object value) throws Exception {
        Path staging = OUTPUT.resolve(name + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(staging.toFile(), value);
        Files.move(staging, OUTPUT.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    private static JsonNode findQuestion(JsonNode questions, String id) {
        for (JsonNode q : questions.path("questions")) if (q.path("id").asText().equals(id)) return q;
        throw new IllegalStateException("Frozen question missing");
    }

    private List<Map<String, Object>> availableFacts(JsonNode question) {
        List<Map<String, Object>> facts = new ArrayList<>();
        for (JsonNode f : question.path("expectedFacts")) {
            Map<String, Object> fact = JSON.convertValue(f, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            boolean present = context.sources().stream().anyMatch(s -> s.originalFilename().equals(f.path("document").asText()) && s.content().contains(f.path("marker").asText()));
            fact.put("contextAvailability", present ? "RETAINED" : "CONTEXT_MISS");
            facts.add(fact);
        }
        // Additional rubric markers are measured afresh too; no P9 historic availability is used.
        for (JsonNode marker : question.path("rules").path("requiredMarkers")) {
            String text = marker.asText();
            if (facts.stream().anyMatch(f -> String.valueOf(f.get("marker")).contains(text))) continue;
            var matches = context.sources().stream().filter(s -> s.content().contains(text)).toList();
            facts.add(Map.of("marker", text, "document", matches.isEmpty() ? "UNAVAILABLE" : matches.getFirst().originalFilename(),
                    "contextAvailability", matches.isEmpty() ? "CONTEXT_MISS" : "RETAINED",
                    "origin", "RUBRIC_REQUIRED_MARKER_OBSERVED_IN_CURRENT_CONTEXT"));
        }
        return facts;
    }

    private Map<String, Object> resultRow() {
        return results.stream().filter(row -> row.get("id").equals(current.get("id"))).findFirst().orElseThrow();
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
