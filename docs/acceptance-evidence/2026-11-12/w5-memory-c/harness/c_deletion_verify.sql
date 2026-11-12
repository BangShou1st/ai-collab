\pset pager off
select 'projects_remaining', count(*)
  from project
 where id in (
   '8325433a-ae11-483e-a33b-059ededc0dc9',
   '27408ecd-d0e3-4940-b884-8a4c490531a9'
 );

select 'jobs_remaining', count(*)
  from ai_job
 where project_id in (
   '8325433a-ae11-483e-a33b-059ededc0dc9',
   '27408ecd-d0e3-4940-b884-8a4c490531a9'
 );

select 'results_remaining', count(*)
  from ai_job_result
 where job_id='e65fbaa4-938b-44cf-99e1-0574370018f8';

select 'memories_remaining', count(*)
  from agent_memory
 where project_id in (
   '8325433a-ae11-483e-a33b-059ededc0dc9',
   '27408ecd-d0e3-4940-b884-8a4c490531a9'
 );

select 'deletion_audit', entity_id, project_id, user_id, created_at
  from audit_log
 where action='PROJECT_DELETED'
   and entity_id in (
     '8325433a-ae11-483e-a33b-059ededc0dc9',
     '27408ecd-d0e3-4940-b884-8a4c490531a9'
   )
 order by entity_id;
