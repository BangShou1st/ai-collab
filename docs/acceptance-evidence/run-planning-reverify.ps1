$ErrorActionPreference='Stop'
$root='E:\project\ai-collab'
$settings=@{};Get-Content (Join-Path $root '.env')|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
. (Join-Path $root 'docs/acceptance-evidence/get-acceptance-auth.ps1')
function Api($method,$path,$body=$null){
 $p=@{Uri=('http://localhost:18080/api/v1'+$path);Method=$method;Headers=$headers}
 if($null-ne $body){$p.ContentType='application/json';$p.Body=$body|ConvertTo-Json -Depth 40 -Compress}
 (Invoke-RestMethod @p).data
}
$head=(& git -C $root rev-parse HEAD).Trim()
$folder='E:\project\ai-collab\docs\acceptance-evidence\2026-10-04\agent-stability-remainder\planning-reverify'
New-Item $folder -ItemType Directory -Force|Out-Null
$project=Api POST '/projects' @{name='ai-collab完整前置状态规划复验';type='OTHER';description='原子结算与错误反馈修复后，完整前置状态下的规划生成复验，隔离副本';startDate='2026-10-04';dueDate='2026-10-31'}
$client=[Net.Http.HttpClient]::new();$client.DefaultRequestHeaders.Add('Authorization',$headers.Authorization);$client.DefaultRequestHeaders.Add('Origin','http://localhost:15173')
$form=[Net.Http.MultipartFormDataContent]::new();$part=[Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes((Join-Path $root 'docs/ai-next-stage-blueprint.md')))
$part.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('text/markdown')
$form.Add($part,'file','ai-next-stage-blueprint.md');$form.Add([Net.Http.StringContent]::new('ai-collab下一阶段主蓝图'),'displayName')
try{
  $response=$client.PostAsync("http://localhost:18080/api/v1/projects/$($project.id)/documents",$form).GetAwaiter().GetResult()
  $null=$response.EnsureSuccessStatusCode();$document=($response.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json).data
}finally{$form.Dispose();$client.Dispose()}
$docStatus=''
foreach($i in 1..30){ Start-Sleep -Seconds 5; $d=Api GET "/projects/$($project.id)/documents/$($document.id)"; $docStatus=$d.status; if($d.status -eq 'READY'){break} }
$session=Api POST "/projects/$($project.id)/agent/sessions" @{title='完整前置状态规划复验'}
@{projectId=$project.id;documentId=$document.id;documentStatus=$docStatus;sessionId=$session.id;codeVersion=$head}|ConvertTo-Json|Set-Content (Join-Path $folder 'setup.json')
Write-Output ('doc='+$docStatus)
# 重放必要前序：1（日期/负责人/10项）→ 7（无关问答）→ 10（8项取代10）→ 18（新目标+6项）
foreach($t in @(1,7,10,18)){
  $entry=Get-Content (Join-Path $root 'docs/acceptance-evidence/2026-10-04/reliability/conversation-turns.json') -Raw|ConvertFrom-Json|Where-Object turn -eq $t
  $run=Api POST "/projects/$($project.id)/agent/sessions/$($session.id)/messages" @{content=$entry.prompt;skillCode='ITERATION_PLANNING';pageContext=@{route='/documents';selectedDocumentId=$document.id}}
  $deadline=(Get-Date).AddMinutes(10)
  do{ $detail=Api GET "/projects/$($project.id)/agent/runs/$($run.id)"; if($detail.run.status -notin @('QUEUED','RUNNING','WAITING_APPROVAL','FAILED_RETRYABLE')){break}; Start-Sleep -Seconds 2 }while((Get-Date)-lt $deadline)
  $messages=Api GET "/projects/$($project.id)/agent/sessions/$($session.id)/messages"
  $answer=$messages|Where-Object{$_.runId-eq $run.id-and $_.role-eq 'ASSISTANT'}|Select-Object -Last 1
  @{turn=$t;prompt=$entry.prompt;run=$detail.run;steps=$detail.steps;answer=$answer}|ConvertTo-Json -Depth 50|Set-Content (Join-Path $folder ('turn-{0:D2}.json'-f $t))
  Write-Output ('replay'+$t+'='+$detail.run.status)
}
# 第23轮：完整前置状态下的规划生成
$entry=Get-Content (Join-Path $root 'docs/acceptance-evidence/2026-10-04/reliability/conversation-turns.json') -Raw|ConvertFrom-Json|Where-Object turn -eq 23
$run=Api POST "/projects/$($project.id)/agent/sessions/$($session.id)/messages" @{content=$entry.prompt;skillCode='ITERATION_PLANNING';pageContext=@{route='/documents';selectedDocumentId=$document.id}}
$deadline=(Get-Date).AddMinutes(10)
do{ $detail=Api GET "/projects/$($project.id)/agent/runs/$($run.id)"; if($detail.run.status -notin @('QUEUED','RUNNING','WAITING_APPROVAL','FAILED_RETRYABLE')){break}; Start-Sleep -Seconds 2 }while((Get-Date)-lt $deadline)
$messages=Api GET "/projects/$($project.id)/agent/sessions/$($session.id)/messages"
$answer=$messages|Where-Object{$_.runId-eq $run.id-and $_.role-eq 'ASSISTANT'}|Select-Object -Last 1
@{turn=23;prompt=$entry.prompt;run=$detail.run;steps=$detail.steps;answer=$answer}|ConvertTo-Json -Depth 50|Set-Content (Join-Path $folder 'turn-23.json')
Write-Output ('turn23='+$detail.run.status+' tools='+$detail.run.toolCallsUsed)
$ops=Api GET "/projects/$($project.id)/agent/sessions/$($session.id)/planning-operations"
$ops|ConvertTo-Json -Depth 20|Set-Content (Join-Path $folder 'operation.json')
Write-Output ('op='+$ops.status+'|'+$ops.attemptStatus)
