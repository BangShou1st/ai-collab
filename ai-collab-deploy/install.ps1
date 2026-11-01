# AI Collab 本地一键安装与维护入口（Windows PowerShell 5.1+ / PowerShell 7）。
# 面向最终用户：只要求 Docker Desktop，不要求 Java/Maven/Node/pnpm。
# 与 Linux/macOS 的 install.sh 管理同一套 compose.local.yml 拓扑，命令语义保持一致。
#
# 用法：
#   .\install.ps1 install        # 首次安装（幂等，可重复执行）
#   .\install.ps1 bootstrap-admin # 首位管理员初始化（仅空系统可用一次）
#   .\install.ps1 status|up|stop|restart|logs|doctor|version
#   .\install.ps1 backup         # 备份数据库与对象存储（含校验）
#   .\install.ps1 restore -Dump <file.dump> [-Minio <file.tgz>]
#   .\install.ps1 upgrade -Tag <版本>   # 备份 -> 更新镜像 -> 健康检查 -> 失败回滚
#   .\install.ps1 uninstall [-RemoveData]
#
# 退出码：0 成功；1 一般错误；2 Docker 不可用；3 配置缺失/占位符；
#         4 端口冲突；5 架构不兼容；10 升级失败（已回滚）。
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$Command = 'status',
    [int]$Port,
    [string]$Image,           # 后端镜像覆盖（本地构建 or 注册表引用）
    [string]$FrontendImage,   # 前端镜像覆盖
    [string]$Tag = 'latest',  # 从 GHCR 解析默认镜像时使用的标签
    [string]$Username = 'admin',
    [int]$Tail = 100,
    [switch]$Follow,
    [string]$Dump,            # restore：PostgreSQL 备份文件
    [string]$Minio,           # restore：MinIO 备份归档
    [switch]$RemoveData
)
$ErrorActionPreference = 'Stop'
$DeployDir = $PSScriptRoot
$RepoRoot = Split-Path -Parent $DeployDir
$EnvFile = Join-Path $DeployDir 'local.env'
$ComposeFile = Join-Path $DeployDir 'compose.local.yml'
$BackupRoot = Join-Path $RepoRoot 'backups\local'
$HistoryLog = Join-Path $BackupRoot 'upgrade-history.log'
$BackendDevImage = 'ai-collab-backend:local'
$FrontendDevImage = 'ai-collab-frontend:local'
$RegistryBackend = "ghcr.io/bangshou1st/ai-collab-backend:$Tag"
$RegistryFrontend = "ghcr.io/bangshou1st/ai-collab-frontend:$Tag"
# 判定占位符（local.env.example 中的 REQUIRED_* 不允许直接使用）。
$script:ExitCode = 0

function Info($msg) { Write-Host "[install] $msg" }
function Fail([int]$code, $msg) { Write-Host "[install] ERROR: $msg" -ForegroundColor Red; exit $code }

function Compose { & docker compose --env-file $EnvFile -f $ComposeFile @args; return $LASTEXITCODE }
function ComposeQuiet { & docker compose --env-file $EnvFile -f $ComposeFile @args *> $null; return $LASTEXITCODE }
function ServiceId($service) {
    $ids = & docker compose --env-file $EnvFile -f $ComposeFile ps -q $service 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return @($ids | Where-Object { $_ })[0]
}
function ContainerHealth($service) {
    $id = ServiceId $service
    if (!$id) { return 'absent' }
    $raw = & docker inspect $id 2>$null
    if ($LASTEXITCODE -ne 0) { return 'absent' }
    $c = @($raw | ConvertFrom-Json)[0]
    if (!$c.State.Running) { return 'exited' }
    if ($c.State.Health) { return $c.State.Health.Status }
    return 'running'
}
function Read-EnvFile {
    $values = @{}
    if (Test-Path $EnvFile) {
        foreach ($line in Get-Content $EnvFile) {
            if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=(.*)$') { $values[$matches[1]] = $matches[2].Trim() }
        }
    }
    return $values
}
function Set-EnvValue($key, $value) {
    $lines = @(Get-Content $EnvFile -ErrorAction SilentlyContinue)
    $found = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^\s*$key\s*=") { $lines[$i] = "$key=$value"; $found = $true; break }
    }
    if (!$found) { $lines += "$key=$value" }
    Set-Content -Path $EnvFile -Value $lines -Encoding Ascii
}
function New-Random([int]$bytes) {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $buf = New-Object byte[] $bytes
        $rng.GetBytes($buf)
        return ([BitConverter]::ToString($buf)).Replace('-', '').ToLowerInvariant()
    } finally { $rng.Dispose() }
}
function Test-PortBusy([int]$p) {
    return @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -eq $p).Count -gt 0
}
# 本机 HTTP 探测：绕过继承的系统代理，凭据绝不进入参数。
function Invoke-Api([string]$method, [string]$path, $body, [string]$token) {
    $base = "http://localhost:$($env:AI_PORT)"
    $curlArgs = @('--noproxy', '*', '-s', '-S', '-X', $method, "$base$path",
        '-H', 'Content-Type: application/json', '--max-time', '30')
    if ($body) { $curlArgs += @('-d', ($body | ConvertTo-Json -Depth 5 -Compress)) }
    if ($token) { $curlArgs += @('-H', "Authorization: Bearer $token") }
    $raw = & curl.exe @curlArgs
    if ($LASTEXITCODE -ne 0 -or !$raw) { return $null }
    try { return ($raw -join "`n") | ConvertFrom-Json } catch { return $null }
}
function Assert-Docker {
    if (!(Get-Command docker -ErrorAction SilentlyContinue)) { Fail 2 '未找到 docker 命令。请先安装并启动 Docker Desktop：https://www.docker.com/products/docker-desktop/' }
    $null = & docker info 2>$null
    if ($LASTEXITCODE -ne 0) { Fail 2 'Docker 引擎不可用。请启动 Docker Desktop 并等待其就绪后重试。' }
    & docker compose version *> $null
    if ($LASTEXITCODE -ne 0) { Fail 2 '缺少 Compose v2 插件（docker compose version 失败）。请升级 Docker Desktop。' }
}
function Assert-ArchCompatible {
    $serverArch = (& docker version --format '{{.Server.Arch}}')
    # 后端镜像内置 linux-x64-musl 的 Bun（Zen 中继依赖），仅提供 amd64。
    if ($serverArch -match 'arm|aarch') {
        Write-Warning "Docker 引擎架构为 $serverArch：后端镜像为 amd64 构建，依赖模拟层（Docker Desktop 的 Rosetta/QEMU）。性能会明显下降，建议在 x64 主机上运行。"
    }
}
function Assert-EnvReady {
    if (!(Test-Path $EnvFile)) { Fail 3 "缺少 $EnvFile。请先执行： .\install.ps1 install" }
    $values = Read-EnvFile
    foreach ($key in @('APP_IMAGE', 'WEB_IMAGE', 'HTTP_PORT', 'POSTGRES_DB', 'POSTGRES_USER', 'POSTGRES_PASSWORD',
            'REDIS_PASSWORD', 'MINIO_ROOT_USER', 'MINIO_ROOT_PASSWORD', 'JWT_SECRET', 'MODEL_CONFIG_MASTER_KEY')) {
        if (!$values[$key] -or $values[$key] -match '^REQUIRED_') { Fail 3 "local.env 缺少有效配置：$key（请重新执行 install，且不要手工填入 REQUIRED_* 占位符）" }
    }
    $env:AI_PORT = $values['HTTP_PORT']
    return $values
}
function Wait-WebHealthy([int]$timeoutSec) {
    Info "等待服务就绪（首次启动需完成数据库迁移，可能需要 1-3 分钟）..."
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        $raw = & curl.exe --noproxy '*' -s --max-time 5 "http://localhost:$($env:AI_PORT)/api/v1/auth/registration-policy" 2>$null
        if ($LASTEXITCODE -eq 0 -and $raw -match '"code"\s*:\s*"SUCCESS"') { return $true }
        Start-Sleep -Seconds 5
    }
    return $false
}
function Wait-ContainerHealthy([string]$service, [int]$timeoutSec) {
    $deadline = (Get-Date).AddSeconds($timeoutSec)
    while ((Get-Date) -lt $deadline) {
        if ((ContainerHealth $service) -eq 'healthy') { return $true }
        Start-Sleep -Seconds 5
    }
    return $false
}
function Try-Pull([string]$ref) {
    & docker pull $ref *> $null
    return ($LASTEXITCODE -eq 0)
}
function Resolve-Images {
    # 解析顺序：命令行参数 > local.env 已有有效值 > 注册表拉取 > 本地源码构建。
    $values = Read-EnvFile
    $backend = $Image
    if (!$backend) { $backend = $values['APP_IMAGE']; if ($backend -match '^REQUIRED_') { $backend = $null } }
    if (!$backend) {
        Info "尝试拉取预构建后端镜像 $RegistryBackend ..."
        if (Try-Pull $RegistryBackend) { $backend = $RegistryBackend }
    }
    $frontend = $FrontendImage
    if (!$frontend) { $frontend = $values['WEB_IMAGE']; if ($frontend -match '^REQUIRED_') { $frontend = $null } }
    if (!$frontend) {
        Info "尝试拉取预构建前端镜像 $RegistryFrontend ..."
        if (Try-Pull $RegistryFrontend) { $frontend = $RegistryFrontend }
    }
    if (!$backend -and (Test-Path (Join-Path $RepoRoot 'ai-collab-backend\Dockerfile'))) {
        Info "预构建镜像不可用，改为从本地源码构建后端镜像（无需 Java/Maven，约需几分钟）..."
        $rev = $null; try { $rev = & git -C $RepoRoot rev-parse HEAD } catch {}
        $buildArgs = @('build', '-f', (Join-Path $RepoRoot 'ai-collab-backend\Dockerfile'), '-t', $BackendDevImage)
        if ($rev) { $buildArgs += @('--label', "org.opencontainers.image.revision=$rev") }
        & docker @buildArgs (Join-Path $RepoRoot '.') | ForEach-Object { Write-Verbose $_ }
        if ($LASTEXITCODE -ne 0) { Fail 1 '后端镜像本地构建失败。请检查 Docker 磁盘空间与网络。' }
        $backend = $BackendDevImage
    }
    if (!$frontend -and (Test-Path (Join-Path $RepoRoot 'ai-collab-frontend\Dockerfile'))) {
        Info "从本地源码构建前端镜像（无需 Node/pnpm）..."
        $rev = $null; try { $rev = & git -C $RepoRoot rev-parse HEAD } catch {}
        $buildArgs = @('build', '-f', (Join-Path $RepoRoot 'ai-collab-frontend\Dockerfile'), '-t', $FrontendDevImage)
        if ($rev) { $buildArgs += @('--label', "org.opencontainers.image.revision=$rev") }
        & docker @buildArgs (Join-Path $RepoRoot '.') | ForEach-Object { Write-Verbose $_ }
        if ($LASTEXITCODE -ne 0) { Fail 1 '前端镜像本地构建失败。请检查 Docker 磁盘空间与网络。' }
        $frontend = $FrontendDevImage
    }
    if (!$backend -or !$frontend) { Fail 3 '无法解析应用镜像（无预构建镜像且当前目录不含源码）。请用 -Image / -FrontendImage 显式指定。' }
    return @{ backend = $backend; frontend = $frontend }
}
function New-EnvFile($images) {
    if (Test-Path $EnvFile) {
        Info "已存在 local.env，保留现有凭据与数据配置（幂等安装）。"
        if ($PSBoundParameters.ContainsKey('Port')) { Set-EnvValue 'HTTP_PORT' "$Port" }
        Set-EnvValue 'APP_IMAGE' $images.backend
        Set-EnvValue 'WEB_IMAGE' $images.frontend
        return
    }
    $httpPort = if ($PSBoundParameters.ContainsKey('Port')) { $Port } else { 8080 }
    $content = @"
APP_IMAGE=$($images.backend)
WEB_IMAGE=$($images.frontend)
HTTP_PORT=$httpPort
POSTGRES_DB=ai_collab
POSTGRES_USER=ai_collab
POSTGRES_PASSWORD=$(New-Random 24)
REDIS_PASSWORD=$(New-Random 24)
MINIO_ROOT_USER=ai-collab
MINIO_ROOT_PASSWORD=$(New-Random 24)
MINIO_BUCKET=ai-collab
JWT_SECRET=$(New-Random 48)
MODEL_CONFIG_MASTER_KEY=$(New-Random 32)
AUTH_PUBLIC_REGISTRATION_ENABLED=true
AGENT_RUNTIME_ALLOWED=true
BACKUP_RETENTION_DAYS=14
"@
    Set-Content -Path $EnvFile -Value $content -Encoding Ascii
    Info "已生成 local.env（含随机凭据，文件已在 .gitignore 中排除，请勿提交或外传）。"
}
function Invoke-Backup([switch]$silent) {
    $values = Read-EnvFile
    $ts = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ')
    $pgDir = Join-Path $BackupRoot "postgres"
    $minioDir = Join-Path $BackupRoot "minio"
    New-Item -ItemType Directory -Force $pgDir, $minioDir | Out-Null
    $pgId = ServiceId 'postgres'; $minioId = ServiceId 'minio'
    if (!$pgId -or !$minioId) { Fail 1 '备份目标容器未运行；请先执行 .\install.ps1 up' }

    # ---- PostgreSQL：容器内 pg_dump -> docker cp（避免 PowerShell 管道破坏二进制流）----
    $dumpFile = Join-Path $pgDir "pg_$($values['POSTGRES_DB'])_$ts.dump"
    & docker exec -e PGPASSWORD=$($values['POSTGRES_PASSWORD']) $pgId sh -c "pg_dump -h localhost -U '$($values['POSTGRES_USER'])' -d '$($values['POSTGRES_DB'])' -Fc -f /tmp/ai-collab-backup.dump"
    if ($LASTEXITCODE -ne 0) { Fail 1 'pg_dump 执行失败' }
    & docker cp "${pgId}:/tmp/ai-collab-backup.dump" $dumpFile | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail 1 '备份文件复制失败' }
    & docker exec $pgId rm -f /tmp/ai-collab-backup.dump | Out-Null
    $size = (Get-Item $dumpFile).Length
    if ($size -le 0) { Remove-Item $dumpFile -Force; Fail 1 'pg_dump 输出为空（0 字节），已删除不完整备份' }
    # 完整性校验 1：pg_restore --list 可解析。
    $manifest = "$dumpFile.manifest.txt"
    & docker cp $dumpFile "${pgId}:/tmp/ai-collab-verify.dump" | Out-Null
    & docker exec $pgId sh -c "pg_restore --list /tmp/ai-collab-verify.dump" > $manifest
    if ($LASTEXITCODE -ne 0) { Remove-Item $dumpFile, $manifest -Force -ErrorAction SilentlyContinue; Fail 1 '完整性校验失败：pg_restore --list 无法解析备份' }
    & docker exec $pgId rm -f /tmp/ai-collab-verify.dump | Out-Null
    # 完整性校验 2：SHA256。
    $hash = (Get-FileHash $dumpFile -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -Path "$dumpFile.sha256" -Value "$hash  $(Split-Path -Leaf $dumpFile)" -Encoding Ascii

    # ---- MinIO：容器内 mc mirror 到临时目录 -> docker cp -> tar 归档 ----
    $mcTmp = "/tmp/ai-collab-backup-minio"
    & docker exec $minioId sh -c "rm -rf $mcTmp && mc alias set bkp http://127.0.0.1:9000 '$($values['MINIO_ROOT_USER'])' '$($values['MINIO_ROOT_PASSWORD'])' >/dev/null && mc mirror --overwrite bkp/$($values['MINIO_BUCKET']) $mcTmp/bucket"
    if ($LASTEXITCODE -ne 0) { Fail 1 'MinIO 备份（mc mirror）失败' }
    $extractDir = Join-Path $env:TEMP "ai-collab-minio-$ts"
    New-Item -ItemType Directory -Force $extractDir | Out-Null
    & docker cp "${minioId}:$mcTmp/bucket" "$extractDir/bucket" | Out-Null
    & docker exec $minioId sh -c "rm -rf $mcTmp" | Out-Null
    $tgz = Join-Path $minioDir "minio_$($values['MINIO_BUCKET'])_$ts.tgz"
    & tar -czf $tgz -C $extractDir bucket
    if ($LASTEXITCODE -ne 0) { Fail 1 'MinIO 备份归档（tar）失败' }
    Remove-Item $extractDir -Recurse -Force
    $minioHash = (Get-FileHash $tgz -Algorithm SHA256).Hash.ToLowerInvariant()
    Set-Content -Path "$tgz.sha256" -Value "$minioHash  $(Split-Path -Leaf $tgz)" -Encoding Ascii

    # ---- 保留策略 ----
    $retention = 14
    if ($values['BACKUP_RETENTION_DAYS']) { $retention = [int]$values['BACKUP_RETENTION_DAYS'] }
    if ($retention -gt 0) {
        Get-ChildItem $pgDir, $minioDir -File -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-$retention) } |
            Remove-Item -Force -ErrorAction SilentlyContinue
    }
    $summary = "database=$($values['POSTGRES_DB']) dump=$dumpFile size_bytes=$size sha256=$hash`n" +
        "minio_bucket=$($values['MINIO_BUCKET']) archive=$tgz sha256=$minioHash`n" +
        "created_utc=$ts`n" +
        "note=数据库与对象存储先后备份，非原子快照；跨系统一致性以恢复演练为准（见 docs/deployment/local-install.md）。"
    Set-Content -Path "$dumpFile.summary" -Value $summary -Encoding UTF8
    if (!$silent) {
        Info "备份完成："
        Info "  PostgreSQL: $dumpFile ($size 字节, SHA256 已校验)"
        Info "  MinIO:      $tgz (SHA256 已校验)"
        Info "  摘要:       $dumpFile.summary"
    }
    return @{ dump = $dumpFile; minio = $tgz }
}

switch ($Command) {
    'help' {
        Write-Host "AI Collab 本地部署维护命令：install | bootstrap-admin | up | stop | restart | status | logs | doctor | backup | restore | upgrade | version | uninstall"
    }
    'install' {
        Assert-Docker
        Assert-ArchCompatible
        $values = Read-EnvFile
        $httpPort = if ($PSBoundParameters.ContainsKey('Port')) { $Port } else { if ($values['HTTP_PORT']) { [int]$values['HTTP_PORT'] } else { 8080 } }
        if (Test-PortBusy $httpPort) {
            $owners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -eq $httpPort | Select-Object -ExpandProperty OwningProcess -Unique)
            $detail = ($owners | ForEach-Object { try { (Get-Process -Id $_).ProcessName } catch { "PID=$_" } }) -join ', '
            Fail 4 "端口 $httpPort 已被占用（$detail）。请换用其他端口： .\install.ps1 install -Port 8090"
        }
        $images = Resolve-Images
        New-EnvFile $images
        $values = Read-EnvFile
        $env:AI_PORT = $values['HTTP_PORT']
        Info "启动服务（postgres/redis/minio/app/web）..."
        $code = Compose 'up' '-d'
        if ($code -ne 0) { Fail 1 'compose up 失败；可用 .\install.ps1 logs 查看详情' }
        if (!(Wait-WebHealthy 420)) {
            & docker compose --env-file $EnvFile -f $ComposeFile logs --tail 50 app
            Fail 1 '应用健康检查超时；容器已保留供诊断，可查看上方日志或运行 .\install.ps1 doctor'
        }
        Info ""
        Info "安装完成！访问地址: http://localhost:$($values['HTTP_PORT'])"
        Info "下一步：创建首位管理员 ->  .\install.ps1 bootstrap-admin"
        Info "日常维护：status | stop | up | logs | backup | doctor（详见 docs/deployment/local-install.md）"
    }
    'bootstrap-admin' {
        $values = Assert-EnvReady
        if ($Username -notmatch '^[A-Za-z0-9_]{3,40}$') { Fail 3 '用户名只允许字母、数字与下划线，长度 3-40。' }
        $pgId = ServiceId 'postgres'
        if (!$pgId) { Fail 1 'PostgreSQL 容器未运行；请先执行 .\install.ps1 up' }
        $countRaw = & docker exec $pgId psql -U $values['POSTGRES_USER'] -d $values['POSTGRES_DB'] -t -A -c 'SELECT count(*) FROM app_user WHERE system_admin = true;'
        if ($LASTEXITCODE -ne 0) { Fail 1 '无法查询管理员数量（数据库尚未初始化？请先执行 install 并等待启动完成）' }
        if ([int](@($countRaw)[0]) -gt 0) {
            Info '系统已存在管理员，初始化通道保持关闭；本命令不再执行（防重复引导）。'
            return
        }
        $password = "ac-" + (New-Random 12) + "-A1"
        $reg = Invoke-Api 'POST' '/api/v1/auth/register' @{
            username    = $Username; password = $password
            displayName = 'System Administrator'; email = "$Username@ai-collab.local"
        }
        if (!$reg -or $reg.code -ne 'SUCCESS') { Fail 1 "注册接口调用失败：$($reg | ConvertTo-Json -Depth 5 -Compress)。若系统已关闭公开注册，请先在界面或其他渠道创建普通用户，再联系运维处理。" }
        if ($reg.data.user.systemAdmin) { Fail 1 '安全异常：公开注册用户被自动赋予管理员角色，已中止。' }
        # 受控提权：PostgreSQL 事务级咨询锁保证并发引导只有一个赢家。
        $u = $Username
        $sql = "BEGIN; SELECT pg_advisory_xact_lock(917463); " +
            "UPDATE app_user SET system_admin = true WHERE username = '$u' AND (SELECT count(*) FROM app_user WHERE system_admin = true) = 0; COMMIT;"
        $out = & docker exec $pgId psql -U $values['POSTGRES_USER'] -d $values['POSTGRES_DB'] -t -A -c $sql
        if ($LASTEXITCODE -ne 0) { Fail 1 '提权 SQL 执行失败，新注册用户保持普通用户状态。' }
        if ($out -notmatch 'UPDATE 1') {
            Info '并发竞争中另一位管理员已完成初始化，本次未提权（新注册用户保留为普通用户）。'
            return
        }
        # 用新凭据重新登录验证提权生效。
        $login = Invoke-Api 'POST' '/api/v1/auth/login' @{ username = $Username; password = $password }
        if (!$login -or $login.code -ne 'SUCCESS') { Fail 1 '初始化后登录验证失败，请立即检查应用日志。' }
        $me = Invoke-Api 'GET' '/api/v1/auth/me' $null $login.data.accessToken
        if (!$me -or !$me.data.systemAdmin) { Fail 1 '权限验证失败：/auth/me 未返回 systemAdmin=true。' }
        Info ""
        Info "首位管理员创建成功（本凭据仅显示这一次，不会写入任何日志或文件）："
        Info "  用户名: $Username"
        Info "  密  码: $password"
        Info ""
        Info "请立即登录 http://localhost:$($values['HTTP_PORT']) 并在个人设置中修改密码。"
    }
    'up' {
        $values = Assert-EnvReady
        $code = Compose 'up' '-d'
        if ($code -ne 0) { Fail 1 'compose up 失败' }
        if (Wait-WebHealthy 300) { Info "已启动: http://localhost:$($values['HTTP_PORT'])" }
        else { Fail 1 '启动后健康检查未通过；请用 .\install.ps1 logs 查看' }
    }
    'stop' {
        Assert-EnvReady | Out-Null
        Info '停止服务（数据卷保留，不会删除任何数据）...'
        $code = Compose 'stop'
        if ($code -ne 0) { Fail 1 '停止失败' }
        Info '已停止。数据保留在 Docker 卷中，下次 up/install 自动恢复。'
    }
    'restart' {
        Assert-EnvReady | Out-Null
        $code = Compose 'restart'
        if ($code -ne 0) { Fail 1 '重启失败' }
        $values = Read-EnvFile; $env:AI_PORT = $values['HTTP_PORT']
        if (Wait-WebHealthy 300) { Info "已重启: http://localhost:$($values['HTTP_PORT'])" } else { Fail 1 '重启后健康检查未通过' }
    }
    'status' {
        $values = Assert-EnvReady
        foreach ($svc in @('postgres', 'redis', 'minio', 'app', 'web')) {
            $h = ContainerHealth $svc
            Write-Host ("{0,-9} {1}" -f $svc, $h)
        }
        $raw = & curl.exe --noproxy '*' -s --max-time 5 "http://localhost:$($values['HTTP_PORT'])/api/v1/auth/registration-policy" 2>$null
        if ($LASTEXITCODE -eq 0 -and $raw -match 'SUCCESS') { Info "Web 入口正常: http://localhost:$($values['HTTP_PORT'])" }
        else { Info "Web 入口暂不可达（服务未启动或仍在初始化）" }
    }
    'logs' {
        Assert-EnvReady | Out-Null
        $logArgs = @('logs', '--tail', "$Tail")
        if ($Follow) { $logArgs += '-f' }
        $code = Compose @logArgs
        if ($code -ne 0) { Fail 1 '日志获取失败' }
    }
    'doctor' {
        Assert-Docker
        Info "Docker 引擎: OK ($(& docker version --format '{{.Server.Version}}'))"
        Assert-ArchCompatible
        $values = Read-EnvFile
        if (!(Test-Path $EnvFile)) { Write-Warning 'local.env 不存在（尚未安装）。执行 .\install.ps1 install 开始安装。' }
        else {
            foreach ($key in @('APP_IMAGE', 'WEB_IMAGE', 'JWT_SECRET', 'MODEL_CONFIG_MASTER_KEY')) {
                if (!$values[$key] -or $values[$key] -match '^REQUIRED_') { Write-Warning "local.env 配置不完整：$key" }
            }
        }
        $port = if ($values['HTTP_PORT']) { [int]$values['HTTP_PORT'] } else { 8080 }
        if (Test-Path $EnvFile) {
            if (Test-PortBusy $port) { Info "端口 ${port}: 占用（预期内：本部署入口）" } else { Write-Warning "端口 ${port}: 未监听（web 服务未启动）" }
        } elseif (Test-PortBusy $port) { Write-Warning "端口 ${port}: 被其他程序占用，安装前请用 -Port 换端口" }
        if (Test-Path $EnvFile) {
            foreach ($svc in @('postgres', 'redis', 'minio', 'app', 'web')) {
                $h = ContainerHealth $svc
                if ($h -in @('healthy', 'running')) { Info "容器 $svc`: $h" } else { Write-Warning "容器 $svc`: $h" }
            }
        }
        $drive = Get-PSDrive -Name ($RepoRoot.Substring(0, 1))
        Info "磁盘剩余空间（$($RepoRoot.Substring(0,1))盘): $([math]::Round($drive.Free/1GB, 1)) GB"
        $null = & docker system df
        Info 'doctor 完成。'
    }
    'backup' {
        Assert-EnvReady | Out-Null
        Invoke-Backup | Out-Null
    }
    'restore' {
        $values = Assert-EnvReady
        if (!$Dump -and !$Minio) { Fail 3 'restore 需要 -Dump <pg 备份文件> 和/或 -Minio <tgz 归档>' }
        $pgId = ServiceId 'postgres'; $minioId = ServiceId 'minio'
        if (!$pgId) { Fail 1 'PostgreSQL 容器未运行' }
        Info '恢复会覆盖当前本地数据，且先停止 app 容器以避免并发写入。'
        $code = ComposeQuiet 'stop' 'app'
        if ($code -ne 0) { Fail 1 '停止 app 失败' }
        try {
            if ($Dump) {
                if (!(Test-Path $Dump)) { Fail 3 "找不到备份文件: $Dump" }
                # 先核对 SHA256（若存在同名校验文件）。
                $shaFile = "$Dump.sha256"
                if (Test-Path $shaFile) {
                    $expected = (Get-Content $shaFile).Split(' ')[0].ToLowerInvariant()
                    $actual = (Get-FileHash $Dump -Algorithm SHA256).Hash.ToLowerInvariant()
                    if ($expected -ne $actual) { Fail 1 'SHA256 校验失败：备份文件已损坏或不匹配' }
                }
                & docker cp $Dump "${pgId}:/tmp/restore.dump" | Out-Null
                & docker exec $pgId sh -c "pg_restore --clean --if-exists -U '$($values['POSTGRES_USER'])' -d '$($values['POSTGRES_DB'])' /tmp/restore.dump" 2>$null
                # pg_restore 对已存在对象的 ignore-error 会产生非零码；以能否查询关键表为准。
                & docker exec $pgId rm -f /tmp/restore.dump | Out-Null
                $check = & docker exec $pgId psql -U $values['POSTGRES_USER'] -d $values['POSTGRES_DB'] -t -A -c 'SELECT count(*) FROM flyway_schema_history;'
                if ($LASTEXITCODE -ne 0 -or !$check) { Fail 1 '恢复后数据库校验失败' }
                Info "PostgreSQL 恢复完成（flyway_schema_history 迁移记录 $($check[0]) 条）。"
            }
            if ($Minio) {
                if (!(Test-Path $Minio)) { Fail 3 "找不到 MinIO 归档: $Minio" }
                if (!$minioId) { Fail 1 'MinIO 容器未运行' }
                $extractDir = Join-Path $env:TEMP "ai-collab-restore-$(Get-Date -Format yyyyMMddTHHmmss)"
                New-Item -ItemType Directory -Force $extractDir | Out-Null
                & tar -xzf $Minio -C $extractDir
                if ($LASTEXITCODE -ne 0) { Fail 1 'MinIO 归档解压失败' }
                & docker cp "$extractDir/bucket" "${minioId}:/tmp/restore-bucket" | Out-Null
                & docker exec $minioId sh -c "mc alias set rst http://127.0.0.1:9000 '$($values['MINIO_ROOT_USER'])' '$($values['MINIO_ROOT_PASSWORD'])' >/dev/null && mc mirror --overwrite --remove rst/$($values['MINIO_BUCKET']) /tmp/restore-bucket"
                $mcCode = $LASTEXITCODE
                & docker exec $minioId sh -c "rm -rf /tmp/restore-bucket" | Out-Null
                Remove-Item $extractDir -Recurse -Force
                if ($mcCode -ne 0) { Fail 1 'MinIO 恢复（mc mirror）失败' }
                Info 'MinIO 恢复完成（与备份归档完全一致的镜像，含删除多余对象）。'
            }
        } finally {
            Info '重新启动 app...'
            $null = ComposeQuiet 'up' '-d' 'app'
        }
        $env:AI_PORT = $values['HTTP_PORT']
        if (Wait-WebHealthy 300) { Info "恢复完成，服务已恢复: http://localhost:$($values['HTTP_PORT'])" }
        else { Fail 1 '恢复后应用健康检查未通过，请查看 .\install.ps1 logs' }
    }
    'upgrade' {
        $values = Assert-EnvReady
        $oldBackend = $values['APP_IMAGE']; $oldFrontend = $values['WEB_IMAGE']
        Info "== 受控升级：先备份，再更新镜像，失败自动回滚 =="
        Info "[1/6] 升级前备份..."
        $bk = Invoke-Backup -silent:$false
        Info "[2/6] 核验备份..."
        $hash = (Get-FileHash $bk.dump -Algorithm SHA256).Hash.ToLowerInvariant()
        $expected = (Get-Content "$($bk.dump).sha256").Split(' ')[0].ToLowerInvariant()
        if ($hash -ne $expected -or (Get-Item $bk.dump).Length -le 0) { Fail 1 '备份核验失败，升级中止（数据未变动）' }
        Info "[3/6] 解析新镜像..."
        $newBackend = $Image; $newFrontend = $FrontendImage
        if (!$newBackend -and $Tag -ne 'latest') { if (Try-Pull $RegistryBackend) { $newBackend = $RegistryBackend } }
        elseif (!$newBackend) { if (Try-Pull $RegistryBackend) { $newBackend = $RegistryBackend } }
        if (!$newFrontend) { if (Try-Pull $RegistryFrontend) { $newFrontend = $RegistryFrontend } }
        if (!$newBackend) { $newBackend = $oldBackend }
        if (!$newFrontend) { $newFrontend = $oldFrontend }
        if ($newBackend -eq $oldBackend -and $newFrontend -eq $oldFrontend) { Info '镜像无变化，无需升级。'; return }
        $pgId = ServiceId 'postgres'
        $flyBefore = & docker exec $pgId psql -U $values['POSTGRES_USER'] -d $values['POSTGRES_DB'] -t -A -c 'SELECT max(installed_rank) FROM flyway_schema_history;' 2>$null
        Info "[4/6] 更新镜像: $newBackend / $newFrontend"
        Set-EnvValue 'APP_IMAGE' $newBackend
        Set-EnvValue 'WEB_IMAGE' $newFrontend
        $code = Compose 'up' '-d'
        if ($code -ne 0) { $upgradeFailed = $true }
        else {
            $env:AI_PORT = $values['HTTP_PORT']
            if (!(Wait-WebHealthy 420)) { $upgradeFailed = $true }
        }
        if ($upgradeFailed) {
            Info '升级失败，回滚到升级前镜像（数据卷不动）...'
            Set-EnvValue 'APP_IMAGE' $oldBackend
            Set-EnvValue 'WEB_IMAGE' $oldFrontend
            $null = Compose 'up' '-d'
            $env:AI_PORT = $values['HTTP_PORT']
            $rolled = Wait-WebHealthy 300
            $line = "$(Get-Date -Format o) RESULT=ROLLBACK from=$newBackend to=$oldBackend rolled_back=$rolled"
            Add-Content -Path $HistoryLog -Value $line
            Fail 10 "升级失败已回滚（应用恢复=$rolled）。注意：若新版本已执行数据库迁移，旧镜像可能无法完全兼容，恢复请使用步骤 1 的备份；数据库迁移不可自动回退，详见升级文档。"
        }
        $flyAfter = & docker exec $pgId psql -U $values['POSTGRES_USER'] -d $values['POSTGRES_DB'] -t -A -c 'SELECT max(installed_rank) FROM flyway_schema_history;' 2>$null
        Info "[5/6] 健康检查与冒烟通过。"
        if ("$flyBefore" -ne "$flyAfter") { Write-Warning "检测到数据库迁移已推进（Flyway installed_rank $flyBefore -> $flyAfter）：数据库结构升级不可自动回退，请保留步骤 1 的备份。" }
        Info "[6/6] 记录升级历史。"
        Add-Content -Path $HistoryLog -Value "$(Get-Date -Format o) RESULT=OK from=$oldBackend/$oldFrontend to=$newBackend/$newFrontend flyway=$flyBefore->$flyAfter backup=$($bk.dump)"
        Info "升级完成: http://localhost:$($values['HTTP_PORT'])"
    }
    'version' {
        $values = Assert-EnvReady
        $rev = $null; try { $rev = & git -C $RepoRoot rev-parse --short HEAD } catch {}
        Info "仓库基线: $(if ($rev) { $rev } else { '非 Git 目录' })"
        foreach ($pair in @(@('app', $values['APP_IMAGE']), @('web', $values['WEB_IMAGE']))) {
            $id = ServiceId $pair[0]
            if (!$id) { Write-Host "$($pair[0]): 未运行"; continue }
            $c = @(& docker inspect $id | ConvertFrom-Json)[0]
            $imgRef = $c.Config.Image
            $digest = $c.Image
            $label = $c.Config.Labels.'org.opencontainers.image.revision'
            Write-Host "$($pair[0]): image=$imgRef"
            Write-Host "         imageID=$($c.Image.Split(':')[-1]) revision=$label"
        }
        Info "访问地址: http://localhost:$($values['HTTP_PORT'])"
    }
    'uninstall' {
        $values = Assert-EnvReady
        Info '停止并移除容器与网络（数据卷默认保留）...'
        if ($RemoveData) { Fail 1 '删除数据卷属于破坏性操作，请手动执行（确认卷名后）：docker volume rm ai-collab-local_postgres_data ai-collab-local_redis_data ai-collab-local_minio_data' }
        $code = Compose 'down'
        if ($code -ne 0) { Fail 1 '卸载失败' }
        Info '已卸载容器。数据卷已保留（可用于重装恢复）。'
    }
    default { Fail 1 "未知命令: $Command。可用命令见 .\install.ps1 help" }
}
exit $script:ExitCode
