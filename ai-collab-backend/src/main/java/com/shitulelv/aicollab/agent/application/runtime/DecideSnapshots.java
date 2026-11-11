package com.shitulelv.aicollab.agent.application.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * decide 快照的 canonical JSON 与摘要（W4 冻结契约 §3.2）。
 *
 * <p>canonical JSON 定义：对象键按字典序排序、数组保持顺序、无多余空白、UTF-8。
 * 快照哈希 = {@code "sha256:" + sha256(canonicalJson(快照去掉 snapshotHash))[0:32]}。</p>
 *
 * <p>本类只做纯字符串/JSON 变换，不读取任何业务数据、不写日志。</p>
 */
final class DecideSnapshots {

    private static final ObjectMapper JSON = new ObjectMapper();

    private DecideSnapshots() {
    }

    static String canonicalJson(JsonNode node) {
        StringBuilder builder = new StringBuilder();
        append(node, builder);
        return builder.toString();
    }

    static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unreachable) {
            throw new IllegalStateException("SHA-256 不可用", unreachable);
        }
    }

    private static void append(JsonNode node, StringBuilder builder) {
        if (node == null || node.isNull()) {
            builder.append("null");
            return;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            builder.append('{');
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                String name = names.get(index);
                builder.append(quote(name)).append(':');
                append(node.get(name), builder);
            }
            builder.append('}');
            return;
        }
        if (node.isArray()) {
            builder.append('[');
            for (int index = 0; index < node.size(); index++) {
                if (index > 0) {
                    builder.append(',');
                }
                append(node.get(index), builder);
            }
            builder.append(']');
            return;
        }
        if (node.isTextual()) {
            builder.append(quote(node.asText()));
            return;
        }
        // number / boolean：Jackson 的紧凑表示本身不含空白，且与键顺序无关。
        builder.append(node.toString());
    }

    private static String quote(String raw) {
        try {
            return JSON.writeValueAsString(raw);
        } catch (JsonProcessingException unreachable) {
            throw new IllegalStateException("JSON 字符串转义失败", unreachable);
        }
    }
}
