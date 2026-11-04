$login = Invoke-RestMethod http://localhost:8080/api/v1/auth/login -Method Post -ContentType 'application/json' -Headers @{Origin='http://localhost:5173'} -Body '{"username":"owner","password":"12345678"}'
$h = @{Authorization=('Bearer ' + $login.data.accessToken); Origin='http://localhost:5173'}
$list = Invoke-RestMethod http://localhost:8080/api/v1/user/ai-providers -Method Get -Headers $h
'PROVIDERS_LEFT=' + $list.data.Count

