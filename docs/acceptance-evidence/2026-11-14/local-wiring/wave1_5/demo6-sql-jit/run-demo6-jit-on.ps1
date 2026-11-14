param(
    [string]$DatabaseName = 'ai_collab_lane_b_demo6',
    [string]$EnvFile = 'E:\project\ai-collab\.env',
    [switch]$RecreateDatabase
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..\..\..')).Path
$backend = Join-Path $repo 'ai-collab-backend'
$jar = Join-Path $backend 'target\ai-collab-backend-0.0.1-SNAPSHOT.jar'
$raw = Join-Path $PSScriptRoot 'raw'
$logs = Join-Path $backend 'target\demo6-sql-jit'
$baseUrl = 'http://127.0.0.1:18097'
$healthUrl = 'http://127.0.0.1:18098/actuator/health'
$origin = 'http://localhost:5173'
$container = 'ai-collab-postgres'
$dbUser = 'ai_collab'
$dbHost = '127.0.0.1'
$dbPort = 5433
$seedSql = Join-Path $PSScriptRoot 'seed-demo6.sql'

New-Item -ItemType Directory -Force -Path $raw | Out-Null
New-Item -ItemType Directory -Force -Path $logs | Out-Null

if (-not (Test-Path $jar)) {
    throw "backend jar not found: $jar"
}
if (-not (Test-Path $EnvFile)) {
    throw "environment file not found: $EnvFile"
}
if ($DatabaseName -ne 'ai_collab_lane_b_demo6') {
    throw 'refusing to manage an unexpected database name'
}

$config = @{}
Get-Content -Encoding UTF8 $EnvFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') {
        $config[$matches[1].Trim()] = $matches[2].Trim()
    }
}
foreach ($key in @('POSTGRES_PASSWORD', 'DEMO_OWNER_USERNAME', 'DEMO_OWNER_PASSWORD')) {
    if (-not $config[$key]) {
        throw "missing $key in environment file"
    }
}

function Invoke-Psql([string]$database, [string[]]$arguments) {
    $output = & docker exec -e "PGPASSWORD=$($config['POSTGRES_PASSWORD'])" `
        $container psql -X -v ON_ERROR_STOP=1 -U $dbUser -d $database @arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "psql failed: $($output -join [Environment]::NewLine)"
    }
    return $output
}

function Ensure-Database {
    $exists = ((& docker exec -e "PGPASSWORD=$($config['POSTGRES_PASSWORD'])" `
            $container psql -X -At -U $dbUser -d postgres `
            -c "SELECT 1 FROM pg_database WHERE datname='$DatabaseName'") -join '').Trim()
    if ($RecreateDatabase -and $exists -eq '1') {
        Invoke-Psql 'postgres' @('-c', "DROP DATABASE `"$DatabaseName`" WITH (FORCE)") | Out-Null
        $exists = ''
    }
    if ($exists -ne '1') {
        Invoke-Psql 'postgres' @('-c', "CREATE DATABASE `"$DatabaseName`"") | Out-Null
    }
}

function Wait-Health {
    for ($index = 0; $index -lt 120; $index++) {
        if ($script:appProcess -and $script:appProcess.HasExited) {
            throw "application exited with code $($script:appProcess.ExitCode)"
        }
        try {
            $request = [Net.WebRequest]::Create($healthUrl)
            $request.Proxy = $null
            $request.Timeout = 3000
            $response = $request.GetResponse()
            try {
                $reader = [IO.StreamReader]::new($response.GetResponseStream())
                try {
                    if ($reader.ReadToEnd() -match '"status"\s*:\s*"UP"') {
                        return
                    }
                } finally {
                    $reader.Dispose()
                }
            } finally {
                $response.Dispose()
            }
        } catch {
        }
        Start-Sleep -Seconds 1
    }
    throw "health did not become UP: $healthUrl"
}

function Stop-App {
    if ($script:appProcess -and -not $script:appProcess.HasExited) {
        $script:appProcess.Kill($true)
        $script:appProcess.WaitForExit(15000) | Out-Null
    }
}

function Percentile([double[]]$values, [double]$quantile) {
    $sorted = @($values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($sorted.Count * $quantile) - 1)
    return [double]$sorted[$index]
}

function Invoke-TaskPage(
        [System.Net.Http.HttpClient]$client,
        [string]$token,
        [int]$sample,
        [string]$bodyPath) {
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Get,
        "$baseUrl/api/v1/projects/${script:projectId}/tasks?page=0&size=200")
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $response = $client.SendAsync(
        $request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    $ttfb = $watch.Elapsed.TotalMilliseconds
    $status = [int]$response.StatusCode
    if ($status -ne 200) {
        throw "task page returned HTTP $status"
    }
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $total = $watch.Elapsed.TotalMilliseconds
    if ($bodyPath) {
        [IO.File]::WriteAllBytes($bodyPath, $bytes)
    }
    $request.Dispose()
    $response.Dispose()
    return [ordered]@{
        sample = $sample
        status = $status
        ttfbMs = [Math]::Round($ttfb, 3)
        totalMs = [Math]::Round($total, 3)
        bytes = $bytes.Length
    }
}

function Write-JsonFile([string]$path, $value) {
    $json = $value | ConvertTo-Json -Depth 8
    [IO.File]::WriteAllText($path, $json, [Text.UTF8Encoding]::new($false))
}

function Write-TextFile([string]$path, [string]$value) {
    [IO.File]::WriteAllText($path, $value, [Text.UTF8Encoding]::new($false))
}

$script:projectId = [guid]::NewGuid().ToString()
$milestoneId = [guid]::NewGuid().ToString()
$scenarioStarted = Get-Date
$script:appProcess = $null
$stdout = Join-Path $logs 'app-18097.stdout.log'
$stderr = Join-Path $logs 'app-18097.stderr.log'

try {
    Ensure-Database
    $arguments = @(
        '-XX:MaxRAMPercentage=50',
        '-jar', $jar,
        '--spring.profiles.active=local',
        '--server.port=18097',
        '--management.server.port=18098',
        "--spring.datasource.url=jdbc:postgresql://$($dbHost):$($dbPort)/$DatabaseName",
        '--logging.level.com.shitulelv.aicollab=DEBUG'
    )
    $script:appProcess = Start-Process -FilePath 'java.exe' -ArgumentList $arguments `
        -WorkingDirectory (Join-Path 'E:\project\ai-collab' 'ai-collab-backend') `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
        -WindowStyle Hidden -PassThru
    Wait-Health

    $seedArguments = @(
        '-v', "project_id=$script:projectId",
        '-v', "milestone_id=$milestoneId",
        '-v', "owner_username=$($config['DEMO_OWNER_USERNAME'])",
        '-f', '-'
    )
    Get-Content -Raw -Encoding UTF8 $seedSql |
        & docker exec -i -e "PGPASSWORD=$($config['POSTGRES_PASSWORD'])" `
            $container psql -X -v ON_ERROR_STOP=1 -U $dbUser -d $DatabaseName @seedArguments
    if ($LASTEXITCODE -ne 0) {
        throw 'seeding failed'
    }
    $seededTasks = ((Invoke-Psql $DatabaseName @(
                '-At', '-c', "SELECT count(*) FROM project_task WHERE project_id='$($script:projectId)'")) -join '').Trim()
    $seededDependencies = ((Invoke-Psql $DatabaseName @(
                '-At', '-c', "SELECT count(*) FROM task_dependency d JOIN project_task t ON t.id=d.task_id WHERE t.project_id='$($script:projectId)'")) -join '').Trim()
    if ($seededTasks -ne '10000' -or $seededDependencies -ne '200') {
        throw "unexpected seed counts: tasks=$seededTasks dependencies=$seededDependencies"
    }
    Set-Content -Encoding UTF8 (Join-Path $raw 'demo6-project-id.txt') $script:projectId

    $loginBody = @{
        username = $config['DEMO_OWNER_USERNAME']
        password = $config['DEMO_OWNER_PASSWORD']
    } | ConvertTo-Json
    $handler = [System.Net.Http.SocketsHttpHandler]::new()
    $handler.UseProxy = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(60)
    $loginRequest = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post, "$baseUrl/api/v1/auth/login")
    $loginRequest.Headers.Add('Origin', $origin)
    $loginRequest.Content = [System.Net.Http.StringContent]::new(
        $loginBody, [Text.Encoding]::UTF8, 'application/json')
    $loginResponse = $client.SendAsync($loginRequest).GetAwaiter().GetResult()
    $loginText = $loginResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if ([int]$loginResponse.StatusCode -ne 200) {
        throw "login returned HTTP $([int]$loginResponse.StatusCode)"
    }
    $token = ($loginText | ConvertFrom-Json).data.accessToken
    if (-not $token) {
        throw 'login did not return an access token'
    }
    $loginRequest.Dispose()
    $loginResponse.Dispose()

    for ($index = 0; $index -lt 10; $index++) {
        Invoke-TaskPage $client $token ($index + 1) $null | Out-Null
    }

    $responsePath = Join-Path $raw 'demo6-response-jit-on.json'
    $samples = @()
    for ($index = 0; $index -lt 50; $index++) {
        $bodyPath = if ($index -eq 0) { $responsePath } else { $null }
        $samples += Invoke-TaskPage $client $token ($index + 1) $bodyPath
    }
    $client.Dispose()

    $latencies = [double[]]@($samples | ForEach-Object { $_.totalMs })
    $ttfb = [double[]]@($samples | ForEach-Object { $_.ttfbMs })
    $p95 = [Math]::Round((Percentile $latencies 0.95), 3)
    $measurementStatus = if ($p95 -lt 100) { 'MEASURED_LOCAL' } else { 'BLOCKED' }
    $result = [ordered]@{
        status = $measurementStatus
        measuredAt = (Get-Date).ToString('o')
        baseUrl = $baseUrl
        endpoint = '/api/v1/projects/{projectId}/tasks?page=0&size=200'
        database = $DatabaseName
        databaseHost = "$($dbHost):$($dbPort)"
        projectId = $script:projectId
        loginMethod = 'POST /api/v1/auth/login with DEMO_OWNER_* from local .env'
        warmupSamples = 10
        samples = 50
        targetP95Ms = 100
        responseBytes = $samples[0].bytes
        p50Ms = [Math]::Round((Percentile $latencies 0.50), 3)
        p95Ms = $p95
        p99Ms = [Math]::Round((Percentile $latencies 0.99), 3)
        minMs = [Math]::Round(($latencies | Measure-Object -Minimum).Minimum, 3)
        maxMs = [Math]::Round(($latencies | Measure-Object -Maximum).Maximum, 3)
        ttfbP50Ms = [Math]::Round((Percentile $ttfb 0.50), 3)
        ttfbP95Ms = [Math]::Round((Percentile $ttfb 0.95), 3)
        responseFile = 'raw/demo6-response-jit-on.json'
        samplesFile = 'raw/demo6-http-jit-on.json'
        rawSamples = @($samples)
    }
    Write-JsonFile (Join-Path $raw 'demo6-http-jit-on.json') $result

    $settingOutput = @()
    $settingOutput += 'database=' + $DatabaseName
    $settingOutput += 'project_id=' + $script:projectId
    $settingOutput += 'project_task_count=' + ((Invoke-Psql $DatabaseName @(
                '-At', '-c', "SELECT count(*) FROM project_task WHERE project_id='$($script:projectId)'")) -join '').Trim()
    $settingOutput += 'task_dependency_count=' + ((Invoke-Psql $DatabaseName @(
                '-At', '-c', "SELECT count(*) FROM task_dependency d JOIN project_task t ON t.id=d.task_id WHERE t.project_id='$($script:projectId)'")) -join '').Trim()
    foreach ($setting in @('jit', 'jit_above_cost', 'jit_inline_above_cost', 'jit_optimize_above_cost')) {
        $value = ((Invoke-Psql $DatabaseName @('-At', '-c', "SHOW $setting")) -join '').Trim()
        $settingOutput += "$setting=$value"
    }
    Write-TextFile (Join-Path $raw 'demo6-jit-setting.txt') ($settingOutput -join [Environment]::NewLine)

    $explainSql = @"
WITH filtered AS (
    SELECT t.id, t.created_at
    FROM project_task t
    WHERE t.project_id='$($script:projectId)'
), total AS (
    SELECT count(*) AS total_count FROM filtered
), page_ids AS (
    SELECT id, created_at
    FROM filtered
    ORDER BY created_at ASC, id ASC
    LIMIT 200 OFFSET 0
)
SELECT t.*, u.display_name AS assignee_display_name, m.name AS milestone_name,
  (SELECT count(*) FROM task_dependency d
   JOIN project_task required ON required.id=d.depends_on_task_id
   WHERE d.task_id=t.id AND required.project_id=t.project_id
     AND required.status NOT IN ('DONE','CANCELED')) AS unfinished_dependency_count,
  total.total_count
FROM total
LEFT JOIN page_ids p ON TRUE
LEFT JOIN project_task t ON t.id=p.id
LEFT JOIN app_user u ON u.id=t.assignee_id
LEFT JOIN milestone m ON m.id=t.milestone_id AND m.project_id=t.project_id
ORDER BY p.created_at ASC, p.id ASC
"@
    $explainOutput = @(
        "database=$DatabaseName",
        "project_id=$($script:projectId)",
        'jit.setting=on is required; no SET jit has been issued by this script'
    )
    for ($index = 1; $index -le 5; $index++) {
        $explainOutput += "`n===== explain run $index ====="
        $explainOutput += Invoke-Psql $DatabaseName @(
            '-c', "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $explainSql")
    }
    $explainOutput += "`n===== jit setting after explain ====="
    $explainOutput += Invoke-Psql $DatabaseName @('-At', '-c', 'SHOW jit')
    Write-TextFile (Join-Path $raw 'demo6-explain-analyze-buffers-jit-on.txt') `
        ($explainOutput -join [Environment]::NewLine)

    $elapsed = (Get-Date) - $scenarioStarted
    Write-Host ("{0} samples={1} p50Ms={2} p95Ms={3} p99Ms={4} bytes={5} elapsedSeconds={6}" -f `
            $measurementStatus, $result.samples, $result.p50Ms, $result.p95Ms, $result.p99Ms, `
            $result.responseBytes, `
            [Math]::Round($elapsed.TotalSeconds, 1))
} finally {
    Stop-App
}
