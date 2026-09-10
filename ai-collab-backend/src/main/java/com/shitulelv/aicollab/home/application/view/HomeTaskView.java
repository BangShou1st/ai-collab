package com.shitulelv.aicollab.home.application.view;

import java.time.LocalDate;
import java.util.UUID;

public record HomeTaskView(UUID id, UUID projectId, String projectName, String title,
        String status, String priority, LocalDate dueDate) {
}
