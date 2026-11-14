$ErrorActionPreference = 'Stop'
$raw = Join-Path $PSScriptRoot 'raw'
$projectId = (Get-Content -Raw -Encoding UTF8 (Join-Path $raw 'demo6-project-id.txt')).Trim()
$config = @{}
Get-Content -Encoding UTF8 'E:\project\ai-collab\.env' | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') {
        $config[$matches[1].Trim()] = $matches[2].Trim()
    }
}
$loginBody = @{
    username = $config['DEMO_OWNER_USERNAME']
    password = $config['DEMO_OWNER_PASSWORD']
} | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18093/api/v1/auth/login' `
    -Headers @{ Origin = 'http://localhost:5173' } -ContentType 'application/json' -Body $loginBody
$token = $login.data.accessToken
$uri = "http://127.0.0.1:18093/api/v1/projects/$projectId/tasks?page=0&size=200"
$curl = "$env:SystemRoot\System32\curl.exe"
$processTimes = @()
$curlTimes = @()

for ($index = 0; $index -lt 10; $index++) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $transferSeconds = & $curl -s -o NUL -w '%{time_total}' `
        -H "Authorization: Bearer $token" $uri
    $watch.Stop()
    $processTimes += $watch.Elapsed.TotalMilliseconds
    $curlTimes += ([double]$transferSeconds) * 1000.0
}

function Pct([double[]]$values, [double]$quantile) {
    $sorted = @($values | Sort-Object)
    return [double]$sorted[[Math]::Max(0, [Math]::Ceiling($sorted.Count * $quantile) - 1)]
}

$result = [ordered]@{
    samples = 10
    curlTransferP50Ms = [Math]::Round((Pct $curlTimes 0.50), 1)
    curlTransferP95Ms = [Math]::Round((Pct $curlTimes 0.95), 1)
    processWallP50Ms = [Math]::Round((Pct $processTimes 0.50), 1)
    processWallP95Ms = [Math]::Round((Pct $processTimes 0.95), 1)
    processOverheadP50Ms = [Math]::Round((Pct $processTimes 0.50) - (Pct $curlTimes 0.50), 1)
}
$result | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $raw 'demo6-curl-client.json')
$result | ConvertTo-Json -Depth 5
