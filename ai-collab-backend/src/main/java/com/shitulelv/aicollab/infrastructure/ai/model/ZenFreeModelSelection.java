package com.shitulelv.aicollab.infrastructure.ai.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zen 免费且可执行模型的选择结果：可执行（Chat 协议）模型列表 +
 * 因原生协议不受支持而排除的模型及其协议。
 */
public record ZenFreeModelSelection(List<String> models, Map<String, ZenModelProtocol> unsupportedProtocols) {

    public ZenFreeModelSelection {
        models = List.copyOf(models);
        unsupportedProtocols = Map.copyOf(unsupportedProtocols);
    }

    public static ZenFreeModelSelection empty() {
        return new ZenFreeModelSelection(List.of(), Map.of());
    }

    /**
     * 纯函数选择规则，不触网。
     *
     * <p>可用性以当前上游 {@code GET /models} 列表为基础；是否免费与原生协议由可信模型目录的元数据判定：</p>
     * <ol>
     *   <li>只有出现在当前上游列表中的模型才可能入选。</li>
     *   <li>已退役 / 已禁用（deprecated / deprecated_at / retirement_date / status|lifecycle）直接排除。</li>
     *   <li>已知输入或输出价格非零 → 即使名字带 {@code -free} 也排除。</li>
     *   <li>可信的零价格（input=0 且 output=0）→ 即使名字不带 {@code -free} 也可入选。</li>
     *   <li>原生协议必须能判定为 Chat Completions；Responses / Anthropic / 协议未知一律不作为可执行模型，
     *       并记录到 {@code unsupportedProtocols}，以便配置阶段给出明确的协议不匹配原因。</li>
     *   <li>可信目录不可用时保守回退：只保留带 {@code -free} 后缀的模型（与改造前的行为一致），
     *       绝不把价格未知的模型当作免费，也不放行协议未知的模型。</li>
     * </ol>
     *
     * @param upstreamListing 当前上游 {@code GET /models} 响应；不可用或结构异常时返回空结果
     * @param trustedCatalog  可信公开模型目录（{@code models.opencode.ai/api.json}）；不可用时返回 {@code null}
     */
    static ZenFreeModelSelection select(JsonNode upstreamListing, JsonNode trustedCatalog) {
        JsonNode data = upstreamListing == null ? null : upstreamListing.path("data");
        if (data == null || !data.isArray()) {
            return empty();
        }
        boolean metadataAvailable = trustedCatalog != null && trustedCatalog.isObject();
        JsonNode provider = metadataAvailable ? trustedCatalog.path("opencode") : null;
        JsonNode entries = provider == null ? null : provider.path("models");
        if (entries != null && !entries.isObject()) {
            entries = null;
        }
        String providerNpm = provider == null ? null : provider.path("provider").path("npm").asText(null);

        List<String> chatModels = new ArrayList<>();
        Map<String, ZenModelProtocol> unsupported = new LinkedHashMap<>();
        for (JsonNode node : data) {
            String rawId = node.path("id").asText(null);
            if (rawId == null || rawId.isBlank()) {
                continue;
            }
            String modelId = rawId.strip();
            JsonNode entry = entries == null ? null : entries.path(modelId);
            if (entry != null && !entry.isObject()) {
                entry = null;
            }
            if (isRetired(entry)) {
                continue;
            }
            if (!isFree(modelId, entry)) {
                continue;
            }
            ZenModelProtocol protocol = resolveProtocol(entry, providerNpm, modelId);
            if (protocol == ZenModelProtocol.CHAT) {
                chatModels.add(modelId);
            } else {
                unsupported.put(modelId, protocol);
            }
        }
        return new ZenFreeModelSelection(chatModels, unsupported);
    }

    private static boolean isRetired(JsonNode entry) {
        if (entry == null) {
            return false;
        }
        if (entry.path("deprecated").asBoolean(false)) {
            return true;
        }
        if (present(entry, "deprecated_at") || present(entry, "retirement_date")) {
            return true;
        }
        String status = entry.path("status").asText(entry.path("lifecycle").asText("active"));
        return "deprecated".equals(status) || "retired".equals(status) || "disabled".equals(status);
    }

    private static boolean present(JsonNode entry, String field) {
        JsonNode value = entry.path(field);
        return !value.isMissingNode() && !value.isNull();
    }

    private static boolean isFree(String modelId, JsonNode entry) {
        if (isKnownZeroPrice(entry)) {
            return true;
        }
        if (hasPositivePrice(entry)) {
            // 已知收费：名字里的 -free 不足以判定免费。
            return false;
        }
        return modelId.endsWith("-free");
    }

    private static boolean isKnownZeroPrice(JsonNode entry) {
        JsonNode cost = costOf(entry);
        return cost != null && isZero(cost.path("input")) && isZero(cost.path("output"));
    }

    private static boolean hasPositivePrice(JsonNode entry) {
        JsonNode cost = costOf(entry);
        return cost != null && (isPositive(cost.path("input")) || isPositive(cost.path("output")));
    }

    private static JsonNode costOf(JsonNode entry) {
        if (entry == null) {
            return null;
        }
        JsonNode cost = entry.path("cost");
        return cost.isObject() ? cost : null;
    }

    private static boolean isZero(JsonNode value) {
        return value.isNumber() && value.asDouble() == 0d;
    }

    private static boolean isPositive(JsonNode value) {
        return value.isNumber() && value.asDouble() > 0d;
    }

    private static ZenModelProtocol resolveProtocol(JsonNode entry, String providerNpm, String modelId) {
        String npm = entry == null ? null : entry.path("provider").path("npm").asText(null);
        ZenModelProtocol protocol = ZenModelProtocol.fromNpmPackage(npm);
        if (protocol == null) {
            protocol = ZenModelProtocol.fromNpmPackage(providerNpm);
        }
        if (protocol == null && modelId.endsWith("-free")) {
            // 保守回退：免费后缀模型按既有 Chat 路径处理（与改造前行为一致）。
            protocol = ZenModelProtocol.CHAT;
        }
        return protocol == null ? ZenModelProtocol.UNKNOWN : protocol;
    }
}
