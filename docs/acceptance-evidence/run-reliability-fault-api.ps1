param([ValidateSet('Prepare','Send','Read','Cancel','Race','RetryDetail')][string]$Mode='Read',[string]$Case='queued')
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$statePath=Join-Path $root 'ai-collab-backend/target/reliability-fault-state.json'
$evidence=Join-Path $PSScriptRoot ((Get-Date -Format yyyy-MM-dd)+'/reliability')
$settings=@{};Get-Content (Join-Path $root '.env')|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
. (Join-Path $PSScriptRoot 'get-acceptance-auth.ps1')
function Api($method,$path,$body=$null){$p=@{Uri=('http://localhost:18080/api/v1'+$path);Method=$method;Headers=$headers};if($null-ne $body){$p.ContentType='application/json';$p.Body=$body|ConvertTo-Json -Depth 30 -Compress};(Invoke-RestMethod @p).data}
if($Mode-eq 'Prepare'){
 if(Test-Path $statePath){throw 'Existing fault state: inspect first'}
 $p=Api POST '/projects' @{name='ai-collab进程故障隔离验收20261004';type='OTHER';description='受控模型传输与真实进程重启';startDate='2026-10-04';dueDate='2026-10-31'}
 @{projectId=$p.id;cases=@{}}|ConvertTo-Json|Set-Content $statePath
}
$state=Get-Content $statePath -Raw|ConvertFrom-Json;$base="/projects/$($state.projectId)"
if($Mode-eq 'Send'){
 if($state.cases.PSObject.Properties.Name -contains $Case){throw 'Do not duplicate submitted fault case'}
 $session=Api POST "$base/agent/sessions" @{title=('故障-'+$Case)}
 $run=Api POST "$base/agent/sessions/$($session.id)/messages" @{content=('请生成故障验收规划草稿：'+$Case+'。不确认正式任务。');skillCode='ITERATION_PLANNING'}
 $state.cases|Add-Member $Case ([pscustomobject]@{sessionId=$session.id;runId=$run.id}) -Force;$state|ConvertTo-Json -Depth 10|Set-Content $statePath
 $end=(Get-Date).AddSeconds(45)
 do{$ops=@(Api GET "$base/agent/sessions/$($session.id)/planning-operations");if($ops.Count){break};if((Get-Date)-gt $end){throw 'No operation yet; inspect original run, do not resubmit'};Start-Sleep -Seconds 1}while($true)
 $state.cases.$Case|Add-Member planId $ops[0].planId -Force;$state.cases.$Case|Add-Member operationId $ops[0].operationId -Force;$state|ConvertTo-Json -Depth 10|Set-Content $statePath
 $ops|ConvertTo-Json -Depth 6
}
if($Mode -in @('Read','Cancel','Race','RetryDetail')){
 $c=$state.cases.$Case
 if(!$c){throw 'Unknown case'}
 if($Mode-eq 'Cancel'){Api POST "$base/ai/task-plans/$($c.planId)/cancel"|Out-Null}
 if($Mode-eq 'RetryDetail'){Api POST "$base/ai/task-plans/$($c.planId)/retry-detail"|Out-Null}
 if($Mode-eq 'Race'){
  $client=[Net.Http.HttpClient]::new();$client.DefaultRequestHeaders.Add('Authorization',$headers.Authorization);$client.DefaultRequestHeaders.Add('Origin',$headers.Origin)
  $before=(Get-Date).ToUniversalTime().ToString('o')
  $cancel=$client.PostAsync("http://localhost:18080/api/v1$base/ai/task-plans/$($c.planId)/cancel",[Net.Http.StringContent]::new(''))
  @{mode='NORMAL';queueHold=$false;title='取消与完成竞争'}|ConvertTo-Json|Set-Content (Join-Path $root 'ai-collab-backend/target/reliability-fault-control.json')
  $released=(Get-Date).ToUniversalTime().ToString('o');$response=$cancel.GetAwaiter().GetResult()
  @{case=$Case;cancelSentAt=$before;modelReleasedAt=$released;httpStatus=[int]$response.StatusCode;response=($response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json)}|ConvertTo-Json -Depth 12|Set-Content (Join-Path $evidence ('race-'+$Case+'.json'))
  $client.Dispose()
 }
 $detail=Api GET "$base/ai/task-plans/$($c.planId)"
 $result=@{at=(Get-Date).ToUniversalTime().ToString('o');layer='REAL_PROCESS_AND_HTTP_TRANSPORT_WITH_SCRIPTED_MODEL';case=$Case;action=$Mode;identity=$c;detail=$detail;versions=(Api GET "$base/ai/task-plans/$($c.planId)/versions");operations=(Api GET "$base/agent/sessions/$($c.sessionId)/planning-operations");run=(Api GET "$base/agent/runs/$($c.runId)")}
 $result|ConvertTo-Json -Depth 40|Set-Content (Join-Path $evidence ('fault-'+$Case+'-'+(Get-Date -Format 'HHmmss')+'.json'))
 @{case=$Case;planStatus=$detail.plan.status;versionNo=$detail.plan.latestVersionNo;attempt=$detail.latestAttempt;operation=$result.operations}|ConvertTo-Json -Depth 8
}
