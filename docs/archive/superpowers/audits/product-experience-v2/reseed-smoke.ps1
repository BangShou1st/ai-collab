$login = Invoke-RestMethod http://localhost:8080/api/v1/auth/login -Method Post -ContentType 'application/json' -Headers @{Origin='http://localhost:5173'} -Body '{"username":"owner","password":"12345678"}'
$h = @{Authorization=('Bearer ' + $login.data.accessToken); Origin='http://localhost:5173'}
$projId = '70a68d4f-c53a-40f2-8cd1-cb5455ffb58b'
$tasks = Invoke-RestMethod http://localhost:8080/api/v1/projects/$projId/tasks -Method Get -Headers $h
$tasks.data | ForEach-Object { Invoke-RestMethod http://localhost:8080/api/v1/projects/$projId/tasks/$($_.id) -Method Delete -Headers $h | Out-Null; 'deleted ' + $_.id }
1..3 | ForEach-Object {
  $t = Invoke-RestMethod http://localhost:8080/api/v1/projects/$projId/tasks -Method Post -ContentType 'application/json' -Headers $h -Body ('{"title":"Smoke task ' + $_ + '","priority":"HIGH"}')
  'task=' + $t.data.title
}

