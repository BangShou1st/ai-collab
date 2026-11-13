package com.shitulelv.aicollab.agent.infrastructure.mcp.gateway;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MCP Gateway 冻结契约的 canonical JSON / SHA-256 实现。
 *
 * <p>对象键按 Unicode code point 排序；字符串 NFC、标准 JSON 转义且不转义非 ASCII；
 * Hash 对象禁止浮点。该实现不依赖 Jackson 的序列化排序选项，避免配置变化改变冻结 Hash。</p>
 */
final class McpCanonicalJson {

    private static final Comparator<String> CODE_POINT_ORDER = McpCanonicalJson::compareCodePoints;

    private McpCanonicalJson() {
    }

    static byte[] bytes(JsonNode value) {
        StringBuilder output = new StringBuilder();
        append(value, output);
        return output.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String sha256(JsonNode value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes(value));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static void append(JsonNode node, StringBuilder output) {
        if (node == null || node.isNull()) {
            output.append("null");
            return;
        }
        if (node.isObject()) {
            appendObject(node, output);
            return;
        }
        if (node.isArray()) {
            output.append('[');
            boolean first = true;
            for (JsonNode item : node) {
                if (!first) {
                    output.append(',');
                }
                append(item, output);
                first = false;
            }
            output.append(']');
            return;
        }
        if (node.isTextual()) {
            appendString(node.textValue(), output);
            return;
        }
        if (node.isBoolean()) {
            output.append(node.booleanValue());
            return;
        }
        if (node.isIntegralNumber()) {
            output.append(node.bigIntegerValue().toString());
            return;
        }
        if (node.isNumber()) {
            throw new IllegalArgumentException("canonical JSON Hash 对象禁止浮点数");
        }
        throw new IllegalArgumentException("canonical JSON 不支持节点类型: " + node.getNodeType());
    }

    private static void appendObject(JsonNode node, StringBuilder output) {
        List<Map.Entry<String, JsonNode>> fields = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
        while (iterator.hasNext()) {
            fields.add(iterator.next());
        }
        fields.sort(Map.Entry.comparingByKey(CODE_POINT_ORDER));
        output.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonNode> field : fields) {
            if (!first) {
                output.append(',');
            }
            appendString(field.getKey(), output);
            output.append(':');
            append(field.getValue(), output);
            first = false;
        }
        output.append('}');
    }

    private static void appendString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            if (codePoint >= 0xd800 && codePoint <= 0xdfff) {
                throw new IllegalArgumentException("canonical JSON 字符串包含非法代理项");
            }
            switch (codePoint) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (codePoint < 0x20) {
                        output.append("\\u");
                        appendHex(codePoint, output);
                    } else {
                        output.appendCodePoint(codePoint);
                    }
                }
            }
            index += Character.charCount(codePoint);
        }
        output.append('"');
    }

    private static void appendHex(int value, StringBuilder output) {
        char[] digits = "0123456789abcdef".toCharArray();
        output.append(digits[(value >>> 12) & 0xf]);
        output.append(digits[(value >>> 8) & 0xf]);
        output.append(digits[(value >>> 4) & 0xf]);
        output.append(digits[value & 0xf]);
    }

    private static int compareCodePoints(String left, String right) {
        int leftIndex = 0;
        int rightIndex = 0;
        while (leftIndex < left.length() && rightIndex < right.length()) {
            int leftCodePoint = left.codePointAt(leftIndex);
            int rightCodePoint = right.codePointAt(rightIndex);
            if (leftCodePoint != rightCodePoint) {
                return Integer.compare(leftCodePoint, rightCodePoint);
            }
            leftIndex += Character.charCount(leftCodePoint);
            rightIndex += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length(), right.length());
    }
}
