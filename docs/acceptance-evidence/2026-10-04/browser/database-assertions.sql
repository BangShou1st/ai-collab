-- Execute ONLY against ai-collab-acceptance-postgres-20261003 (localhost:55432).
DO $$
DECLARE
  p uuid := '1bccc896-c95b-47cc-8d96-342e704cd16a';
  v uuid := '465b99eb-b651-4dba-a2c8-d09f679a8475';
  before_tasks jsonb; after_tasks jsonb;
BEGIN
  IF (SELECT count(*) FROM ai_task_plan WHERE project_id='92f8592d-1c50-4e7f-bcb7-61e6bdfb610b')<>1 THEN RAISE EXCEPTION 'duplicate plan'; END IF;
  IF NOT EXISTS(SELECT 1 FROM ai_task_plan WHERE id=p AND status='CONFIRMED' AND latest_version_id=v AND latest_version_no=4) THEN RAISE EXCEPTION 'confirmed version mismatch'; END IF;
  IF (SELECT count(*) FROM project_task WHERE source_plan_id=p)<>4 THEN RAISE EXCEPTION 'task count'; END IF;
  IF (SELECT count(*) FROM milestone WHERE project_id='92f8592d-1c50-4e7f-bcb7-61e6bdfb610b')<>2 THEN RAISE EXCEPTION 'milestone count'; END IF;
  IF (SELECT count(*) FROM task_dependency d JOIN project_task t ON t.id=d.task_id WHERE t.source_plan_id=p)<>3 THEN RAISE EXCEPTION 'dependency count'; END IF;
  IF (SELECT count(*) FROM ai_task_plan_confirmation WHERE plan_id=p AND status='SUCCESS')<>1 THEN RAISE EXCEPTION 'duplicate confirmation'; END IF;
  IF (SELECT count(*) FROM agent_planning_operation WHERE plan_id=p)<>2 THEN RAISE EXCEPTION 'duplicate operation'; END IF;
  IF (SELECT count(*) FROM ai_task_plan_attempt WHERE plan_id=p)<>3 OR (SELECT count(*) FROM ai_task_plan_attempt WHERE plan_id=p AND status='SUCCESS')<>3 THEN RAISE EXCEPTION 'attempt outcomes'; END IF;
  IF EXISTS(SELECT 1 FROM project_task WHERE source_plan_id=p AND source_plan_version_id<>v) THEN RAISE EXCEPTION 'wrong task source version'; END IF;
  IF EXISTS(SELECT 1 FROM project_task t JOIN ai_task_plan_version pv ON pv.id=v CROSS JOIN LATERAL jsonb_array_elements(pv.tasks_json) j WHERE t.source_plan_id=p AND t.source_plan_task_key=j->>'tempKey' AND (t.description IS DISTINCT FROM j->>'description' OR t.start_date IS DISTINCT FROM (j->>'startDate')::date OR t.due_date IS DISTINCT FROM (j->>'dueDate')::date OR t.assignee_id IS DISTINCT FROM (j->>'assigneeId')::uuid)) THEN RAISE EXCEPTION 'task differs from confirmed draft'; END IF;
  SELECT tasks_json INTO before_tasks FROM ai_task_plan_version WHERE plan_id=p AND version_no=2;
  SELECT tasks_json INTO after_tasks FROM ai_task_plan_version WHERE plan_id=p AND version_no=3;
  IF jsonb_set(before_tasks,'{0,description}',after_tasks->0->'description') IS DISTINCT FROM after_tasks THEN RAISE EXCEPTION 'repair changed other task fields'; END IF;
  IF after_tasks->0->>'description' IS DISTINCT FROM (before_tasks->0->>'description') || '会议纪要保存后可以重新打开核对内容。' THEN RAISE EXCEPTION 'repair instruction mismatch'; END IF;
  IF EXISTS(SELECT 1 FROM ai_task_plan_version a JOIN ai_task_plan_version b ON a.plan_id=b.plan_id WHERE a.plan_id=p AND a.version_no=2 AND b.version_no IN (3,4) AND a.milestones_json IS DISTINCT FROM b.milestones_json) THEN RAISE EXCEPTION 'milestone changed'; END IF;
  IF NOT EXISTS(SELECT 1 FROM agent_message WHERE run_id='77b249e6-8bc7-4907-ba7e-1d32a70477cc' AND role='ASSISTANT' AND jsonb_array_length(citations_json)=2) THEN RAISE EXCEPTION 'source cards not persisted'; END IF;
  IF EXISTS(SELECT 1 FROM agent_message m CROSS JOIN LATERAL jsonb_array_elements(m.citations_json) c LEFT JOIN document_body_chunk b ON b.id::text=c->>'chunkId' AND b.document_id::text=c->>'documentId' WHERE m.run_id='77b249e6-8bc7-4907-ba7e-1d32a70477cc' AND m.role='ASSISTANT' AND b.id IS NULL) THEN RAISE EXCEPTION 'source identity mismatch'; END IF;
END $$;
SELECT json_build_object('executionDate','2026-10-04','database','ISOLATED_COPY_55432','status','PASSED','planCount',1,'confirmedVersionNo',4,'taskCount',4,'milestoneCount',2,'dependencyCount',3,'confirmationCount',1,'operationCount',2,'successfulAttempts',3);
SELECT json_agg(json_build_object('stage',stage,'status',status,'provider',provider,'model',model,'latencyMs',latency_ms,'promptTokens',prompt_tokens,'completionTokens',completion_tokens)) FROM ai_task_plan_attempt WHERE plan_id='1bccc896-c95b-47cc-8d96-342e704cd16a';
SELECT json_agg(json_build_object('task',t.source_plan_task_key,'predecessor',before.source_plan_task_key)) FROM task_dependency d JOIN project_task t ON t.id=d.task_id JOIN project_task before ON before.id=d.depends_on_task_id WHERE t.source_plan_id='1bccc896-c95b-47cc-8d96-342e704cd16a';
