param(
    [ValidateSet('debug', 'info')]
    [string]$Level = 'debug'
)

$ErrorActionPreference = 'Stop'
$raw = Join-Path $PSScriptRoot 'raw'
$log = Join-Path $raw "app-18093-jitoff-$Level.out.txt"
$projectId = (Get-Content -Raw -Encoding UTF8 (Join-Path $raw 'demo6-project-id.txt')).Trim()
$config = @{}
Get-Content -Encoding UTF8 'E:\project\ai-collab\.env' | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') {
        $config[$matches[1].Trim()] = $matches[2].Trim()
    }
}
$body = @{
    username = $config['DEMO_OWNER_USERNAME']
    password = $config['DEMO_OWNER_PASSWORD']
} | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18093/api/v1/auth/login' `
    -Headers @{ Origin = 'http://localhost:5173' } -ContentType 'application/json' -Body $body
$token = $login.data.accessToken
$uri = "http://127.0.0.1:18093/api/v1/projects/$projectId/tasks?page=0&size=200"
$before = (Get-Item $log).Length
$handler = [System.Net.Http.SocketsHttpHandler]::new()
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(60)

for ($index = 0; $index -lt 10; $index++) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $uri)
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult() | Out-Null
    $request.Dispose()
    $response.Dispose()
}

$latencies = @()
$bytes = 0
for ($index = 0; $index -lt 50; $index++) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Get, $uri)
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $token)
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $response = $client.SendAsync(
        $request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).GetAwaiter().GetResult()
    $payload = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $watch.Stop()
    $bytes = $payload.Length
    $latencies += $watch.Elapsed.TotalMilliseconds
    $request.Dispose()
    $response.Dispose()
}
$after = (Get-Item $log).Length
$client.Dispose()

function Pct([double[]]$values, [double]$quantile) {
    $sorted = @($values | Sort-Object)
    return [double]$sorted[[Math]::Max(0, [Math]::Ceiling($sorted.Count * $quantile) - 1)]
}

$result = [ordered]@{
    phase = "$Level-jit-off"
    samples = 50
    responseBytes = $bytes
    p50Ms = [Math]::Round((Pct $latencies 0.50), 1)
    p95Ms = [Math]::Round((Pct $latencies 0.95), 1)
    p99Ms = [Math]::Round((Pct $latencies 0.99), 1)
    minMs = [Math]::Round(($latencies | Measure-Object -Minimum).Minimum, 1)
    maxMs = [Math]::Round(($latencies | Measure-Object -Maximum).Maximum, 1)
    logBytes = $after - $before
    logBytesPerSample = [Math]::Round(($after - $before) / 50, 1)
}
$result | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 (Join-Path $raw "demo6-$Level-jit-off.json")
$result | ConvertTo-Json -Depth 5
