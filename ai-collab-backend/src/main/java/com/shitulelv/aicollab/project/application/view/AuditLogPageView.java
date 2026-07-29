package com.shitulelv.aicollab.project.application.view;

import java.util.List;

public record AuditLogPageView(
        List<AuditLogItemView> items,
        int page,
        int size,
        long total) {
}
