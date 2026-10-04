param([ValidateSet('minimal','fixed24','correction')][string]$Batch='minimal', [ValidateSet('Setup','Turn','Recover','Read')][string]$Mode='Read', [int]$Turn=0)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if(!(Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue)){throw 'Isolated acceptance host must be running'}
$original=Get-Content (Join-Path $root 'ai-collab-backend/target/reliability-conversation-state.json') -Raw|ConvertFrom-Json
$private=Join-Path $root ('ai-collab-backend/target/long-quality-'+$Batch+'-state-private.json')
$folder=Join-Path $PSScriptRoot ('2026-10-04/long-quality-fix/'+$Batch)
New-Item $folder -ItemType Directory -Force|Out-Null
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
 $session=if($Batch-eq 'correction'){Api GET "/projects/$($original.projectId)/agent/sessions/$($original.sessionId)"}else{Api POST "/projects/$($original.projectId)/agent/sessions" @{title=('四类失败复验 '+$Batch)}}
 @{projectId=$original.projectId;documentId=$original.documentId;sessionId=$session.id;completedTurns=0;codeVersion=$head;batch=$Batch}|ConvertTo-Json|Set-Content $private
}
$state=Get-Content $private -Raw|ConvertFrom-Json
if($head-ne $state.codeVersion){throw 'Fixed batch code changed; stop and register incomplete batch'}
$base="/projects/$($state.projectId)";$session="$base/agent/sessions/$($state.sessionId)"
if($Mode-in @('Turn','Recover')){
 if($Batch-ne 'correction'-and $Turn-ne $state.completedTurns+1){throw 'Sequential same-session turns required'}
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
 $deadline=(Get-Date).AddMinutes(5)
 do{
  $detail=Api GET "$base/agent/runs/$($run.id)"
  if($detail.run.status-notin @('QUEUED','RUNNING','WAITING_APPROVAL')){break}
  if((Get-Date)-gt $deadline){throw 'Nonterminal original run; inspect without duplicate submission'}
  Start-Sleep -Seconds 1
 }while($true)
 $messages=Api GET "$session/messages"
 $answer=$messages|Where-Object{$_.runId-eq $run.id-and $_.role-eq 'ASSISTANT'}|Select-Object -Last 1
 $working=Api GET $session
 # Full model request/response capture is independently kept under ignored target by the opt-in host.
 @{executionDate='2026-10-04';codeVersion=$head;batch=$Batch;layer='REAL_SPACE_BUNNY_PRODUCTION_TRANSPORT_ISOLATED_PG';turn=$Turn;prompt=$entry.prompt;run=$detail.run;steps=$detail.steps;answer=$answer;session=$working}|ConvertTo-Json -Depth 50|Set-Content (Join-Path $folder ('turn-{0:D2}.json'-f $Turn))
 $state.completedTurns=$Turn;$state|ConvertTo-Json|Set-Content $private
 @{turn=$Turn;status=$detail.run.status;input=$detail.run.inputTokensUsed;output=$detail.run.outputTokensUsed;answer=$answer.content}|ConvertTo-Json -Depth 5
}else{
 @{codeVersion=$head;state=$state;session=(Api GET $session);operations=(Api GET "$session/planning-operations")}|ConvertTo-Json -Depth 50|Set-Content (Join-Path $folder 'latest.json')
 @{batch=$Batch;sessionId=$state.sessionId;completedTurns=$state.completedTurns}|ConvertTo-Json
}
