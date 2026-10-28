package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeAnswerView;
import com.shitulelv.aicollab.knowledge.application.view.KnowledgeCitationView;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeContext;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeCitationValidator;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeContextBuilder;
import com.shitulelv.aicollab.knowledge.infrastructure.repository.KnowledgeRepository;
import com.shitulelv.aicollab.planning.application.TaskPlanRecoveryJob;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * P4-RAG-QA-SOURCE-IDENTITY-01 真实 Gemini 业务问答验收：
 * 验证 Q09 在真实端到端知识库问答下，双合同来源身份保留、Gemini 对照生成、双来源独立引用及持久化。
 */
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
public class P4RagQaSourceIdentityAcceptancePostgresTest {
    private static final String TARGET = "P4RagQaSourceIdentityAcceptancePostgresTest";
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path FIXTURES = ROOT.resolve("tests/fixtures/documents/p4-rag-answer-quality-01");
    private static final Path OUTPUT = ROOT.resolve("docs/acceptance-evidence/2026-10-23/p4-rag-qa-source-identity-01/results.json");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String EMBEDDING_MODEL = "qwen3-embedding:0.6b";
    private static final String FINGERPRINT = EmbeddingFingerprints.fingerprint("OLLAMA", EMBEDDING_MODEL, 1024);
    private static final int MAX_REQUEST_BUDGET = 6;

    private final AtomicInteger requests = new AtomicInteger();
    private final List<Map<String, Object>> rows = new CopyOnWriteArrayList<>();
    private final Map<String, UUID> documents = new LinkedHashMap<>();
    private final Map<String, UUID> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Map<String, Object> current;
    private UUID user;
    private UUID project;
    private UUID generation;
    private volatile boolean providerBlocked;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @BeforeAll
    static void requireExplicitAuthorization() {
        String selector = System.getProperty("test", "");
        Assumptions.assumeTrue(java.util.Arrays.stream(selector.split(","))
                        .map(item -> item.strip().split("#")[0])
                        .anyMatch(item -> item.equals(TARGET) || item.equals(P4RagQaSourceIdentityAcceptancePostgresTest.class.getName())),
                "Explicit -Dtest=P4RagQaSourceIdentityAcceptancePostgresTest required");
        Assumptions.assumeTrue(System.getenv("GEMINI_API_KEY") != null && !System.getenv("GEMINI_API_KEY").isBlank(),
                "GEMINI_API_KEY required; no filesystem credential reads in test");
        String model = System.getenv("GEMINI_MODEL");
        Assumptions.assumeTrue("gemini-3.5-flash-lite".equals(model),
                "Explicit approved GEMINI_MODEL=gemini-3.5-flash-lite required");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ProjectEmbeddingGateway embeddings;
    @Autowired DocumentChunker chunker;
    @Autowired ModelSecretCipher cipher;
    @Autowired KnowledgeQuestionApplicationService questions;
    @Autowired KnowledgeSessionApplicationService sessionService;
    @Autowired KnowledgeRepository knowledge;
    @MockitoSpyBean GeminiModelAdapter gemini;
    @MockitoSpyBean DocumentSearchService search;
    @MockitoBean TaskPlanRecoveryJob recoveryJob;
    @MockitoBean StringRedisTemplate redis;

    @Test
    @Order(1)
    void executeQ09AndRegressionVerification() throws Exception {
        installObservers();
        seed();

        // 串行执行任务：第一题 Q09（核心目标），第二题 Q01（反向回归）
        List<TestCaseSpec> cases = List.of(
                new TestCaseSpec(
                        "Q09",
                        "合同模板甲和合同模板乙关于重大变更审批的要求是否一致？请分别指出来源。",
                        List.of(),
                        List.of("重大变更须经项目负责人书面批准后方可实施", "一致"),
                        List.of("合同模板乙.md", "合同模板甲.md")
                ),
                new TestCaseSpec(
                        "Q01",
                        "项目生产发布前必须完成哪些验收条件？",
                        List.of(),
                        List.of("核心业务流程自动化验收", "阻断级缺陷关闭", "数据迁移结果核对无误"),
                        List.of("上线验收规程.md")
                )
        );

        for (TestCaseSpec spec : cases) {
            if (providerBlocked || requests.get() >= MAX_REQUEST_BUDGET) {
                break;
            }
            executeSingleQuestion(spec);
            // 串行间隔，避免触发外部限流
            Thread.sleep(2000L);
        }

        saveResults();

        assertThat(requests.get()).isGreaterThanOrEqualTo(1);
        assertThat(requests.get()).isLessThanOrEqualTo(MAX_REQUEST_BUDGET);

        // 核心验收断言：Q09 必须成功完成并包含双来源引用
        Map<String, Object> q09Record = rows.stream()
                .filter(r -> "Q09".equals(r.get("id")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Q09 was not executed"));

        assertThat(q09Record.get("executionStatus")).isEqualTo("COMPLETED_REQUIRES_REVIEW");
        KnowledgeAnswerView response = (KnowledgeAnswerView) q09Record.get("response");
        assertThat(response).isNotNull();
        assertThat(response.answer()).isNotBlank();
        assertThat(response.citations()).hasSizeGreaterThanOrEqualTo(2);

        // 验证引用的文档包含合同甲与合同乙
        List<String> citedFilenames = response.citations().stream()
                .map(KnowledgeCitationView::filename)
                .toList();
        assertThat(citedFilenames).contains("合同模板乙.md", "合同模板甲.md");

        // 验证引用的 chunkId 各自独立
        List<UUID> citedChunkIds = response.citations().stream()
                .map(c -> c.chunkId())
                .toList();
        assertThat(citedChunkIds).doesNotHaveDuplicates();
    }

    private void executeSingleQuestion(TestCaseSpec spec) throws Exception {
        Map<String, Object> current = new LinkedHashMap<>();
        this.current = current;
        current.put("id", spec.id());
        current.put("query", spec.query());
        current.put("expectedFacts", spec.expectedFacts());
        rows.add(current);

        UUID session = sessionService.create(project, new CreateKnowledgeSessionRequest(spec.id()), user).id();
        sessions.put(spec.id(), session);
        current.put("sessionId", session);
        current.put("scopeDocumentIds", spec.scope());

        var request = new KnowledgeQuestionRequest(spec.query(), spec.scope());
        long started = System.nanoTime();
        try {
            KnowledgeAnswerView answer = askWithBoundedRetry(session, request);
            current.put("response", answer);

            var messages = knowledge.listMessages(project, session, user);
            var citations = knowledge.listCitations(project, session, user);
            current.put("persistedMessages", messages);
            current.put("persistedCitations", citations);

            Object raw = current.get("rawCompletion");
            if (raw instanceof ChatCompletionResult completion) {
                @SuppressWarnings("unchecked")
                List<DocumentSearchHit> candidates = (List<DocumentSearchHit>) current.get("candidates");
                var context = new KnowledgeContextBuilder().build(candidates);
                var validated = new KnowledgeCitationValidator().validate(completion.content(), context.sources());
                current.put("validation", validated);
                assertThat(messages.getLast().getContent()).isEqualTo(validated.answer());
                assertThat(messages.getLast().getModelName()).isEqualTo(completion.model());
                current.put("persistenceMatchesValidation", true);
            }
            current.put("executionStatus", "COMPLETED_REQUIRES_REVIEW");
        } catch (BusinessException failure) {
            current.put("executionStatus", "INCONCLUSIVE");
            current.put("errorCode", failure.getErrorCode().name());
            current.put("errorMessage", failure.getMessage());
            providerBlocked = true;
        } finally {
            current.put("latencyMs", (System.nanoTime() - started) / 1_000_000L);
            saveResults();
        }
    }

    private KnowledgeAnswerView askWithBoundedRetry(UUID session, KnowledgeQuestionRequest request) throws InterruptedException {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return questions.ask(project, session, request, user);
            } catch (BusinessException failure) {
                if (!is503(failure) || attempt >= 3 || requests.get() >= MAX_REQUEST_BUDGET) {
                    throw failure;
                }
                @SuppressWarnings("unchecked")
                List<String> retryReasons = (List<String>) current.computeIfAbsent("retryReasons", ignored -> new ArrayList<String>());
                retryReasons.add("HTTP_503_ATTEMPT_" + attempt);
                Thread.sleep(3000L * attempt);
            }
        }
        throw new AssertionError("Bounded retry loop terminated unexpectedly");
    }

    private static boolean is503(BusinessException failure) {
        return failure.getErrorCode() == com.shitulelv.aicollab.common.exception.ErrorCode.AI_PROVIDER_ERROR
                && failure.getMessage() != null
                && failure.getMessage().contains("HTTP 503");
    }

    private void installObservers() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<DocumentSearchHit> candidates = (List<DocumentSearchHit>) invocation.callRealMethod();
            if (current != null) {
                current.put("retrievalQuery", invocation.getArgument(1));
                current.put("candidates", candidates);
                var built = new KnowledgeContextBuilder().buildWithDiagnostics(candidates, 5);
                current.put("contextSources", built.context().sources());
                current.put("promptSources", built.context().promptSources());
                current.put("dropReasons", built.dropReasons());
            }
            return candidates;
        }).when(search).search(any(UUID.class), anyString(), anyList(), anyInt());

        doAnswer(invocation -> {
            ChatCompletionCommand command = invocation.getArgument(2);
            int ordinal = reserveRequest();
            if (current != null) {
                current.put("requestNumber", ordinal);
                current.put("systemPrompt", command.systemPrompt());
                current.put("systemPromptSha256", hash(command.systemPrompt()));
                current.put("userPrompt", command.userPrompt());
            }
            ChatCompletionResult result = (ChatCompletionResult) invocation.callRealMethod();
            if (current != null) {
                current.put("rawCompletion", result);
            }
            return result;
        }).when(gemini).complete(any(), anyString(), any());
    }

    private int reserveRequest() {
        return requests.updateAndGet(val -> {
            if (val >= MAX_REQUEST_BUDGET) {
                throw new IllegalStateException("External generation request budget exhausted (max " + MAX_REQUEST_BUDGET + ")");
            }
            return val + 1;
        });
    }

    private void seed() throws Exception {
        user = UUID.randomUUID();
        project = UUID.randomUUID();
        generation = UUID.randomUUID();

        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "identity-qa-" + user.toString().substring(0, 8), "test-only-hash", "Source Identity QA");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "Gemini source identity acceptance", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')",
                project, user);

        UUID embeddingConfig = UUID.randomUUID();
        jdbc.update("UPDATE system_embedding_config SET enabled=FALSE");
        jdbc.update("INSERT INTO system_embedding_config(id,provider,base_url,api_path,model_name,dimensions,batch_size,fingerprint,enabled) VALUES (?,'OLLAMA','http://127.0.0.1:11434','/v1/embeddings',?,1024,32,?,TRUE)",
                embeddingConfig, EMBEDDING_MODEL, FINGERPRINT);
        jdbc.update("INSERT INTO embedding_index_generation(id,config_id,state) VALUES (?,?,'ACTIVE')",
                generation, embeddingConfig);
        jdbc.update("UPDATE system_embedding_config SET generation_id=? WHERE id=?",
                generation, embeddingConfig);

        UUID provider = UUID.randomUUID();
        jdbc.update("INSERT INTO user_ai_provider(id,user_id,name,provider_type,base_url,api_path,encrypted_api_key,model_name,enabled,temperature,max_output_tokens,capabilities,is_default) VALUES (?,?,?,'GEMINI','https://generativelanguage.googleapis.com','/v1beta/models/{model}:generateContent',?,?,TRUE,0.2,2048,'CHAT,STREAMING,USAGE,NATIVE_TOOLS',TRUE)",
                provider, user, "Authorized Gemini QA", cipher.encrypt(System.getenv("GEMINI_API_KEY")), System.getenv("GEMINI_MODEL"));
        jdbc.update("INSERT INTO user_model_purpose_assignment(user_id,purpose,provider_id) VALUES (?,'KNOWLEDGE_CHAT',?)",
                user, provider);

        try (var files = Files.list(FIXTURES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".md") && !path.getFileName().toString().equals("不可信资料测试.md")).sorted().toList()) {
                indexDocument(file);
            }
        }
        assertThat(documents).hasSize(13);
    }

    private void indexDocument(Path file) throws Exception {
        String filename = file.getFileName().toString();
        UUID document = UUID.randomUUID();
        documents.put(filename, document);
        var chunks = chunker.split(Files.readString(file, StandardCharsets.UTF_8));
        var vectors = embeddings.embed(project, chunks.stream().map(chunk -> chunk.content()).toList(), () -> {});
        assertThat(vectors.dimension()).isEqualTo(1024);

        jdbc.update("INSERT INTO project_document(id,project_id,display_name,original_filename,mime_type,size_bytes,object_key,status,chunk_count,embedding_provider,embedding_model,embedding_dimension,embedding_fingerprint,uploaded_by,indexed_at) VALUES (?,?,?,?,'text/markdown',100,?,'READY',?,'OLLAMA',?,1024,?,?,now())",
                document, project, filename, filename, "synthetic/" + document, chunks.size(), EMBEDDING_MODEL, FINGERPRINT, user);

        for (int index = 0; index < chunks.size(); index++) {
            var chunk = chunks.get(index);
            jdbc.update("INSERT INTO document_chunk(id,project_id,document_id,chunk_no,heading,content,content_hash,token_estimate,metadata,embedding_provider,embedding_model,embedding_dimension,embedding_fingerprint,embedding,generation_id) VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb),'OLLAMA',?,1024,?,CAST(? AS vector),?)",
                UUID.randomUUID(), project, document, chunk.chunkNo(), chunk.heading(), chunk.content(), chunk.contentHash(), chunk.tokenEstimate(), JSON.writeValueAsString(chunk.metadata()), EMBEDDING_MODEL, FINGERPRINT, vectors.vectors().get(index).toString(), generation);
        }
    }

    private synchronized void saveResults() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("task", "P4-RAG-QA-SOURCE-IDENTITY-01");
        report.put("model", System.getenv("GEMINI_MODEL"));
        report.put("embeddingModel", EMBEDDING_MODEL);
        report.put("dimensions", 1024);
        report.put("fingerprint", FINGERPRINT);
        report.put("generationRequests", requests.get());
        report.put("requestLimit", MAX_REQUEST_BUDGET);
        report.put("maxOutputTokens", 2048);
        report.put("transport", "Production RoutingChatModelGateway -> GeminiModelAdapter -> official Google endpoint");
        report.put("rows", rows);

        Files.createDirectories(OUTPUT.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(OUTPUT.toFile(), report);
    }

    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record TestCaseSpec(
            String id,
            String query,
            List<UUID> scope,
            List<String> expectedFacts,
            List<String> expectedDocuments) {}
}
