param([Parameter(Mandatory=$true)][ValidatePattern('^stability-(minimal|fixed24|correction)$')][string]$Batch, [ValidateSet('Setup','Turn','Recover','Read')][string]$Mode='Read', [int]$Turn=0)
# Agent stability remainder re-verification runner (2026-10-04 agent-stability-remainder batch).
# Adapted from run-root-cause-repair.ps1: identical original prompts, strict sequential
# same-session rule, evidence in the INDEPENDENT folder agent-stability-remainder/<batch>;
# the old script, old batches and old evidence are untouched.
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if(!(Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue)){throw 'Isolated acceptance host must be running'}
$originalRaw=if(Test-Path (Join-Path $root 'ai-collab-backend/target/reliability-conversation-state.json')){Get-Content (Join-Path $root 'ai-collab-backend/target/reliability-conversation-state.json') -Raw|ConvertFrom-Json}
  else{(Get-Content (Join-Path $PSScriptRoot '2026-10-04/reliability/conversation-latest.json') -Raw|ConvertFrom-Json).state}
$original=$originalRaw
$private=Join-Path $root ('ai-collab-backend/target/stability-'+$Batch+'-state-private.json')
$folder=Join-Path $PSScriptRoot ('2026-10-04/agent-stability-remainder/'+$Batch)
New-Item $folder -ItemType Directory -Force|Out-Null
$today=(Get-Date).ToString('yyyy-MM-dd')
$settings=@{};Get-Content (Join-Path $root '.env')|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
. (Join-Path $PSScriptRoot 'get-acceptance-auth.ps1')
function Api($method,$path,$body=$null){
 $p=@{Uri=('http://localhost:18080/api/v1'+$path);Method=$method;Headers=$headers}
 if($null-ne $body){$p.ContentType='application/json';$p.Body=$body|ConvertTo-Json -Depth 40 -Compress}
 (Invoke-RestMethod @p).data
}
$head=(& git -C $root rev-parse HEAD).Trim()
if($Mode-eq 'Setup'){
 if(Test-Path $private){throw 'Batch already exists; recover/read original run instead of duplicating'}
 if($Batch-notlike '*correction'){
  $project=Api POST '/projects' @{name=('ai-collab真实需求长对话stability'+$Batch);type='OTHER';description='稳定性剩余缺陷修复复验，隔离副本';startDate='2026-10-04';dueDate='2026-10-31'}
  $client=[Net.Http.HttpClient]::new();$client.DefaultRequestHeaders.Add('Authorization',$headers.Authorization);$client.DefaultRequestHeaders.Add('Origin','http://localhost:15173')
  $form=[Net.Http.MultipartFormDataContent]::new();$part=[Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes((Join-Path $root 'docs/ai-next-stage-blueprint.md')))
  $part.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('text/markdown')
  $form.Add($part,'file','ai-next-stage-blueprint.md');$form.Add([Net.Http.StringContent]::new('ai-collab下一阶段主蓝图'),'displayName')
  try{
   $response=$client.PostAsync("http://localhost:18080/api/v1/projects/$($project.id)/documents",$form).GetAwaiter().GetResult()
   $null=$response.EnsureSuccessStatusCode();$document=($response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json).data
  }finally{$form.Dispose();$client.Dispose()}
  $session=Api POST "/projects/$($project.id)/agent/sessions" @{title=('稳定性修复复验 '+$Batch)}
  @{projectId=$project.id;documentId=$document.id;sessionId=$session.id;completedTurns=0;codeVersion=$head;batch=$Batch}|ConvertTo-Json|Set-Content $private
 }else{
  $session=Api GET "/projects/$($original.projectId)/agent/sessions/$($original.sessionId)"
  @{projectId=$original.projectId;documentId=$original.documentId;sessionId=$session.id;completedTurns=0;codeVersion=$head;batch=$Batch}|ConvertTo-Json|Set-Content $private
 }
}
$state=Get-Content $private -Raw|ConvertFrom-Json
if($head-ne $state.codeVersion){throw 'Fixed batch code changed; stop and register incomplete batch'}
$base="/projects/$($state.projectId)";$session="$base/agent/sessions/$($state.sessionId)"
if($Mode-in @('Turn','Recover')){
 if($Batch-notlike '*correction'-and $Turn-ne $state.completedTurns+1){throw 'Sequential same-session turns required'}
 $entry=Get-Content (Join-Path $PSScriptRoot '2026-10-04/reliability/conversation-turns.json') -Raw|ConvertFrom-Json|Where-Object turn -eq $Turn
 if(!$entry){throw 'Original scenario prompt missing'}
 if($Mode-eq 'Recover'){
  if($state.submittedTurn-ne $Turn){throw 'No matching submitted request'}
  $run=[pscustomobject]@{id=$state.runId}
 }else{
  if($state.submittedTurn-eq $Turn){throw 'Already submitted: Recover reads original run'}
  $run=Api POST "$session/messages" @{content=$entry.prompt;skillCode='ITERATION_PLANNING';pageContext=@{route='/documents';selectedDocumentId=$state.documentId}}
  $state|Add-Member runId $run.id -Force;$state|Add-Member submittedTurn $Turn -Force;$state|ConvertTo-Json|Set-Content $private
 }
 $deadline=(Get-Date).AddMinutes(10)
 $observedStatus=$null
 do{
  $detail=Api GET "$base/agent/runs/$($run.id)"
  if($detail.run.status-ne $observedStatus){
   @{at=(Get-Date).ToUniversalTime().ToString('o');runId=$run.id;status=$detail.run.status;errorCode=$detail.run.errorCode;retryCount=$detail.run.retryCount}|ConvertTo-Json -Compress|Add-Content (Join-Path $folder ('turn-{0:D2}-states.jsonl'-f $Turn))
   $observedStatus=$detail.run.status
  }
  if($detail.run.status-notin @('QUEUED','RUNNING','WAITING_APPROVAL','FAILED_RETRYABLE')){break}
  if((Get-Date)-gt $deadline){throw 'Nonterminal original run; inspect without duplicate submission'}
  Start-Sleep -Seconds 1
 }while($true)
 $messages=Api GET "$session/messages"
 $answer=$messages|Where-Object{$_.runId-eq $run.id-and $_.role-eq 'ASSISTANT'}|Select-Object -Last 1
 $working=Api GET $session
 @{executionDate=$today;codeVersion=$head;batch=$Batch;layer='REAL_SPACE_BUNNY_PRODUCTION_TRANSPORT_ISOLATED_PG';turn=$Turn;prompt=$entry.prompt;run=$detail.run;steps=$detail.steps;answer=$answer;session=$working}|ConvertTo-Json -Depth 50|Set-Content (Join-Path $folder ('turn-{0:D2}.json'-f $Turn))
 $state.completedTurns=$Turn;$state|ConvertTo-Json|Set-Content $private
 @{turn=$Turn;status=$detail.run.status;input=$detail.run.inputTokensUsed;inputActual=$detail.run.inputTokensActual;output=$detail.run.outputTokensUsed;outputActual=$detail.run.outputTokensActual;answer=$answer.content}|ConvertTo-Json -Depth 5
}else{
 @{codeVersion=$head;state=$state;session=(Api GET $session);operations=(Api GET "$session/planning-operations")}|ConvertTo-Json -Depth 50|Set-Content (Join-Path $folder 'latest.json')
 @{batch=$Batch;sessionId=$state.sessionId;completedTurns=$state.completedTurns}|ConvertTo-Json
}
