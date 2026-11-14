param(
    [string]$OutputDir = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'
$repo = 'E:\project\ai-collab-wt\wiring-loopback-perf'
$backend = Join-Path $repo 'ai-collab-backend'
$jar = Join-Path $backend 'target\ai-collab-backend-0.0.1-SNAPSHOT.jar'
$envFile = 'E:\project\ai-collab\.env'
$raw = Join-Path $OutputDir 'raw'
$baseUrl = 'http://127.0.0.1:18093'
$healthUrl = 'http://127.0.0.1:18096/actuator/health'
$origin = 'http://localhost:5173'
$projectId = (Get-Content -Raw -Encoding UTF8 (Join-Path $raw 'demo6-project-id.txt')).Trim()

New-Item -ItemType Directory -Force -Path $raw | Out-Null

$config = @{}
Get-Content -Encoding UTF8 $envFile | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') {
        $config[$matches[1].Trim()] = $matches[2].Trim()
    }
}

function Get-AppProcess {
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq 'java.exe' -and
            $_.CommandLine -like '*wiring-loopback-perf*ai-collab-backend-0.0.1-SNAPSHOT.jar*' -and
            $_.CommandLine -like '*--server.port=18093*'
        }
}

function Stop-App {
    foreach ($item in Get-AppProcess) {
        $process = Get-Process -Id $item.ProcessId -ErrorAction SilentlyContinue
        if ($process) {
            $process.Kill($true)
            $process.WaitForExit(10000) | Out-Null
        }
    }
    for ($index = 0; $index -lt 30; $index++) {
        $listener = Get-NetTCPConnection -State Listen -LocalPort 18093 -ErrorAction SilentlyContinue
        if (-not $listener) { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'port 18093 did not become free'
}

function Wait-Health {
    for ($index = 0; $index -lt 60; $index++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 $healthUrl
            $text = if ($response.Content -is [byte[]]) {
                [Text.Encoding]::UTF8.GetString($response.Content)
            } else {
                [string]$response.Content
            }
            if ($text -match '"status"\s*:\s*"UP"') { return }
        } catch {
        }
        Start-Sleep -Seconds 2
    }
    throw "application health did not become UP: $healthUrl"
}

function Start-App([string]$level) {
    Stop-App
    $stamp = Get-Date -Format 'HHmmss'
    $stdout = Join-Path $raw "app-18093-$($level.ToLowerInvariant())-$stamp.out.txt"
    $stderr = Join-Path $raw "app-18093-$($level.ToLowerInvariant())-$stamp.err.txt"
    $arguments = @(
        '-XX:MaxRAMPercentage=50',
        '-jar', $jar,
        '--spring.profiles.active=local',
        '--server.port=18093',
        '--management.server.port=18096',
        '--spring.datasource.url=jdbc:postgresql://localhost:5433/ai_collab_w3_perf',
        "--logging.level.com.shitulelv.aicollab=$level"
    )
    $process = Start-Process -FilePath 'java.exe' -ArgumentList $arguments `
        -WorkingDirectory 'E:\project\ai-collab\ai-collab-backend' `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
        -WindowStyle Hidden -PassThru
    Wait-Health
    return [pscustomobject]@{ Process = $process; Stdout = $stdout; Stderr = $stderr }
}

function Login {
    $body = @{
        username = $config['DEMO_OWNER_USERNAME']
        password = $config['DEMO_OWNER_PASSWORD']
    } | ConvertTo-Json
    $response = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/v1/auth/login" `
        -Headers @{ Origin = $origin } -ContentType 'application/json' -Body $body
    if (-not $response.data.accessToken) { throw 'login did not return an access token' }
    return $response.data.accessToken
}

function Percentile([double[]]$values, [double]$quantile) {
    $sorted = @($values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($sorted.Count * $quantile) - 1)
    return [double]$sorted[$index]
}

function Measure-Once(
        [System.Net.Http.HttpClient]$client,
        [string]$token,
        [bool]$closeConnection,
        [string]$bodyPath) {
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Get,
        "$baseUrl/api/v1/projects/$projectId/tasks?page=0&size=200")
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    if ($closeConnection) { $request.Headers.ConnectionClose = $true }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $response = $client.SendAsync(
        $request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    $ttfb = $watch.Elapsed.TotalMilliseconds
    if ([int]$response.StatusCode -ne 200) { throw "task page returned $([int]$response.StatusCode)" }
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $total = $watch.Elapsed.TotalMilliseconds
    $request.Dispose()
    $response.Dispose()
    if ($bodyPath -and $bytes.Length -gt 0) {
        [IO.File]::WriteAllBytes($bodyPath, $bytes)
    }
    return [pscustomobject]@{
        ttfb = [double]$ttfb
        total = [double]$total
        bytes = [int]$bytes.Length
    }
}

function Measure-Phase([string]$tag, [string]$level) {
    $app = Start-App $level
    try {
        $token = Login
        $before = (Get-Item $app.Stdout).Length
        $handler = [System.Net.Http.SocketsHttpHandler]::new()
        $handler.PooledConnectionLifetime = [TimeSpan]::FromMinutes(5)
        $client = [System.Net.Http.HttpClient]::new($handler)
        $client.Timeout = [TimeSpan]::FromSeconds(60)
        $bodyPath = Join-Path $raw "demo6-response-$tag.json"

        for ($index = 0; $index -lt 10; $index++) {
            Measure-Once $client $token $false $null | Out-Null
        }
        $pooled = @()
        for ($index = 0; $index -lt 50; $index++) {
            $pooled += Measure-Once $client $token $false $(if ($index -eq 0) { $bodyPath } else { $null })
        }
        $cold = @()
        for ($index = 0; $index -lt 15; $index++) {
            $cold += Measure-Once $client $token $true $null
        }
        $client.Dispose()
        $after = (Get-Item $app.Stdout).Length

        $pooledTotal = [double[]]@($pooled | ForEach-Object total)
        $pooledTtfb = [double[]]@($pooled | ForEach-Object ttfb)
        $coldTotal = [double[]]@($cold | ForEach-Object total)
        $result = [ordered]@{
            phase = $tag
            logLevel = $level
            samples = $pooled.Count
            coldSamples = $cold.Count
            responseBytes = $pooled[0].bytes
            pooled = [ordered]@{
                ttfbP50Ms = [Math]::Round((Percentile $pooledTtfb 0.50), 1)
                ttfbP95Ms = [Math]::Round((Percentile $pooledTtfb 0.95), 1)
                totalP50Ms = [Math]::Round((Percentile $pooledTotal 0.50), 1)
                totalP95Ms = [Math]::Round((Percentile $pooledTotal 0.95), 1)
                totalP99Ms = [Math]::Round((Percentile $pooledTotal 0.99), 1)
                minMs = [Math]::Round(($pooledTotal | Measure-Object -Minimum).Minimum, 1)
                maxMs = [Math]::Round(($pooledTotal | Measure-Object -Maximum).Maximum, 1)
            }
            coldConnection = [ordered]@{
                p50Ms = [Math]::Round((Percentile $coldTotal 0.50), 1)
                p95Ms = [Math]::Round((Percentile $coldTotal 0.95), 1)
            }
            debugLogBytes = $after - $before
            debugLogBytesPerSample = [Math]::Round(($after - $before) / [Math]::Max(1, $pooled.Count), 1)
            stdout = $app.Stdout
            stderr = $app.Stderr
        }
        $result | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $raw "demo6-$tag.json")
        return $result
    } finally {
        Stop-App
    }
}

function Run-Explain {
    $file = Join-Path $raw 'demo6-db-explain.txt'
    $query1 = @"
WITH filtered AS (
    SELECT t.*, u.display_name AS assignee_display_name, m.name AS milestone_name,
      (SELECT count(*) FROM task_dependency d
       JOIN project_task required ON required.id=d.depends_on_task_id
       WHERE d.task_id=t.id AND required.project_id=t.project_id
         AND required.status NOT IN ('DONE','CANCELED')) AS unfinished_dependency_count
    FROM project_task t
    LEFT JOIN app_user u ON u.id=t.assignee_id
    LEFT JOIN milestone m ON m.id=t.milestone_id AND m.project_id=t.project_id
    WHERE t.project_id='$projectId'
), total AS (
    SELECT count(*) AS total_count FROM filtered
), page AS (
    SELECT * FROM filtered ORDER BY created_at ASC, id ASC LIMIT 200 OFFSET 0
)
SELECT total.total_count, page.*
FROM total LEFT JOIN page ON TRUE
ORDER BY page.created_at ASC, page.id ASC
"@
    $query2 = @"
SELECT d.task_id AS taskId, d.depends_on_task_id AS dependsOnTaskId
FROM task_dependency d
JOIN project_task t ON t.id=d.task_id
JOIN project_task required ON required.id=d.depends_on_task_id
WHERE t.project_id='$projectId' AND required.project_id='$projectId'
  AND d.task_id IN (
      SELECT id FROM project_task WHERE project_id='$projectId'
      ORDER BY created_at, id LIMIT 200
  )
ORDER BY d.task_id, d.depends_on_task_id
"@
    Set-Content -Encoding UTF8 $file "project_id=$projectId`n"
    foreach ($query in @(@('page', $query1), @('dependency', $query2))) {
        for ($index = 0; $index -lt 5; $index++) {
            Add-Content -Encoding UTF8 $file "`n===== $($query[0]) run $($index + 1) ====="
            $output = & docker exec -e "PGPASSWORD=$($config['POSTGRES_PASSWORD'])" ai-collab-postgres `
                psql -U $config['POSTGRES_USER'] -d ai_collab_w3_perf `
                -c "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) $($query[1])" 2>&1
            Add-Content -Encoding UTF8 $file ($output | Out-String)
        }
    }
}

function Run-SerializationProbe([string]$bodyPath) {
    $classpathFile = Join-Path $backend 'target\classpath.txt'
    Push-Location $backend
    try {
        cmd /c mvnw.cmd -q -B "-Dmdep.outputFile=$classpathFile" "-Dmdep.includeScope=runtime" dependency:build-classpath
        if ($LASTEXITCODE -ne 0) { throw 'dependency:build-classpath failed' }
        $classpath = (Get-Content -Raw -Encoding UTF8 $classpathFile).Trim() + ';' + (Join-Path $backend 'target\classes')
        $probeClasses = Join-Path $backend 'target\w3-probe'
        New-Item -ItemType Directory -Force -Path $probeClasses | Out-Null
        $source = Join-Path $PSScriptRoot 'SerializationProbe.java'
        $result = Join-Path $raw 'demo6-serialization.json'
        $javacArgsFile = Join-Path $backend 'target\w3-javac.args'
        @(
            '-encoding UTF-8',
            "-cp $classpath",
            "-d $probeClasses",
            $source
        ) | Set-Content -Encoding UTF8 $javacArgsFile
        $compileOut = Join-Path $raw 'serialization-javac.out.txt'
        $compileErr = Join-Path $raw 'serialization-javac.err.txt'
        $compile = Start-Process -FilePath 'javac.exe' `
            -ArgumentList "@$javacArgsFile" `
            -WorkingDirectory $backend -RedirectStandardOutput $compileOut `
            -RedirectStandardError $compileErr -WindowStyle Hidden -Wait -PassThru
        if ($compile.ExitCode -ne 0) {
            Get-Content -Raw -Encoding UTF8 $compileErr | Out-String | Write-Error
            throw 'SerializationProbe compilation failed'
        }
        $runOut = Join-Path $raw 'serialization-java.out.txt'
        $runErr = Join-Path $raw 'serialization-java.err.txt'
        $javaArgsFile = Join-Path $backend 'target\w3-java.args'
        @(
            "-cp $probeClasses;$classpath",
            'SerializationProbe',
            $bodyPath,
            $result
        ) | Set-Content -Encoding UTF8 $javaArgsFile
        $run = Start-Process -FilePath 'java.exe' `
            -ArgumentList "@$javaArgsFile" `
            -WorkingDirectory $backend -RedirectStandardOutput $runOut `
            -RedirectStandardError $runErr -WindowStyle Hidden -Wait -PassThru
        if ($run.ExitCode -ne 0) {
            Get-Content -Raw -Encoding UTF8 $runErr | Out-String | Write-Error
            throw 'SerializationProbe execution failed'
        }
    } finally {
        Pop-Location
    }
}

Stop-App
$debug = Measure-Phase 'debug' 'DEBUG'
$info = Measure-Phase 'info' 'INFO'
Run-Explain
Run-SerializationProbe (Join-Path $raw 'demo6-response-debug.json')
$summary = [ordered]@{ projectId = $projectId; debug = $debug; info = $info }
$summary | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $raw 'demo6-breakdown.json')
$summary | ConvertTo-Json -Depth 10
