param([Parameter(Mandatory)][ValidatePattern('^[a-z][a-z0-9-]*$')][string]$Case,[Parameter(Mandatory)][ValidateSet('READY','FAILED','CANCELED','DETAIL_GENERATING','SKELETON_GENERATING','DETAIL_GENERATION_FAILED')][string]$ExpectedStatus,[int]$ExpectedVersions=2)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$state=Get-Content (Join-Path $root 'ai-collab-backend/target/reliability-fault-state.json') -Raw|ConvertFrom-Json
$entry=$state.cases.$Case
$plan=[guid]$entry.planId;$operation=[guid]$entry.operationId;$project=[guid]$state.projectId
$cfg=@{};Get-Content (Join-Path $root '.env')|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$cfg[$matches[1]]=$matches[2]}}
# Fixed isolated container only. No arbitrary database target.
$sql=@"
SELECT json_build_object('at',now(),'case','$Case','plan',(SELECT row_to_json(p) FROM
 (SELECT id,status,latest_version_no,active_attempt_id,last_error_code,updated_at FROM ai_task_plan WHERE id='$plan')p),
 'operationCount',(SELECT count(*) FROM agent_planning_operation WHERE plan_id='$plan'),
 'matchingOperation',(SELECT count(*) FROM agent_planning_operation WHERE id='$operation' AND plan_id='$plan'),
 'versions',(SELECT json_agg(v ORDER BY version_no) FROM
 (SELECT id,version_no,md5(jsonb_build_array(summary,assumptions_json,risks_json,milestones_json,tasks_json,sources_json)::text) AS content_hash FROM ai_task_plan_version WHERE plan_id='$plan')v),
 'attempts',(SELECT json_agg(a ORDER BY attempt_no) FROM
 (SELECT id,attempt_no,stage,status,error_code,started_at,finished_at FROM ai_task_plan_attempt WHERE plan_id='$plan')a),
 'formalTasks',(SELECT count(*) FROM project_task WHERE project_id='$project'),
 'confirmations',(SELECT count(*) FROM ai_task_plan_confirmation WHERE plan_id='$plan'));
"@
$raw=& docker exec ai-collab-reliability-pg-20261004 psql -U $cfg.POSTGRES_USER -d $cfg.POSTGRES_DB -Atc $sql
if($LASTEXITCODE-ne 0){throw 'Isolated SQL query failed'}
$data=$raw|ConvertFrom-Json
if($data.plan.status-ne $ExpectedStatus){throw "Unexpected plan status $($data.plan.status)"}
if($data.operationCount-ne 1-or $data.matchingOperation-ne 1){throw 'Duplicate or changed operation'}
if(@($data.versions).Count-ne $ExpectedVersions){throw 'Unexpected persisted version count'}
if($data.formalTasks-ne 0-or $data.confirmations-ne 0){throw 'Unexpected formal write'}
if($ExpectedStatus-notin @('DETAIL_GENERATING','SKELETON_GENERATING')-and $null-ne $data.plan.active_attempt_id){throw 'Terminal plan retained active attempt'}
$data|Add-Member assertions 'PASS: original operation, expected status/version count, no formal tasks/confirmation, terminal active attempt cleared'
$folder=Join-Path $PSScriptRoot ((Get-Date -Format yyyy-MM-dd)+'/reliability')
$baseline=Get-ChildItem $folder -Filter ('db-'+$Case+'-*.json') | Sort-Object Name | Select-Object -First 1
if($baseline){
 $old=Get-Content $baseline.FullName -Raw|ConvertFrom-Json
 foreach($version in @($old.versions)){
  $same=@($data.versions)|Where-Object id -eq $version.id
  if(!$same-or $same.content_hash-ne $version.content_hash){throw 'Existing immutable draft changed or disappeared'}
 }
}
$data|ConvertTo-Json -Depth 15|Set-Content (Join-Path $folder ('db-'+$Case+'-'+(Get-Date -Format HHmmss)+'.json'))
Write-Output "PASS isolated database assertions: $Case $ExpectedStatus versions=$ExpectedVersions"
