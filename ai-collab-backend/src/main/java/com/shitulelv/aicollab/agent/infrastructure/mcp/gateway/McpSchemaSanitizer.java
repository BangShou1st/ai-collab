package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具描述与 inputSchema 进入模型上下文前的归一化/清洗。
 *
 * <p>清洗不是安全证明：输出仍由 Gateway 标记为外部不可信数据。任何限制失败都拒绝
 * 整个工具（或连接），不做静默截断。</p>
 */
public class McpSchemaSanitizer {

    static final int MAX_DESCRIPTION_CODE_POINTS = 512;
    static final int MAX_TOOL_COMPONENT_CHARS = 128;
    static final int MAX_SCHEMA_DEPTH = 16;
    static final int MAX_SCHEMA_NODES = 2000;
    static final int MAX_SCHEMA_CANONICAL_BYTES = 32 * 1024;

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern INJECTION = Pattern.compile(
            "(?is)(?:ignore|disregard|forget|override)\\s+(?:all\\s+)?"
                    + "(?:previous|prior|above|system|developer)\\s+"
                    + "(?:instructions?|messages?|prompts?|rules?)"
                    + "|(?:system|developer)\\s*(?:prompt|message|instructions?)\\s*:"
                    + "|you\\s+are\\s+now\\s+(?:the\\s+)?system"
                    + "|忽略|无视|忘记|覆盖|绕过"
                    + "(?:之前|以上|系统|开发者|安全|审批)?(?:的)?(?:全部)?"
                    + "(?:指令|提示词|规则|审批|限制)"
                    + "|系统提示词|开发者指令|越权执行");
    private static final Set<String> SCRIPT_FIELDS = Set.of(
            "script", "javascript", "shell", "exec", "eval", "code", "command");
    private static final String REMOVED_INSTRUCTION = "[REMOVED_INSTRUCTION]";

    private final ObjectMapper json;

    public McpSchemaSanitizer(ObjectMapper json) {
        this.json = json;
    }

    /** 返回清洗后的描述；超长或空结构按契约拒绝整个工具。 */
    public Optional<String> description(String raw) {
        String normalized = normalizeText(raw);
        if (normalized.codePointCount(0, normalized.length()) > MAX_DESCRIPTION_CODE_POINTS) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    /**
     * 与 Python {@code normalize_tool_component} 同语义：NFC→小写，仅连字符/点/空格
     * 转为下划线，连续下划线折叠，去首尾下划线；其他字符、空值或超长直接拒绝。
     */
    public Optional<String> component(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        StringBuilder safe = new StringBuilder();
        boolean previousUnderscore = false;
        for (int index = 0; index < normalized.length(); ) {
            int codePoint = normalized.codePointAt(index);
            if ((codePoint >= 'a' && codePoint <= 'z')
                    || (codePoint >= '0' && codePoint <= '9')) {
                safe.appendCodePoint(codePoint);
                previousUnderscore = false;
            } else if (codePoint == '_' || codePoint == '-' || codePoint == '.' || codePoint == ' ') {
                if (!previousUnderscore) {
                    safe.append('_');
                }
                previousUnderscore = true;
            } else {
                return Optional.empty();
            }
            index += Character.charCount(codePoint);
        }
        String value = trimUnderscores(safe.toString());
        return value.isEmpty() || value.length() > MAX_TOOL_COMPONENT_CHARS
                ? Optional.empty() : Optional.of(value);
    }

    public String normalizeText(String raw) {
        if (raw == null) {
            return "";
        }
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);
        StringBuilder withoutControls = new StringBuilder(nfc.length());
        for (int index = 0; index < nfc.length(); ) {
            int codePoint = nfc.codePointAt(index);
            if (codePoint == '\n' || codePoint == '\t') {
                withoutControls.append(' ');
            } else if (!Character.isISOControl(codePoint) && Character.getType(codePoint) != Character.FORMAT) {
                withoutControls.appendCodePoint(codePoint);
            }
            index += Character.charCount(codePoint);
        }
        String collapsed = WHITESPACE.matcher(withoutControls).replaceAll(" ").strip();
        return INJECTION.matcher(collapsed).replaceAll(REMOVED_INSTRUCTION);
    }

    /**
     * 复制并清洗 inputSchema。方法不修改原始节点；失败抛 {@link SchemaRejectedException}。
     */
    public ObjectNode schema(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new SchemaRejectedException();
        }
        State state = new State();
        JsonNode sanitized = clean(raw, 0, state);
        if (!(sanitized instanceof ObjectNode object)) {
            throw new SchemaRejectedException();
        }
        if (McpCanonicalJson.bytes(object).length > MAX_SCHEMA_CANONICAL_BYTES) {
            throw new SchemaRejectedException();
        }
        return object;
    }

    private JsonNode clean(JsonNode node, int depth, State state) {
        if (node == null || depth > MAX_SCHEMA_DEPTH) {
            throw new SchemaRejectedException();
        }
        state.nodes++;
        if (state.nodes > MAX_SCHEMA_NODES) {
            throw new SchemaRejectedException();
        }
        if (node.isObject()) {
            ObjectNode copy = json.createObjectNode();
            Set<String> keys = new HashSet<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = normalizeText(field.getKey());
                String lower = key.toLowerCase(Locale.ROOT);
                if (key.isBlank() || !keys.add(key) || SCRIPT_FIELDS.contains(lower)
                        || !validReferenceField(lower, field.getValue())) {
                    throw new SchemaRejectedException();
                }
                copy.set(key, clean(field.getValue(), depth + 1, state));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = json.createArrayNode();
            for (JsonNode item : node) {
                copy.add(clean(item, depth + 1, state));
            }
            return copy;
        }
        if (node.isTextual()) {
            return json.getNodeFactory().textNode(normalizeText(node.textValue()));
        }
        if (node.isNumber() && !node.isIntegralNumber()) {
            throw new SchemaRejectedException();
        }
        if (node.isNull() || node.isBoolean() || node.isIntegralNumber()) {
            return node.deepCopy();
        }
        throw new SchemaRejectedException();
    }

    private static boolean validReferenceField(String key, JsonNode value) {
        if (!"$ref".equals(key) && !"$dynamicref".equals(key)) {
            return true;
        }
        return value != null && value.isTextual() && value.textValue().startsWith("#");
    }

    private static String trimUnderscores(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '_') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '_') {
            end--;
        }
        return value.substring(start, end);
    }

    public static final class SchemaRejectedException extends RuntimeException {
    }

    private static final class State {
        private int nodes;
    }
}
