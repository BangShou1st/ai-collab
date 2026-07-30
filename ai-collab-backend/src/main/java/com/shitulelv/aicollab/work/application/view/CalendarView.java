package com.shitulelv.aicollab.work.application.view;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record CalendarView(
        List<CalendarEvent> events
) {
    public record CalendarEvent(
            UUID id,
            String title,
            String type,
            LocalDate date,
            String status
    ) {}
}
