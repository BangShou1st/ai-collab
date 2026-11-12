package com.shitulelv.aicollab.infrastructure.ai.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RerankSettingsSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RerankSettingsSchema schema = new RerankSettingsSchema(objectMapper);

    @Test
    void fillsFrozenDefaults() {
        RerankSettings settings = schema.validate(objectMapper.createObjectNode());

        assertThat(settings.protocol()).isEqualTo("LLAMACPP_RERANK");
        assertThat(settings.timeoutMs()).isEqualTo(3000);
        assertThat(settings.maxCandidates()).isEqualTo(50);
        assertThat(settings.batchSize()).isEqualTo(8);
        assertThat(settings.maxLength()).isEqualTo(2048);
        assertThat(settings.scoreType()).isEqualTo("sigmoid");
        assertThat(settings.topK()).isEqualTo(8);
        assertThat(settings.healthPath()).isEqualTo("/health");
        assertThat(settings.fallback()).isEqualTo("java-heuristic");
    }

    @Test
    void acceptsOnlyFrozenFields() throws Exception {
        RerankSettings settings = schema.validate(objectMapper.readTree("""
                {
                  "protocol": "LLAMACPP_RERANK",
                  "timeoutMs": 1200,
                  "maxCandidates": 20,
                  "batchSize": 4,
                  "maxLength": 1024,
                  "scoreType": "logit",
                  "topK": 5,
                  "healthPath": "/ready",
                  "fallback": "java-heuristic"
                }
                """));

        assertThat(settings.timeoutMs()).isEqualTo(1200);
        assertThat(settings.scoreType()).isEqualTo("logit");
    }

    @Test
    void rejectsChatAndEmbeddingFields() throws Exception {
        for (String field : new String[]{"maxOutputTokens", "streaming", "toolCalling", "dimension"}) {
            assertThatThrownBy(() -> schema.validate(objectMapper.readTree(
                    "{\"" + field + "\": true}")))
                    .isInstanceOf(RerankSettingsSchema.RerankSettingsInvalidException.class)
                    .hasMessageContaining("不允许字段");
        }
    }

    @Test
    void rejectsBoundsAndTypeDrift() throws Exception {
        assertThatThrownBy(() -> schema.validate(objectMapper.readTree("{\"timeoutMs\": 99}")))
                .hasMessageContaining("最小值");
        assertThatThrownBy(() -> schema.validate(objectMapper.readTree("{\"maxCandidates\": 51}")))
                .hasMessageContaining("最大值");
        assertThatThrownBy(() -> schema.validate(objectMapper.readTree("{\"timeoutMs\": 1.5}")))
                .hasMessageContaining("整数");
        assertThatThrownBy(() -> schema.validate(objectMapper.readTree("{\"scoreType\": \"ratio\"}")))
                .hasMessageContaining("允许集合");
        assertThatThrownBy(() -> schema.validate(objectMapper.readTree("{\"healthPath\": \"health\"}")))
                .hasMessageContaining("格式约束");
    }

    @Test
    void rejectsBatchLargerThanCandidateLimit() throws Exception {
        assertThatThrownBy(() -> schema.validate(objectMapper.readTree(
                "{\"maxCandidates\": 4, \"batchSize\": 5}")))
                .hasMessageContaining("batchSize");
    }
}
