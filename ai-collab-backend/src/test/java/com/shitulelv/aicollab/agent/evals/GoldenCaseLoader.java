package com.shitulelv.aicollab.agent.evals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * golden case 加载器：从 classpath {@code evals/golden-cases.json} 读取并严格校验。
 *
 * <p><b>畸形 case 启动即失败</b>：任何 schema 违例（缺字段、非法枚举、id 重复、
 * 未知 probe 词表、非法运行状态等）都会抛出带精确 JSON 定位与 caseId 的
 * {@link GoldenCaseFormatException}，由评估套件在装配阶段失败——绝不让坏 case
 * 静默跳过或产生无意义结果。</p>
 *
 * <p>校验是白名单式的：事件类型限定 {@link com.shitulelv.aicollab.agent.domain.model.AgentEventType}，
 * 运行状态限定 {@link com.shitulelv.aicollab.agent.domain.model.AgentRunStatus}，
 * DB probe 限定运行器支持的受限词表（禁止任意 SQL 断言，保证 case 定义可移植且可审计）。</p>
 */
public final class GoldenCaseLoader {

    /** classpath 资源路径。 */
    public static final String RESOURCE = "/evals/golden-cases.json";

    /** DB 副作用断言支持的 probe 词表（运行器逐个实现，禁止扩展为任意 SQL）。 */
    static final Set<String> DB_PROBES = Set.of(
            "APPROVAL_STATUS", "APPROVAL_COUNT", "TASK_COUNT", "MEMORY_COUNT",
            "MEMORY_CONTENT_CONTAINS", "CHILD_RUN_COUNT", "LEDGER_COUNT");

    /** agent_tool_invocation.status 列的合法取值（与 V47 迁移的 CHECK 约束一致）。 */
    static final Set<String> INVOCATION_STATUSES = Set.of(
            "PENDING", "SUCCEEDED", "FAILED", "REJECTED", "CANCELED", "SKIPPED", "UNKNOWN");

    private static final Set<String> EVENT_TYPES = eventTypeNames();
    private static final Set<String> RUN_STATUSES = runStatusNames();

    private final ObjectMapper json = new ObjectMapper();

    /** 从 classpath 加载全部 golden case（按文件顺序，校验通过后才返回）。 */
    public List<GoldenCase> load() {
        try (InputStream in = GoldenCaseLoader.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new GoldenCaseFormatException("找不到评估集定义资源 " + RESOURCE);
            }
            // UTF-8 无 BOM 是 fixtures 硬规则：BOM 会让首个字段名带 \uFEFF，这里显式拒绝
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (raw.startsWith("\uFEFF")) {
                throw new GoldenCaseFormatException(RESOURCE + " 带 UTF-8 BOM，必须保存为无 BOM 的 UTF-8");
            }
            return parse(raw);
        } catch (IOException e) {
            throw new GoldenCaseFormatException("读取 " + RESOURCE + " 失败", e);
        }
    }

    /** 解析并校验，畸形即抛 {@link GoldenCaseFormatException}。 */
    public List<GoldenCase> parse(String raw) {
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (IOException e) {
            throw new GoldenCaseFormatException("评估集定义不是合法 JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new GoldenCaseFormatException("根节点必须是 JSON 对象");
        }
        requireField(root, "version");
        int version = root.path("version").asInt(-1);
        if (version < 1) {
            throw new GoldenCaseFormatException("$.version 必须是 >=1 的整数");
        }
        JsonNode casesNode = requireField(root, "cases");
        if (!casesNode.isArray() || casesNode.isEmpty()) {
            throw new GoldenCaseFormatException("$.cases 必须是非空数组");
        }
        Set<String> seenIds = new HashSet<>();
        List<GoldenCase> cases = new ArrayList<>();
        for (int i = 0; i < casesNode.size(); i++) {
            GoldenCase c = parseCase(casesNode.get(i), "$.cases[" + i + "]");
            if (!seenIds.add(c.id())) {
                throw new GoldenCaseFormatException("$.cases[" + i + "].id 重复: " + c.id());
            }
            cases.add(c);
        }
        return List.copyOf(cases);
    }

    private GoldenCase parseCase(JsonNode node, String at) {
        requireObject(node, at);
        String id = requireText(node, "id", at);
        if (!id.matches("[a-z0-9-]+")) {
            fail(at + ".id 只允许小写字母、数字与连字符: " + id);
        }
        String atId = at + "(id=" + id + ")";
        String categoryRaw = requireText(node, "category", atId);
        GoldenCase.Category category = parseEnum(GoldenCase.Category.class, categoryRaw, atId + ".category");
        String title = requireText(node, "title", atId);
        String guards = requireText(node, "guards", atId);
        String userRequest = requireText(node, "userRequest", atId);

        // skillCode：显式给出时必须是注册表中存在的 Scenario（防止拼错后静默回退）
        String skillCode = optionalText(node, "skillCode");
        if (skillCode != null && !new com.shitulelv.aicollab.agent.domain.model.AgentScenarioRegistry().exists(skillCode)) {
            fail(atId + ".skillCode 不是已注册 Scenario: " + skillCode);
        }

        GoldenCase.Seed seed = parseSeed(node.path("seed"), atId + ".seed");
        GoldenCase.ToolBehavior toolBehavior = parseToolBehavior(node.path("toolBehavior"), atId + ".toolBehavior");
        GoldenCase.Budget budget = parseBudget(node.path("budget"), atId + ".budget");
        GoldenCase.RunOverrides overrides = parseRunOverrides(node.path("runOverrides"), atId + ".runOverrides");
        List<GoldenCase.SeedStep> seedSteps = parseSeedSteps(node.path("seedSteps"), atId + ".seedSteps");
        GoldenCase.Script script = parseScript(requireField(node, "script", atId), atId + ".script");
        GoldenCase.PostRun postRun = parsePostRun(node.path("postRun"), atId + ".postRun");
        GoldenCase.Assertions assertions = parseAssertions(
                requireField(node, "assertions", atId), atId + ".assertions");

        return new GoldenCase(id, category, title, guards, skillCode, userRequest, seed,
                toolBehavior, budget, overrides, seedSteps, script, postRun, assertions);
    }

    // ---------- 各段解析 ----------

    private GoldenCase.Seed parseSeed(JsonNode node, String at) {
        if (node.isMissingNode() || node.isNull()) return new GoldenCase.Seed(List.of());
        requireObject(node, at);
        List<GoldenCase.Seed.SeedTask> tasks = new ArrayList<>();
        JsonNode tasksNode = node.path("tasks");
        if (tasksNode.isArray()) {
            for (int i = 0; i < tasksNode.size(); i++) {
                JsonNode t = tasksNode.get(i);
                requireObject(t, at + ".tasks[" + i + "]");
                tasks.add(new GoldenCase.Seed.SeedTask(
                        requireText(t, "title", at + ".tasks[" + i + "]"),
                        t.path("status").asText("TODO")));
            }
        } else if (!tasksNode.isMissingNode()) {
            fail(at + ".tasks 必须是数组");
        }
        return new GoldenCase.Seed(List.copyOf(tasks));
    }

    private GoldenCase.ToolBehavior parseToolBehavior(JsonNode node, String at) {
        if (node.isMissingNode() || node.isNull()) return null;
        requireObject(node, at);
        // 目前只支持 list_tasks 一个可控行为；扩展时同步更新加载器与运行器
        for (String field : iterable(node.fieldNames())) {
            if (!"list_tasks".equals(field)) {
                fail(at + "." + field + " 不是支持的 toolBehavior 键（当前仅支持 list_tasks）");
            }
        }
        if (node.has("list_tasks")) {
            String value = node.path("list_tasks").asText();
            if (!"OK".equals(value) && !"FAIL".equals(value)) {
                fail(at + ".list_tasks 只允许 OK 或 FAIL: " + value);
            }
            return new GoldenCase.ToolBehavior(Map.of("list_tasks", value));
        }
        return null;
    }

    private GoldenCase.Budget parseBudget(JsonNode node, String at) {
        if (node.isMissingNode() || node.isNull()) return null;
        requireObject(node, at);
        Long softCap = node.has("softInputTokenCap") ? node.path("softInputTokenCap").asLong() : null;
        Long seeded = node.has("seedInputTokensActual") ? node.path("seedInputTokensActual").asLong() : null;
        if (softCap != null && softCap <= 0) fail(at + ".softInputTokenCap 必须 >0");
        if (seeded != null && seeded < 0) fail(at + ".seedInputTokensActual 不能为负");
        return new GoldenCase.Budget(softCap, seeded);
    }

    private GoldenCase.RunOverrides parseRunOverrides(JsonNode node, String at) {
        if (node.isMissingNode() || node.isNull()) return null;
        requireObject(node, at);
        Integer policyVersion = node.has("contextPolicyVersion")
                ? node.path("contextPolicyVersion").asInt() : null;
        if (policyVersion != null && policyVersion != 1 && policyVersion != 2) {
            fail(at + ".contextPolicyVersion 只允许 1 或 2");
        }
        Integer maxInput = node.has("maxInputTokens") ? node.path("maxInputTokens").asInt() : null;
        if (maxInput != null && maxInput <= 0) fail(at + ".maxInputTokens 必须 >0");
        Long seeded = node.has("seedInputTokensActual") ? node.path("seedInputTokensActual").asLong() : null;
        Boolean useAllButOneStep = node.has("useAllButOneStep")
                ? node.path("useAllButOneStep").asBoolean() : null;
        return new GoldenCase.RunOverrides(policyVersion, maxInput, seeded, useAllButOneStep);
    }

    private List<GoldenCase.SeedStep> parseSeedSteps(JsonNode node, String at) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) fail(at + " 必须是数组");
        List<GoldenCase.SeedStep> steps = new ArrayList<>();
        for (int i = 0; i < node.size(); i++) {
            JsonNode s = node.get(i);
            requireObject(s, at + "[" + i + "]");
            String type = requireText(s, "type", at + "[" + i + "]");
            // 目前只允许预置工具完成步骤（证据兜底场景）；扩展时同步运行器
            if (!"TOOL_CALL_COMPLETED".equals(type)) {
                fail(at + "[" + i + "].type 只允许 TOOL_CALL_COMPLETED: " + type);
            }
            String toolName = requireText(s, "toolName", at + "[" + i + "]");
            JsonNode output = requireField(s, "output", at + "[" + i + "]");
            if (!output.isObject()) fail(at + "[" + i + "].output 必须是对象");
            steps.add(new GoldenCase.SeedStep(type, toolName,
                    json.convertValue(output, Map.class), s.path("reason").asText("TOOL_SUCCESS")));
        }
        return List.copyOf(steps);
    }

    private GoldenCase.Script parseScript(JsonNode node, String at) {
        requireObject(node, at);
        JsonNode rootTurns = requireField(node, "rootTurns", at);
        if (!rootTurns.isArray() || rootTurns.isEmpty()) fail(at + ".rootTurns 必须是非空数组");
        List<GoldenCase.ScriptTurn> turns = new ArrayList<>();
        for (int i = 0; i < rootTurns.size(); i++) {
            turns.add(parseTurn(rootTurns.get(i), at + ".rootTurns[" + i + "]"));
        }
        List<GoldenCase.ScriptTurn> childTurns = new ArrayList<>();
        JsonNode childNode = node.path("childTurns");
        if (childNode.isArray()) {
            for (int i = 0; i < childNode.size(); i++) {
                childTurns.add(parseTurn(childNode.get(i), at + ".childTurns[" + i + "]"));
            }
        } else if (!childNode.isMissingNode()) {
            fail(at + ".childTurns 必须是数组");
        }
        int maxAdvances = node.path("maxAdvances").asInt(12);
        if (maxAdvances < 1) fail(at + ".maxAdvances 必须 >=1");
        return new GoldenCase.Script(List.copyOf(turns), List.copyOf(childTurns), maxAdvances);
    }

    private GoldenCase.ScriptTurn parseTurn(JsonNode node, String at) {
        requireObject(node, at);
        String content = requireText(node, "content", at);
        List<GoldenCase.ScriptTurn.ScriptToolCall> calls = new ArrayList<>();
        JsonNode callsNode = node.path("toolCalls");
        if (callsNode.isArray()) {
            for (int i = 0; i < callsNode.size(); i++) {
                JsonNode c = callsNode.get(i);
                requireObject(c, at + ".toolCalls[" + i + "]");
                String name = requireText(c, "name", at + ".toolCalls[" + i + "]");
                JsonNode args = requireField(c, "arguments", at + ".toolCalls[" + i + "]");
                if (!args.isObject()) fail(at + ".toolCalls[" + i + "].arguments 必须是对象");
                calls.add(new GoldenCase.ScriptTurn.ScriptToolCall(name,
                        json.convertValue(args, Map.class)));
            }
        } else if (!callsNode.isMissingNode()) {
            fail(at + ".toolCalls 必须是数组");
        }
        return new GoldenCase.ScriptTurn(content, List.copyOf(calls));
    }

    private GoldenCase.PostRun parsePostRun(JsonNode node, String at) {
        if (node.isMissingNode() || node.isNull()) return null;
        requireObject(node, at);
        String action = requireText(node, "action", at);
        if (!"APPROVE".equals(action) && !"REJECT".equals(action)) {
            fail(at + ".action 只允许 APPROVE 或 REJECT: " + action);
        }
        String reason = node.path("reason").asText(null);
        if ("REJECT".equals(action) && (reason == null || reason.isBlank())) {
            fail(at + ".reason 在 REJECT 时必填");
        }
        return new GoldenCase.PostRun(action, reason);
    }

    private GoldenCase.Assertions parseAssertions(JsonNode node, String at) {
        requireObject(node, at);
        String finalStatus = null;
        if (node.has("finalStatus")) {
            finalStatus = node.path("finalStatus").asText();
            if (!RUN_STATUSES.contains(finalStatus)) {
                fail(at + ".finalStatus 不是合法 AgentRunStatus: " + finalStatus);
            }
        }
        List<GoldenCase.EventAssertion> events = new ArrayList<>();
        JsonNode eventsNode = node.path("events");
        if (eventsNode.isArray()) {
            for (int i = 0; i < eventsNode.size(); i++) {
                JsonNode e = eventsNode.get(i);
                requireObject(e, at + ".events[" + i + "]");
                String type = requireText(e, "type", at + ".events[" + i + "]");
                if (!EVENT_TYPES.contains(type)) {
                    fail(at + ".events[" + i + "].type 不是合法 AgentEventType: " + type);
                }
                int min = e.path("min").asInt(1);
                if (min < 0) fail(at + ".events[" + i + "].min 不能为负");
                Integer max = e.has("max") ? e.path("max").asInt() : null;
                if (max != null && max < min) fail(at + ".events[" + i + "].max 不能小于 min");
                events.add(new GoldenCase.EventAssertion(type, min, max, nullableText(e, "payloadContains")));
            }
        } else if (!eventsNode.isMissingNode()) {
            fail(at + ".events 必须是数组");
        }
        List<GoldenCase.ToolInvocationAssertion> invocations = new ArrayList<>();
        JsonNode invocationsNode = node.path("toolInvocations");
        if (invocationsNode.isArray()) {
            for (int i = 0; i < invocationsNode.size(); i++) {
                JsonNode t = invocationsNode.get(i);
                requireObject(t, at + ".toolInvocations[" + i + "]");
                String tool = requireText(t, "tool", at + ".toolInvocations[" + i + "]");
                String status = requireText(t, "status", at + ".toolInvocations[" + i + "]");
                if (!INVOCATION_STATUSES.contains(status)) {
                    fail(at + ".toolInvocations[" + i + "].status 不合法: " + status);
                }
                int min = t.path("min").asInt(1);
                Integer max = t.has("max") ? t.path("max").asInt() : null;
                if (max != null && max < min) fail(at + ".toolInvocations[" + i + "].max 不能小于 min");
                invocations.add(new GoldenCase.ToolInvocationAssertion(tool, status, min, max));
            }
        } else if (!invocationsNode.isMissingNode()) {
            fail(at + ".toolInvocations 必须是数组");
        }
        List<GoldenCase.DbAssertion> db = new ArrayList<>();
        JsonNode dbNode = node.path("db");
        if (dbNode.isArray()) {
            for (int i = 0; i < dbNode.size(); i++) {
                JsonNode d = dbNode.get(i);
                requireObject(d, at + ".db[" + i + "]");
                String name = requireText(d, "name", at + ".db[" + i + "]");
                String probe = requireText(d, "probe", at + ".db[" + i + "]");
                if (!DB_PROBES.contains(probe)) {
                    fail(at + ".db[" + i + "].probe 不在受限词表中: " + probe);
                }
                String expected = nullableText(d, "expected");
                if (("APPROVAL_STATUS".equals(probe) || "MEMORY_CONTENT_CONTAINS".equals(probe))
                        && (expected == null || expected.isBlank())) {
                    fail(at + ".db[" + i + "].expected 在 probe=" + probe + " 时必填");
                }
                int min = d.path("min").asInt(0);
                Integer max = d.has("max") ? d.path("max").asInt() : null;
                if (min < 0) fail(at + ".db[" + i + "].min 不能为负");
                if (max != null && max < min) fail(at + ".db[" + i + "].max 不能小于 min");
                db.add(new GoldenCase.DbAssertion(name, probe, expected, min, max));
            }
        } else if (!dbNode.isMissingNode()) {
            fail(at + ".db 必须是数组");
        }
        return new GoldenCase.Assertions(
                finalStatus,
                List.copyOf(events),
                List.copyOf(invocations),
                List.copyOf(db),
                stringList(node.path("modelRequestContains")),
                stringList(node.path("answerContains")),
                stringList(node.path("forbiddenEvents")),
                stringList(node.path("forbiddenToolExecuted")),
                stringList(node.path("forbiddenErrorCodes")));
    }

    private List<String> stringList(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return List.of();
        if (!node.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        node.forEach(n -> values.add(n.asText()));
        return List.copyOf(values);
    }

    // ---------- 基础校验助手 ----------

    private JsonNode requireField(JsonNode node, String field, String at) {
        if (node == null || !node.has(field)) fail(at + "." + field + " 必填");
        return node.get(field);
    }

    private JsonNode requireField(JsonNode node, String field) {
        return requireField(node, field, "$");
    }

    private void requireObject(JsonNode node, String at) {
        if (node == null || !node.isObject()) fail(at + " 必须是 JSON 对象");
    }

    private String requireText(JsonNode node, String field, String at) {
        JsonNode value = requireField(node, field, at);
        if (!value.isTextual() || value.asText().isBlank()) fail(at + "." + field + " 必须是非空字符串");
        return value.asText();
    }

    private String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) return null;
        return value.asText();
    }

    private <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String at) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException e) {
            fail(at + " 不是合法 " + type.getSimpleName() + ": " + raw);
            return null; // 不可达
        }
    }

    private static Iterable<String> iterable(java.util.Iterator<String> names) {
        List<String> list = new ArrayList<>();
        names.forEachRemaining(list::add);
        return list;
    }

    private static void fail(String message) {
        throw new GoldenCaseFormatException(message);
    }

    private static Set<String> eventTypeNames() {
        Set<String> names = new HashSet<>();
        for (com.shitulelv.aicollab.agent.domain.model.AgentEventType type
                : com.shitulelv.aicollab.agent.domain.model.AgentEventType.values()) {
            names.add(type.name());
        }
        return Set.copyOf(names);
    }

    private static Set<String> runStatusNames() {
        Set<String> names = new HashSet<>();
        for (com.shitulelv.aicollab.agent.domain.model.AgentRunStatus status
                : com.shitulelv.aicollab.agent.domain.model.AgentRunStatus.values()) {
            names.add(status.name());
        }
        return Set.copyOf(names);
    }

    /** 畸形 case 异常：评估套件装配阶段必须失败。 */
    public static final class GoldenCaseFormatException extends RuntimeException {
        public GoldenCaseFormatException(String message) { super(message); }
        public GoldenCaseFormatException(String message, Throwable cause) { super(message, cause); }
    }
}
