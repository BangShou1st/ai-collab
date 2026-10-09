# Dot-source from a guarded localhost acceptance script. Never print the returned token.
$cachePath=Join-Path $root 'ai-collab-backend/target/reliability-auth-private.json'
$cached=$null
if(Test-Path $cachePath){$cached=Get-Content $cachePath -Raw|ConvertFrom-Json}
if($cached-and [DateTimeOffset]::Parse($cached.expiresAt)-gt [DateTimeOffset]::UtcNow-and $cached.username-eq $settings.DEMO_OWNER_USERNAME){
 $login=$cached.login
}else{
 $login=Invoke-RestMethod 'http://localhost:18080/api/v1/auth/login' -Method Post -Headers @{Origin='http://localhost:15173'} -ContentType application/json -Body (@{username=$settings.DEMO_OWNER_USERNAME;password=$settings.DEMO_OWNER_PASSWORD}|ConvertTo-Json)
 @{username=$settings.DEMO_OWNER_USERNAME;expiresAt=[DateTimeOffset]::UtcNow.AddMinutes(50).ToString('o');login=$login}|ConvertTo-Json -Depth 8|Set-Content $cachePath
}
$headers=@{Origin='http://localhost:15173';Authorization='Bearer '+$login.data.accessToken}
