package com.shitulelv.aicollab.work.application.service;

import com.shitulelv.aicollab.common.exception.BusinessException;
import com.shitulelv.aicollab.common.exception.ErrorCode;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ProjectDateRangePolicy {

    private final JdbcTemplate jdbc;

    public ProjectDateRangePolicy(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void validate(
            UUID projectId,
            LocalDate startDate,
            LocalDate dueDate,
            LocalDate targetDate) {
        LocalDate[] range = jdbc.queryForObject(
                "SELECT start_date,due_date FROM project WHERE id=?",
                (resultSet, rowNumber) -> new LocalDate[] {
                    resultSet.getObject("start_date", LocalDate.class),
                    resultSet.getObject("due_date", LocalDate.class)
                },
                projectId);
        if (range == null) {
            return;
        }
        boolean outside = Arrays.stream(new LocalDate[] {startDate, dueDate, targetDate})
                .filter(date -> date != null)
                .anyMatch(date -> range[0] != null && date.isBefore(range[0])
                        || range[1] != null && date.isAfter(range[1]));
        if (outside) {
            String lower = range[0] == null ? "不限" : range[0].toString();
            String upper = range[1] == null ? "不限" : range[1].toString();
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "日期必须在项目周期 " + lower + " 至 " + upper + " 内");
        }
    }
}
