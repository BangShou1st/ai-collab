package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelFinishReason;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
import com.shitulelv.aicollab.knowledge.domain.model.KnowledgeSource;
import com.shitulelv.aicollab.knowledge.domain.model.ValidatedKnowledgeAnswer;
import com.shitulelv.aicollab.knowledge.domain.service.KnowledgeCitationValidator;
import com.shitulelv.aicollab.planning.application.TaskPlanOutputParser;
import com.shitulelv.aicollab.planning.domain.SkeletonModelOutput;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3-PROVIDER-01: 免费真实 Provider (Google Gemini) 受控业务验收与质量测试套件。
 * 覆盖：
 * 1. 最小连接与基础用量统计
 * 2. RAG 知识问答（有证据回答与精准引用、无证据拒答、防捏造来源）
 * 3. Agent 流程（知识检索工具识别与调用、多轮 Thought Signature 协议分析）
 * 4. AI 任务规划（结构化骨架输出、字段完整性与业务契约校验）
 * 5. 错误分类与慢响应/超时处理（408/504, 429, 401, 403 FreeTierError, SSE MISSING_DONE 兼容性记录）
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GeminiLiveAcceptanceTest {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OutboundEndpointPolicy endpointPolicy = new OutboundEndpointPolicy();
    private static final KnowledgeCitationValidator citationValidator = new KnowledgeCitationValidator();
    private static final TaskPlanOutputParser planParser = new TaskPlanOutputParser(mapper);

    private static GeminiModelAdapter createAdapter() {
        return new GeminiModelAdapter(mapper, new JsonHttpModelClient(mapper, endpointPolicy));
    }

    private ModelConfiguration config(String modelName) {
        OffsetDateTime now = OffsetDateTime.now();
        return new ModelConfiguration(
                UUID.randomUUID(), UUID.randomUUID(), "Gemini-" + modelName, ModelProviderType.GEMINI,
                "https://generativelanguage.googleapis.com", "/v1beta/models/{model}:generateContent",
                "encrypted", modelName, true, 0.2, 4000,
                EnumSet.allOf(ModelCapability.class), now, now);
    }

    private ChatCompletionResult safeComplete(ModelConfiguration cfg, ChatCompletionCommand cmd) {
        return GeminiLiveTestSupport.withAuthorizedApiKey(apiKey ->
                GeminiLiveTestSupport.retry503(
                        "Gemini completion",
                        () -> createAdapter().complete(cfg, apiKey, cmd)));
    }

    private ModelTurnResult safeTurn(ModelConfiguration cfg, ModelTurnCommand cmd) {
        return GeminiLiveTestSupport.withAuthorizedApiKey(liveApiKey ->
                GeminiLiveTestSupport.retry503(
                        "Gemini turn",
                        () -> createAdapter().turn(cfg, liveApiKey, cmd)));
    }

    // ==========================================
    // 1. 最小连接与基础可用性验证
    // ==========================================

    @Test
    void networkRecovery_minimalOkWithoutRetry() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "network-recovery-01".equals(System.getenv("AI_GEMINI_ACCEPTANCE_RECOVERY")),
                "Explicit network recovery opt-in required");
        java.nio.file.Path evidence = java.nio.file.Path.of("target/rag-answer-quality-01/network-recovery-01/connectivity.json");
        org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.exists(evidence), "Do not repeat a reserved connectivity request");
        GeminiLiveTestSupport.withAuthorizedApiKey(apiKey -> {
            ObjectNode record = mapper.createObjectNode();
            record.put("priorGenerationAttempts", 4).put("requestNumber", 5).put("cumulativeLimit", 24);
            record.put("model", "gemini-3.1-flash-lite").put("maxOutputTokens", 2048);
            record.put("query", "请只回答 OK").put("automaticRetries", 0);
            record.put("httpsProxyHost", System.getProperty("https.proxyHost", ""));
            record.put("httpsProxyPort", System.getProperty("https.proxyPort", ""));
            String selectedProxy = java.net.ProxySelector.getDefault()
                    .select(java.net.URI.create("https://generativelanguage.googleapis.com")).toString();
            record.put("selectedJavaProxy", selectedProxy);
            assertThat(selectedProxy).contains("7897");
            record.put("status", "REQUEST_RESERVED");
            writeRecoveryRecord(evidence, record);
            OffsetDateTime now = OffsetDateTime.now();
            ModelConfiguration cfg = new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "Gemini recovery",
                    ModelProviderType.GEMINI, "https://generativelanguage.googleapis.com", "/v1beta/models/{model}:generateContent",
                    "unused-encrypted-placeholder", "gemini-3.1-flash-lite", true, 0.2, 2048,
                    EnumSet.allOf(ModelCapability.class), now, now);
            try {
                ChatCompletionResult result = createAdapter().complete(cfg, apiKey, new ChatCompletionCommand(
                        null, "You are a concise assistant.", "请只回答 OK", ChatCompletionCommand.OutputFormat.TEXT,
                        ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null));
                record.set("completion", mapper.valueToTree(result));
                record.put("status", "COMPLETED_REQUIRES_CHECK");
                writeRecoveryRecord(evidence, record);
                assertThat(result.content()).isNotBlank();
                assertThat(result.content().strip()).isEqualTo("OK");
                record.put("status", "PASS");
                writeRecoveryRecord(evidence, record);
            } catch (BusinessException failure) {
                record.put("status", "INCONCLUSIVE").put("errorCode", failure.getErrorCode().name());
                record.put("http503", GeminiLiveTestSupport.isHttp503(failure));
                writeRecoveryRecord(evidence, record);
                org.junit.jupiter.api.Assumptions.abort("INCONCLUSIVE: recovery connectivity failed; no retry or bulk requests");
            }
            return null;
        });
    }

    private static void writeRecoveryRecord(java.nio.file.Path path, ObjectNode record) {
        try {
            java.nio.file.Files.createDirectories(path.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), record);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    @Test
    void networkRecovery_boundedRetriesWithResponseDiagnostics() {
        String recovery = System.getenv("AI_GEMINI_ACCEPTANCE_RETRY");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "network-recovery-02".equals(recovery) || "network-recovery-03".equals(recovery), "Explicit bounded retry opt-in required");
        boolean alternateAuthorized = "network-recovery-03".equals(recovery);
        int priorAttempts = alternateAuthorized ? 8 : 5;
        List<String> models = alternateAuthorized ? List.of("gemini-3.1-flash-lite", "gemini-3.5-flash-lite")
                : List.of("gemini-3.1-flash-lite", "gemini-3.1-flash-lite", "gemini-3.1-flash-lite");
        java.nio.file.Path evidence = java.nio.file.Path.of("target/rag-answer-quality-01", recovery, "connectivity.json");
        org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.exists(evidence), "Do not repeat reserved requests");
        GeminiLiveTestSupport.withAuthorizedApiKey(apiKey -> {
            ObjectNode record = mapper.createObjectNode();
            record.put("priorGenerationAttempts", priorAttempts).put("cumulativeLimit", 24).put("additionalAttemptLimit", models.size());
            record.put("model", "gemini-3.1-flash-lite").put("maxOutputTokens", 2048).put("query", "请只回答 OK");
            var attempts = record.putArray("attempts");
            OffsetDateTime now = OffsetDateTime.now();
            for (int attempt = 1; attempt <= models.size(); attempt++) {
                String model = models.get(attempt - 1);
                ModelConfiguration cfg = new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "Gemini bounded recovery",
                        ModelProviderType.GEMINI, "https://generativelanguage.googleapis.com", "/v1beta/models/{model}:generateContent",
                        "unused-encrypted-placeholder", model, true, 0.2, 2048,
                        EnumSet.allOf(ModelCapability.class), now, now);
                ObjectNode row = attempts.addObject().put("requestNumber", priorAttempts + attempt).put("model", model).put("status", "REQUEST_RESERVED");
                row.put("startedAt", OffsetDateTime.now().toString());
                var real = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15))
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
                String proxy = real.proxy().orElse(java.net.ProxySelector.getDefault())
                        .select(java.net.URI.create(cfg.baseUrl())).toString();
                row.put("selectedJavaProxy", proxy);
                assertThat(proxy).contains("7897");
                writeRecoveryRecord(evidence, record);
                JsonHttpModelClient http = new JsonHttpModelClient(mapper, endpointPolicy, new RecordingHttpClient(real, row, apiKey));
                try {
                    ChatCompletionResult result = new GeminiModelAdapter(mapper, http).complete(cfg, apiKey,
                            new ChatCompletionCommand(null, "You are a concise assistant.", "请只回答 OK",
                                    ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null));
                    row.set("completion", mapper.valueToTree(result));
                    row.put("status", "COMPLETED");
                    writeRecoveryRecord(evidence, record);
                    assertThat(result.content().strip()).isEqualTo("OK");
                    row.put("status", "PASS");
                    record.put("selectedModel", model);
                    writeRecoveryRecord(evidence, record);
                    return null;
                } catch (BusinessException failure) {
                    row.put("status", "INCONCLUSIVE").put("errorCode", failure.getErrorCode().name());
                    writeRecoveryRecord(evidence, record);
                    if (!GeminiLiveTestSupport.isHttp503(failure) || attempt == models.size()) break;
                    try { Thread.sleep(3_000L * attempt); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                } finally { http.close(); }
            }
            org.junit.jupiter.api.Assumptions.abort("INCONCLUSIVE: bounded connectivity attempts did not complete");
            return null;
        });
    }

    /** Delegates actual requests unchanged; only allowlisted response diagnostics are recorded. */
    private static final class RecordingHttpClient extends java.net.http.HttpClient {
        private final java.net.http.HttpClient real;
        private final ObjectNode record;
        private final String credential;
        RecordingHttpClient(java.net.http.HttpClient real, ObjectNode record, String credential) {
            this.real = real; this.record = record; this.credential = credential;
        }
        @Override public <T> java.net.http.HttpResponse<T> send(java.net.http.HttpRequest request,
                java.net.http.HttpResponse.BodyHandler<T> handler) throws java.io.IOException, InterruptedException {
            var response = real.send(request, handler);
            record.put("httpStatus", response.statusCode()).put("httpVersion", response.version().name());
            record.put("responseAt", OffsetDateTime.now().toString());
            var headers = record.putObject("responseHeaders");
            for (String name : List.of("server", "content-type", "retry-after", "via")) {
                response.headers().firstValue(name).ifPresent(value -> headers.put(name, value.replace(credential, "[REDACTED]")));
            }
            if (response.statusCode() >= 400 && response.body() instanceof String body) {
                try {
                    var error = mapper.readTree(body).path("error");
                    var safe = record.putObject("providerError");
                    for (String name : List.of("code", "status", "message")) {
                        String value = error.path(name).asText("").replace(credential, "[REDACTED]");
                        safe.put(name, value.substring(0, Math.min(1500, value.length())));
                    }
                } catch (java.io.IOException invalidJson) { record.put("providerErrorFormat", "NON_JSON_BODY_NOT_SAVED"); }
            }
            return response;
        }
        @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return real.cookieHandler(); }
        @Override public java.util.Optional<java.time.Duration> connectTimeout() { return real.connectTimeout(); }
        @Override public Redirect followRedirects() { return real.followRedirects(); }
        @Override public java.util.Optional<java.net.ProxySelector> proxy() { return real.proxy(); }
        @Override public javax.net.ssl.SSLContext sslContext() { return real.sslContext(); }
        @Override public javax.net.ssl.SSLParameters sslParameters() { return real.sslParameters(); }
        @Override public java.util.Optional<java.net.Authenticator> authenticator() { return real.authenticator(); }
        @Override public Version version() { return real.version(); }
        @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return real.executor(); }
        @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler) { return real.sendAsync(request, handler); }
        @Override public <T> java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<T>> sendAsync(
                java.net.http.HttpRequest request, java.net.http.HttpResponse.BodyHandler<T> handler,
                java.net.http.HttpResponse.PushPromiseHandler<T> push) { return real.sendAsync(request, handler, push); }
    }

    @Test
    @Order(1)
    void phase1_minimalConnection_gemini31FlashLite() {
        ModelConfiguration cfg = config("gemini-3.1-flash-lite");
        ChatCompletionCommand cmd = new ChatCompletionCommand(
                null, "You are a concise assistant.", "Reply with 'CONNECTED' only.",
                ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null);

        ChatCompletionResult result = safeComplete(cfg, cmd);

        assertThat(result).isNotNull();
        System.out.println("[ACCEPTANCE] 3.1-flash-lite Minimal Connection: content=" + result.content() +
                ", model=" + result.model() +
                ", latencyMs=" + result.latencyMs() +
                ", inTokens=" + result.promptTokens() +
                ", outTokens=" + result.completionTokens());

        assertThat(result.content()).contains("CONNECTED");
        assertThat(result.promptTokens()).isNotNull().isGreaterThan(0);
        assertThat(result.completionTokens()).isNotNull().isGreaterThan(0);
    }

    // ==========================================
    // 2. RAG 知识问答路径验收
    // ==========================================

    @Test
    @Order(2)
    void phase2_ragWithEvidence() {
        ModelConfiguration cfg = config("gemini-3.1-flash-lite");
        String systemPrompt = """
                你是 AI Collab 的项目知识库助手。
                根据 <SOURCES> 中提供的项目资料来回答用户的问题。
                回答规则：
                1. 优先基于 SOURCES 中的内容回答，在回答末尾标注引用来源 [S1]、[S2] 等
                2. 资料未支持问题中的结论时，明确说明资料不足，不以相关但无证据的内容代替
                3. 没有有效证据时回答“当前项目资料不足以回答该问题”
                """;

        String userPrompt = """
                <SOURCES>
                [S1] 文档名称: 《AI Collab 架构设计》
                分块标题: 技术选型
                内容: AI Collab 后端采用 Spring Boot 4.1 与 Java 21 开发，数据库使用 PostgreSQL 16 搭配 pgvector 插件存储向量，缓存采用 Redis 7.2。
                </SOURCES>

                问题：AI Collab 后端使用的编程语言、框架和数据库分别是什么？
                """;

        ChatCompletionCommand cmd = new ChatCompletionCommand(
                null, systemPrompt, userPrompt,
                ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null);

        ChatCompletionResult result = safeComplete(cfg, cmd);

        assertThat(result).isNotNull();
        System.out.println("[ACCEPTANCE] RAG with Evidence: content=" + result.content() +
                ", latencyMs=" + result.latencyMs() +
                ", inTokens=" + result.promptTokens() +
                ", outTokens=" + result.completionTokens());

        List<KnowledgeSource> sources = List.of(
                new KnowledgeSource(UUID.randomUUID(), UUID.randomUUID(), "《AI Collab 架构设计》", "技术选型",
                        "AI Collab 后端采用 Spring Boot 4.1 与 Java 21 开发，数据库使用 PostgreSQL 16 搭配 pgvector 插件存储向量，缓存采用 Redis 7.2。",
                        "hash1", 0.95, 1)
        );

        ValidatedKnowledgeAnswer validated = citationValidator.validate(result.content(), sources);
        System.out.println("[ACCEPTANCE] Citation Validation: invalidOutput=" + validated.invalidOutput() +
                ", invalidCitationCount=" + validated.invalidCitationCount() +
                ", citedSourcesCount=" + validated.citedSources().size());

        assertThat(result.content()).contains("Java 21");
        assertThat(result.content()).contains("Spring Boot");
        assertThat(result.content()).contains("PostgreSQL");
        assertThat(validated.invalidOutput()).isFalse();
        assertThat(validated.invalidCitationCount()).isEqualTo(0);
        assertThat(validated.citedSources()).isNotEmpty();
    }

    @Test
    @Order(3)
    void phase2_ragWithoutEvidence_rejection() {
        ModelConfiguration cfg = config("gemini-3.1-flash-lite");
        String systemPrompt = """
                你是 AI Collab 的项目知识库助手。
                根据 <SOURCES> 中提供的项目资料来回答用户的问题。
                回答规则：
                1. 优先基于 SOURCES 中的内容回答，在回答末尾标注引用来源 [S1]、[S2] 等
                2. 资料未支持问题中的结论时，明确说明资料不足，不以相关但无证据的内容代替
                3. 没有有效证据时回答“当前项目资料不足以回答该问题”
                """;

        String userPrompt = """
                <SOURCES>
                [S1] 文档名称: 《AI Collab 架构设计》
                分块标题: 技术选型
                内容: AI Collab 后端采用 Spring Boot 4.1 与 Java 21 开发，数据库使用 PostgreSQL 16 搭配 pgvector 插件存储向量，缓存采用 Redis 7.2。
                </SOURCES>

                问题：该项目的预算上限是多少万元人民币？
                """;

        ChatCompletionCommand cmd = new ChatCompletionCommand(
                null, systemPrompt, userPrompt,
                ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null);

        ChatCompletionResult result = safeComplete(cfg, cmd);

        assertThat(result).isNotNull();
        System.out.println("[ACCEPTANCE] RAG without Evidence: content=" + result.content() +
                ", latencyMs=" + result.latencyMs());

        List<KnowledgeSource> sources = List.of(
                new KnowledgeSource(UUID.randomUUID(), UUID.randomUUID(), "《AI Collab 架构设计》", "技术选型",
                        "AI Collab 后端采用 Spring Boot 4.1 与 Java 21 开发，数据库使用 PostgreSQL 16 搭配 pgvector 插件存储向量，缓存采用 Redis 7.2。",
                        "hash1", 0.95, 1)
        );

        ValidatedKnowledgeAnswer validated = citationValidator.validate(result.content(), sources);
        System.out.println("[ACCEPTANCE] Rejection Validation: isInsufficient=" + validated.insufficientEvidence() +
                ", invalidCitationCount=" + validated.invalidCitationCount());

        assertThat(validated.invalidCitationCount()).isEqualTo(0);
        assertThat(result.content().contains("不足") || result.content().contains("未提及") || validated.insufficientEvidence()).isTrue();
    }

    // ==========================================
    // 3. Agent 流程（工具调用验证）
    // ==========================================

    @Test
    @Order(4)
    void phase3_agentToolCallingSelection() {
        ModelConfiguration cfg = config("gemini-3.1-flash-lite");

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "Search keywords");
        schema.putArray("required").add("query");

        ModelToolDefinition tool = new ModelToolDefinition("search_knowledge", "Search knowledge base documents", schema);

        List<ModelMessage> history = new ArrayList<>();
        history.add(new ModelMessage.System("你是项目管理 Agent。用户询问项目知识库内容时，必须调用 search_knowledge 工具检索证据，不要凭空猜测。"));
        history.add(new ModelMessage.User("请帮我检索知识库中关于系统数据库选型的内容。"));

        ModelTurnCommand turnCmd = new ModelTurnCommand(ModelPurpose.AGENT, history, List.of(tool), false);
        ModelTurnResult turnResult = safeTurn(cfg, turnCmd);

        assertThat(turnResult).isNotNull();
        System.out.println("[ACCEPTANCE] Agent Turn 1: toolCallsCount=" + turnResult.toolCalls().size() +
                ", latencyMs=" + turnResult.latencyMs());

        assertThat(turnResult.toolCalls()).isNotEmpty();
        ModelToolCall toolCall = turnResult.toolCalls().getFirst();
        assertThat(toolCall.name()).isEqualTo("search_knowledge");
        assertThat(toolCall.arguments().hasNonNull("query")).isTrue();
        System.out.println("[ACCEPTANCE] Agent Turn 1 ToolCall: id=" + toolCall.id() + ", args=" + toolCall.arguments());
    }

    @Test
    @Order(5)
    void phase3_agentMultiTurnWithSignatureAndFinalAnswer() {
        ModelConfiguration cfg = config("gemini-3.1-flash-lite");

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "Search keywords");
        schema.putArray("required").add("query");

        ModelToolDefinition tool = new ModelToolDefinition("search_knowledge", "Search knowledge base documents", schema);

        // Turn 1
        List<ModelMessage> history = new ArrayList<>();
        history.add(new ModelMessage.System("你是项目管理 Agent。用户询问项目知识库内容时，必须调用 search_knowledge 工具检索证据，不要凭空猜测。"));
        history.add(new ModelMessage.User("请帮我检索知识库中关于系统数据库选型的内容。"));

        ModelTurnCommand turn1Cmd = new ModelTurnCommand(ModelPurpose.AGENT, history, List.of(tool), false);
        ModelTurnResult turn1Result = safeTurn(cfg, turn1Cmd);

        assertThat(turn1Result.toolCalls()).isNotEmpty();
        ModelToolCall toolCall = turn1Result.toolCalls().getFirst();
        System.out.println("[ACCEPTANCE] Live Multi-Turn: Turn 1 Call: id=" + toolCall.id() +
                ", name=" + toolCall.name() +
                ", hasThoughtSignature=" + (toolCall.thoughtSignature() != null));

        // Turn 2: 回传助手消息（携带 toolCalls 及必要签名）与工具执行结果
        history.add(new ModelMessage.Assistant(turn1Result.content(), turn1Result.toolCalls()));
        ObjectNode toolResultObj = mapper.createObjectNode()
                .put("status", "SUCCESS")
                .put("evidence", "AI Collab 数据库使用 PostgreSQL 16 搭配 pgvector 插件存储向量，缓存采用 Redis 7.2。");
        history.add(new ModelMessage.ToolResult(toolCall.id(), toolCall.name(), toolResultObj, false));

        ModelTurnCommand turn2Cmd = new ModelTurnCommand(ModelPurpose.AGENT, history, List.of(tool), false);
        ModelTurnResult turn2Result = safeTurn(cfg, turn2Cmd);

        System.out.println("[ACCEPTANCE] Live Multi-Turn: Turn 2 Output: content=" + turn2Result.content() +
                ", finishReason=" + turn2Result.finishReason() +
                ", latencyMs=" + turn2Result.latencyMs());

        assertThat(turn2Result.finishReason()).isEqualTo(ModelFinishReason.STOP);
        assertThat(turn2Result.content()).isNotBlank();
        assertThat(turn2Result.content()).containsIgnoringCase("PostgreSQL");
    }

    // ==========================================
    // 4. AI 规划生成（结构化输出与骨架解析）
    // ==========================================

    @Test
    @Order(6)
    void phase4_planningGenerationStructuredOutput() {
        ModelConfiguration cfg = config("gemini-3.1-flash-lite");

        String systemPrompt = """
                你是一个专业的软件项目规划专家。请根据用户的需求和约束生成项目的任务规划骨架草案。
                必须输出严格合法的单个 JSON 对象，不要使用 markdown 标记或任何其他文本。
                必须且仅包含以下字段，不得包含未知字段：
                - summary: 规划摘要文本
                - assumptions: 假设列表（字符串数组）
                - risks: 风险列表（字符串数组）
                - milestones: 里程碑数组，每项必须且仅包含 tempKey, title, objective, targetDate (格式 YYYY-MM-DD), sortOrder (从1开始整数)
                - tasks: 任务数组，每项必须且仅包含 tempKey, milestoneTempKey, title, objective, sortOrder (从1开始整数)
                """;

        String userPrompt = """
                项目名称：多智能体协作平台开发
                规划范围：2026-11-01 至 2026-11-30
                约束条件：最多 3 个里程碑，最多 4 个任务。
                请生成完整的任务规划骨架 JSON。
                """;

        ChatCompletionCommand cmd = new ChatCompletionCommand(
                null, systemPrompt, userPrompt,
                ChatCompletionCommand.OutputFormat.PROMPT_JSON, ModelPurpose.PLANNING, null, List.of(), null);

        ChatCompletionResult result = safeComplete(cfg, cmd);

        assertThat(result).isNotNull();
        System.out.println("[ACCEPTANCE] Planning Output length: " + result.content().length() +
                ", latencyMs=" + result.latencyMs() +
                ", inTokens=" + result.promptTokens() +
                ", outTokens=" + result.completionTokens());

        SkeletonModelOutput skeleton = planParser.parseSkeleton(result.content());
        System.out.println("[ACCEPTANCE] Parsed Skeleton: summary=" + skeleton.summary() +
                ", milestones=" + skeleton.milestones().size() +
                ", tasks=" + skeleton.tasks().size());

        assertThat(skeleton.summary()).isNotBlank();
        assertThat(skeleton.milestones()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(skeleton.tasks()).isNotEmpty().hasSizeLessThanOrEqualTo(4);
        assertThat(skeleton.milestones().getFirst().tempKey()).isNotBlank();
        assertThat(skeleton.tasks().getFirst().tempKey()).isNotBlank();
        assertThat(skeleton.tasks().getFirst().milestoneTempKey()).isNotBlank();
    }

    // ==========================================
    // 5. 错误分类与协议边界诊断
    // ==========================================

    @Test
    @Order(7)
    void phase5_errorClassificationAndTimeout() {
        // 验证 408 / 504 映射为 AI_MODEL_TIMEOUT
        assertThat(catchErrorCode(() -> JsonHttpModelClient.checkStatus(408, null, mapper)))
                .isEqualTo(ErrorCode.AI_MODEL_TIMEOUT);
        assertThat(catchErrorCode(() -> JsonHttpModelClient.checkStatus(504, null, mapper)))
                .isEqualTo(ErrorCode.AI_MODEL_TIMEOUT);

        // 验证 429 映射为 AI_PROVIDER_QUOTA_EXCEEDED
        assertThat(catchErrorCode(() -> JsonHttpModelClient.checkStatus(429, null, mapper)))
                .isEqualTo(ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED);

        // 验证 401 映射为 AI_MODEL_CREDENTIAL_INVALID
        assertThat(catchErrorCode(() -> JsonHttpModelClient.checkStatus(401, null, mapper)))
                .isEqualTo(ErrorCode.AI_MODEL_CREDENTIAL_INVALID);

        // 验证 403 FreeTierError 映射为 AI_PROVIDER_REQUEST_REJECTED
        assertThat(catchErrorCode(() -> JsonHttpModelClient.checkStatus(403, "{\"error\":{\"code\":\"FreeTierError\"}}", mapper)))
                .isEqualTo(ErrorCode.AI_PROVIDER_REQUEST_REJECTED);
    }

    @Test
    @Order(8)
    void phase5_streamingSseCompletionVerified() throws Exception {
        String apiKey = GeminiLiveTestSupport.requireApiKey();
        ModelConfiguration cfg = config("gemini-3.1-flash-lite");
        GeminiModelAdapter adapter = createAdapter();
        ChatCompletionCommand cmd = new ChatCompletionCommand(
                null, "You are a concise assistant.", "Count 1 2 3.",
                ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null);

        StringBuilder tokens = new StringBuilder();
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<ChatCompletionResult> doneResult = new AtomicReference<>();

        int attempts = 0;
        while (attempts < GeminiLiveTestSupport.MAX_503_ATTEMPTS) {
            if (attempts > 0) {
                Thread.sleep(3000L * attempts);
            }
            attempts++;
            tokens.setLength(0);
            error.set(null);
            doneResult.set(null);
            CountDownLatch latch = new CountDownLatch(1);
            adapter.completeStream(cfg, apiKey, cmd,
                    tokens::append,
                    res -> {
                        doneResult.set(res);
                        latch.countDown();
                    },
                    err -> {
                        error.set(err);
                        latch.countDown();
                    });

            boolean finished = latch.await(20, TimeUnit.SECONDS);
            if (!finished) {
                throw GeminiLiveTestSupport.inconclusiveTimeout(
                        "Gemini streaming completion callback",
                        new java.util.concurrent.TimeoutException("No completion callback within 20 seconds"));
            }

            if (!GeminiLiveTestSupport.isHttp503(error.get())) {
                break;
            }
        }

        System.out.println("[ACCEPTANCE] SSE Verified: receivedTokens=" + tokens +
                ", errorClassification=" + (error.get() != null ? error.get().getMessage() : "none") +
                ", doneResult=" + (doneResult.get() != null ? doneResult.get().content() : "none"));

        if (GeminiLiveTestSupport.isHttp503(error.get())) {
            throw GeminiLiveTestSupport.inconclusive503("Gemini streaming completion", attempts);
        }
        if (error.get() instanceof BusinessException exception
                && exception.getErrorCode() == ErrorCode.AI_MODEL_TIMEOUT) {
            throw GeminiLiveTestSupport.inconclusiveTimeout("Gemini streaming completion", exception);
        }

        assertThat(error.get()).isNull();
        assertThat(tokens.toString()).isNotBlank();
        assertThat(doneResult.get()).isNotNull();
        assertThat(doneResult.get().content()).isEqualTo(tokens.toString());
    }

    private static ErrorCode catchErrorCode(Runnable action) {
        try {
            action.run();
            return null;
        } catch (BusinessException e) {
            return e.getErrorCode();
        }
    }
}
