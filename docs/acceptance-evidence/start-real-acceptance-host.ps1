$ErrorActionPreference='Stop'
Set-Location 'E:\project\ai-collab\ai-collab-backend'
# 隔离副本容器必须在线；异常退出的容器先拉起
foreach($c in 'ai-collab-rootfix-pg','ai-collab-rootfix-redis','ai-collab-rootfix-minio'){
    $state = docker inspect -f '{{.State.Running}}' $c 2>$null
    if($state -ne 'true'){ docker start $c | Out-Null }
}
Start-Sleep -Seconds 5
$env:AI_REAL_ACCEPTANCE='true'
$env:AI_UPGRADE_REHEARSAL='true'
mvn test '-Dtest=RealAcceptanceHostTest' '-DfailIfNoTests=true' 2>&1 |
    Out-File -FilePath 'E:\project\ai-collab\ai-collab-backend\target\real-acceptance-host.log' -Encoding utf8
