package com.shitulelv.aicollab.agent.api.controller;

import com.shitulelv.aicollab.agent.api.dto.AgentApprovalInboxResponse;
import com.shitulelv.aicollab.agent.application.AgentApprovalService;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalInboxView;
import com.shitulelv.aicollab.agent.application.view.AgentApprovalView;
import com.shitulelv.aicollab.agent.domain.model.AgentProposalFamily;
import com.shitulelv.aicollab.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentApprovalControllerTest {
    private final AgentApprovalService service = mock(AgentApprovalService.class);
    private final AgentApprovalController controller = new AgentApprovalController(service);
    private final UUID projectId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Jwt jwt = Jwt.withTokenValue("test")
            .header("alg", "none").subject(userId.toString()).build();

    @Test
    void returnsInboxEnvelopeAndStableEtagThenNotModified() {
        OffsetDateTime updatedAt = OffsetDateTime.ofInstant(
                Instant.parse("2026-11-14T02:03:04Z"), ZoneOffset.UTC);
        AgentApprovalView approval = mock(AgentApprovalView.class);
        when(approval.id()).thenReturn(UUID.randomUUID());
        when(approval.projectId()).thenReturn(projectId);
        when(approval.runId()).thenReturn(UUID.randomUUID());
        when(approval.stepId()).thenReturn(UUID.randomUUID());
        when(approval.toolName()).thenReturn("create_task_after_approval");
        when(approval.arguments()).thenReturn(null);
        when(approval.diff()).thenReturn(null);
        when(approval.status()).thenReturn("PENDING");
        when(approval.requesterId()).thenReturn(userId);
        when(approval.expiresAt()).thenReturn(updatedAt.plusHours(1));
        when(approval.createdAt()).thenReturn(updatedAt.minusMinutes(1));
        when(approval.version()).thenReturn(2);
        when(approval.revision()).thenReturn(1);
        when(approval.updatedAt()).thenReturn(updatedAt);
        when(approval.sessionId()).thenReturn(UUID.randomUUID());
        when(approval.proposalFamily()).thenReturn(AgentProposalFamily.TASK_CREATE);
        when(approval.subjectKey()).thenReturn(UUID.randomUUID());
        when(approval.nonce()).thenReturn("nonce");
        when(service.inbox(projectId, userId, "PENDING", null))
                .thenReturn(new AgentApprovalInboxView(List.of(approval), updatedAt, updatedAt));

        ResponseEntity<ApiResponse<AgentApprovalInboxResponse>> first =
                controller.list(projectId, "PENDING", null, null, jwt);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getHeaders().getETag()).isNotBlank();
        assertThat(first.getBody()).isNotNull();
        assertThat(first.getBody().data().items()).hasSize(1);
        assertThat(first.getBody().data().serverTime()).isEqualTo(updatedAt);
        assertThat(first.getBody().data().maxUpdatedAt()).isEqualTo(updatedAt);

        ResponseEntity<ApiResponse<AgentApprovalInboxResponse>> second =
                controller.list(projectId, "PENDING", null, first.getHeaders().getETag(), jwt);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(second.getBody()).isNull();
        assertThat(second.getHeaders().getETag()).isEqualTo(first.getHeaders().getETag());
    }

    @Test
    void forwardsStatusAndUpdatedSinceToService() {
        OffsetDateTime since = OffsetDateTime.parse("2026-11-14T02:03:04Z");
        OffsetDateTime serverTime = since.plusSeconds(5);
        when(service.inbox(projectId, userId, "PENDING", since))
                .thenReturn(new AgentApprovalInboxView(List.of(), serverTime, null));

        ResponseEntity<ApiResponse<AgentApprovalInboxResponse>> response =
                controller.list(projectId, "PENDING", since, null, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().data().items()).isEmpty();
        assertThat(response.getBody().data().maxUpdatedAt()).isNull();
        verify(service).inbox(projectId, userId, "PENDING", since);
    }
}
