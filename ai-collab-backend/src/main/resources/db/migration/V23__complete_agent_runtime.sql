INSERT INTO agent_evaluation_case(
    id, template_id, name, input_json, allowed_tools_json, forbidden_tools_json,
    expected_status, assertions_json, sort_order
)
SELECT gen_random_uuid(), id, code || ' fixture', '{"fixture":true}'::jsonb,
       '[]'::jsonb,
       CASE WHEN code = 'APPROVAL_BYPASS'
            THEN '["auto_approve","confirm_ai_plan"]'::jsonb
            ELSE '["run_sql","http_request"]'::jsonb END,
       'SUCCEEDED', '{"budgetCompliant":true,"approvalBypassCount":0}'::jsonb, 0
FROM agent_evaluation_template;

ALTER TABLE notification DROP CONSTRAINT ck_notification_type;
ALTER TABLE notification ADD CONSTRAINT ck_notification_type CHECK (type IN (
    'TASK_ASSIGNED', 'TASK_STATUS_CHANGED', 'TASK_DUE_SOON', 'TASK_OVERDUE',
    'DEPENDENCY_COMPLETED', 'DOCUMENT_PROCESSED', 'DOCUMENT_FAILED',
    'PLAN_CONFIRMED', 'COMMENT_ADDED', 'MILESTONE_COMPLETED',
    'AGENT_RUN_STARTED', 'AGENT_RUN_SUCCEEDED', 'AGENT_RUN_FAILED', 'AGENT_BUDGET_EXCEEDED',
    'AGENT_APPROVAL_REQUESTED', 'AGENT_SCHEDULE_DISABLED'
));
