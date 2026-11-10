package com.shitulelv.aicollab.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionCommand;
import com.shitulelv.aicollab.infrastructure.ai.ChatCompletionResult;
import com.shitulelv.aicollab.infrastructure.ai.model.AiRequestMetadata;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelCapability;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelProviderType;
import com.shitulelv.aicollab.infrastructure.ai.model.ModelPurpose;
import com.shitulelv.aicollab.infrastructure.ai.model.RoutingChatModelGateway;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProvider;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderRepository;
import com.shitulelv.aicollab.infrastructure.ai.user.UserAiProviderService;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG_V2_PUBLIC_BENCHMARK-01｜Benchmark B：RGB 中文版生成质量评测（模式 B1：官方固定证据）。
 *
 * <p>协议：
 * <ul>
 *   <li>数据：chen700564/RGB @ 65ec39e4（CC BY-NC-SA 4.0，非商业研究使用；不重新分发数据）。
 *       每题证据文档由冻结抽样脚本按<b>官方 evalue.py processdata 语义</b>（每题 random.seed(2333)）
 *       选定并冻结，标准答案零泄漏到模型输入。</li>
 *   <li>Prompt：官方 zh system + instruction（config/instruction.yaml 逐字复制），保证与官方协议可比。
 *       生成网关为生产 {@link RoutingChatModelGateway}，模型为魔搭 DeepSeek-V4.1-Flash-1017
 *       （用户已授权配置，密钥只写不读），temperature=0（官方示例 0.2/0.7，此处取 0 保证可复现，
 *       已在报告中如实标注差异）。</li>
 *   <li>评分：完全复刻官方 evalue.py 的 checkanswer / '信息不足' 拒答判定 / '事实性错误' factlabel
 *       语义。官方 all_rate、reject 口径、fact_check_rate、correct_rate 与自定义诊断指标分开呈现。
 *       官方 reject_evalue.py / fact_evalue.py 需 GPT-3.5 判分器（需 OpenAI 付费 API，未授权），
 *       本轮以 evalue.py 的确定性字符串判定为准，判分器差异如实标注。</li>
 *   <li>模式边界：B1 为官方固定证据模式，<b>不测量检索能力</b>，不得宣称端到端 RAG 成绩。</li>
 * </ul>
 * 输出仅写 {@code target/rgb-benchmark/}；整类由 {@code AI_RGB_BENCHMARK=true} 显式门控；
 * 支持 JSONL 断点续跑。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "embedding.enabled=false",
        "chat.enabled=false",
        "planning.enabled=false",
        "agent.enabled=false",
        "security.jwt.secret=test-only-secret-with-at-least-thirty-two-characters",
        "model.config.master-key=test-only-master-key-for-integration-tests",
        "security.jwt.access-token-minutes=30",
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=100ms",
        "spring.data.redis.timeout=100ms",
        "storage.minio.endpoint=http://127.0.0.1:1",
        "storage.minio.access-key=test-access",
        "storage.minio.secret-key=test-secret",
        "storage.minio.bucket=test-bucket"
})
@EnabledIfEnvironmentVariable(named = "AI_RGB_BENCHMARK", matches = "true")
@ActiveProfiles("test")
class RgbGenerationBenchmarkPostgresTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String CHAT_MODEL = "deepseek-ai/DeepSeek-V4.1-Flash-1017";
    private static final double CHAT_TEMPERATURE = 0.0d;
    private static final int CHAT_MAX_OUTPUT_TOKENS = 2048;
    /** 实际使用路由（覆盖或快照），写入 summary，不包含任何密钥。 */
    private String chatModelInUse = CHAT_MODEL;
    private String chatRouteNote = "MODELSCOPE_SHARED_ROUTE_SNAPSHOT model=" + CHAT_MODEL;

    /** 官方 zh prompt（config/instruction.yaml @ 65ec39e4 逐字复制）。 */
    private static final String OFFICIAL_ZH_SYSTEM = "你是一个准确和可靠的人工智能助手，能够借助外部文档回答问题，"
            + "请注意外部文档可能存在噪声事实性错误。如果文档中的信息包含了正确答案，你将进行准确的回答。"
            + "如果文档中的信息不包含答案，你将生成“文档信息不足，因此我无法基于提供的文档回答该问题。”。"
            + "如果部分文档中存在与事实不一致的错误，请先生成“提供文档的文档存在事实性错误。”，并生成正确答案。";
    private static final String OFFICIAL_ZH_INSTRUCTION = "文档：\n{DOCS} \n\n问题：\n{QUERY}";

    private static final Path REPO = Path.of("..").toAbsolutePath().normalize();
    private static final Path CACHE = Path.of(System.getenv()
            .getOrDefault("AI_RGB_BENCH_CACHE", "E:/project/ai-collab-benchmark-cache"));
    private static final Path FROZEN = CACHE.resolve("rgb-frozen-inputs.json");
    private static final Path OUTPUT = REPO.resolve("ai-collab-backend/target/rgb-benchmark");
    private static final Path PREDICTIONS = OUTPUT.resolve("rgb-generation-results.jsonl");

    @Autowired JdbcTemplate jdbc;
    @Autowired RoutingChatModelGateway chat;
    @Autowired UserAiProviderRepository providers;
    @Autowired UserAiProviderService routing;
    @Autowired com.shitulelv.aicollab.infrastructure.ai.model.ModelSecretCipher cipher;

    @MockitoBean
    TaskPlanRecoveryJob taskPlanRecoveryJob;

    private UUID user;
    private UUID project;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("AI_RGB_BENCH_DB_URL");
        registry.add("spring.datasource.url", () -> Objects.requireNonNull(url,
                "必须提供 AI_RGB_BENCH_DB_URL（隔离 Benchmark 库）"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("AI_RGB_BENCH_DB_USER", "bench"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("AI_RGB_BENCH_DB_PASSWORD", "bench"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("model.config.master-key", () -> {
            try {
                for (String line : Files.readAllLines(REPO.resolve(".env"))) {
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

    @Test
    void runRgbGeneration() throws Exception {
        Files.createDirectories(OUTPUT);
        setupContext();
        // 冻结输入读取
        JsonNode frozen = JSON.readTree(FROZEN.toFile());
        // 断点续跑：读取既有预测
        Map<String, Map<String, Object>> done = new LinkedHashMap<>();
        if (Files.exists(PREDICTIONS)) {
            for (String line : Files.readAllLines(PREDICTIONS, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                Map<String, Object> row = JSON.readValue(line, Map.class);
                if (!"OK".equals(row.get("status"))) continue; // 失败行重跑
                done.put(row.get("task") + "#" + row.get("id") + "#" + row.get("condition"), row);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>(done.values());
        try (var writer = Files.newBufferedWriter(PREDICTIONS, StandardCharsets.UTF_8)) {
            for (Map<String, Object> prev : done.values()) {
                writer.write(JSON.writeValueAsString(prev));
                writer.write("\n");
            }

            rows.addAll(runNoiseRobustness(frozen, done, writer));
            rows.addAll(runNegativeRejection(frozen, done, writer));
            rows.addAll(runInformationIntegration(frozen, done, writer));
            rows.addAll(runCounterfactual(frozen, done, writer));
        }

        Map<String, Object> summary = summarize(rows);
        JSON.writerWithDefaultPrettyPrinter().writeValue(
                OUTPUT.resolve("rgb-summary.json").toFile(), summary);
        writeSummaryCsv(summary);
        System.out.println("RGB_BENCHMARK_RESULTS=" + OUTPUT.toAbsolutePath());
        System.out.println(JSON.writeValueAsString(summary.get("officialMetrics")));
    }

    // ==================== 各任务执行 ====================

    private List<Map<String, Object>> runNoiseRobustness(JsonNode frozen,
            Map<String, Map<String, Object>> done, java.io.BufferedWriter writer) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : frozen.path("noiseRobustness")) {
            for (JsonNode cond : item.path("conditions")) {
                String key = "NOISE_ROBUSTNESS#" + item.path("id").asInt() + "#" + cond.path("noiseRate").asDouble();
                if (done.containsKey(key)) continue;
                List<String> docs = new ArrayList<>();
                cond.path("docs").forEach(d -> docs.add(d.asText()));
                Map<String, Object> row = generateAndScore("NOISE_ROBUSTNESS", item.path("id").asInt(),
                        item.path("query").asText(), JSON.convertValue(item.path("answer"), List.class),
                        docs, String.valueOf(cond.path("noiseRate").asDouble()));
                rows.add(row);
                writer.write(JSON.writeValueAsString(row));
                writer.write("\n");
                writer.flush();
            }
        }
        return rows;
    }

    private List<Map<String, Object>> runNegativeRejection(JsonNode frozen,
            Map<String, Map<String, Object>> done, java.io.BufferedWriter writer) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : frozen.path("negativeRejection")) {
            String key = "NEGATIVE_REJECTION#" + item.path("id").asInt() + "#1.0";
            if (done.containsKey(key)) continue;
            List<String> docs = new ArrayList<>();
            item.path("docs").forEach(d -> docs.add(d.asText()));
            Map<String, Object> row = generateAndScore("NEGATIVE_REJECTION", item.path("id").asInt(),
                    item.path("query").asText(), JSON.convertValue(item.path("answer"), List.class),
                    docs, "1.0");
            rows.add(row);
            writer.write(JSON.writeValueAsString(row));
            writer.write("\n");
            writer.flush();
        }
        return rows;
    }

    private List<Map<String, Object>> runInformationIntegration(JsonNode frozen,
            Map<String, Map<String, Object>> done, java.io.BufferedWriter writer) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : frozen.path("informationIntegration")) {
            String key = "INFORMATION_INTEGRATION#" + item.path("id").asInt() + "#0.0";
            if (done.containsKey(key)) continue;
            List<String> docs = new ArrayList<>();
            item.path("docs").forEach(d -> docs.add(d.asText()));
            Map<String, Object> row = generateAndScore("INFORMATION_INTEGRATION", item.path("id").asInt(),
                    item.path("query").asText(), JSON.convertValue(item.path("answer"), List.class),
                    docs, "0.0");
            rows.add(row);
            writer.write(JSON.writeValueAsString(row));
            writer.write("\n");
            writer.flush();
        }
        return rows;
    }

    private List<Map<String, Object>> runCounterfactual(JsonNode frozen,
            Map<String, Map<String, Object>> done, java.io.BufferedWriter writer) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode item : frozen.path("counterfactualRobustness")) {
            String key = "COUNTERFACTUAL_ROBUSTNESS#" + item.path("id").asInt() + "#0.0";
            if (done.containsKey(key)) continue;
            List<String> docs = new ArrayList<>();
            item.path("docs").forEach(d -> docs.add(d.asText()));
            Map<String, Object> row = generateAndScore("COUNTERFACTUAL_ROBUSTNESS", item.path("id").asInt(),
                    item.path("query").asText(),
                    // zh_fact 官方 answer 为字符串（zh_refine/zh_int 为数组），统一包装为单元素列表
                    item.path("answer").isTextual() ? List.of(item.path("answer").asText())
                            : JSON.convertValue(item.path("answer"), List.class),
                    docs, "0.0");
            row.put("fakeAnswer", item.path("fakeAnswer").asText());
            rows.add(row);
            writer.write(JSON.writeValueAsString(row));
            writer.write("\n");
            writer.flush();
        }
        return rows;
    }

    // ==================== 生成与官方语义评分 ====================

    private Map<String, Object> generateAndScore(String task, int id, String query,
            List<Object> groundTruth, List<String> docs, String condition) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("task", task);
        row.put("id", id);
        row.put("condition", condition);
        row.put("query", query);
        row.put("ans", groundTruth);
        row.put("docCount", docs.size());
        try {
            String userPrompt = OFFICIAL_ZH_INSTRUCTION
                    .replace("{DOCS}", String.join("\n", docs))
                    .replace("{QUERY}", query);
            ChatCompletionResult completion = completeWithRetry(new ChatCompletionCommand(
                    project, OFFICIAL_ZH_SYSTEM, userPrompt,
                    ChatCompletionCommand.OutputFormat.TEXT, ModelPurpose.KNOWLEDGE_CHAT, null,
                    List.of(), user));
            String prediction = completion.content() == null ? "" : completion.content();
            // 官方 zh 后处理：去空格后判定
            String normalized = prediction.replace(" ", "");
            row.put("prediction", prediction);
            row.put("label", checkAnswer(normalized, groundTruth));
            row.put("factlabel", normalized.contains("事实性错误") ? 1 : 0);
            row.put("latencyMs", completion.latencyMs());
            row.put("promptTokens", completion.promptTokens());
            row.put("completionTokens", completion.completionTokens());
            row.put("status", "OK");
        } catch (Exception failure) {
            row.put("status", "CHAT_FAILED");
            row.put("errorType", failure.getClass().getSimpleName());
            // 业务异常消息为业务级文案（不含密钥）；其余异常只留类名与首个冒号前摘要，防泄漏
            String message = String.valueOf(failure.getMessage());
            if (!(failure instanceof com.shitulelv.aicollab.common.exception.BusinessException)) {
                int cut = message.indexOf(':');
                message = cut > 0 ? message.substring(0, cut) : message;
            }
            row.put("errorMessage", message.length() > 300 ? message.substring(0, 300) : message);
            // 定位 UNAVAILABLE 来源：记录堆栈前 6 帧（该路径不含密钥/敏感参数）
            StackTraceElement[] stack = failure.getStackTrace();
            StringBuilder frames = new StringBuilder();
            for (int i = 0; i < Math.min(6, stack.length); i++) {
                frames.append(stack[i].getClassName().replaceAll(".*\\.", "")).append('.').append(stack[i].getMethodName()).append(';');
            }
            row.put("errorFrames", frames.toString());
        }
        return row;
    }

    /** 官方 checkanswer 逐字复刻（predict 的 label 语义，含 '信息不足' → [-1]）。 */
    static List<Integer> checkAnswer(String prediction, List<Object> groundTruth) {
        if (prediction.contains("信息不足")) {
            return List.of(-1);
        }
        List<Integer> labels = new ArrayList<>();
        for (Object gt : groundTruth) {
            if (gt instanceof List<?> alternatives) {
                boolean any = false;
                for (Object alt : alternatives) {
                    if (prediction.contains(String.valueOf(alt).toLowerCase())) { any = true; break; }
                }
                labels.add(any ? 1 : 0);
            } else {
                labels.add(prediction.contains(String.valueOf(gt).toLowerCase()) ? 1 : 0);
            }
        }
        return labels;
    }

    static boolean officialCorrect(List<Integer> label) {
        return !label.isEmpty() && !label.contains(0) && label.contains(1);
    }

    private Map<String, Object> summarize(List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> byTask = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byTask.computeIfAbsent(String.valueOf(row.get("task")), k -> new ArrayList<>()).add(row);
        }

        Map<String, Object> official = new LinkedHashMap<>();
        Map<String, Object> diagnostic = new LinkedHashMap<>();

        // Noise Robustness：官方 all_rate（准确率）按噪声率分档
        Map<String, Object> noise = new LinkedHashMap<>();
        for (String nr : List.of("0.2", "0.6", "0.8")) {
            List<Map<String, Object>> subset = rows.stream()
                    .filter(r -> "NOISE_ROBUSTNESS".equals(r.get("task")) && nr.equals(String.valueOf(r.get("condition"))))
                    .toList();
            List<Map<String, Object>> ok = subset.stream().filter(r -> "OK".equals(r.get("status"))).toList();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("samples", subset.size());
            entry.put("executed", ok.size());
            if (!ok.isEmpty()) {
                entry.put("allRateAccuracy", ok.stream()
                        .filter(r -> officialCorrect((List<Integer>) r.get("label"))).count() / (double) ok.size());
                entry.put("rejected", ok.stream()
                        .filter(r -> ((List<Integer>) r.get("label")).getFirst() == -1).count());
                entry.put("wrongAnswers", ok.stream()
                        .filter(r -> !officialCorrect((List<Integer>) r.get("label"))
                                && ((List<Integer>) r.get("label")).getFirst() != -1).count());
            }
            noise.put("noise" + nr, entry);
        }
        official.put("noiseRobustness", noise);

        // Negative Rejection：官方 noise=1 口径（label[0]==-1 比例即 all_rate）
        List<Map<String, Object>> rejectRows = byTask.getOrDefault("NEGATIVE_REJECTION", List.of());
        List<Map<String, Object>> rejectOk = rejectRows.stream().filter(r -> "OK".equals(r.get("status"))).toList();
        Map<String, Object> rejectEntry = new LinkedHashMap<>();
        rejectEntry.put("samples", rejectRows.size());
        rejectEntry.put("executed", rejectOk.size());
        if (!rejectOk.isEmpty()) {
            long rejected = rejectOk.stream()
                    .filter(r -> ((List<Integer>) r.get("label")).getFirst() == -1).count();
            long confidentWrong = rejectOk.size() - rejected;
            rejectEntry.put("allRateRejection", rejected / (double) rejectOk.size());
            rejectEntry.put("correctRejections", rejected);
            rejectEntry.put("confidentWrongAnswers", confidentWrong);
            rejectEntry.put("confidentWrongRate", confidentWrong / (double) rejectOk.size());
        }
        official.put("negativeRejection", rejectEntry);

        // Information Integration：官方 all_rate（两个答案都必须出现）
        List<Map<String, Object>> intRows = byTask.getOrDefault("INFORMATION_INTEGRATION", List.of());
        List<Map<String, Object>> intOk = intRows.stream().filter(r -> "OK".equals(r.get("status"))).toList();
        Map<String, Object> intEntry = new LinkedHashMap<>();
        intEntry.put("samples", intRows.size());
        intEntry.put("executed", intOk.size());
        if (!intOk.isEmpty()) {
            intEntry.put("allRateAccuracy", intOk.stream()
                    .filter(r -> officialCorrect((List<Integer>) r.get("label"))).count() / (double) intOk.size());
            // 自定义诊断：必要证据覆盖率（至少一个答案命中）
            intEntry.put("diagPartialCoverage", intOk.stream()
                    .filter(r -> {
                        List<Integer> label = (List<Integer>) r.get("label");
                        return !officialCorrect(label) && label.contains(1);
                    }).count() / (double) intOk.size());
            intEntry.put("diagMissedBoth", intOk.stream()
                    .filter(r -> {
                        List<Integer> label = (List<Integer>) r.get("label");
                        return !label.contains(1);
                    }).count() / (double) intOk.size());
        }
        official.put("informationIntegration", intEntry);

        // Counterfactual：官方 fact_check_rate（ED）与 correct_rate（CR，检出者中答案正确比例）
        List<Map<String, Object>> factRows = byTask.getOrDefault("COUNTERFACTUAL_ROBUSTNESS", List.of());
        List<Map<String, Object>> factOk = factRows.stream().filter(r -> "OK".equals(r.get("status"))).toList();
        Map<String, Object> factEntry = new LinkedHashMap<>();
        factEntry.put("samples", factRows.size());
        factEntry.put("executed", factOk.size());
        if (!factOk.isEmpty()) {
            long detected = factOk.stream().filter(r -> ((Number) r.get("factlabel")).intValue() == 1).count();
            long detectedAndCorrect = factOk.stream()
                    .filter(r -> ((Number) r.get("factlabel")).intValue() == 1
                            && officialCorrect((List<Integer>) r.get("label"))).count();
            factEntry.put("factCheckRate", detected / (double) factOk.size());
            factEntry.put("correctRateAmongDetected", detected > 0 ? detectedAndCorrect / (double) detected : 0);
            factEntry.put("detected", detected);
            factEntry.put("detectedAndCorrect", detectedAndCorrect);
            // 自定义诊断：接受了错误答案（答案与 fakeAnswer 一致且未检出错误）
            factEntry.put("diagAcceptedFakeAnswer", factOk.stream()
                    .filter(r -> {
                        if (((Number) r.get("factlabel")).intValue() == 1) return false;
                        String pred = String.valueOf(r.get("prediction")).replace(" ", "");
                        String fake = String.valueOf(r.get("fakeAnswer"));
                        return !fake.isBlank() && pred.contains(fake);
                    }).count() / (double) factOk.size());
        }
        official.put("counterfactualRobustness", factEntry);

        // 诊断：失败调用 / 延迟 / token
        long chatFailed = rows.stream().filter(r -> "CHAT_FAILED".equals(r.get("status"))).count();
        List<Long> latencies = rows.stream().filter(r -> r.get("latencyMs") != null)
                .map(r -> ((Number) r.get("latencyMs")).longValue()).sorted().toList();
        diagnostic.put("totalItems", rows.size());
        diagnostic.put("chatFailed", chatFailed);
        diagnostic.put("latencyP50Ms", latencies.isEmpty() ? 0 : latencies.get(latencies.size() / 2));
        diagnostic.put("totalPromptTokens", rows.stream().filter(r -> r.get("promptTokens") != null)
                .mapToLong(r -> ((Number) r.get("promptTokens")).longValue()).sum());
        diagnostic.put("totalCompletionTokens", rows.stream().filter(r -> r.get("completionTokens") != null)
                .mapToLong(r -> ((Number) r.get("completionTokens")).longValue()).sum());
        diagnostic.put("generationMode", "B1_OFFICIAL_FIXED_EVIDENCE（不测量检索能力）");
        diagnostic.put("scoringDeviation", "官方 reject_evalue/fact_evalue 需 GPT-3.5 判分器（未授权付费 API），"
                + "以 evalue.py 确定性字符串判定为准；temperature=0（官方示例 0.2）");

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("chatModel", chatModelInUse);
        summary.put("chatRoute", chatRouteNote);
        summary.put("officialMetrics", official);
        summary.put("diagnostics", diagnostic);
        return summary;
    }

    @SuppressWarnings("unchecked")
    private void writeSummaryCsv(Map<String, Object> summary) throws Exception {
        StringBuilder csv = new StringBuilder("task,samples,executed,officialMetric,value\n");
        Map<String, Object> official = (Map<String, Object>) summary.get("officialMetrics");
        Map<String, Object> noise = (Map<String, Object>) official.get("noiseRobustness");
        for (String nr : List.of("noise0.2", "noise0.6", "noise0.8")) {
            appendCsv(csv, nr, noise.get(nr), "allRateAccuracy");
        }
        appendCsv(csv, "negativeRejection", official.get("negativeRejection"), "allRateRejection");
        appendCsv(csv, "informationIntegration", official.get("informationIntegration"), "allRateAccuracy");
        appendCsv(csv, "counterfactual", official.get("counterfactualRobustness"), "factCheckRate");
        appendCsv(csv, "counterfactual", official.get("counterfactualRobustness"), "correctRateAmongDetected");
        Files.writeString(OUTPUT.resolve("rgb-summary.csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private void appendCsv(StringBuilder csv, String task, Object node, String metric) {
        if (!(node instanceof Map)) return;
        Object value = ((Map<String, Object>) node).get(metric);
        if (value == null) return;
        csv.append(task).append(',').append(((Map<String, Object>) node).get("samples")).append(',')
                .append(((Map<String, Object>) node).get("executed")).append(',')
                .append(metric).append(',').append(value).append('\n');
    }

    private void setupContext() throws Exception {
        user = UUID.randomUUID();
        project = UUID.randomUUID();
        jdbc.execute("""
                TRUNCATE document_chunk, project_document, project_member, project, app_user,
                         embedding_index_generation, system_embedding_config CASCADE
                """);
        jdbc.update("INSERT INTO app_user(id,username,password_hash,display_name) VALUES (?,?,?,?)",
                user, "rgb-bench-" + user.toString().substring(0, 8), "test-only-hash", "RgbBenchmark");
        jdbc.update("INSERT INTO project(id,name,owner_id,created_by) VALUES (?,?,?,?)",
                project, "RGB 中文生成评测", user, user);
        jdbc.update("INSERT INTO project_member(project_id,user_id,role) VALUES (?,?,'OWNER')", project, user);
        prepareChatProvider();
    }

    /** 路由配置从本地开发库 SELECT 快照复制（只读、不打印密钥），解密依赖 .env 的 MODEL_CONFIG_MASTER_KEY。
     *  支持环境变量覆盖（仅评测）：AI_RGB_BENCH_CHAT_BASE_URL / MODEL / API_KEY（用户已授权端点）。 */
    private void prepareChatProvider() throws Exception {
        String overrideBaseUrl = System.getenv("AI_RGB_BENCH_CHAT_BASE_URL");
        String overrideModel = System.getenv("AI_RGB_BENCH_CHAT_MODEL");
        String overrideKey = System.getenv("AI_RGB_BENCH_CHAT_API_KEY");
        if (overrideBaseUrl != null && !overrideBaseUrl.isBlank()
                && overrideModel != null && !overrideModel.isBlank()
                && overrideKey != null && !overrideKey.isBlank()) {
            chatModelInUse = overrideModel.strip();
            chatRouteNote = "ENV_OVERRIDE_OPENAI_COMPATIBLE base=" + overrideBaseUrl.strip()
                    + " model=" + overrideModel.strip() + "（原 ModelScope 路由额度不足，经用户授权切换）";
            UUID overrideId = UUID.randomUUID();
            UserAiProvider overrideProvider = new UserAiProvider(overrideId, user,
                    "RGB Eval Override Chat Route", ModelProviderType.OPENAI_COMPATIBLE,
                    overrideBaseUrl.strip(), "/chat/completions",
                    cipher.encrypt(overrideKey.strip()), overrideModel.strip(), true,
                    CHAT_TEMPERATURE, CHAT_MAX_OUTPUT_TOKENS,
                    EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.USAGE),
                    false, OffsetDateTime.now(), OffsetDateTime.now(), null);
            providers.save(overrideProvider);
            routing.assignPurpose(user, ModelPurpose.KNOWLEDGE_CHAT, overrideId);
            return;
        }
        String sql = "SELECT row_to_json(p) FROM shared_ai_provider p "
                + "WHERE p.model_name='" + CHAT_MODEL + "' AND p.enabled AND p.status='PUBLISHED' "
                + "ORDER BY p.published_at DESC LIMIT 1";
        Process names = new ProcessBuilder("docker", "exec", "ai-collab-postgres", "printenv",
                "POSTGRES_USER", "POSTGRES_DB").start();
        String[] databaseNames = new String(names.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip().split("\\R");
        if (names.waitFor() != 0 || databaseNames.length != 2) throw new IllegalStateException("Local database unavailable");
        Process process = new ProcessBuilder("docker", "exec", "-i", "ai-collab-postgres", "psql", "-X", "-q", "-t", "-A",
                "-v", "ON_ERROR_STOP=1", "-U", databaseNames[0], "-d", databaseNames[1]).start();
        process.getOutputStream().write((sql + ";\n").getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String source = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0 || source.isBlank()) throw new IllegalStateException("ModelScope DeepSeek route unavailable");
        JsonNode p = JSON.readTree(source);
        UUID providerId = UUID.randomUUID();
        UserAiProvider provider = new UserAiProvider(providerId, user, "ModelScope DeepSeek RGB Eval Chat",
                ModelProviderType.OPENAI_COMPATIBLE, p.path("base_url").asText(), "/chat/completions",
                p.path("encrypted_api_key").asText(), CHAT_MODEL, true, CHAT_TEMPERATURE, CHAT_MAX_OUTPUT_TOKENS,
                EnumSet.of(ModelCapability.CHAT, ModelCapability.STREAMING, ModelCapability.USAGE),
                false, OffsetDateTime.now(), OffsetDateTime.now(), null);
        providers.save(provider);
        routing.assignPurpose(user, ModelPurpose.KNOWLEDGE_CHAT, providerId);
    }

    private ChatCompletionResult completeWithRetry(ChatCompletionCommand command) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return chat.complete(command, new AiRequestMetadata(UUID.randomUUID().toString()));
            } catch (Exception failure) {
                lastFailure = failure;
                if (attempt < 3) Thread.sleep(15000L);
            }
        }
        throw lastFailure;
    }
}
