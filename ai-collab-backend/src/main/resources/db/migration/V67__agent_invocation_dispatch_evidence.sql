-- PENDING alone cannot distinguish a never-started call from a lost remote response.
ALTER TABLE agent_tool_invocation
    ADD COLUMN dispatch_started_at timestamptz,
    ADD COLUMN dispatch_claim_version integer,
    ADD COLUMN execution_safety varchar(40),
    ADD CONSTRAINT ck_agent_invocation_dispatch_evidence CHECK (
        (dispatch_started_at IS NULL AND dispatch_claim_version IS NULL AND execution_safety IS NULL)
        OR (dispatch_started_at IS NOT NULL AND dispatch_claim_version IS NOT NULL AND execution_safety IS NOT NULL
            AND execution_safety IN ('SAFE_READ','IDEMPOTENT_OR_RECONCILABLE','UNCONFIRMABLE_SIDE_EFFECT'))
    );

-- V66 emitted TOOL_CALL_STARTED before executing the tool. Preserve this evidence
-- across an upgrade; an old started PENDING must not be mistaken for never-started.
-- The original claim was not recorded, so -1 explicitly means historical/unknown.
UPDATE agent_tool_invocation i
SET dispatch_started_at=(
        SELECT min(e.created_at) FROM agent_run_event e
        WHERE e.run_id=i.run_id AND e.type='TOOL_CALL_STARTED'
          AND e.payload_json->>'invocationId'=i.invocation_id::text),
    dispatch_claim_version=-1,
    execution_safety='UNCONFIRMABLE_SIDE_EFFECT'
WHERE (i.status IN ('PENDING','CANCELED','SKIPPED') OR
       (i.status='FAILED' AND i.result_json->>'error'='TOOL_EXECUTION_TIMEOUT')) AND EXISTS (
    SELECT 1 FROM agent_run_event e
    WHERE e.run_id=i.run_id AND e.type='TOOL_CALL_STARTED'
      AND e.payload_json->>'invocationId'=i.invocation_id::text
);

-- A V66 local cancellation/timeout was not remote cancellation evidence either.
-- Keep the old step history, but do not let an active run retry such an outcome.
UPDATE agent_tool_invocation
SET status='UNKNOWN',updated_at=now(),
    result_json='{"status":"UNKNOWN","retryable":false,"error":"LEGACY_TOOL_OUTCOME_UNKNOWN","message":"旧版本已启动调用的远端结果需人工核对，不自动重发"}'::jsonb
WHERE dispatch_started_at IS NOT NULL AND status IN ('FAILED','CANCELED','SKIPPED');
