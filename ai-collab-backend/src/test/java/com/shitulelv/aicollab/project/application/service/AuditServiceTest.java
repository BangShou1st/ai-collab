package com.shitulelv.aicollab.project.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shitulelv.aicollab.project.infrastructure.mapper.AuditLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private static final UUID PROJECT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID TASK_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");

    @Test
    void writesValidBoundedJsonForStructuredDetail() throws Exception {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AuditService service = new AuditService(mapper, objectMapper);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);

        service.write(
                PROJECT_ID,
                USER_ID,
                "TASK_CREATED",
                "TASK",
                TASK_ID,
                Map.of("title", "A".repeat(500), "count", 2));

        verify(mapper).insertWithDetail(
                any(),
                eq(PROJECT_ID),
                eq(USER_ID),
                eq("TASK_CREATED"),
                eq("TASK"),
                eq(TASK_ID),
                detail.capture(),
                any());
        JsonNode parsed = objectMapper.readTree(detail.getValue());
        assertThat(parsed.get("title").asText()).endsWith("…").hasSize(201);
        assertThat(parsed.get("count").asInt()).isEqualTo(2);
        assertThat(detail.getValue().length()).isLessThanOrEqualTo(4096);
    }

    @Test
    void writesEmptyObjectForEmptyDetail() {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        AuditService service = new AuditService(mapper, new ObjectMapper());
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);

        service.write(PROJECT_ID, USER_ID, "TASK_CREATED", "TASK", TASK_ID, Map.of());

        verify(mapper).insertWithDetail(
                any(), eq(PROJECT_ID), eq(USER_ID), eq("TASK_CREATED"), eq("TASK"),
                eq(TASK_ID), detail.capture(), any());
        assertThat(detail.getValue()).isEqualTo("{}");
    }

    @Test
    void serializationFailureFallsBackToEmptyObject() throws Exception {
        AuditLogMapper mapper = mock(AuditLogMapper.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("无法序列化") { });
        AuditService service = new AuditService(mapper, objectMapper);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);

        service.write(
                PROJECT_ID,
                USER_ID,
                "TASK_CREATED",
                "TASK",
                TASK_ID,
                Map.of("title", "任务"));

        verify(mapper).insertWithDetail(
                any(), eq(PROJECT_ID), eq(USER_ID), eq("TASK_CREATED"), eq("TASK"),
                eq(TASK_ID), detail.capture(), any());
        assertThat(detail.getValue()).isEqualTo("{}");
    }
}
