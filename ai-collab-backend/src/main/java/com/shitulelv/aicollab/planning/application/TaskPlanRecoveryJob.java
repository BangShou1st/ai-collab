package com.shitulelv.aicollab.planning.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TaskPlanRecoveryJob {
    private final JdbcTemplate jdbc;
    public TaskPlanRecoveryJob(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(fixedDelayString = "${planning.recovery-delay-ms:60000}")
    @Transactional
    public void recoverStaleWork() {
        jdbc.update("""
                UPDATE ai_task_plan_attempt SET status='FAILED',error_code='PROCESS_RESTARTED',
                  error_summary='进程重启中断处理',finished_at=now(),updated_at=now()
                WHERE status IN ('QUEUED','RUNNING') AND updated_at < now() - interval '10 minutes'
                """);
        // C5: All terminal states must clear active_attempt_id in the same SQL
        jdbc.update("""
                UPDATE ai_task_plan SET status=CASE
                    WHEN status='SKELETON_GENERATING' THEN 'FAILED'
                    WHEN status='DETAIL_GENERATING' THEN 'DETAIL_GENERATION_FAILED'
                    ELSE status END,
                  active_attempt_id=NULL,
                  last_error_code='PROCESS_RESTARTED',last_error_summary='进程重启中断处理',updated_at=now()
                WHERE status IN ('SKELETON_GENERATING','DETAIL_GENERATING')
                  AND updated_at < now() - interval '10 minutes'
                """);
        jdbc.update("""
                UPDATE ai_task_plan p SET status=CASE WHEN EXISTS (
                    SELECT 1 FROM ai_task_plan_confirmation c WHERE c.plan_id=p.id AND c.status='SUCCESS')
                    THEN 'CONFIRMED' ELSE 'READY' END,
                  active_attempt_id=NULL,updated_at=now()
                WHERE p.status='CONFIRMING' AND p.updated_at < now() - interval '10 minutes'
                """);
        jdbc.update("""
                UPDATE ai_task_plan_confirmation SET status='FAILED',error_code='PROCESS_RESTARTED',
                  error_summary='进程重启中断确认',completed_at=now(),updated_at=now()
                WHERE status='PROCESSING' AND updated_at < now() - interval '10 minutes'
                """);
    }
}
