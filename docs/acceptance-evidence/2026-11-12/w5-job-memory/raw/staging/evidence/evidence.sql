select 'job', id, project_id, status, attempt_count, lease_epoch, last_error_code, created_at, updated_at from ai_job order by created_at;
select 'result', job_id, review_status, count(*) from ai_job_result group by job_id, review_status order by job_id, review_status;
select 'attempt', job_id, attempt_no, status, error_code, lease_epoch, started_at, finished_at from ai_job_attempt order by started_at;
select 'memory', source_type, count(*) from agent_memory group by source_type order by source_type;
