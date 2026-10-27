package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.agent.application.runtime.AgentCancellationService;
import com.shitulelv.aicollab.agent.application.view.AgentRunView;
import com.shitulelv.aicollab.agent.domain.model.AgentRunStatus;
import com.shitulelv.aicollab.agent.infrastructure.repository.AgentRepository;
import com.shitulelv.aicollab.document.application.service.DocumentContentService;
import com.shitulelv.aicollab.document.application.service.DocumentIndexWriter;
import com.shitulelv.aicollab.document.application.service.DocumentSearchService;
import com.shitulelv.aicollab.document.domain.service.DocumentChunker;
import com.shitulelv.aicollab.document.infrastructure.ai.EmbeddingProgressListener;
import com.shitulelv.aicollab.document.infrastructure.parser.DocumentParser;
import com.shitulelv.aicollab.infrastructure.ai.embedding.EmbeddingFingerprints;
import com.shitulelv.aicollab.infrastructure.ai.embedding.ProjectEmbeddingGateway;
import com.shitulelv.aicollab.infrastructure.ai.model.ExternalProviderFailure;
import com.shitulelv.aicollab.infrastructure.ai.model.KiloGatewayTransport;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnGateway;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderRepository;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * P10-REAL-GENERATION-04: 有限真实复测与截断故障诊断。
 * 针对 AG06, AG15, AG19 截断场景与 AG01 成功对照场景，重建真实调用链并捕获完整原始响应。
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true", "embedding.enabled=false", "chat.enabled=false",
        "planning.enabled=false", "agent.enabled=true",
        "agent.worker-delay-ms=500", "agent.planning.recovery-delay-ms=120000",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "security.jwt.access-token-minutes=30", "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=100ms", "spring.data.redis.timeout=100ms",
        "storage.minio.endpoint=http://127.0.0.1:1", "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret", "storage.minio.bucket=test-bucket"
})
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "P10_LOCAL_PREFLIGHT", matches = "true")
@Testcontainers
class P10RealAgentGeneration04ReproductionPostgresTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURES = ROOT.resolve("tests/fixtures/documents/p9-rag-generalization-01");
    private static final P10RealAgent04Gate GATE = new P10RealAgent04Gate(System.getenv());
    private static final String EMBED_MODEL = "qwen3-embedding:0.6b";
    private static final String FP = EmbeddingFingerprints.fingerprint("OLLAMA", EMBED_MODEL, 1024);
    private static final String F4 = "F4-外部审计参考摘录-未审核来源.md";
    private static final long SCENARIO_TIMEOUT_MS = 180_000;
    private static final Path OUTPUT = ROOT.resolve("docs/acceptance-evidence/2026-10-27/p10-real-generation-04/raw");

    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("model.config.master-key", () -> {
            try {
                for (String line : Files.readAllLines(ROOT.resolve(".env"))) {
                    if (line.startsWith("MODEL_CONFIG_MASTER_KEY=")) {
                        String value = line.substring(line.indexOf('=') + 1).strip();
                        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                                || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
                        return value;
                    }
                }
            } catch (Exception ignored) { /* fail closed */ }
            throw new IllegalStateException("Local master key unavailable");
        });
    }

    @MockitoBean TaskPlanRecoveryJob planningRecovery;
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentParser parser;
    @Autowired DocumentChunker chunker;
    @Autowired DocumentIndexWriter writer;
    @Autowired ProjectEmbeddingGateway embeddings;
    @Autowired DocumentContentService content;
    @Autowired AgentRepository repository;
    @Autowired AgentCancellationService cancellation;
    @Autowired UserAiProviderRepository providers;
    @MockitoSpyBean DocumentSearchService search;
    @MockitoSpyBean ModelTurnGateway modelTurn;
    @MockitoSpyBean KiloGatewayTransport kiloTransport;

    private UUID user, project;
    private final Map<String, UUID> documents = new LinkedHashMap<>();
    private final List<Map<String, Object>> scenarioResults = new ArrayList<>();
    private final List<Map<String, Object>> modelCalls = new ArrayList<>();
    private final List<Map<String, Object>> rawTransportCalls = new ArrayList<>();
    private JsonNode scenarios;
    private String currentScenario;
    private int currentScenarioModelCalls;
    private int syntheticTurns;
    private String routeSnapshot;

    private static final Set<String> TARGET_SCENARIOS = Set.of("AG01", "AG06", "AG15", "AG19");

    @Test
    void executeTargetScenariosWithFullObservability() throws Exception {
        Files.createDirectories(OUTPUT);
        scenarios = JSON.readTree(FIXTURES.resolve("p10-real-generation-03-agent-scenarios.json").toFile()).path("scenarios");
        prepareDatabase();
        instrumentKiloTransport();

        boolean externalBlocked = false;
        String blockedReason = null;
        for (JsonNode scenario : scenarios) {
            String id = scenario.path("id").asText();
            if (!TARGET_SCENARIOS.contains(id)) continue;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id);
            row.put("dimension", scenario.path("dimension").asText());
            row.put("status", "NOT_RUN");
            scenarioResults.add(row);
            if (GATE.real()) {
                if (externalBlocked) {
                    row.put("status", "NOT_RUN_AFTER_EXTERNAL_BLOCK");
                    row.put("blockedReason", blockedReason);
                    continue;
                }
                GATE.reserveScenario();
            }
            try {
                executeScenario(scenario, row);
            } catch (ExternalBlock block) {
                externalBlocked = true;
                blockedReason = block.reason;
                row.put("status", "BLOCKED_EXTERNAL");
                row.put("blockedReason", block.reason);
            } finally {
                flush();
            }
        }
        flush();
    }

    private void executeScenario(JsonNode scenario, Map<String, Object> row) throws Exception {
        currentScenario = scenario.path("id").asText();
        currentScenarioModelCalls = 0;
        List<String> temporary = new ArrayList<>();
        for (JsonNode name : scenario.path("temporaryDocuments")) {
            if (!documents.containsKey(name.asText())) index(name.asText());
            temporary.add(name.asText());
        }
        long businessRowsBefore = countBusinessRows();
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO agent_session(id,project_id,creator_id,title) VALUES (?,?,?,?)", session, project, user, currentScenario);
        String goal = scenario.path("goal").asText();
        AgentRunView run = repository.createRun(project, session, user, goal, false, null, null);
        jdbc.update("UPDATE agent_run SET max_steps=6, max_tool_calls=4, max_children=1 WHERE id=?", run.id());
        row.put("runId", run.id().toString());
        row.put("goal", goal);
        long started = System.nanoTime();
        AgentRunStatus status = waitTerminal(run.id(), started);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        try {
            row.put("status", "EXECUTED");
            row.put("runStatus", status.name());
            var runRow = jdbc.queryForMap("SELECT status, COALESCE(error_code,'') AS err FROM agent_run WHERE id=?", run.id());
            row.put("runStatus", String.valueOf(runRow.get("status")));
            row.put("runError", String.valueOf(runRow.get("err")));
            row.put("elapsedMs", elapsedMs);
            row.put("modelCalls", currentScenarioModelCalls);
            row.put("finalAnswer", safeAnswer(run.id()));
            row.put("citations", citationsOf(run.id()));
            row.put("toolInvocations", toolInvocations(run.id()));
            assertNoBusinessWrites(row, businessRowsBefore);
        } finally {
            for (String name : temporary)
                jdbc.update("UPDATE project_document SET status='FAILED' WHERE id=?", documents.get(name));
        }
    }

    private AgentRunStatus waitTerminal(UUID runId, long startedNanos) throws InterruptedException {
        while ((System.nanoTime() - startedNanos) / 1_000_000 < SCENARIO_TIMEOUT_MS) {
            Optional<AgentRunView> view = repository.findRun(project, runId);
            if (view.isPresent() && view.get().status().terminal()) return view.get().status();
            Thread.sleep(400);
        }
        cancellation.interrupt(runId);
        for (int i = 0; i < 60; i++) {
            Optional<AgentRunView> view = repository.findRun(project, runId);
            if (view.isPresent() && view.get().status().terminal()) return view.get().status();
            Thread.sleep(500);
        }
        throw new IllegalStateException("scenario run did not reach terminal state within wall-clock budget");
    }

    private String safeAnswer(UUID runId) {
        try {
            return jdbc.queryForObject("""
                    SELECT content FROM agent_message WHERE run_id=? AND role='ASSISTANT'
                    ORDER BY created_at DESC LIMIT 1
                    """, String.class, runId);
        } catch (org.springframework.dao.EmptyResultDataAccessException none) {
            return null;
        }
    }

    private List<String> citationsOf(UUID runId) {
        String raw;
        try {
            raw = jdbc.queryForObject("""
                    SELECT COALESCE(citations_json::text,'[]') FROM agent_message
                    WHERE run_id=? AND role='ASSISTANT' ORDER BY created_at DESC LIMIT 1
                    """, String.class, runId);
        } catch (org.springframework.dao.EmptyResultDataAccessException none) {
            return new ArrayList<>();
        }
        List<String> found = new ArrayList<>();
        if (raw == null) return found;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                .matcher(raw);
        while (matcher.find()) found.add(matcher.group());
        return found.stream().distinct().toList();
    }

    private List<Map<String, Object>> toolInvocations(UUID runId) {
        return jdbc.queryForList("""
                SELECT sequence_no, type, tool_name, input_json::text AS input,
                       COALESCE(output_json->>'status','UNKNOWN') AS tool_status,
                       output_json::text AS output, reason
                FROM agent_step WHERE run_id=? AND type IN ('TOOL_CALL_COMPLETED','TOOL_CALL_FAILED')
                ORDER BY sequence_no
                """, runId);
    }

    private long countBusinessRows() {
        return jdbc.queryForObject("SELECT (SELECT count(*) FROM project_task WHERE project_id=?) + (SELECT count(*) FROM milestone WHERE project_id=?) + (SELECT count(*) FROM agent_memory WHERE project_id=?)", Long.class, project, project, project);
    }

    private void assertNoBusinessWrites(Map<String, Object> row, long before) {
        long after = countBusinessRows();
        row.put("businessRowsBefore", before);
        row.put("businessRowsAfter", after);
        assertThat(after).as("Agent 必须只读：业务表不得出现新行").isEqualTo(before);
    }

    private void prepareDatabase() throws Exception {
        String sql = "SELECT row_to_json(p) FROM user_ai_provider p JOIN app_user u ON u.id=p.user_id "
                + "WHERE u.username='p3u04_admin' AND p.is_default";
        Process names = new ProcessBuilder("docker", "exec", "ai-collab-postgres", "printenv", "POSTGRES_USER", "POSTGRES_DB").start();
        String[] databaseNames = new String(names.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip().split("\\R");
        if (names.waitFor() != 0 || databaseNames.length != 2) throw new IllegalStateException("Local database unavailable");
        Process process = new ProcessBuilder("docker", "exec", "-i", "ai-collab-postgres", "psql", "-X", "-q", "-t", "-A",
                "-v", "ON_ERROR_STOP=1", "-U", databaseNames[0], "-d", databaseNames[1]).start();
        process.getOutputStream().write((sql + ";\n").getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String source = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0 || source.isBlank()) throw new IllegalStateException("Default route unavailable");
        JsonNode p = JSON.readTree(source);
        if (GATE.real()) {
            String model = p.path("model_name").asText();
            if (!GATE.model().equals(model) || !"OPENAI_COMPATIBLE".equals(p.path("provider_type").asText()) || !p.path("enabled").asBoolean())
                throw new IllegalStateException("Default agent route changed; re-review required");
        }
        Map<String, String> route = new TreeMap<>();
        route.put("provider_type", p.path("provider_type").asText());
        route.put("base_url", p.path("base_url").asText());
        route.put("model_name", p.path("model_name").asText());
        route.put("enabled", p.path("enabled").asText());
        routeSnapshot = hash(JSON.writeValueAsString(route));
        if (GATE.real() && !GATE.approvedRouteSha256().equals(routeSnapshot))
            throw new IllegalStateException("Agent route snapshot changed after approval");
        user = UUID.fromString(p.path("user_id").asText());
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)", user, "p10-agent-04", "test-only-hash", "P10 agent 04 isolated");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)", project, "P10 agent 04 isolated", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", project, user);
        providers.save(new UserAiProvider(UUID.fromString(p.path("id").asText()), user, p.path("name").asText(), ModelProviderType.OPENAI_COMPATIBLE,
                p.path("base_url").asText(), p.path("api_path").asText(), null,
                p.path("model_name").asText(), true, 0.2, 2000,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.NATIVE_TOOLS),
                true, OffsetDateTime.now(), OffsetDateTime.now(), "KILO_GATEWAY_FREE"));
        UUID config = UUID.randomUUID();
        jdbc.update("INSERT INTO system_embedding_config(id,provider,base_url,api_path,model_name,dimensions,batch_size,fingerprint,enabled) VALUES (?,'OLLAMA','http://127.0.0.1:11434','/v1/embeddings',?,1024,32,?,TRUE)", config, EMBED_MODEL, FP);
        UUID generation = UUID.randomUUID();
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'ACTIVE')", generation, config);
        jdbc.update("UPDATE system_embedding_config SET generation_id=? WHERE id=?", generation, config);
        Set<String> seeds = new LinkedHashSet<>();
        for (JsonNode scenario : scenarios) {
            String id = scenario.path("id").asText();
            if (!TARGET_SCENARIOS.contains(id)) continue;
            scenario.path("seedScope").forEach(n -> seeds.add(n.asText()));
            scenario.path("temporaryDocuments").forEach(n -> seeds.add(n.asText()));
        }
        for (JsonNode entry : JSON.readTree(FIXTURES.resolve("corpus-manifest.json").toFile()).path("documents")) {
            String name = entry.path("filename").asText();
            if (seeds.contains(name) && !name.equals(F4)) index(name);
        }
    }

    private void index(String filename) throws Exception {
        byte[] bytes = Files.readAllBytes(FIXTURES.resolve(filename));
        var parsed = parser.parse(bytes, filename, "text/markdown");
        var chunks = chunker.split(parsed.text());
        var vectors = embeddings.embed(project, chunks.stream().map(c -> c.content()).toList(), EmbeddingProgressListener.NONE);
        UUID document = UUID.randomUUID(), token = UUID.randomUUID();
        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,processing_token,uploaded_by) VALUES (?,?,?,?,'text/markdown',?,?,'INDEXING',?,?)",
                document, project, filename, filename, bytes.length, "p10/" + document, token, user);
        writer.replaceAndComplete(project, document, token, chunks, vectors, filename);
        documents.put(filename, document);
    }

    @BeforeEach
    void resetScenarioCounters() {
        currentScenario = null;
        currentScenarioModelCalls = 0;
    }

    /** 核心观测性：拦截 KiloGatewayTransport 捕获原始 HTTP 请求和原始返回报文。 */
    private void instrumentKiloTransport() {
        doAnswer(inv -> {
            com.fasterxml.jackson.databind.node.ObjectNode request = inv.getArgument(0);
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("scenario", currentScenario);
            record.put("attempt", currentScenarioModelCalls);
            record.put("timestamp", OffsetDateTime.now().toString());
            record.put("model", request.path("model").asText());
            record.put("max_tokens", request.path("max_tokens").asInt());
            record.put("messagesCount", request.path("messages").size());
            record.put("toolsCount", request.path("tools").size());
            long start = System.nanoTime();
            try {
                JsonNode response = (JsonNode) inv.callRealMethod();
                record.put("status", "SUCCESS");
                record.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
                record.put("rawResponse", response);
                rawTransportCalls.add(record);
                return response;
            } catch (Exception err) {
                record.put("status", "FAILED");
                record.put("latencyMs", (System.nanoTime() - start) / 1_000_000);
                record.put("errorClass", err.getClass().getName());
                record.put("errorMessage", err.getMessage());
                rawTransportCalls.add(record);
                throw err;
            }
        }).when(kiloTransport).chat(any());
    }

    @jakarta.annotation.PostConstruct
    void instrumentModelTurn() {
        doAnswer(inv -> {
            ModelTurnCommand command = inv.getArgument(0);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("scenario", currentScenario);
            call.put("attempt", ++currentScenarioModelCalls);
            call.put("startedAt", OffsetDateTime.now().toString());
            call.put("purpose", command.purpose().name());
            call.put("messageCount", command.messages().size());
            call.put("toolDefinitionCount", command.tools().size());
            modelCalls.add(call);
            if (GATE.real()) {
                if (currentScenarioModelCalls > P10RealAgent04Gate.MAX_MODEL_CALLS_PER_SCENARIO)
                    throw new IllegalStateException("scenario model-call limit exceeded");
                long start = System.nanoTime();
                try {
                    ModelTurnResult result = (ModelTurnResult) inv.callRealMethod();
                    call.put("status", "HTTP_RETURNED");
                    call.put("finishReason", String.valueOf(result.finishReason()));
                    call.put("latencyMs", result.latencyMs());
                    call.put("provider", result.provider());
                    call.put("model", result.model());
                    if (result.usage() != null) call.put("usage", result.usage());
                    return result;
                } catch (ExternalProviderFailure failure) {
                    call.put("status", "BLOCKED_EXTERNAL");
                    call.put("blockedReason", failure.kind().name());
                    call.put("providerStatusCode", failure.statusCode());
                    throw new ExternalBlock(failure.kind().name() + (failure.statusCode() > 0 ? ":" + failure.statusCode() : ""));
                } catch (Exception failure) {
                    call.put("status", "FAILED_OR_UNKNOWN");
                    call.put("errorType", failure.getClass().getSimpleName());
                    call.put("errorMessage", failure.getMessage());
                    throw failure;
                } finally {
                    call.put("wallMs", (System.nanoTime() - start) / 1_000_000);
                    flush();
                }
            }
            syntheticTurns++;
            if (currentScenarioModelCalls == 1) {
                var arguments = JSON.createObjectNode().put("query", "项目资料");
                call.put("status", "SYNTHETIC_TRANSPORT");
                return new ModelTurnResult("", List.of(new ModelToolCall("synthetic-1", "search_project_knowledge", arguments)),
                        ModelFinishReason.TOOL_CALLS, null, "SYNTHETIC", "preflight", 0, false);
            }
            call.put("status", "SYNTHETIC_TRANSPORT");
            return new ModelTurnResult("当前项目资料不足以回答该问题。", List.of(), ModelFinishReason.STOP, null, "SYNTHETIC", "preflight", 0, false);
        }).when(modelTurn).turn(any(ModelTurnCommand.class), any(com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata.class));
    }

    private void flush() throws Exception {
        Files.createDirectories(OUTPUT);
        write("agent-execution-results.json", scenarioResults);
        write("raw-transport-calls.json", rawTransportCalls);
        Map<String, Object> ledger = new LinkedHashMap<>();
        ledger.put("task", "P10-REAL-GENERATION-04");
        ledger.put("mode", GATE.real() ? "REAL" : "PREFLIGHT_SYNTHETIC_TRANSPORT");
        ledger.put("authorization", GATE.real());
        ledger.put("provider", "KILO_GATEWAY_FREE");
        ledger.put("model", GATE.model());
        ledger.put("routeSnapshotSha256", routeSnapshot);
        ledger.put("scenarioReservations", GATE.scenarios());
        ledger.put("modelCallCount", modelCalls.size());
        ledger.put("syntheticTransportTurns", syntheticTurns);
        ledger.put("actualCost", "FREE_TIER_CONFIRMED_0");
        ledger.put("calls", modelCalls);
        ledger.put("rawTransportCalls", rawTransportCalls);
        write("reproduction-ledger.json", ledger);
    }

    private static void write(String name, Object value) throws Exception {
        Path staging = OUTPUT.resolve(name + ".tmp");
        JSON.writerWithDefaultPrettyPrinter().writeValue(staging.toFile(), value);
        Files.move(staging, OUTPUT.resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class ExternalBlock extends RuntimeException {
        final String reason;
        ExternalBlock(String reason) { super(reason); this.reason = reason; }
    }
}
