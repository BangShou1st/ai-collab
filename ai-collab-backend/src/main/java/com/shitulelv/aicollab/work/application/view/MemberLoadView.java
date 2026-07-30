package com.shitulelv.aicollab.work.application.view;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record MemberLoadView(
        List<MemberLoad> members
) {
    public record MemberLoad(
            UUID userId,
            String displayName,
            int totalTasks,
            int completedTasks,
            int inProgressTasks,
            int overdueTasks,
            BigDecimal totalEstimateHours,
            BigDecimal completedHours
    ) {}
}
