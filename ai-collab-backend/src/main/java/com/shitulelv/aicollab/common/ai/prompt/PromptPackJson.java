package com.shitulelv.aicollab.common.ai.prompt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * PromptPack 的线上 JSON 形状（设计 §7.2）。
 *
 * <p>仓库 manifest 与 {@code GET /v1/prompts/manifest} 共用 {@link Manifest}：
 * 线上响应不含 {@code variables}（Python 侧只把它用于仓库文件与服务端校验），
 * 未知字段一律忽略，保证"只加字段、不改语义"的契约演进。</p>
 */
final class PromptPackJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PromptPackJson() {
    }

    /** {@code GET /v1/prompts/manifest} 响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Manifest(Integer schemaVersion, List<Entry> prompts) {
    }

    /** manifest 条目：仓库文件额外带 {@code variables}，线上响应省略。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Entry(String key, String version, String sha256, Integer chars, List<String> variables) {
    }

    /** {@code GET /v1/prompts/{key}/{version}} 响应。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(Integer schemaVersion, String key, String version, String sha256,
                    String text, List<String> variables) {
    }

    static Manifest readManifest(byte[] bytes) {
        try {
            return MAPPER.readValue(bytes, Manifest.class);
        } catch (IOException invalid) {
            // 异常消息可能带 JSON 片段，这里只暴露固定短语。
            throw new PromptAssetSupport.AssetException("manifest not valid JSON");
        }
    }
}
