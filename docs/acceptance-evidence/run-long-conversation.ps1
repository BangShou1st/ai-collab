param([ValidateSet('Setup','Turn','Read')][string]$Mode='Read',[int]$Turn=0)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$statePath=Join-Path $root 'ai-collab-backend/target/reliability-conversation-state.json'
$evidence=Join-Path $PSScriptRoot ((Get-Date -Format yyyy-MM-dd)+'/reliability')
New-Item $evidence -ItemType Directory -Force|Out-Null
$settings=@{};Get-Content (Join-Path $root '.env')|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
$login=Invoke-RestMethod 'http://localhost:18080/api/v1/auth/login' -Method Post -Headers @{Origin='http://localhost:15173'} -ContentType application/json -Body (@{username=$settings.DEMO_OWNER_USERNAME;password=$settings.DEMO_OWNER_PASSWORD}|ConvertTo-Json)
$headers=@{Origin='http://localhost:15173';Authorization='Bearer '+$login.data.accessToken}
function Api($method,$path,$body=$null){
 $p=@{Uri=('http://localhost:18080/api/v1'+$path);Method=$method;Headers=$headers}
 if($null-ne $body){$p.ContentType='application/json';$p.Body=$body|ConvertTo-Json -Depth 30 -Compress}
 (Invoke-RestMethod @p).data
}
if($Mode-eq 'Setup'){
 if(Test-Path $statePath){throw 'Existing acceptance state: inspect before starting another project'}
 $project=Api POST '/projects' @{name='ai-collab真实需求长对话20261004';type='OTHER';description='真实仓库蓝图可靠性专项，隔离副本';startDate='2026-10-04';dueDate='2026-10-31'}
 $client=[Net.Http.HttpClient]::new();$client.DefaultRequestHeaders.Add('Authorization',$headers.Authorization);$client.DefaultRequestHeaders.Add('Origin','http://localhost:15173')
 $form=[Net.Http.MultipartFormDataContent]::new();$part=[Net.Http.ByteArrayContent]::new([IO.File]::ReadAllBytes((Join-Path $root 'docs/ai-next-stage-blueprint.md')));$part.Headers.ContentType=[Net.Http.Headers.MediaTypeHeaderValue]::new('text/markdown')
 $form.Add($part,'file','ai-next-stage-blueprint.md');$form.Add([Net.Http.StringContent]::new('ai-collab下一阶段主蓝图'),'displayName')
 $r=$client.PostAsync("http://localhost:18080/api/v1/projects/$($project.id)/documents",$form).GetAwaiter().GetResult();$null=$r.EnsureSuccessStatusCode();$doc=($r.Content.ReadAsStringAsync().GetAwaiter().GetResult()|ConvertFrom-Json).data
 $session=Api POST "/projects/$($project.id)/agent/sessions" @{title='真实需求24轮约束与目标验收'}
 @{projectId=$project.id;documentId=$doc.id;sessionId=$session.id;ownerId=$login.data.user.id;completedTurns=0}|ConvertTo-Json|Set-Content $statePath
}
$state=Get-Content $statePath -Raw|ConvertFrom-Json;$base="/projects/$($state.projectId)";$session="$base/agent/sessions/$($state.sessionId)"
if($Mode-eq 'Turn'){
 if($Turn -ne $state.completedTurns+1){throw 'Do not replay a submitted conversation turn'}
 $prompts=Get-Content (Join-Path $evidence 'conversation-turns.json') -Raw|ConvertFrom-Json
 $entry=$prompts|Where-Object turn -eq $Turn
 if(!$entry){throw 'No approved scenario prompt for turn'}
 $run=Api POST "$session/messages" @{content=$entry.prompt;skillCode='ITERATION_PLANNING';pageContext=@{route='/documents';selectedDocumentId=$state.documentId}}
 $state|Add-Member runId $run.id -Force;$state|Add-Member submittedTurn $Turn -Force;$state|ConvertTo-Json|Set-Content $statePath
 $deadline=(Get-Date).AddMinutes(5)
 do{$detail=Api GET "$base/agent/runs/$($run.id)";if($detail.run.status -notin @('QUEUED','RUNNING','WAITING_APPROVAL')){break};if((Get-Date)-gt $deadline){throw 'Run still nonterminal; inspect, never submit duplicate'};Start-Sleep -Seconds 1}while($true)
 $messages=Api GET "$session/messages";$answer=$messages|Where-Object{$_.runId -eq $run.id -and $_.role -eq 'ASSISTANT'}|Select-Object -Last 1
 $working=Api GET $session
 @{executionDate=(Get-Date -Format yyyy-MM-dd);layer='REAL_API_SPACE_BUNNY_PRODUCTION_ADAPTER';turn=$Turn;scenario=$entry.scenario;prompt=$entry.prompt;run=$detail.run;steps=$detail.steps;answer=$answer;session=$working}|ConvertTo-Json -Depth 40|Set-Content (Join-Path $evidence ('turn-{0:D2}.json'-f $Turn))
 $state.completedTurns=$Turn;$state|ConvertTo-Json|Set-Content $statePath
 @{turn=$Turn;status=$detail.run.status;answer=$answer.content}|ConvertTo-Json -Depth 5
}else{
 @{state=$state;document=(Api GET "$base/documents/$($state.documentId)/reading");session=(Api GET $session);latest=(Api GET "$session/latest-run");operations=(Api GET "$session/planning-operations")}|ConvertTo-Json -Depth 40|Set-Content (Join-Path $evidence 'conversation-latest.json')
 $state|ConvertTo-Json
}
