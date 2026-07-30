ALTER TABLE ai_task_plan
    DROP CONSTRAINT ck_ai_task_plan_max_tasks;

ALTER TABLE ai_task_plan
    ADD CONSTRAINT ck_ai_task_plan_max_tasks
        CHECK (max_task_count BETWEEN 1 AND 40);
