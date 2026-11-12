[CmdletBinding()]
param(
    [ValidateSet('all', 'seed', 'run', 'smoke', 'read', 'mixed', 'spike', 'soak', 'ai', 'sse', 'followup', 'cleanup', 'summary')]
    [string]$Action = 'all',
    [string]$BaseUrl = 'http://127.0.0.1:8090',
    [string]$MgmtUrl = 'http://127.0.0.1:8091',
    [string]$JmeterHome = '',
    [string]$ResultsDir = '',
    [string]$Token = '',
    [string]$AiServiceToken = '',
    [string]$ProjectId = '',
    [string]$TaskId = '',
    [string]$SessionId = '',
    [int]$Threads = 0,
    [int]$Ramp = 0,
    [int]$Duration = 0,
    [int]$Loops = 0,
    [int]$SeedTasks = 80,
    [switch]$SkipSeed,
    [switch]$Quick,
    [switch]$KeepTestData,
    [switch]$CleanupAiStub
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$envFile = Join-Path $repoRoot '.env'
$envValues = @{}
if (Test-Path $envFile) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=(.*)$') {
            $envValues[$matches[1]] = $matches[2].Trim()
        }
    }
}

if ([string]::IsNullOrWhiteSpace($ResultsDir)) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $ResultsDir = Join-Path $repoRoot "target\perf\results\$stamp"
}
New-Item -ItemType Directory -Force $ResultsDir | Out-Null

$baseUri = [Uri]$BaseUrl
$mgmtUri = [Uri]$MgmtUrl
$server = $baseUri.Host
$port = if ($baseUri.Port -gt 0) { $baseUri.Port } else { 80 }
$protocol = $baseUri.Scheme
$mgmtServer = $mgmtUri.Host
$mgmtPort = if ($mgmtUri.Port -gt 0) { $mgmtUri.Port } else { 80 }
$mgmtProtocol = $mgmtUri.Scheme

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        $Body = $null
    )
    $params = @{
        Method      = $Method
        Uri         = $Uri
        Headers     = $Headers
        TimeoutSec  = 30
        ErrorAction = 'Stop'
    }
    if ((Get-Command Invoke-RestMethod).Parameters.ContainsKey('NoProxy')) {
        $params.NoProxy = $true
    }
    if ($null -ne $Body) {
        $params.ContentType = 'application/json'
        $params.Body = ($Body | ConvertTo-Json -Depth 12 -Compress)
    }
    Invoke-RestMethod @params
}

function Get-OwnerToken {
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        return $Token
    }
    if (-not $envValues.ContainsKey('DEMO_OWNER_USERNAME') -or -not $envValues.ContainsKey('DEMO_OWNER_PASSWORD')) {
        throw 'Token not supplied and DEMO_OWNER_USERNAME/PASSWORD are absent from .env'
    }
    $login = Invoke-Json -Method 'POST' -Uri "$BaseUrl/api/v1/auth/login" -Body @{
        username = $envValues['DEMO_OWNER_USERNAME']
        password = $envValues['DEMO_OWNER_PASSWORD']
    } -Headers @{ Origin = 'http://localhost:5173' }
    return [string]$login.data.accessToken
}

function Get-AuthHeaders([string]$AccessToken) {
    return @{ Authorization = "Bearer $AccessToken"; Accept = 'application/json' }
}

function Write-SeedFile {
    param($Seed)
    $Seed | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $ResultsDir 'seed.json') -Encoding UTF8
    $csv = "projectId,taskId,sessionId`n$($Seed.projectId),$($Seed.taskId),$($Seed.sessionId)`n"
    Set-Content -LiteralPath (Join-Path $ResultsDir 'testdata.local.csv') -Value $csv -Encoding UTF8
}

function Read-SeedFile {
    $file = Join-Path $ResultsDir 'seed.json'
    if (-not (Test-Path $file)) { return $null }
    return Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
}

function Invoke-Seed {
    param([string]$AccessToken)
    if ($SkipSeed) { return }
    $headers = Get-AuthHeaders $AccessToken
    $me = Invoke-Json -Method 'GET' -Uri "$BaseUrl/api/v1/auth/me" -Headers $headers
    $ownerId = [string]$me.data.id
    $runTag = Get-Date -Format 'yyyyMMdd-HHmmss'
    $projectStart = (Get-Date).Date
    $projectDue = $projectStart.AddDays(30)
    $project = Invoke-Json -Method 'POST' -Uri "$BaseUrl/api/v1/projects" -Headers $headers -Body @{
        name        = "[perf] jmeter $runTag"
        description = 'Dedicated JMeter performance test project; safe to delete after the run.'
        type        = 'INTERNAL_PROJECT'
        startDate   = $projectStart.ToString('yyyy-MM-dd')
        dueDate     = $projectDue.ToString('yyyy-MM-dd')
    }
    $projectId = [string]$project.data.id

    $milestoneBodies = @(
        @{ name = '[perf] M1'; description = 'perf milestone one'; startDate = (Get-Date).ToString('yyyy-MM-dd'); endDate = (Get-Date).AddDays(14).ToString('yyyy-MM-dd'); targetDate = (Get-Date).AddDays(14).ToString('yyyy-MM-dd'); sortOrder = 0 },
        @{ name = '[perf] M2'; description = 'perf milestone two'; startDate = (Get-Date).AddDays(14).ToString('yyyy-MM-dd'); endDate = (Get-Date).AddDays(28).ToString('yyyy-MM-dd'); targetDate = (Get-Date).AddDays(28).ToString('yyyy-MM-dd'); sortOrder = 1 }
    )
    $milestoneIds = @()
    foreach ($body in $milestoneBodies) {
        $created = Invoke-Json -Method 'POST' -Uri "$BaseUrl/api/v1/projects/$projectId/milestones" -Headers $headers -Body $body
        $milestoneIds += [string]$created.data.id
    }

    $statuses = @('TODO', 'IN_PROGRESS', 'DONE', 'BLOCKED')
    $priorities = @('LOW', 'MEDIUM', 'HIGH', 'URGENT')
    $taskId = $null
    for ($i = 1; $i -le $SeedTasks; $i++) {
        $milestoneId = if ($i % 3 -eq 0) { $milestoneIds[[int](($i / 3) % 2)] } else { $null }
        $taskBody = @{
            title         = ('[perf] seed {0:D3} {1}' -f $i, $runTag)
            description   = 'Seeded for JMeter read baseline and mixed baseline.'
            milestoneId   = $milestoneId
            status        = $statuses[($i - 1) % $statuses.Count]
            priority      = $priorities[($i - 1) % $priorities.Count]
            estimateHours = 4
            startDate     = $projectStart.AddDays($i % 5).ToString('yyyy-MM-dd')
            dueDate       = $projectStart.AddDays(20 + ($i % 10)).ToString('yyyy-MM-dd')
        }
        $task = Invoke-Json -Method 'POST' -Uri "$BaseUrl/api/v1/projects/$projectId/tasks" -Headers $headers -Body $taskBody
        if ($null -eq $taskId) { $taskId = [string]$task.data.id }
    }

    $session = Invoke-Json -Method 'POST' -Uri "$BaseUrl/api/v1/projects/$projectId/knowledge/sessions" -Headers $headers -Body @{}
    $sessionId = [string]$session.data.id

    $seed = [ordered]@{
        projectId   = $projectId
        taskId      = $taskId
        sessionId   = $sessionId
        projectName = "[perf] jmeter $runTag"
        ownerId     = $ownerId
        tasks       = $SeedTasks
        milestones  = $milestoneIds.Count
        createdAt   = (Get-Date).ToString('o')
        baseUrl     = $BaseUrl
    }
    Write-SeedFile $seed
    Write-Host "Seeded project=$projectId tasks=$SeedTasks task=$taskId session=$sessionId"
}

function Resolve-Jmeter {
    if ([string]::IsNullOrWhiteSpace($JmeterHome)) {
        $JmeterHome = if ($env:JMETER_HOME) { $env:JMETER_HOME } else { Join-Path $repoRoot 'target\perf-tools\apache-jmeter-5.6.3' }
    }
    $candidates = @(
        (Join-Path $JmeterHome 'bin\jmeter.bat'),
        (Join-Path $JmeterHome 'bin\jmeter')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) { return $candidate }
    }
    throw "JMeter not found under $JmeterHome. Set JMETER_HOME or download apache-jmeter-5.6.3."
}

function Add-RunMeta {
    param($Row)
    $file = Join-Path $ResultsDir 'run-meta.csv'
    if (-not (Test-Path $file)) {
        Set-Content -LiteralPath $file -Value 'scenario,start,end,exitCode,jtl,note' -Encoding UTF8
    }
    $line = @($Row.scenario, $Row.start, $Row.end, $Row.exitCode, $Row.jtl, $Row.note) -join ','
    Add-Content -LiteralPath $file -Value $line -Encoding UTF8
}

function Invoke-Scenario {
    param(
        [string]$Name,
        [string]$Jmx,
        [string]$JtlName,
        [string[]]$ExtraProps = @()
    )
    $jmeter = Resolve-Jmeter
    $jmxPath = Join-Path $PSScriptRoot $Jmx
    if (-not (Test-Path $jmxPath)) { throw "JMX not found: $jmxPath" }
    $jtl = Join-Path $ResultsDir "$JtlName.jtl"
    $jmeterLog = Join-Path $ResultsDir "$JtlName.jmeter.log"
    $stdout = Join-Path $ResultsDir "$JtlName.stdout.log"
    $stderr = Join-Path $ResultsDir "$JtlName.stderr.log"
    Remove-Item -LiteralPath $jtl, $jmeterLog, $stdout, $stderr -ErrorAction SilentlyContinue
    $common = @(
        '-Jjmeter.save.saveservice.output_format=csv',
        '-Jjmeter.save.saveservice.default_delimiter=,',
        '-Jjmeter.save.saveservice.print_field_names=true',
        '-Jjmeter.save.saveservice.response_data=false',
        '-Jjmeter.save.saveservice.samplerData=false',
        '-Jjmeter.save.saveservice.requestHeaders=false',
        '-Jjmeter.save.saveservice.responseHeaders=false',
        '-Jjmeter.save.saveservice.response_message=false',
        '-Jjmeter.save.saveservice.failure_message=false',
        '-Jjmeter.save.saveservice.assertion_results_failure_message=false',
        '-Jsample_variables=overheadMs,providerLatencyMs',
        "-Jserver=$server",
        "-Jport=$port",
        "-Jprotocol=$protocol",
        "-JmgmtServer=$mgmtServer",
        "-JmgmtPort=$mgmtPort",
        "-JmgmtProtocol=$mgmtProtocol",
        "-Jtoken=$script:Token",
        "-JprojectId=$script:ProjectId",
        "-JtaskId=$script:TaskId",
        "-JsessionId=$script:SessionId",
        "-JaiServiceToken=$script:AiServiceToken"
    )
    $arguments = @('-n', '-t', $jmxPath, '-l', $jtl, '-j', $jmeterLog) + $common + $ExtraProps
    $start = (Get-Date).ToString('o')
    Write-Host "Running $Name ..."
    $process = Start-Process -FilePath $jmeter -ArgumentList $arguments -Wait -PassThru -NoNewWindow `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $end = (Get-Date).ToString('o')
    $note = if ($process.ExitCode -eq 0) { 'completed' } else { "jmeter exit $($process.ExitCode)" }
    Add-RunMeta @{ scenario = $Name; start = $start; end = $end; exitCode = $process.ExitCode; jtl = $jtl; note = $note }
    Write-Host "$Name finished exit=$($process.ExitCode) jtl=$jtl"
}

function Invoke-Summary {
    $summarizer = Join-Path $PSScriptRoot 'tools\summarize.mjs'
    $summaryLog = Join-Path $ResultsDir 'summary-console.log'
    $process = Start-Process -FilePath (Get-Command node).Source -ArgumentList @($summarizer, $ResultsDir) `
        -Wait -PassThru -NoNewWindow -RedirectStandardOutput $summaryLog -RedirectStandardError (Join-Path $ResultsDir 'summary-error.log')
    Set-Content -LiteralPath (Join-Path $ResultsDir 'summary.exit.txt') -Value $process.ExitCode -Encoding UTF8
    Get-Content -LiteralPath $summaryLog -ErrorAction SilentlyContinue | ForEach-Object { Write-Host $_ }
    return $process.ExitCode
}

function Invoke-Cleanup {
    param([string]$AccessToken)
    $seed = Read-SeedFile
    if ($null -eq $seed) { Write-Host 'No seed.json; nothing to clean.'; return }
    $headers = Get-AuthHeaders $AccessToken
    $result = [ordered]@{ projectId = $seed.projectId; apiDelete = 'not_attempted'; sqlDelete = 'not_attempted'; aiStub = 'not_attempted' }
    try {
        Invoke-Json -Method 'DELETE' -Uri "$BaseUrl/api/v1/projects/$($seed.projectId)" -Headers $headers | Out-Null
        $result.apiDelete = 'ok'
    } catch {
        $result.apiDelete = 'failed'
        if ($envValues.ContainsKey('POSTGRES_DB') -and $envValues.ContainsKey('POSTGRES_USER')) {
            $sql = "DELETE FROM project WHERE id = '$($seed.projectId)';"
            $sql | docker exec -i ai-collab-postgres psql -U $envValues['POSTGRES_USER'] -d $envValues['POSTGRES_DB'] -v ON_ERROR_STOP=1 | Out-Null
            if ($LASTEXITCODE -eq 0) { $result.sqlDelete = 'ok' } else { $result.sqlDelete = 'failed' }
        }
    }
    if ($CleanupAiStub) {
        $aiScript = Join-Path $PSScriptRoot 'tools\seed-ai-stub.ps1'
        if (Test-Path $aiScript) {
            & $aiScript -Action cleanup | Out-Null
            $result.aiStub = 'cleanup_invoked'
        }
    }
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $ResultsDir 'cleanup.json') -Encoding UTF8
    Write-Host "Cleanup project=$($seed.projectId) api=$($result.apiDelete) sql=$($result.sqlDelete)"
}

$needsBackend = $Action -ne 'summary'
$script:Token = if ($needsBackend) { Get-OwnerToken } else { '' }
$seed = Read-SeedFile
if ($seed) {
    if ([string]::IsNullOrWhiteSpace($ProjectId)) { $ProjectId = [string]$seed.projectId }
    if ([string]::IsNullOrWhiteSpace($TaskId)) { $TaskId = [string]$seed.taskId }
    if ([string]::IsNullOrWhiteSpace($SessionId)) { $SessionId = [string]$seed.sessionId }
}

if ($Action -in @('all', 'seed')) {
    Invoke-Seed $script:Token
    $seed = Read-SeedFile
    if ($seed) {
        if ([string]::IsNullOrWhiteSpace($ProjectId)) { $ProjectId = [string]$seed.projectId }
        if ([string]::IsNullOrWhiteSpace($TaskId)) { $TaskId = [string]$seed.taskId }
        if ([string]::IsNullOrWhiteSpace($SessionId)) { $SessionId = [string]$seed.sessionId }
    }
}

if ([string]::IsNullOrWhiteSpace($ProjectId) -or [string]::IsNullOrWhiteSpace($TaskId) -or [string]::IsNullOrWhiteSpace($SessionId)) {
    if ($Action -notin @('seed', 'cleanup', 'summary')) {
        throw 'projectId/taskId/sessionId are required. Run -Action seed first or pass them explicitly.'
    }
}

if ($needsBackend) {
    $health = Invoke-Json -Method 'GET' -Uri "$MgmtUrl/actuator/health"
    if ($health.status -ne 'UP') { throw "Backend management health is $($health.status); expected UP" }
    Write-Host "Backend healthy base=$BaseUrl mgmt=$MgmtUrl results=$ResultsDir"
}

$runAll = $Action -eq 'all'
$failedGate = $false
$readThreads = if ($Threads -gt 0) { $Threads } else { 10 }
$readRamp = if ($Ramp -gt 0) { $Ramp } else { 10 }
$readDuration = if ($Duration -gt 0) { $Duration } else { 60 }
if ($Quick) { $readThreads = 5; $readRamp = 5; $readDuration = 15 }

if ($Action -in @('all', 'run', 'smoke')) {
    Invoke-Scenario -Name 'smoke' -Jmx 'smoke.jmx' -JtlName 'smoke'
}
if ($Action -in @('all', 'run', 'read')) {
    Invoke-Scenario -Name 'read-baseline-b1' -Jmx 'read-baseline.jmx' -JtlName 'read-baseline-b1' -ExtraProps @("-Jthreads=$readThreads", "-Jramp=$readRamp", "-Jduration=$readDuration")
    Invoke-Scenario -Name 'read-baseline-b2' -Jmx 'read-baseline.jmx' -JtlName 'read-baseline-b2' -ExtraProps @("-Jthreads=$readThreads", "-Jramp=$readRamp", "-Jduration=$readDuration")
}
if ($Action -in @('all', 'run', 'mixed')) {
    $mixedThreads = if ($Threads -gt 0) { $Threads } elseif ($Quick) { 4 } else { 8 }
    $mixedDuration = if ($Duration -gt 0) { $Duration } elseif ($Quick) { 15 } else { 60 }
    Invoke-Scenario -Name 'mixed-baseline' -Jmx 'mixed-baseline.jmx' -JtlName 'mixed-baseline' -ExtraProps @("-Jthreads=$mixedThreads", "-Jramp=$mixedThreads", "-Jduration=$mixedDuration")
}
if ($Action -in @('all', 'run', 'spike')) {
    Invoke-Scenario -Name 'spike' -Jmx 'spike.jmx' -JtlName 'spike'
}
if ($Action -in @('all', 'run', 'soak')) {
    if ($Quick) {
        Set-Content -LiteralPath (Join-Path $ResultsDir 'soak.skipped.txt') -Value 'skipped by -Quick' -Encoding UTF8
        Write-Host 'soak skipped by -Quick'
    } else {
        $soakThreads = if ($Threads -gt 0) { $Threads } else { 6 }
        $soakRamp = if ($Ramp -gt 0) { $Ramp } else { 10 }
        $soakDuration = if ($Duration -gt 0) { $Duration } else { 600 }
        Invoke-Scenario -Name 'soak' -Jmx 'soak.jmx' -JtlName 'soak' -ExtraProps @("-Jthreads=$soakThreads", "-Jramp=$soakRamp", "-Jduration=$soakDuration")
    }
}
if ($Action -in @('all', 'run', 'ai')) {
    if ([string]::IsNullOrWhiteSpace($AiServiceToken)) {
        Set-Content -LiteralPath (Join-Path $ResultsDir 'ai-stub.skipped.txt') -Value 'AI_SERVICE_TOKEN not supplied' -Encoding UTF8
        Write-Host 'ai-stub skipped: -AiServiceToken not supplied'
    } else {
        $aiThreads = if ($Threads -gt 0) { $Threads } elseif ($Quick) { 2 } else { 5 }
        $aiDuration = if ($Duration -gt 0) { $Duration } elseif ($Quick) { 15 } else { 60 }
        Invoke-Scenario -Name 'ai-stub' -Jmx 'ai-stub.jmx' -JtlName 'ai-stub' -ExtraProps @("-Jthreads=$aiThreads", "-Jramp=2", "-Jduration=$aiDuration")
    }
}
if ($Action -in @('all', 'run', 'sse')) {
    Invoke-Scenario -Name 'sse-smoke' -Jmx 'sse-smoke.jmx' -JtlName 'sse-smoke'
}
if ($Action -eq 'followup') {
    $fuThreads = if ($Threads -gt 0) { $Threads } else { 20 }
    $fuLoops = if ($Loops -gt 0) { $Loops } else { 500 }
    Invoke-Scenario -Name 'project-detail-warmup' -Jmx 'project-detail-followup.jmx' -JtlName 'project-detail-warmup' -ExtraProps @('-Jthreads=5', '-Jramp=1', '-Jloops=100')
    for ($round = 1; $round -le 3; $round++) {
        Invoke-Scenario -Name "project-detail-r$round" -Jmx 'project-detail-followup.jmx' -JtlName "project-detail-r$round" -ExtraProps @("-Jthreads=$fuThreads", '-Jramp=2', "-Jloops=$fuLoops")
    }
}
if ($Action -in @('all', 'run', 'summary')) {
    $summaryExit = Invoke-Summary
    if ($summaryExit -ne 0) { $failedGate = $true }
}
if ($Action -eq 'all' -and -not $KeepTestData) {
    Invoke-Cleanup $script:Token
}
if ($Action -eq 'cleanup') {
    Invoke-Cleanup $script:Token
}

if ($failedGate) {
    Write-Host 'PERF GATE FAILED: see summary.csv, gate-summary.csv and comparison CSV files.'
    exit 1
}
Write-Host 'Perf run completed.'
