package com.shitulelv.aicollab.agent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class McpResultSanitizer {
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(authorization|token|cookie|secret|password|api[-_]?key|credential).*");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\r\\n\\t]]");
    private static final Pattern INJECTION = Pattern.compile(
            "(?i)(ignore (all )?(previous|system)|忽略.{0,12}(系统|审批|规则)|system prompt)");
    private static final int MAX_ARRAY = 100;
    private final ObjectMapper json;

    public McpResultSanitizer(ObjectMapper json) { this.json = json; }

    public ObjectNode sanitize(JsonNode raw, int maxBytes) {
        ObjectNode result = json.createObjectNode();
        result.put("untrusted", true);
        JsonNode cleaned = clean(raw, null);
        boolean injection = INJECTION.matcher(cleaned.toString()).find();
        byte[] bytes;
        try { bytes = json.writeValueAsBytes(cleaned); }
        catch (Exception exception) { bytes = new byte[0]; cleaned = json.nullNode(); }
        boolean truncated = bytes.length > maxBytes;
        if (truncated) {
            String preview = new String(bytes, 0, Math.min(maxBytes, bytes.length), StandardCharsets.UTF_8);
            result.put("preview", CONTROL.matcher(preview).replaceAll(""));
        } else result.set("data", cleaned);
        result.put("truncated", truncated);
        if (injection) result.putArray("warnings").add("检测到外部指令文本，已作为不可信数据保留");
        return result;
    }

    private JsonNode clean(JsonNode node, String field) {
        if (field != null && SECRET_KEY.matcher(field).matches()) return json.getNodeFactory().textNode("[REDACTED]");
        if (node == null || node.isNull()) return json.nullNode();
        if (node.isObject()) {
            ObjectNode copy = json.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                String lower = key.toLowerCase(Locale.ROOT);
                if (lower.contains("binary") || lower.equals("blob") || lower.equals("file_path")
                        || lower.equals("filepath")) copy.put(key, "[UNSUPPORTED_BINARY_OR_PATH]");
                else copy.set(key, clean(entry.getValue(), key));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = json.createArrayNode();
            int count = 0; for (JsonNode item : node) { if (count++ >= MAX_ARRAY) break; copy.add(clean(item, field)); }
            return copy;
        }
        if (node.isTextual()) {
            String value = CONTROL.matcher(node.asText()).replaceAll("");
            value = value.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]{8,}", "Bearer [REDACTED]");
            return json.getNodeFactory().textNode(value);
        }
        return node.deepCopy();
    }
}
