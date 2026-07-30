package com.shitulelv.aicollab.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JsonApiValueTest {
    @Test
    void convertsInternalJacksonTreeToPlainApiValues() throws Exception {
        var tree = new ObjectMapper().readTree("""
                {"title":"task","priority":"HIGH","tags":["agent","qa"],"count":2}
                """);

        Object converted = JsonApiValue.from(tree);

        assertThat(converted).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) converted;
        assertThat(values)
                .containsEntry("title", "task")
                .containsEntry("priority", "HIGH")
                .containsEntry("tags", List.of("agent", "qa"));
    }
}
