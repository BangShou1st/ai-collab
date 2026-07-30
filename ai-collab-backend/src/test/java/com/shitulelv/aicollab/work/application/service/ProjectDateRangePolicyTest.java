package com.shitulelv.aicollab.work.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.shitulelv.aicollab.common.exception.BusinessException;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ProjectDateRangePolicyTest {

    @Test
    void rejectsWorkDatesOutsideTheProjectRange() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID projectId = UUID.randomUUID();
        when(jdbc.queryForObject(
                eq("SELECT start_date,due_date FROM project WHERE id=?"),
                any(RowMapper.class),
                eq(projectId)))
                .thenReturn(new LocalDate[] {
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 31)
                });
        ProjectDateRangePolicy policy = new ProjectDateRangePolicy(jdbc);

        assertThatThrownBy(() -> policy.validate(
                projectId,
                LocalDate.of(2026, 7, 31),
                LocalDate.of(2026, 8, 20),
                null))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> org.assertj.core.api.Assertions.assertThat(error.getMessage())
                                .isEqualTo("日期必须在项目周期 2026-08-01 至 2026-08-31 内"));
    }
}
