$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
if(Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue){throw 'Stop the acceptance host before restoring configuration'}
$snapshot=Get-Content (Join-Path $root 'ai-collab-backend/target/reliability-provider-backup.json') -Raw|ConvertFrom-Json
$settings=@{};Get-Content (Join-Path $root '.env')|ForEach-Object{if($_ -match '^([^#=]+)=(.*)$'){$settings[$matches[1]]=$matches[2]}}
$owner=[guid](Get-Content (Join-Path $root 'ai-collab-backend/target/reliability-conversation-state.json') -Raw|ConvertFrom-Json).ownerId
$sql=[Collections.Generic.List[string]]::new();$sql.Add('BEGIN;')
$sql.Add("UPDATE user_ai_provider SET is_default=false WHERE user_id='$owner';")
foreach($row in $snapshot.providers){
 $id=[guid]$row.id;$model=$row.model_name.Replace("'","''");$stamp=([DateTimeOffset]::Parse($row.updated_at)).ToString('o');$default=if($row.is_default){'true'}else{'false'}
 $sql.Add("UPDATE user_ai_provider SET is_default=$default,model_name='$model',updated_at='$stamp'::timestamptz WHERE id='$id' AND user_id='$owner';")
}
foreach($row in $snapshot.assignments){
 $provider=[guid]$row.provider_id;$purpose=$row.purpose.Replace("'","''")
 $sql.Add("UPDATE user_model_purpose_assignment SET provider_id='$provider' WHERE user_id='$owner' AND purpose='$purpose';")
}
$sql.Add("UPDATE user_ai_provider SET enabled=false,is_default=false WHERE user_id='$owner' AND name='reliability-scripted-peer';")
$sql.Add('COMMIT;')
($sql -join "`n")|& docker exec -i ai-collab-reliability-pg-20261004 psql -v ON_ERROR_STOP=1 -U $settings.POSTGRES_USER -d $settings.POSTGRES_DB | Out-Null
if($LASTEXITCODE-ne 0){throw 'Configuration restore failed'}
$query="SELECT json_build_object('providers',(SELECT json_agg(x ORDER BY id) FROM(SELECT id,is_default,model_name,updated_at FROM user_ai_provider WHERE user_id='$owner' AND name<>'reliability-scripted-peer')x),'assignments',(SELECT json_agg(x ORDER BY purpose) FROM(SELECT purpose,provider_id FROM user_model_purpose_assignment WHERE user_id='$owner')x));"
$current=(& docker exec ai-collab-reliability-pg-20261004 psql -U $settings.POSTGRES_USER -d $settings.POSTGRES_DB -Atc $query)|ConvertFrom-Json
foreach($row in $snapshot.providers){
 $restored=$current.providers|Where-Object id -eq $row.id
 if(!$restored-or $restored.is_default-ne $row.is_default-or $restored.model_name-ne $row.model_name-or [DateTimeOffset]::Parse($restored.updated_at)-ne [DateTimeOffset]::Parse($row.updated_at)){throw 'Provider restore assertion failed'}
}
foreach($row in $snapshot.assignments){if(($current.assignments|Where-Object purpose -eq $row.purpose).provider_id-ne $row.provider_id){throw 'Purpose restore assertion failed'}}
@{at=(Get-Date).ToUniversalTime().ToString('o');assertions='PASS';providersRestored=@($snapshot.providers).Count;purposesRestored=@($snapshot.assignments).Count;testProvider='disabled; historical references retained';scope='isolated pre-fault snapshot only'}|ConvertTo-Json|Set-Content (Join-Path $PSScriptRoot ((Get-Date -Format yyyy-MM-dd)+'/reliability/config-restoration.json'))
Write-Output 'PASS isolated provider and purpose restoration assertions'
