package com.sharedlibs.opencodezen;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Parses the public models.opencode.ai catalog into shared policy facts. */
public final class OpenCodeZenModelCatalogParser {

    private OpenCodeZenModelCatalogParser() {
    }

    /**
     * Returns empty when the provider/catalog shape is not authoritative. Missing
     * model pricing remains "not proven free"; it never becomes free by default.
     */
    public static Optional<Map<String, ZenModelMetadata>> parse(JsonNode root) {
        JsonNode provider = root == null ? null : root.get("opencode");
        if (provider == null || !provider.isObject()) {
            return Optional.empty();
        }
        JsonNode models = provider.get("models");
        if (models == null || !models.isObject()) {
            return Optional.empty();
        }

        ZenModelProtocol providerProtocol =
                ZenModelProtocol.fromNpmPackage(text(provider.get("npm")));
        Map<String, ZenModelMetadata> byId = new LinkedHashMap<>();
        models.fieldNames().forEachRemaining(id -> {
            JsonNode entry = models.get(id);
            if (entry == null || !entry.isObject()) {
                return;
            }
            JsonNode modelNpm = entry.path("provider").path("npm");
            ZenModelProtocol protocol = modelNpm.isTextual() && !modelNpm.asText().isBlank()
                    ? ZenModelProtocol.fromNpmPackage(modelNpm.asText())
                    : providerProtocol;
            byId.put(id, new ZenModelMetadata(
                    id,
                    OpenCodeZenFreeModelPolicy.hasExplicitZeroPrice(entry),
                    OpenCodeZenFreeModelPolicy.isRetired(entry),
                    protocol));
        });
        return Optional.of(Map.copyOf(byId));
    }

    private static String text(JsonNode node) {
        return node == null || !node.isTextual() ? null : node.asText();
    }
}