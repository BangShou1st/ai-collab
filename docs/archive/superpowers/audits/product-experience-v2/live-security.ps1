$login = Invoke-RestMethod http://localhost:8080/api/v1/auth/login -Method Post -ContentType 'application/json' -Headers @{Origin='http://localhost:5173'} -Body '{"username":"owner","password":"12345678"}'
$token = $login.data.accessToken
$h = @{Authorization=('Bearer ' + $token); Origin='http://localhost:5173'}
# 1. SSRF: loopback provider must be rejected
try {
  $bad = '{"name":"evil","providerType":"OPENAI_COMPATIBLE","baseUrl":"http://127.0.0.1:9","apiPath":"/v1/chat/completions","apiKey":"sk-x","modelName":"m","enabled":true,"temperature":0.2,"maxOutputTokens":100,"capabilities":["CHAT"]}'
  Invoke-RestMethod http://localhost:8080/api/v1/user/ai-providers -Method Post -ContentType 'application/json' -Headers $h -Body $bad | Out-Null
  'SSRF_LOOPBACK: NOT BLOCKED (BAD)'
} catch {
  'SSRF_LOOPBACK: blocked as expected'
}
# 2. public provider passes validation (fake key, no network on create)
$good = '{"name":"qa","providerType":"OPENAI_COMPATIBLE","baseUrl":"https://api.openai.com","apiPath":"/v1/chat/completions","apiKey":"sk-qa","modelName":"gpt-4o-mini","enabled":true,"temperature":0.2,"maxOutputTokens":100,"capabilities":["CHAT"]}'
$created = Invoke-RestMethod http://localhost:8080/api/v1/user/ai-providers -Method Post -ContentType 'application/json' -Headers $h -Body $good
'CREATED default=' + $created.data.isDefault
# 3. key leak scan
$raw = Invoke-WebRequest http://localhost:8080/api/v1/user/ai-providers -Headers $h -UseBasicParsing | Select-Object -ExpandProperty Content
if ($raw -match 'sk-qa') { 'KEYLEAK: LEAKED (BAD)' } else { 'KEYLEAK: no key material (OK)' }
# 4. set default + purpose override + resolve via home
Invoke-RestMethod http://localhost:8080/api/v1/user/ai-providers/defaults -Method Put -ContentType 'application/json' -Headers $h -Body ('{"providerId":"' + $created.data.id + '"}') | Out-Null
$homeData = Invoke-RestMethod http://localhost:8080/api/v1/home -Method Get -Headers $h
'HOME aiConfigured=' + $homeData.data.aiConfigSummary.configured + ' default=' + $homeData.data.aiConfigSummary.defaultModel + ' providers=' + $homeData.data.aiConfigSummary.providerCount
# 5. cleanup test provider
Invoke-RestMethod http://localhost:8080/api/v1/user/ai-providers/$($created.data.id) -Method Delete -Headers $h | Out-Null
' CLEANED'
