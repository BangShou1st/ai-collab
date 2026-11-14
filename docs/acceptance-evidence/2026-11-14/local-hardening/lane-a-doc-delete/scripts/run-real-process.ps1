$ErrorActionPreference = 'Stop'
$repo = 'E:\project\ai-collab-wt\p0-doc-delete'
$backend = Join-Path $repo 'ai-collab-backend'
$mainBackend = 'E:\project\ai-collab\ai-collab-backend'
$evidence = Join-Path $backend 'target\lane-a-evidence'
$jar = Join-Path $backend 'target\ai-collab-backend-0.0.1-SNAPSHOT.jar'
$appOneLog = Join-Path $evidence 'real-process-app-one.log'
$appOneErr = Join-Path $evidence 'real-process-app-one.err.log'
$appTwoLog = Join-Path $evidence 'real-process-app-two.log'
$appTwoErr = Join-Path $evidence 'real-process-app-two.err.log'
$stateFile = Join-Path $evidence 'real-process-state.txt'
$deleteResponse = Join-Path $evidence 'real-process-delete-202.json'
$uploadFile = Join-Path $evidence 'real-process-upload.txt'
$database = 'ai_collab_lh_a'
$processOne = $null
$processTwo = $null

function Read-EnvValue([string]$key) {
    $line = Get-Content 'E:\project\ai-collab\.env' |
        Where-Object { $_ -match "^\s*$key\s*=" } |
        Select-Object -First 1
    if (-not $line) { throw "missing env key: $key" }
    return ($line -split '=', 2)[1]
}

function Write-NoBom([string]$path, [string]$text) {
    [IO.File]::WriteAllText($path, $text, (New-Object Text.UTF8Encoding($false)))
}

function Invoke-Psql([string]$sql) {
    $output = & docker exec -e "PGPASSWORD=$env:POSTGRES_PASSWORD" ai-collab-postgres `
        psql -U $env:POSTGRES_USER -d $database -v ON_ERROR_STOP=1 -Atc $sql 2>&1
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $($output -join ' ')" }
    return ($output -join "`n")
}

function Wait-Health([string]$uri, [int]$seconds = 120) {
    $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-RestMethod -Uri $uri -TimeoutSec 3
            if ($response.status -eq 'UP') { return }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "health timeout: $uri"
}

function Wait-HttpOk([string]$uri, [int]$seconds = 60) {
    $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    throw "http timeout: $uri"
}

function Start-App([string]$name, [string]$outLog, [string]$errLog) {
    if (-not (Test-Path $jar)) { throw "missing jar: $jar" }
    return Start-Process -FilePath 'E:\Java\jdk-21.0.10\bin\java.exe' `
        -ArgumentList @('-jar', $jar) `
        -WorkingDirectory $mainBackend `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden -PassThru
}

$env:POSTGRES_USER = Read-EnvValue 'POSTGRES_USER'
$env:POSTGRES_PASSWORD = Read-EnvValue 'POSTGRES_PASSWORD'
$env:MINIO_ROOT_USER = Read-EnvValue 'MINIO_ROOT_USER'
$env:MINIO_ROOT_PASSWORD = Read-EnvValue 'MINIO_ROOT_PASSWORD'
$env:DEMO_OWNER_USERNAME = Read-EnvValue 'DEMO_OWNER_USERNAME'
$env:DEMO_OWNER_PASSWORD = Read-EnvValue 'DEMO_OWNER_PASSWORD'
$env:JWT_SECRET = Read-EnvValue 'JWT_SECRET'
$env:MODEL_CONFIG_MASTER_KEY = Read-EnvValue 'MODEL_CONFIG_MASTER_KEY'
$env:REDIS_PASSWORD = Read-EnvValue 'REDIS_PASSWORD'
$env:POSTGRES_DB = $database
$env:AI_COLLAB_PG_HOST_PORT = '5433'
$env:AI_COLLAB_REDIS_HOST_PORT = '6380'
$env:MINIO_ENDPOINT = 'http://127.0.0.1:19002'
$env:MINIO_BUCKET = 'lane-a-real-process'
$env:SERVER_PORT = '18081'
$env:MANAGEMENT_SERVER_PORT = '18091'
$env:SPRING_PROFILES_ACTIVE = 'local'
$env:EMBEDDING_ENABLED = 'false'
$env:CHAT_ENABLED = 'false'
$env:PLANNING_ENABLED = 'false'
$env:AGENT_ENABLED = 'false'
$env:MANAGEMENT_ENDPOINTS_EXPOSURE = 'health,info'
$env:DOCUMENT_DELETION_SCHEDULER_ENABLED = 'false'

$state = [System.Collections.Generic.List[string]]::new()
try {
    & docker rm -f ai-collab-minio-lh-a 2>$null | Out-Null
    & docker run -d --name ai-collab-minio-lh-a `
        -p 19002:9000 -p 19003:9001 `
        -e MINIO_ROOT_USER -e MINIO_ROOT_PASSWORD `
        cgr.dev/chainguard/minio:latest server /data | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'failed to start isolated MinIO' }
    Wait-HttpOk 'http://127.0.0.1:19002/minio/health/ready' 120
    $state.Add("isolated_minio=UP")

    & docker exec -e "PGPASSWORD=$env:POSTGRES_PASSWORD" ai-collab-postgres `
        psql -U $env:POSTGRES_USER -d postgres -v ON_ERROR_STOP=1 `
        -c "DROP DATABASE IF EXISTS $database WITH (FORCE)" 2>&1 | Out-Null
    & docker exec -e "PGPASSWORD=$env:POSTGRES_PASSWORD" ai-collab-postgres `
        psql -U $env:POSTGRES_USER -d postgres -v ON_ERROR_STOP=1 `
        -c "CREATE DATABASE $database" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'failed to create lane database' }
    $state.Add('database=CREATED')

    $processOne = Start-App 'app-one' $appOneLog $appOneErr
    Wait-Health 'http://127.0.0.1:18091/actuator/health' 150
    $state.Add("app_one_pid=$($processOne.Id)")
    $state.Add('app_one_health=UP')

    $loginBody = @{
        username = $env:DEMO_OWNER_USERNAME
        password = $env:DEMO_OWNER_PASSWORD
    } | ConvertTo-Json -Compress
    $login = Invoke-RestMethod -Method Post `
        -Uri 'http://127.0.0.1:18081/api/v1/auth/login' `
        -Headers @{ Origin = 'http://localhost:5173' } `
        -ContentType 'application/json' -Body $loginBody
    $token = $login.data.accessToken
    if (-not $token) { throw 'login did not return access token' }
    $headers = @{ Authorization = "Bearer $token" }
    $state.Add('login=OK')

    $projectBody = @{
        name = 'H1 real process interruption'
        description = 'lane-a synthetic acceptance'
        type = 'PRODUCT_DEVELOPMENT'
    } | ConvertTo-Json -Compress
    $project = Invoke-RestMethod -Method Post `
        -Uri 'http://127.0.0.1:18081/api/v1/projects' `
        -Headers $headers -ContentType 'application/json' -Body $projectBody
    $projectId = $project.data.id
    $state.Add("project_id=$projectId")

    Write-NoBom $uploadFile 'real process deletion fixture'
    $http = [System.Net.Http.HttpClient]::new()
    $http.DefaultRequestHeaders.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    $multipart = [System.Net.Http.MultipartFormDataContent]::new()
    $fileContent = [System.Net.Http.ByteArrayContent]::new(
        [IO.File]::ReadAllBytes($uploadFile))
    $fileContent.Headers.ContentType =
        [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse('text/plain')
    $multipart.Add($fileContent, 'file', 'real-process-upload.txt')
    $multipart.Add([System.Net.Http.StringContent]::new(
        'H1 real process fixture.txt'), 'displayName')
    $uploadResponse = $http.PostAsync(
        "http://127.0.0.1:18081/api/v1/projects/$projectId/documents",
        $multipart).GetAwaiter().GetResult()
    $uploadJson = $uploadResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $uploadResponse.IsSuccessStatusCode) {
        throw "upload failed: $([int]$uploadResponse.StatusCode) $uploadJson"
    }
    $upload = $uploadJson | ConvertFrom-Json
    $documentId = $upload.data.id
    if (-not $documentId) { throw 'upload did not return document id' }
    $state.Add("document_id=$documentId")

    $delete = Invoke-WebRequest -Method Delete `
        -Uri "http://127.0.0.1:18081/api/v1/projects/$projectId/documents/$documentId" `
        -Headers $headers
    if ($delete.StatusCode -ne 202) { throw "unexpected DELETE status: $($delete.StatusCode)" }
    Write-NoBom $deleteResponse $delete.Content
    $state.Add("delete_http_status=$($delete.StatusCode)")

    $beforeKill = Invoke-Psql "SELECT status || '|' || attempts FROM document_deletion_job WHERE project_id='$projectId' AND document_id='$documentId'"
    $state.Add("job_before_kill=$beforeKill")
    Stop-Process -Id $processOne.Id -Force
    $processOne.WaitForExit(10000) | Out-Null
    $processOne = $null
    $state.Add('app_one=FORCE_STOPPED')

    $env:DOCUMENT_DELETION_SCHEDULER_ENABLED = 'true'
    $env:DOCUMENT_DELETION_PURGE_INTERVAL_MS = '500'
    $env:DOCUMENT_DELETION_PURGE_INITIAL_DELAY_MS = '500'
    $env:DOCUMENT_DELETION_RECONCILE_INITIAL_DELAY_MS = '500'
    $processTwo = Start-App 'app-two' $appTwoLog $appTwoErr
    Wait-Health 'http://127.0.0.1:18091/actuator/health' 150
    $state.Add("app_two_pid=$($processTwo.Id)")
    $state.Add('app_two_health=UP')

    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    $finalState = ''
    $finalDocumentCount = ''
    while ([DateTime]::UtcNow -lt $deadline) {
        $finalState = Invoke-Psql "SELECT status || '|' || attempts FROM document_deletion_job WHERE project_id='$projectId' AND document_id='$documentId'"
        $finalDocumentCount = Invoke-Psql "SELECT count(*) FROM project_document WHERE project_id='$projectId' AND id='$documentId'"
        if ($finalState -match '^DONE\|' -and $finalDocumentCount.Trim() -eq '0') { break }
        Start-Sleep -Milliseconds 500
    }
    $state.Add("job_after_restart=$finalState")
    $state.Add("document_rows_after_restart=$finalDocumentCount")
    if ($finalState -notmatch '^DONE\|' -or $finalDocumentCount.Trim() -ne '0') {
        throw "purge did not converge: state=$finalState rows=$finalDocumentCount"
    }
    Write-NoBom $stateFile (($state -join "`n") + "`n")
    $state
} catch {
    $state.Add("ERROR=$($_.Exception.GetType().Name):$($_.Exception.Message)")
    Write-NoBom $stateFile (($state -join "`n") + "`n")
    throw
} finally {
    if ($processOne -and -not $processOne.HasExited) { Stop-Process -Id $processOne.Id -Force }
    if ($processTwo -and -not $processTwo.HasExited) { Stop-Process -Id $processTwo.Id -Force }
    & docker rm -f ai-collab-minio-lh-a 2>$null | Out-Null
    & docker exec -e "PGPASSWORD=$env:POSTGRES_PASSWORD" ai-collab-postgres `
        psql -U $env:POSTGRES_USER -d postgres -v ON_ERROR_STOP=1 `
        -c "DROP DATABASE IF EXISTS $database WITH (FORCE)" 2>$null | Out-Null
    Remove-Item -LiteralPath $uploadFile -Force -ErrorAction SilentlyContinue
}
