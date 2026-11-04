$b = '{"username":"owner","password":"12345678"}'
$r = Invoke-RestMethod http://localhost:8081/api/v1/auth/login -Method Post -ContentType 'application/json' -Headers @{Origin='http://localhost:5173'} -Body $b
$r | ConvertTo-Json -Depth 6

