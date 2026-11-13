package com.shitulelv.aicollab.agent.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentApprovalInboxResponse(
        List<AgentApprovalResponse> items,
        OffsetDateTime serverTime,
        OffsetDateTime maxUpdatedAt) {
}
