[CmdletBinding()]
param(
    [ValidateSet('setup', 'cleanup')]
    [string]$Action = 'setup',
    [string]$Container = 'ai-collab-postgres',
    [string]$ProviderId = '00000000-0000-0000-0000-00000000a1b1',
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [int]$StubDelayMs = 200
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..\..')).Path
$envFile = Join-Path $repoRoot '.env'
if (-not (Test-Path $envFile)) { throw "missing $envFile" }
$envValues = @{}
foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Z][A-Z0-9_]*)\s*=(.*)$') {
        $envValues[$matches[1]] = $matches[2].Trim()
    }
}
foreach ($key in @('POSTGRES_DB', 'POSTGRES_USER', 'DEMO_OWNER_USERNAME', 'MODEL_CONFIG_MASTER_KEY')) {
    if (-not $envValues.ContainsKey($key) -or [string]::IsNullOrWhiteSpace($envValues[$key])) {
        throw "missing $key in .env"
    }
}

$db = $envValues['POSTGRES_DB']
$dbUser = $envValues['POSTGRES_USER']
$ownerName = $envValues['DEMO_OWNER_USERNAME']
$sqlLiteral = $ownerName.Replace("'", "''")
if ($BaseUrl -notmatch '^https://') {
    throw "BaseUrl must be a public HTTPS URL; got $BaseUrl"
}

function Get-Scalar([string]$Sql) {
    $output = docker exec $Container psql -U $dbUser -d $db -Atc $Sql
    if ($null -eq $output) { return '' }
    $value = if ($output -is [array]) { $output | Select-Object -First 1 } else { $output }
    return ([string]$value).Trim()
}

$ownerId = Get-Scalar "SELECT id FROM app_user WHERE username='$sqlLiteral' AND status='ACTIVE';"
if ([string]::IsNullOrWhiteSpace($ownerId)) { throw "active owner '$ownerName' not found" }

function Invoke-Sql([string]$Sql) {
    $Sql | docker exec -i $Container psql -U $dbUser -d $db -v ON_ERROR_STOP=1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "psql failed with exit $LASTEXITCODE" }
}

if ($Action -eq 'cleanup') {
    Invoke-Sql @"
BEGIN;
DELETE FROM user_model_purpose_assignment WHERE provider_id = '$ProviderId';
DELETE FROM user_ai_provider WHERE id = '$ProviderId';
COMMIT;
"@
    [ordered]@{
        action = 'cleanup'
        providerId = $ProviderId
        ownerId = $ownerId
        result = 'ok'
    } | ConvertTo-Json | Write-Output
    exit 0
}

$existingProviderUser = Get-Scalar "SELECT user_id FROM user_ai_provider WHERE id='$ProviderId';"
if ($existingProviderUser -and $existingProviderUser -ne $ownerId) {
    throw "provider id $ProviderId belongs to another user; refusing to overwrite"
}
$existingPurpose = Get-Scalar "SELECT provider_id FROM user_model_purpose_assignment WHERE user_id='$ownerId' AND purpose='KNOWLEDGE_CHAT';"
if ($existingPurpose -and $existingPurpose -ne $ProviderId) {
    throw "owner already has KNOWLEDGE_CHAT provider $existingPurpose; refusing to overwrite"
}

$masterKey = $envValues['MODEL_CONFIG_MASTER_KEY']
$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    $key = $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($masterKey))
} finally {
    $sha.Dispose()
}
$iv = [byte[]]::new(12)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($iv)
$plain = [Text.Encoding]::UTF8.GetBytes('perf-stub-key')
$cipher = [byte[]]::new($plain.Length)
$tag = [byte[]]::new(16)
$aes = [System.Security.Cryptography.AesGcm]::new($key, 16)
try {
    $aes.Encrypt($iv, $plain, $cipher, $tag)
} finally {
    $aes.Dispose()
}
$packed = [byte[]]::new($iv.Length + $cipher.Length + $tag.Length)
[Array]::Copy($iv, 0, $packed, 0, $iv.Length)
[Array]::Copy($cipher, 0, $packed, $iv.Length, $cipher.Length)
[Array]::Copy($tag, 0, $packed, $iv.Length + $cipher.Length, $tag.Length)
$encryptedKey = [Convert]::ToBase64String($packed)

Invoke-Sql @"
BEGIN;
DELETE FROM user_model_purpose_assignment WHERE user_id = '$ownerId' AND purpose = 'KNOWLEDGE_CHAT' AND provider_id = '$ProviderId';
DELETE FROM user_ai_provider WHERE id = '$ProviderId';
INSERT INTO user_ai_provider
  (id, user_id, name, provider_type, base_url, api_path, encrypted_api_key, model_name,
   enabled, temperature, max_output_tokens, capabilities, is_default)
VALUES
  ('$ProviderId', '$ownerId', 'perf-stub-local', 'OPENAI_COMPATIBLE', '$BaseUrl',
   '/v1/chat/completions', '$encryptedKey', 'perf-stub', true, 0.0, 256, 'CHAT,USAGE', false);
INSERT INTO user_model_purpose_assignment (user_id, purpose, provider_id, is_shared)
VALUES ('$ownerId', 'KNOWLEDGE_CHAT', '$ProviderId', false);
COMMIT;
"@

[ordered]@{
    action = 'setup'
    providerId = $ProviderId
    ownerId = $ownerId
    baseUrl = $BaseUrl
    stubDelayMs = $StubDelayMs
    result = 'ok'
} | ConvertTo-Json | Write-Output
