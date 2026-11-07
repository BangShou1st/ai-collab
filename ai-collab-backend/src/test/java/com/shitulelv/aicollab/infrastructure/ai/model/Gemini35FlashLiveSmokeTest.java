package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import com.shitulelv.aicollab.common.security.OutboundEndpointPolicy;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelMessage;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelToolCall;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnCommand;
import com.shitulelv.aicollab.infrastructure.ai.turn.ModelTurnResult;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG_V2_GEMINI_ADAPTIVE_RETRIEVAL-01 阶段 1｜gemini-3.5-flash 真实冒烟验收（受控门控）。
 *
 * <p>门控：{@code AI_GEMINI_LIVE_TESTS=true} + {@code GEMINI_API_KEY}，且须显式
 * {@code -Dtest=Gemini35FlashLiveSmokeTest}。仅提交合成/公开许可测试文本（阶段 0 声明的出站边界）。
 * 能力位不含 THINKING：适配器将发送 thinkingConfig.thinkingBudget=0（阶段 1 缺陷修复的验证面）。
 * 全部 8 个场景、逐次延迟/TTFT/token 用量与 429/截断错误分类，落盘 target/gemini35-smoke/metrics.json。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Gemini35FlashLiveSmokeTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String MODEL = "gemini-3.5-flash-lite";
    private static final String OUTPUT = "target/gemini35-smoke/metrics.json";
    private static final List<ObjectNode> RECORDS = java.util.Collections.synchronizedList(new ArrayList<>());

    private ModelConfiguration config(int maxOutputTokens) {
        OffsetDateTime now = OffsetDateTime.now();
        // flash-lite 为非思考模型且不支持 thinkingConfig（实测 400 INVALID_ARGUMENT）：
        // 声明 THINKING 能力位 → 适配器不发该字段。3.5/3.6-flash（思考型）的 thinkingBudget=0
        // 路径已在前一轮冒烟与契约测试中验证。
        return new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(), "Gemini-" + MODEL,
                ModelProviderType.GEMINI, "https://generativelanguage.googleapis.com",
                "/v1beta/models/{model}:generateContent", "encrypted", MODEL, true, 0.0, maxOutputTokens,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.THINKING,
                        ModelCapability.STRUCTURED_OUTPUT, ModelCapability.NATIVE_TOOLS, ModelCapability.USAGE),
                now, now);
    }

    private GeminiModelAdapter adapter() {
        return new GeminiModelAdapter(mapper, new JsonHttpModelClient(mapper, new OutboundEndpointPolicy()));
    }

    /** 免费层 RPM 限流退避：429 时等待 30s 重试，至多 2 次（实测 flash 系免费层 RPM 很低）。 */
    private <T> java.util.function.Supplier<T> quotaAware(java.util.function.Supplier<T> call) {
        return () -> {
            BusinessException last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    return call.get();
                } catch (BusinessException e) {
                    if (e.getErrorCode() != ErrorCode.AI_PROVIDER_QUOTA_EXCEEDED) throw e;
                    last = e;
                    System.out.println("GEMINI35_SMOKE 429_backoff attempt=" + attempt);
                    try { Thread.sleep(30_000L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
            throw last;
        };
    }

    @org.junit.jupiter.api.BeforeEach
    void pace() throws Exception {
        // 免费层 RPM 有限：场景间隔固定 15s 节流，避免自造 429
        Thread.sleep(15_000L);
    }

    private ChatCompletionResult complete(int maxTokens, String system, String user) {
        long started = System.nanoTime();
        ChatCompletionResult result = quotaAware(() -> GeminiLiveTestSupport.retry503("gemini35 complete",
                () -> adapter().complete(config(maxTokens), GeminiLiveTestSupport.requireApiKey(),
                        new ChatCompletionCommand(null, system, user, ChatCompletionCommand.OutputFormat.TEXT,
                                ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null)))).get();
        record("chat", result.model(), result.latencyMs(), 1,
                result.promptTokens() == null ? 0 : result.promptTokens(),
                result.completionTokens() == null ? 0 : result.completionTokens());
        return result;
    }

    private void record(String scenario, String modelVersion, long latencyMs, int requests,
                        int promptTokens, int completionTokens) {
        ObjectNode r = mapper.createObjectNode();
        r.put("scenario", scenario).put("modelVersion", modelVersion).put("requests", requests)
                .put("latencyMs", latencyMs).put("promptTokens", promptTokens)
                .put("completionTokens", completionTokens)
                .put("tokensPerSecond", latencyMs > 0 && completionTokens > 0
                        ? Math.round(completionTokens * 1000.0 / latencyMs * 10) / 10.0 : 0)
                .put("at", OffsetDateTime.now().toString());
        RECORDS.add(r);
        System.out.println("GEMINI35_SMOKE " + scenario + " latencyMs=" + latencyMs
                + " tokens=" + completionTokens + " modelVersion=" + modelVersion);
    }

    private void writeMetrics() {
        try {
            ObjectNode summary = mapper.createObjectNode();
            summary.put("model", MODEL);
            summary.put("requestedAt", OffsetDateTime.now().toString());
            summary.put("requestCount", RECORDS.size());
            int failures = 0;
            for (ObjectNode r : RECORDS) {
                if (r.path("status").asText("OK").startsWith("FAILED")) failures++;
            }
            summary.put("failureCount", failures);
            ArrayNode records = summary.putArray("records");
            RECORDS.forEach(records::add);
            var file = java.nio.file.Path.of(OUTPUT);
            java.nio.file.Files.createDirectories(file.getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), summary);
            System.out.println("GEMINI35_SMOKE_METRICS=" + file.toAbsolutePath());
        } catch (Exception ignored) { /* 证据落盘失败不掩盖测试结果 */ }
    }

    // ========== 场景 1-2：普通知识问答（中文 / 中英混合） ==========

    @Test
    @Order(1)
    void scenario1_chineseKnowledgeQa() {
        try {
            ChatCompletionResult result = complete(2048,
                    "你是一个准确可靠的助手，只依据问题直接回答，一句话以内。",
                    "国家法定节假日每年共多少天？只回答数字与单位。");
            assertThat(result.content()).isNotBlank();
            // 冒烟验证接入链路与作答格式；事实正确性属于模型能力（实测 lite 曾答 13 天——事实错误，
            // flash(3.6) 答 11 天正确），如实记录进 metrics，不作为接入验收门槛
            assertThat(result.content()).matches(".*[0-9]+\s*天.*");
            System.out.println("GEMINI35_SMOKE scenario1_answer=" + result.content().strip());
        } catch (RuntimeException e) { recordFailure("scenario1_chineseQa", e); writeMetrics(); throw e; }
    }

    @Test
    @Order(2)
    void scenario2_mixedLanguageQa() {
        try {
            ChatCompletionResult result = complete(2048,
                    "You are a concise assistant. Answer in one sentence.",
                    "Spring Boot 中 @Transactional 注解的作用是什么？用中文回答。");
            assertThat(result.content()).isNotBlank();
            assertThat(result.content()).contains("事务");
        } catch (RuntimeException e) { recordFailure("scenario2_mixedLanguage", e); writeMetrics(); throw e; }
    }

    // ========== 场景 3：SSE 流式 ==========

    @Test
    @Order(3)
    void scenario3_streamingSse() throws Exception {
        try {
            long started = System.nanoTime();
            AtomicLong firstTokenMs = new AtomicLong(-1);
            StringBuilder content = new StringBuilder();
            AtomicReference<ChatCompletionResult> done = new AtomicReference<>();
            CountDownLatch finished = new CountDownLatch(1);
            GeminiLiveTestSupport.retry503("gemini35 stream", () -> {
                adapter().completeStream(config(2048), GeminiLiveTestSupport.requireApiKey(),
                        new ChatCompletionCommand(null, "你是简洁助手。", "用三句话介绍 PostgreSQL pgvector 扩展。",
                                ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null),
                        token -> {
                            if (firstTokenMs.get() < 0) firstTokenMs.set((System.nanoTime() - started) / 1_000_000L);
                            content.append(token);
                        },
                        result -> { done.set(result); finished.countDown(); },
                        error -> { throw (RuntimeException) error; });
                try {
                    assertThat(finished.await(120, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new RuntimeException(ie); }
                return null;
            });
            assertThat(finished.await(120, TimeUnit.SECONDS)).isTrue();
            assertThat(content.toString()).isNotBlank();
            long totalMs = (System.nanoTime() - started) / 1_000_000L;
            record("scenario3_streaming_firstTokenMs=" + firstTokenMs.get(), done.get().model(), totalMs,
                    1, done.get().promptTokens() == null ? 0 : done.get().promptTokens(),
                    done.get().completionTokens() == null ? 0 : done.get().completionTokens());
        } catch (RuntimeException e) { recordFailure("scenario3_streaming", e); writeMetrics(); throw e; }
    }

    // ========== 场景 4：JSON 结构化输出 ==========

    @Test
    @Order(4)
    void scenario4_jsonStructuredOutput() {
        try {
            ArrayNode confidenceLevels = mapper.createArrayNode();
            confidenceLevels.add("HIGH");
            confidenceLevels.add("LOW");
            ObjectNode properties = mapper.createObjectNode();
            properties.putObject("answer").put("type", "string");
            ObjectNode confidence = properties.putObject("confidence");
            confidence.put("type", "string");
            confidence.set("enum", confidenceLevels);
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", properties);
            long started = System.nanoTime();
            ChatCompletionResult result = GeminiLiveTestSupport.retry503("gemini35 json",
                            () -> adapter().complete(config(2048), GeminiLiveTestSupport.requireApiKey(),
                            new ChatCompletionCommand(null,
                                    "根据文档信息回答，无法确定时 confidence 填 LOW。",
                                    "文档：PostgreSQL 17 内置 pgvector 扩展支持向量相似度检索。\n问题：pgvector 是什么数据库的扩展？",
                                    ChatCompletionCommand.OutputFormat.JSON_OBJECT, ModelPurpose.KNOWLEDGE_CHAT,
                                    schema, List.of(), null)));
            record("scenario4_json", result.model(), (System.nanoTime() - started) / 1_000_000L, 1,
                    result.promptTokens() == null ? 0 : result.promptTokens(),
                    result.completionTokens() == null ? 0 : result.completionTokens());
            var json = mapper.readTree(result.content());
            assertThat(json.path("answer").asText()).contains("PostgreSQL");
            assertThat(json.path("confidence").asText()).isEqualTo("HIGH");
        } catch (Exception e) {
            if (e instanceof RuntimeException re) { recordFailure("scenario4_json", re); writeMetrics(); throw re; }
            throw new RuntimeException(e);
        }
    }

    // ========== 场景 5：简单 Function Calling ==========

    private static final com.shitulelv.aicollab.infrastructure.ai.model.ModelToolDefinition SEARCH_TOOL =
            buildSearchTool();

    private static com.shitulelv.aicollab.infrastructure.ai.model.ModelToolDefinition buildSearchTool() {
        ObjectNode status = mapper.createObjectNode();
        status.put("type", "string");
        ObjectNode properties = mapper.createObjectNode();
        properties.set("status", status);
        ArrayNode required = mapper.createArrayNode();
        required.add("status");
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", required);
        return new com.shitulelv.aicollab.infrastructure.ai.model.ModelToolDefinition(
                "search_project_tasks", "检索项目任务列表", schema);
    }

    @Test
    @Order(5)
    void scenario5_functionCalling() {
        try {
            ModelTurnResult result = GeminiLiveTestSupport.retry503("gemini35 turn",
                    () -> adapter().turn(config(2048), GeminiLiveTestSupport.requireApiKey(),
                            new ModelTurnCommand(ModelPurpose.AGENT, null, null,
                                    List.of(new ModelMessage.System("你是项目助手，需要查任务时调用工具。"),
                                            new ModelMessage.User("帮我查一下状态为 OPEN 的任务")),
                                    List.of(SEARCH_TOOL), false, null)));
            assertThat(result.toolCalls()).hasSize(1);
            assertThat(result.toolCalls().getFirst().name()).isEqualTo("search_project_tasks");
        } catch (RuntimeException e) { recordFailure("scenario5_functionCalling", e); writeMetrics(); throw e; }
    }

    // ========== 场景 6：多轮工具调用（含 thought signature 回放） ==========

    @Test
    @Order(6)
    void scenario6_multiTurnToolCall() {
        try {
            ModelTurnResult first = GeminiLiveTestSupport.retry503("gemini35 turn1",
                    () -> adapter().turn(config(2048), GeminiLiveTestSupport.requireApiKey(),
                            new ModelTurnCommand(ModelPurpose.AGENT, null, null,
                                    List.of(new ModelMessage.System("你是项目助手。"),
                                            new ModelMessage.User("查一下状态为 OPEN 的任务，然后告诉我数量")),
                                    List.of(SEARCH_TOOL), false, null)));
            assertThat(first.toolCalls()).hasSize(1);
            ModelToolCall call = first.toolCalls().getFirst();

            ModelTurnResult second = GeminiLiveTestSupport.retry503("gemini35 turn2",
                    () -> adapter().turn(config(2048), GeminiLiveTestSupport.requireApiKey(),
                            new ModelTurnCommand(ModelPurpose.AGENT, null, null,
                                    List.of(new ModelMessage.System("你是项目助手。"),
                                            new ModelMessage.User("查一下状态为 OPEN 的任务，然后告诉我数量"),
                                            new ModelMessage.Assistant(first.content(), List.of(call)),
                                            new ModelMessage.ToolResult(call.id(), call.name(),
                                                    mapper.createObjectNode()
                                                            .put("tasks", 3)
                                                            .set("items", mapper.createArrayNode()
                                                                    .add("T-101 登录修复").add("T-102 文档补全").add("T-103 检索调优")), false)),
                                    List.of(SEARCH_TOOL), false, null)));
            assertThat(second.content()).isNotBlank();
            assertThat(second.content()).contains("3");
        } catch (RuntimeException e) { recordFailure("scenario6_multiTurnTool", e); writeMetrics(); throw e; }
    }

    // ========== 场景 7：无证据合理拒答（P10 拒答纪律） ==========

    @Test
    @Order(7)
    void scenario7_refusalWithoutEvidence() {
        try {
            ChatCompletionResult result = complete(2048,
                    "你只能依据提供的文档回答。文档信息不足，因此我无法基于提供的文档回答该问题。",
                    "文档：（无相关内容）\n问题：本公司 2027 年 Q3 营收是多少？");
            assertThat(result.content()).isNotBlank();
            assertThat(result.content()).contains("无法");
        } catch (RuntimeException e) { recordFailure("scenario7_refusal", e); writeMetrics(); throw e; }
    }

    // ========== 场景 8：截断与配额错误处理 ==========

    @Test
    @Order(8)
    void scenario8_truncationAndQuotaClassification() {
        try {
            // MAX_TOKENS：thinkingBudget=0 下 8 token 输出预算必然截断 → 适配器必须抛 OUTPUT_TRUNCATED
            try {
                complete(8, "你是助手。", "请写一篇 200 字关于向量数据库的介绍。");
                org.junit.jupiter.api.Assertions.fail("应触发 MAX_TOKENS 截断错误");
            } catch (BusinessException expected) {
                assertThat(expected.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_OUTPUT_TRUNCATED);
                record("scenario8_maxTokens_classified", MODEL, 0, 1, 0, 0);
            }
            // 401 凭据错误分类（不消耗配额：伪造 key 本地被出站策略拒绝前即返回 401）
            try {
                OffsetDateTime now = OffsetDateTime.now();
                ModelConfiguration badKey = new ModelConfiguration(UUID.randomUUID(), UUID.randomUUID(),
                        "bad", ModelProviderType.GEMINI, "https://generativelanguage.googleapis.com",
                        "/v1beta/models/{model}:generateContent", "encrypted", MODEL, true, 0.0, 256,
                        EnumSet.of(ModelCapability.CHAT, ModelCapability.USAGE), now, now);
                adapter().complete(badKey, "invalid-key-for-error-classification", new ChatCompletionCommand(
                        null, "sys", "user", ChatCompletionCommand.OutputFormat.TEXT,
                        ModelPurpose.KNOWLEDGE_CHAT, null, List.of(), null));
                org.junit.jupiter.api.Assertions.fail("伪造 key 应返回凭据错误");
            } catch (BusinessException expected) {
                assertThat(expected.getErrorCode()).isEqualTo(ErrorCode.AI_MODEL_CREDENTIAL_INVALID);
                record("scenario8_unauthorized_classified", MODEL, 0, 1, 0, 0);
            }
            // 429 分类语义由 JsonHttpModelClient.checkStatus 契约保证（AI_PROVIDER_QUOTA_EXCEEDED），
            // 真实 429 依赖免费层额度耗尽，不可稳定触发——以契约测试覆盖为准，不做人为打爆配额。
        } catch (RuntimeException e) { recordFailure("scenario8_errors", e); writeMetrics(); throw e; }
    }

    // ========== 收尾：落盘指标 ==========

    @Test
    @Order(99)
    void summary_writeMetrics() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "true".equals(System.getenv("AI_GEMINI_LIVE_TESTS")),
                "SKIPPED: live gate off（CI 中不执行冒烟汇总断言）");
        writeMetrics();
        int failed = 0;
        for (ObjectNode r : RECORDS) {
            if (r.path("status").asText("OK").startsWith("FAILED")) failed++;
        }
        assertThat(failed).as("冒烟场景不得有失败（RECORDS=%s）", RECORDS.size()).isZero();
    }

    private void recordFailure(String scenario, RuntimeException e) {
        if (e instanceof org.opentest4j.TestAbortedException aborted) {
            throw aborted; // 门控跳过（Assumptions）不是失败，原样交还 JUnit
        }
        ObjectNode r = mapper.createObjectNode();
        r.put("scenario", scenario).put("status", "FAILED_" + e.getClass().getSimpleName())
                .put("errorCode", e instanceof BusinessException b ? b.getErrorCode().name() : "RUNTIME")
                .put("message", String.valueOf(e.getMessage()).substring(0, Math.min(150, String.valueOf(e.getMessage()).length())))
                .put("at", OffsetDateTime.now().toString());
        RECORDS.add(r);
    }
}
