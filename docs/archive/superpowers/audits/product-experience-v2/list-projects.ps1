$b = '{"username":"owner","password":"12345678"}'
$r = Invoke-RestMethod http://localhost:8080/api/v1/auth/login -Method Post -ContentType 'application/json' -Headers @{Origin='http://localhost:5173'} -Body $b
$token = $r.data.accessToken
$h = @{Authorization=('Bearer ' + $token); Origin='http://localhost:5173'}
$list = Invoke-RestMethod http://localhost:8080/api/v1/projects -Method Get -Headers $h
$list.data | ForEach-Object { $_.id + ' ' + $_.name }

