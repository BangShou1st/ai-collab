$loginBody = '{"username":"owner","password":"12345678"}'
$login = Invoke-RestMethod http://localhost:8081/api/v1/auth/login -Method Post -ContentType 'application/json' -Headers @{Origin='http://localhost:5173'} -Body $loginBody
$token = $login.data.accessToken
'HAS_TOKEN:' + ($token.Length -gt 20)
$h = @{Authorization=('Bearer ' + $token); Origin='http://localhost:5173'}
$projBody = '{"name":"V2 Baseline Audit","description":"baseline audit demo","type":"OTHER","startDate":null,"dueDate":null}'
$proj = Invoke-RestMethod http://localhost:8081/api/v1/projects -Method Post -ContentType 'application/json' -Headers $h -Body $projBody
$proj.data | ConvertTo-Json -Depth 6
$list = Invoke-RestMethod http://localhost:8081/api/v1/projects -Method Get -Headers $h
'PROJECT_COUNT:' + $list.data.Count

