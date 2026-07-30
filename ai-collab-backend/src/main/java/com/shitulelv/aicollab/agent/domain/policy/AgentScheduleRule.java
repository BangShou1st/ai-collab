package com.shitulelv.aicollab.agent.domain.policy;

import java.time.*;
import java.time.temporal.TemporalAdjusters;

public final class AgentScheduleRule {
    public OffsetDateTime next(
            String frequency, ZoneId zone, LocalTime localTime,
            Integer weeklyDay, OffsetDateTime after) {
        if (!"DAILY".equals(frequency) && !"WEEKLY".equals(frequency)) {
            throw new IllegalArgumentException("frequency 必须是 DAILY 或 WEEKLY");
        }
        ZonedDateTime cursor = after.atZoneSameInstant(zone);
        LocalDate date = cursor.toLocalDate();
        if ("WEEKLY".equals(frequency)) {
            if (weeklyDay == null || weeklyDay < 1 || weeklyDay > 7) {
                throw new IllegalArgumentException("weeklyDay 必须在 1 到 7");
            }
            date = date.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(weeklyDay)));
        }
        ZonedDateTime candidate = ZonedDateTime.of(date, localTime, zone);
        if (!candidate.toInstant().isAfter(after.toInstant())) {
            candidate = "DAILY".equals(frequency)
                    ? candidate.plusDays(1) : candidate.plusWeeks(1);
        }
        return candidate.toOffsetDateTime();
    }
}
