package com.shitulelv.aicollab.agent.domain.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AgentPageContextTest {

    @Test
    void emptyPageContext() {
        AgentPageContext page = AgentPageContext.empty();
        assertThat(page.route()).isNull();
        assertThat(page.selectedTaskId()).isNull();
        assertThat(page.selectedMilestoneId()).isNull();
        assertThat(page.selectedDocumentId()).isNull();
        assertThat(page.filters()).isEmpty();
    }

    @Test
    void validPageContext() {
        UUID taskId = UUID.randomUUID();
        AgentPageContext page = new AgentPageContext(
                "TASK_DETAIL", taskId, null, null, Map.of());

        assertThat(page.route()).isEqualTo("TASK_DETAIL");
        assertThat(page.selectedTaskId()).isEqualTo(taskId);
        assertThat(page.hasSelection()).isTrue();
    }

    @Test
    void nullFiltersBecomesEmpty() {
        AgentPageContext page = new AgentPageContext(
                "TASK_BOARD", null, null, null, null);
        assertThat(page.filters()).isEmpty();
    }

    @Test
    void filtersAreCopied() {
        Map<String, JsonNode> original = new java.util.HashMap<>();
        original.put("key", new ObjectMapper().createObjectNode());
        AgentPageContext page = new AgentPageContext(
                "TASK_BOARD", null, null, null, original);

        assertThat(page.filters()).isEqualTo(original);
        assertThat(page.filters()).isNotSameAs(original);
    }

    @Test
    void tooManyFiltersThrows() {
        Map<String, JsonNode> filters = new java.util.HashMap<>();
        ObjectMapper json = new ObjectMapper();
        for (int i = 0; i < 25; i++) {
            filters.put("filter" + i, json.createObjectNode());
        }

        assertThatThrownBy(() -> new AgentPageContext(
                "TASK_BOARD", null, null, null, filters))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filters 过多");
    }

    @Test
    void hasSelectionReturnsFalseWhenEmpty() {
        AgentPageContext page = AgentPageContext.empty();
        assertThat(page.hasSelection()).isFalse();
    }

    @Test
    void hasSelectionReturnsTrueForMilestone() {
        AgentPageContext page = new AgentPageContext(
                "MILESTONE_LIST", null, UUID.randomUUID(), null, null);
        assertThat(page.hasSelection()).isTrue();
    }

    @Test
    void hasSelectionReturnsTrueForDocument() {
        AgentPageContext page = new AgentPageContext(
                "DOCUMENT_DETAIL", null, null, UUID.randomUUID(), null);
        assertThat(page.hasSelection()).isTrue();
    }

    @Test
    void selectedPlanIdIsPartOfControlledContext() {
        assertThat(AgentPageContext.class.getRecordComponents()).hasSize(6);
        assertThat(AgentPageContext.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("selectedPlanId");
    }
}
