-- Flyway 12.4.0 evidence: published/applied V5 = -1995940233; drifted resolved V5 = 1512947011.
-- V5 is immutable. This migration owns the later confirmation cleanup repair.
ALTER TABLE ai_task_plan_confirmation
    DROP CONSTRAINT ai_task_plan_confirmation_plan_id_fkey,
    ADD CONSTRAINT ai_task_plan_confirmation_plan_id_fkey
        FOREIGN KEY (plan_id) REFERENCES ai_task_plan(id) ON DELETE CASCADE;
