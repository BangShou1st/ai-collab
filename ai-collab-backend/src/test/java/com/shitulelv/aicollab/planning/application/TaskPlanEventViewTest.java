package com.shitulelv.aicollab.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.planning.infrastructure.TaskPlanEventRecord;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPlanEventViewTest {

    @Test
    void eventViewKeepsPageAuditFieldsWithoutExposingActorOrHashes() {
        TaskPlanEventRecord record = new TaskPlanEventRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "PLAN_VERSION_EDITED",
                List.of("tasks.t-1.dueDate"),
                List.of("TASK:t-1"),
                "before-sensitive-hash",
                "after-sensitive-hash",
                List.of("TASK_DATE_INVALID"),
                OffsetDateTime.parse("2026-07-28T14:00:00+08:00"));

        TaskPlanEventView view = TaskPlanEventView.from(record);
        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(view);

        assertThat(json.has("id")).isTrue();
        assertThat(json.has("fromVersionId")).isTrue();
        assertThat(json.has("toVersionId")).isTrue();
        assertThat(json.get("changedFields").get(0).asText()).isEqualTo("tasks.t-1.dueDate");
        assertThat(json.get("changedTargets").get(0).asText()).isEqualTo("TASK:t-1");
        assertThat(json.get("issueCodes").get(0).asText()).isEqualTo("TASK_DATE_INVALID");
        assertThat(json.has("actorId")).isFalse();
        assertThat(json.has("beforeHash")).isFalse();
        assertThat(json.has("afterHash")).isFalse();
    }
}
