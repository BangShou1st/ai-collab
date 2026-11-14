\pset pager off
select 'job', id, project_id, status, attempt_count, lease_epoch,
       last_error_code, created_at, updated_at
  from ai_job
 where id='e65fbaa4-938b-44cf-99e1-0574370018f8';

select 'attempt', attempt_no, status, lease_epoch, snapshot_hash,
       snapshot_refs, length(snapshot_payload)
  from ai_job_attempt
 where job_id='e65fbaa4-938b-44cf-99e1-0574370018f8'
 order by attempt_no;

select 'result', id, review_status, payload->>'sourceRef' as source_ref,
       snapshot_hash, snapshot_refs, jsonb_array_length(snapshot_refs),
       created_at
  from ai_job_result
 where job_id='e65fbaa4-938b-44cf-99e1-0574370018f8'
 order by created_at, id;

select 'binding',
       count(*) as result_count,
       count(*) filter (
         where r.snapshot_hash=a.snapshot_hash
           and r.snapshot_refs=a.snapshot_refs
           and a.snapshot_refs ? (r.payload->>'sourceRef')
       ) as bound_ok
  from ai_job_result r
  join ai_job_attempt a
    on a.job_id=r.job_id and a.attempt_no=r.attempt_no
 where r.job_id='e65fbaa4-938b-44cf-99e1-0574370018f8'
   and r.review_status='ACCEPTED';

select 'review_status', review_status, count(*)
  from ai_job_result
 where job_id='e65fbaa4-938b-44cf-99e1-0574370018f8'
 group by review_status
 order by review_status;

select 'published_memory', count(*) as ai_job_memory_count,
       count(*) filter (
         where source_id in (
           select id from ai_job_result
            where job_id='e65fbaa4-938b-44cf-99e1-0574370018f8'
              and review_status='ACCEPTED'
         )
       ) as bound_to_accepted_result
  from agent_memory
 where project_id='8325433a-ae11-483e-a33b-059ededc0dc9'
   and source_type='AI_JOB_RESULT';

select 'approval_audit', count(*) as approval_audit_count
  from audit_log
 where project_id='8325433a-ae11-483e-a33b-059ededc0dc9'
   and action='AGENT_MEMORY_CANDIDATE_APPROVED'
   and detail->>'jobId'='e65fbaa4-938b-44cf-99e1-0574370018f8';

select 'project_present', count(*) as project_count
  from project
 where id in (
   '8325433a-ae11-483e-a33b-059ededc0dc9',
   '27408ecd-d0e3-4940-b884-8a4c490531a9'
 );
