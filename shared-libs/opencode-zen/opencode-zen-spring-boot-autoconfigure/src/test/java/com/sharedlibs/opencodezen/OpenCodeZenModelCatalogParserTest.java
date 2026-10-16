package com.sharedlibs.opencodezen;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenCodeZenModelCatalogParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesExplicitPriceProtocolAndRetirementMetadata() throws Exception {
        var root = mapper.readTree("""
                {
                  "opencode": {
                    "npm": "@ai-sdk/openai-compatible",
                    "models": {
                      "free-chat": {"cost": {"input": 0, "output": 0}},
                      "paid-free-suffix": {"cost": {"input": 0.1, "output": 0.2}},
                      "free-responses": {"cost": {"input": 0, "output": 0}, "provider": {"npm": "@ai-sdk/openai"}},
                      "retired-free": {"cost": {"input": 0, "output": 0}, "deprecated": true},
                      "unknown-price": {}
                    }
                  }
                }
                """);

        var catalog = OpenCodeZenModelCatalogParser.parse(root).orElseThrow();

        assertThat(catalog.get("free-chat").free()).isTrue();
        assertThat(catalog.get("free-chat").protocol()).isEqualTo(ZenModelProtocol.CHAT);
        assertThat(catalog.get("paid-free-suffix").free()).isFalse();
        assertThat(catalog.get("free-responses").free()).isTrue();
        assertThat(catalog.get("free-responses").protocol()).isEqualTo(ZenModelProtocol.RESPONSES);
        assertThat(catalog.get("retired-free").retired()).isTrue();
        assertThat(catalog.get("unknown-price").free()).isFalse();
    }

    @Test
    void failsClosedWhenAvailableCatalogOmitsModelAndUsesSuffixOnlyAsFallback() {
        ZenModelMetadata knownFree = new ZenModelMetadata("free", true, false, ZenModelProtocol.CHAT);

        assertThat(OpenCodeZenFreeModelPolicy.isFreeModel("free", true, true, knownFree)).isTrue();
        assertThat(OpenCodeZenFreeModelPolicy.isFreeModel("unknown-free", true, true, null)).isFalse();
        assertThat(OpenCodeZenFreeModelPolicy.isFreeModel("unknown-free", true, false, null)).isTrue();
        assertThat(OpenCodeZenFreeModelPolicy.isChatModelForListing("unknown", true, true, null)).isFalse();
        assertThat(OpenCodeZenFreeModelPolicy.isVerifiedChatModel("free", true, true, knownFree)).isTrue();
        assertThat(OpenCodeZenFreeModelPolicy.isVerifiedChatModel("free", true, false, knownFree)).isFalse();
    }
}
