package com.shitulelv.aicollab.notification.application.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationReminderJob {
    private final JdbcTemplate jdbc;

    public NotificationReminderJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${notification.reminder-cron:0 0 8 * * *}")
    @Transactional
    public void createTaskDeadlineReminders() {
        insertDueSoon();
        insertOverdue();
    }

    private void insertDueSoon() {
        jdbc.update("""
                INSERT INTO notification(
                    project_id,user_id,type,title,content,entity_type,entity_id,dedupe_key)
                SELECT t.project_id,t.assignee_id,'TASK_DUE_SOON','任务即将截止',
                       '任务「' || t.title || '」将在 ' || t.due_date || ' 截止',
                       'TASK',t.id,'due-soon:' || t.id || ':' || CURRENT_DATE
                FROM project_task t
                JOIN project_member pm
                  ON pm.project_id=t.project_id AND pm.user_id=t.assignee_id
                WHERE t.assignee_id IS NOT NULL
                  AND t.status NOT IN ('DONE','CANCELED')
                  AND t.due_date BETWEEN CURRENT_DATE AND CURRENT_DATE + 2
                ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
                """);
    }

    private void insertOverdue() {
        jdbc.update("""
                INSERT INTO notification(
                    project_id,user_id,type,title,content,entity_type,entity_id,dedupe_key)
                SELECT t.project_id,t.assignee_id,'TASK_OVERDUE','任务已逾期',
                       '任务「' || t.title || '」已超过截止日期 ' || t.due_date,
                       'TASK',t.id,'overdue:' || t.id || ':' || CURRENT_DATE
                FROM project_task t
                JOIN project_member pm
                  ON pm.project_id=t.project_id AND pm.user_id=t.assignee_id
                WHERE t.assignee_id IS NOT NULL
                  AND t.status NOT IN ('DONE','CANCELED')
                  AND t.due_date < CURRENT_DATE
                ON CONFLICT (dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
                """);
    }
}
