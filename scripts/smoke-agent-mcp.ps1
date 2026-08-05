param(
    [Parameter(Mandatory = $true)]
    [string]$AccessToken,
    [Parameter(Mandatory = $true)]
    [guid]$ProjectId,
    [string]$BaseUrl = 'http://localhost:8080/api/v1'
)

$ErrorActionPreference = 'Stop'
$headers = @{ Authorization = "Bearer $AccessToken" }

Write-Host 'Checking backend health...'
$healthBase = $BaseUrl -replace '/api/v1/?$', ''
Invoke-RestMethod -Method Get -Uri "$healthBase/actuator/health" | ConvertTo-Json -Depth 5

Write-Host 'Listing system MCP connections (requires system admin)...'
$connections = Invoke-RestMethod -Method Get -Uri "$BaseUrl/admin/agent/mcp-connections" -Headers $headers
$connections.data | Select-Object id, code, enabled, schemaConfirmed, lastHealthStatus, version | Format-Table

Write-Host 'Listing project MCP bindings...'
$bindings = Invoke-RestMethod -Method Get -Uri "$BaseUrl/projects/$ProjectId/agent/mcp-bindings" -Headers $headers
$bindings.data | Select-Object connectionId, connectionCode, enabled, connectionEnabled, allowedTools, version | Format-Table

if ($connections.data | Where-Object { $_.enabled -and -not $_.schemaConfirmed }) {
    throw 'An enabled MCP connection has no confirmed schema.'
}

Write-Host 'MCP smoke checks passed.'
