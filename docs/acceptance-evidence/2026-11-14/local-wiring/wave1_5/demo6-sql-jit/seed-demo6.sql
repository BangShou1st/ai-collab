\set ON_ERROR_STOP on

BEGIN;

INSERT INTO project (id, name, owner_id, created_by)
SELECT :'project_id'::uuid, 'demo6-sql-jit', id, id
FROM app_user
WHERE username = :'owner_username';

INSERT INTO project_member (project_id, user_id, role)
SELECT :'project_id'::uuid, id, 'OWNER'
FROM app_user
WHERE username = :'owner_username';

INSERT INTO milestone (id, project_id, name, status, sort_order, created_by)
SELECT :'milestone_id'::uuid, :'project_id'::uuid, 'M1', 'ACTIVE', 1, id
FROM app_user
WHERE username = :'owner_username';

INSERT INTO project_task (
    id, project_id, milestone_id, title, description, status, priority,
    assignee_id, sort_order, created_by, version, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    :'project_id'::uuid,
    CASE WHEN g <= 200 THEN :'milestone_id'::uuid ELSE NULL END,
    'Demo6 Task ' || lpad(g::text, 5, '0'),
    '',
    CASE WHEN g % 10 = 0 THEN 'DONE' ELSE 'TODO' END,
    'MEDIUM',
    owner.id,
    0,
    owner.id,
    0,
    now() - (10000 - g) * interval '1 millisecond',
    now() - (10000 - g) * interval '1 millisecond'
FROM app_user owner
CROSS JOIN generate_series(1, 10000) AS g
WHERE owner.username = :'owner_username';

WITH first_page AS (
    SELECT id
    FROM project_task
    WHERE project_id = :'project_id'::uuid
    ORDER BY created_at, id
    LIMIT 200
), unfinished_required AS (
    SELECT id
    FROM project_task
    WHERE project_id = :'project_id'::uuid
    ORDER BY created_at, id
    OFFSET 499
    LIMIT 1
)
INSERT INTO task_dependency (task_id, depends_on_task_id)
SELECT first_page.id, unfinished_required.id
FROM first_page
CROSS JOIN unfinished_required;

ANALYZE project_task;
ANALYZE task_dependency;
ANALYZE app_user;
ANALYZE milestone;

COMMIT;
