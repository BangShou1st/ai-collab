# Windows PowerShell 5.1 / PowerShell 7. No secrets are copied into process arguments or state.
[CmdletBinding()]
param([ValidateSet('start', 'status', 'stop', 'stop-infra')][string]$Action = 'status',
      [ValidateRange(10, 600)][int]$TimeoutSeconds = 120)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$backend = Join-Path $root 'ai-collab-backend'
$frontend = Join-Path $root 'ai-collab-frontend'
$runtime = Join-Path $backend 'target/local-dev'
$stateFile = Join-Path $runtime 'processes.json'
$compose = @('compose', '--env-file', (Join-Path $root '.env'), '-f',
             (Join-Path $root 'ai-collab-deploy/docker-compose.yml'))
$services = @('postgres', 'redis', 'minio')
New-Item -ItemType Directory -Force $runtime | Out-Null

function Read-State {
    if (Test-Path $stateFile) { return Get-Content $stateFile -Raw | ConvertFrom-Json }
    return [pscustomobject]@{ backend = $null; frontend = $null }
}
function Save-State { $script:state | ConvertTo-Json -Depth 5 | Set-Content $stateFile -Encoding UTF8 }
function Run-Logged($exe, $arguments, $log) {
    $previous = $ErrorActionPreference
    try {
        # Windows PowerShell represents native stderr as errors; retain it in the log,
        # then diagnose using the exit code instead of leaking an unqualified exception.
        $ErrorActionPreference = 'Continue'
        & $exe @arguments *> $log
        return $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
}
function Get-Identity($processId) {
    $p = Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
    if (!$p) { return $null }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { $hash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes([string]$p.CommandLine))) }
    finally { $sha.Dispose() }
    return [pscustomobject]@{ pid = [int]$p.ProcessId; created = $p.CreationDate.ToFileTimeUtc().ToString();
        executable = $p.ExecutablePath; commandHash = $hash }
}
function Matches($record) {
    if (!$record) { return $false }
    $current = Get-Identity $record.pid
    return ($current -and $current.created -eq $record.created -and
        $current.executable -eq $record.executable -and $current.commandHash -eq $record.commandHash)
}
function Listeners($port) {
    return @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object LocalPort -eq $port | Select-Object -ExpandProperty OwningProcess -Unique)
}
function Healthy($name) {
    try {
        if ($name -eq 'backend') {
            return ((Local-Http 'http://127.0.0.1:8080/actuator/health') | ConvertFrom-Json).status -eq 'UP'
        }
        return (Local-Http 'http://localhost:5173') -match '/@vite/client'
    } catch { return $false }
}
function Local-Http($url) {
    # Do not route local probes through an inherited system/HTTP proxy.
    $request = [Net.WebRequest]::Create($url)
    $request.Proxy = $null
    $request.Timeout = 3000
    $request.ReadWriteTimeout = 3000
    $response = $request.GetResponse()
    try {
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $response.Dispose() }
}
function Wait-App($name) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        if (!(Matches $state.$name)) { throw "$name exited; see $runtime/$name.stderr.log and $name.stdout.log" }
        $port = if ($name -eq 'backend') { 8080 } else { 5173 }
        $owners = Listeners $port
        if ($owners.Count -and $state.$name.pid -notin $owners) { Assert-Free $port }
        if ($state.$name.pid -in $owners -and (Healthy $name)) { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "$name health check timed out; see $runtime/$name.stderr.log and $name.stdout.log. Processes retained for diagnosis; use stop."
}
function Inspect-Container($name) {
    $previous = $ErrorActionPreference
    try { $ErrorActionPreference = 'Continue'; $raw = & docker inspect $name 2>$null }
    finally { $ErrorActionPreference = $previous }
    if ($LASTEXITCODE -ne 0) { return $null }
    return @($raw | ConvertFrom-Json)[0]
}
function Assert-Container($service, $container) {
    if ($container.Config.Labels.'com.docker.compose.project' -ne 'ai-collab-deploy' -or
        $container.Config.Labels.'com.docker.compose.service' -ne $service) {
        throw "ai-collab-$service belongs to another setup; resolve the container name conflict manually."
    }
}
function Infra {
    if ((Run-Logged 'docker' @('info') (Join-Path $runtime 'docker-info.log')) -ne 0) {
        throw "Docker engine unavailable. Start Docker Desktop; see $runtime/docker-info.log"
    }
    foreach ($service in $services) {
        Write-Host "Checking infrastructure: $service"
        $container = Inspect-Container "ai-collab-$service"
        if ($container) {
            Assert-Container $service $container
            # Start the existing container, preserving its image and mounts even when compose changed.
            if (!$container.State.Running) {
                if ((Run-Logged 'docker' @('start', "ai-collab-$service") (Join-Path $runtime "infra-$service.log")) -ne 0) {
                    throw "$service start failed; see $runtime/infra-$service.log"
                }
            }
        } else {
            $ports = @{ postgres = @(5432); redis = @(6379); minio = @(9000,9001) }
            foreach ($port in $ports[$service]) { Assert-Free $port }
            # A missing container with a surviving volume is recovery, not a fresh install.
            $previous = $ErrorActionPreference
            try { $ErrorActionPreference = 'Continue'; & docker volume inspect "ai-collab-deploy_ai_collab_${service}_data" *> $null }
            finally { $ErrorActionPreference = $previous }
            if ($LASTEXITCODE -eq 0) {
                throw "$service has an existing data volume but no container. Restore its original image/container first; no automatic upgrade."
            }
            if ((Run-Logged 'docker' ($compose + @('up','-d','--no-deps','--no-recreate',$service)) (Join-Path $runtime "infra-$service.log")) -ne 0) {
                throw "$service creation failed; see $runtime/infra-$service.log"
            }
        }
    }
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $ready = $true
        $failures = @()
        foreach ($service in @('postgres','redis')) {
            $c = Inspect-Container "ai-collab-$service"
            if (!$c.State.Running -or $c.State.Health.Status -ne 'healthy') {
                $ready = $false
                $failures += "$service running=$($c.State.Running) health=$($c.State.Health.Status)"
            }
        }
        try {
            $null = Local-Http 'http://127.0.0.1:9000/minio/health/ready'
        } catch { $ready = $false; $failures += 'minio readiness HTTP request failed (127.0.0.1:9000)' }
        if ($ready) { return }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    foreach ($service in $services) { $null = Run-Logged 'docker' @('logs','--tail','100',"ai-collab-$service") (Join-Path $runtime "infra-$service.log") }
    throw "Infrastructure health check timed out: $($failures -join '; '). See $runtime/infra-*.log. Existing containers/volumes retained."
}
function Assert-Free($port) {
    $owners = Listeners $port
    if ($owners.Count) {
        $details = @($owners | ForEach-Object {
            $p = Get-CimInstance Win32_Process -Filter "ProcessId=$_"
            "PID=$_ name=$($p.Name) executable=$($p.ExecutablePath)"
        }) -join '; '
        throw "Port $port occupied: $details. No process stopped. Resolve the conflict manually."
    }
}
function Check-App($name, $port) {
    if (Matches $state.$name) {
        Wait-App $name
        Write-Host "$name reused PID=$($state.$name.pid) managed=$($state.$name.managed)"
        return $true
    }
    $owners = Listeners $port
    if (!$owners.Count) { return $false }
    if ($owners.Count -eq 1) {
        $p = Get-CimInstance Win32_Process -Filter "ProcessId=$($owners[0])"
        $belongs = if ($name -eq 'backend') {
            $p.Name -eq 'java.exe' -and $p.CommandLine.IndexOf($backend, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
                $p.CommandLine.Contains('com.shitulelv.aicollab.AiCollabBackendApplication')
        } else { $p.Name -eq 'node.exe' -and $p.CommandLine.IndexOf((Join-Path $frontend 'node_modules/vite/bin/vite.js'), [StringComparison]::OrdinalIgnoreCase) -ge 0 }
        if ($belongs -and (Healthy $name)) {
            $record = Get-Identity $owners[0]
            $record | Add-Member managed $false
            $state.$name = $record
            Save-State
            Write-Host "$name borrowed PID=$($record.pid); stop will leave it running."
            return $true
        }
    }
    Assert-Free $port
}
function Launch($name, $exe, $arguments, $directory) {
    Assert-Free $(if ($name -eq 'backend') { 8080 } else { 5173 })
    # WMI creates an independent hidden console without inherited terminal pipe handles. cmd redirects
    # directly to files, so log capture survives PowerShell exiting and nested callers return.
    if (($root + $exe) -match '[%"\r\n]') { throw 'Repository/tool path contains characters unsafe for Windows command launching.' }
    $command = '"' + $env:ComSpec + '" /d /v:off /s /c ""' + $exe + '" ' + $arguments + ' >"' +
        (Join-Path $runtime "$name.stdout.log") + '" 2>"' + (Join-Path $runtime "$name.stderr.log") + '""'
    $startup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{
        ShowWindow = [uint16]0; CreateFlags = [uint32]16
    }
    $launch = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine = $command; CurrentDirectory = $directory; ProcessStartupInformation = $startup
    }
    if ($launch.ReturnValue -ne 0) { throw "$name Windows process creation failed (code $($launch.ReturnValue)); no process adopted." }
    $record = $null
    $deadline = (Get-Date).AddSeconds(10)
    do {
        $child = Get-CimInstance Win32_Process -Filter "ParentProcessId=$($launch.ProcessId)" |
            Where-Object ExecutablePath -eq $exe | Select-Object -First 1
        if ($child) { $record = Get-Identity $child.ProcessId; break }
        if (!(Get-Process -Id $launch.ProcessId -ErrorAction SilentlyContinue)) { break }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    if (!$record) { throw "$name exited immediately; see $runtime/$name.stderr.log" }
    $record | Add-Member managed $true
    $record | Add-Member stdout (Join-Path $runtime "$name.stdout.log")
    $record | Add-Member stderr (Join-Path $runtime "$name.stderr.log")
    $state.$name = $record
    Save-State
    Wait-App $name
}
function Validate-Config {
    $envFile = Join-Path $root '.env'
    if (!(Test-Path $envFile)) { throw 'Missing root .env. Copy .env.example only if absent and configure it; no file generated or overwritten.' }
    # .env is Java properties, as loaded by application-local.yml, not a shell script.
    $values = @{}
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=(.*)$') { $values[$matches[1]] = $matches[2].Trim() }
    }
    foreach ($key in @('POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','REDIS_PASSWORD','MINIO_ROOT_USER',
        'MINIO_ROOT_PASSWORD','JWT_SECRET','MODEL_CONFIG_MASTER_KEY','DEMO_OWNER_PASSWORD')) {
        if (!$values[$key] -or $values[$key] -match '^(change-me|replace-with)') { throw "Missing or placeholder $key in root .env. Keep existing encryption keys; configure manually." }
    }
    if ($values['AUTH_ALLOWED_ORIGINS'] -and 'http://localhost:5173' -notin ($values['AUTH_ALLOWED_ORIGINS'] -split ',')) {
        throw 'AUTH_ALLOWED_ORIGINS must include http://localhost:5173; do not use a wildcard.'
    }
    if ($values['AUTH_ALLOWED_ORIGINS'] -match '\*') { throw 'AUTH_ALLOWED_ORIGINS must use exact origins, not wildcards.' }
    if ($values['MINIO_ENDPOINT'] -and $values['MINIO_ENDPOINT'] -ne 'http://localhost:9000') {
        throw 'This workflow requires local MinIO at http://localhost:9000; check root .env.'
    }
    foreach ($file in @('.env','.env.local','.env.development','.env.development.local')) {
        $path = Join-Path $frontend $file
        if (Test-Path $path) {
            foreach ($line in Get-Content $path) {
                if ($line -match '^\s*VITE_BACKEND_ORIGIN\s*=(.+)$' -and $matches[1].Trim(' ', '"', "'") -ne 'http://localhost:8080') {
                    throw "Frontend $file proxy differs from http://localhost:8080. Resolve the configuration manually."
                }
                if ($line -match '^\s*VITE_API_BASE_URL\s*=(.+)$' -and $matches[1].Trim(' ', '"', "'") -ne '/api/v1') {
                    throw "Frontend $file API prefix differs from /api/v1. Resolve the configuration manually."
                }
            }
        }
    }
    # Inherited environment wins over properties/Vite files. Reject ambiguous local overrides.
    foreach ($key in (@($values.Keys) + @('SPRING_APPLICATION_JSON','SPRING_CONFIG_LOCATION','SPRING_CONFIG_ADDITIONAL_LOCATION',
        'SPRING_CONFIG_IMPORT','SPRING_PROFILES_INCLUDE',
        'SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD','SPRING_DATA_REDIS_HOST',
        'SPRING_DATA_REDIS_PORT','SPRING_DATA_REDIS_PASSWORD','MINIO_ENDPOINT','MINIO_ACCESS_KEY','MINIO_SECRET_KEY',
        'MODEL_CONFIG_MASTER_KEY','JWT_SECRET','AUTH_ALLOWED_ORIGINS','VITE_BACKEND_ORIGIN','VITE_API_BASE_URL'))) {
        if ([Environment]::GetEnvironmentVariable($key)) { throw "Inherited $key overrides local files. Use a clean shell or resolve it manually; value not displayed." }
    }
}

$lock = $null
try {
    try { $lock = [IO.File]::Open((Join-Path $runtime 'workflow.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another dev.ps1 operation is running; wait for it to finish.' }
    $state = Read-State
    switch ($Action) {
        'status' {
            foreach ($name in @('backend','frontend')) {
                $port = if ($name -eq 'backend') { 8080 } else { 5173 }
                Write-Host "$name port=$port listeners=$((Listeners $port) -join ',') identityMatch=$(Matches $state.$name) managed=$($state.$name.managed) healthy=$(Healthy $name)"
            }
            if (Get-Command docker -ErrorAction SilentlyContinue) {
                foreach ($service in $services) {
                    $c = Inspect-Container "ai-collab-$service"
                    if ($c) { Write-Host "$service running=$($c.State.Running) health=$($c.State.Health.Status) image=$($c.Config.Image)" }
                    else { Write-Host "$service absent (or Docker unavailable)" }
                }
            }
            Write-Host "Logs and process identity: $runtime"
        }
        'stop' {
            foreach ($name in @('frontend','backend')) {
                $record = $state.$name
                if ($record -and $record.managed -and (Matches $record)) {
                    Stop-Process -Id $record.pid
                    Write-Host "$name stopped PID=$($record.pid)"
                    $state.$name = $null
                } else { Write-Host "$name skipped (borrowed, absent, or identity changed)" }
            }
            Save-State
        }
        'stop-infra' {
            foreach ($service in $services) {
                $c = Inspect-Container "ai-collab-$service"
                if ($c) { Assert-Container $service $c }
            }
            & docker @compose stop @services
            if ($LASTEXITCODE -ne 0) { throw 'Infrastructure stop failed. No volumes removed.' }
        }
        'start' {
            Write-Host 'Checking local configuration and dependencies'
            Validate-Config
            foreach ($tool in @('docker','java','node','pnpm')) {
                if (!(Get-Command $tool -ErrorAction SilentlyContinue)) { throw "Missing dependency $tool; see README environment requirements." }
            }
            $versionCheck = Start-Process -FilePath (Get-Command java).Source -ArgumentList '-version' -WindowStyle Hidden -Wait -PassThru `
                -RedirectStandardOutput (Join-Path $runtime 'java-version.stdout.log') -RedirectStandardError (Join-Path $runtime 'java-version.stderr.log')
            $javaVersion = Get-Content (Join-Path $runtime 'java-version.stderr.log') -Raw
            if ($versionCheck.ExitCode -ne 0) { throw "java -version failed; see $runtime/java-version.stderr.log" }
            if ($javaVersion -notmatch 'version "21\.') { throw 'JDK 21 required (java on PATH).' }
            $nodeVersion = & node --version
            if ([int]($nodeVersion.TrimStart('v').Split('.')[0]) -lt 22) { throw 'Node.js 22+ required.' }
            if (!(Test-Path (Join-Path $frontend 'node_modules/vite/bin/vite.js'))) {
                throw 'Frontend dependencies missing. Run pnpm install --frozen-lockfile in ai-collab-frontend, then retry.'
            }
            # Preflight both application ports before any infrastructure mutation.
            $haveBackend = Check-App 'backend' 8080
            $haveFrontend = Check-App 'frontend' 5173
            Write-Host 'Checking Docker infrastructure (existing containers are preserved)'
            Infra
            if (!$haveBackend) {
                Write-Host "Compiling backend; log: $runtime/build.log"
                Push-Location $backend
                try {
                    $code = Run-Logged '.\mvnw.cmd' @('-B','-ntp','-DskipTests','compile','dependency:build-classpath',
                        '-Dmdep.outputFile=target/local-dev/classpath.txt') (Join-Path $runtime 'build.log')
                    if ($code -ne 0) { throw "Backend compile/dependency resolution failed; see $runtime/build.log" }
                } finally { Pop-Location }
                $classpath = (Join-Path $backend 'target/classes') + ';' + (Get-Content (Join-Path $runtime 'classpath.txt') -Raw).Trim()
                $argFile = Join-Path $runtime 'java.args'
                [IO.File]::WriteAllText($argFile, ('-cp "' + $classpath.Replace('\','/') + '"'), [Text.UTF8Encoding]::new($false))
                Launch 'backend' (Get-Command java).Source ('"@' + $argFile + '" com.shitulelv.aicollab.AiCollabBackendApplication --spring.profiles.active=local --server.port=8080') $backend
            }
            if (!$haveFrontend) {
                Launch 'frontend' (Get-Command node).Source ('"' + (Join-Path $frontend 'node_modules/vite/bin/vite.js') + '" --port 5173 --strictPort') $frontend
            }
            Write-Host "Ready: http://localhost:5173 ; API http://localhost:8080 ; MinIO http://localhost:9001"
            Write-Host "backend PID=$($state.backend.pid); frontend PID=$($state.frontend.pid); logs=$runtime"
        }
    }
} catch {
    Write-Error $_.Exception.Message -ErrorAction Continue
    exit 1
} finally { if ($lock) { $lock.Dispose() } }
