package com.shitulelv.aicollab.agent.application.view;

import java.time.OffsetDateTime;
import java.util.List;

public record AgentApprovalInboxView(
        List<AgentApprovalView> items,
        OffsetDateTime serverTime,
        OffsetDateTime maxUpdatedAt) {
}
