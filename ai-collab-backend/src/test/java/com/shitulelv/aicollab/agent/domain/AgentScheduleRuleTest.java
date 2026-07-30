package com.shitulelv.aicollab.agent.domain;

import com.shitulelv.aicollab.agent.domain.policy.AgentScheduleRule;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScheduleRuleTest {
    private final AgentScheduleRule rule = new AgentScheduleRule();

    @Test
    void dailyScheduleUsesRequestedIanaZoneAcrossUtcBoundary() {
        OffsetDateTime next = rule.next(
                "DAILY", ZoneId.of("Asia/Shanghai"), LocalTime.of(9, 0), null,
                OffsetDateTime.parse("2026-07-29T23:00:00Z"));

        assertThat(next.toInstant().toString()).isEqualTo("2026-07-30T01:00:00Z");
    }

    @Test
    void weeklyScheduleMovesToRequestedWeekday() {
        OffsetDateTime next = rule.next(
                "WEEKLY", ZoneId.of("UTC"), LocalTime.of(9, 30), 1,
                OffsetDateTime.parse("2026-07-29T10:00:00Z"));

        assertThat(next).isEqualTo(OffsetDateTime.parse("2026-08-03T09:30:00Z"));
    }

    @Test
    void rejectsWeeklyRuleWithoutDay() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rule.next(
                "WEEKLY", ZoneId.of("UTC"), LocalTime.NOON, null,
                OffsetDateTime.parse("2026-07-29T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
