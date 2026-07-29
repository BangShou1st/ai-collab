[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080/api/v1",
    [string]$Origin = "http://localhost:5173",
    [string]$EnvFile = (Join-Path (Split-Path -Parent $PSScriptRoot) ".env"),
    [string]$Username,
    [string]$Password
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd("/")
$serverRoot = $BaseUrl -replace "/api/v1$", ""
$session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$accessToken = $null
$loggedIn = $false

function Read-EnvValue {
    param([string]$Path, [string]$Name)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    $prefix = "$Name="
    $line = Get-Content -LiteralPath $Path -Encoding UTF8 |
        Where-Object { $_.StartsWith($prefix) } |
        Select-Object -Last 1
    if (-not $line) {
        return $null
    }
    $value = $line.Substring($prefix.Length).Trim()
    if ($value.Length -ge 2) {
        $first = $value.Substring(0, 1)
        $last = $value.Substring($value.Length - 1, 1)
        if (($first -eq '"' -and $last -eq '"') -or ($first -eq "'" -and $last -eq "'")) {
            $value = $value.Substring(1, $value.Length - 2)
        }
    }
    return $value
}

function Invoke-ApiGet {
    param([string]$Path, [string]$Name)

    $headers = @{
        Authorization = "Bearer $accessToken"
        Origin = $Origin
    }
    $response = Invoke-RestMethod `
        -Uri "$BaseUrl$Path" `
        -Method Get `
        -Headers $headers `
        -WebSession $session
    if ($response.code -ne "SUCCESS") {
        throw "$Name returned business code: $($response.code)"
    }
    Write-Host "[PASS] $Name"
    return $response.data
}

if (-not $Username) {
    $Username = Read-EnvValue -Path $EnvFile -Name "DEMO_OWNER_USERNAME"
}
if (-not $Password) {
    $Password = Read-EnvValue -Path $EnvFile -Name "DEMO_OWNER_PASSWORD"
}
if (-not $Username -or -not $Password) {
    throw "Missing credentials. Set DEMO_OWNER_USERNAME and DEMO_OWNER_PASSWORD or pass parameters."
}

try {
    $health = Invoke-RestMethod -Uri "$serverRoot/actuator/health" -Method Get
    if ($health.status -ne "UP") {
        throw "Health status is not UP."
    }
    Write-Host "[PASS] Backend health"

    $loginBody = @{
        username = $Username
        password = $Password
    } | ConvertTo-Json
    $login = Invoke-RestMethod `
        -Uri "$BaseUrl/auth/login" `
        -Method Post `
        -Headers @{ Origin = $Origin } `
        -ContentType "application/json" `
        -Body $loginBody `
        -WebSession $session
    if ($login.code -ne "SUCCESS" -or -not $login.data.accessToken) {
        throw "Login did not return an access token."
    }
    $accessToken = $login.data.accessToken
    $loggedIn = $true
    Write-Host "[PASS] Login"

    $null = Invoke-ApiGet -Path "/auth/me" -Name "Current user"
    $projects = @(Invoke-ApiGet -Path "/projects" -Name "Project list")

    if ($projects.Count -eq 0) {
        Write-Host "[SKIP] The account has no projects; project-scoped checks were skipped."
    } else {
        $projectId = $projects[0].id
        $checks = @(
            @{ Path = "/projects/$projectId"; Name = "Project detail" },
            @{ Path = "/projects/$projectId/dashboard"; Name = "Dashboard" },
            @{ Path = "/projects/$projectId/audit-logs?page=1&size=20"; Name = "Audit logs" },
            @{ Path = "/projects/$projectId/tasks"; Name = "Tasks" },
            @{ Path = "/projects/$projectId/milestones"; Name = "Milestones" },
            @{ Path = "/projects/$projectId/documents"; Name = "Documents" },
            @{ Path = "/projects/$projectId/knowledge/sessions"; Name = "Knowledge sessions" },
            @{ Path = "/projects/$projectId/ai/task-plans?page=0&size=20"; Name = "AI task plans" }
        )
        foreach ($check in $checks) {
            $null = Invoke-ApiGet -Path $check.Path -Name $check.Name
        }
    }

    Write-Host "All read-only smoke checks passed."
} catch {
    Write-Error "Smoke check failed: $($_.Exception.Message)"
    exit 1
} finally {
    if ($loggedIn) {
        try {
            Invoke-RestMethod `
                -Uri "$BaseUrl/auth/logout" `
                -Method Post `
                -Headers @{ Origin = $Origin } `
                -WebSession $session | Out-Null
            Write-Host "[DONE] Logged out the test session."
        } catch {
            Write-Warning "Test requests ended, but logout failed."
        }
    }
    $accessToken = $null
    $Password = $null
}
