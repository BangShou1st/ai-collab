$login = Invoke-RestMethod http://localhost:8080/api/v1/auth/login -Method Post -ContentType 'application/json' -Headers @{Origin='http://localhost:5173'} -Body '{"username":"owner","password":"12345678"}'
$h = @{Authorization=('Bearer ' + $login.data.accessToken); Origin='http://localhost:5173'}
$projId = '70a68d4f-c53a-40f2-8cd1-cb5455ffb58b'
1..3 | ForEach-Object {
  $t = Invoke-RestMethod http://localhost:8080/api/v1/projects/$projId/tasks -Method Post -ContentType 'application/json' -Headers $h -Body ('{"title":"冒烟任务' + $_ + '","priority":"HIGH"}')
  'task=' + $t.data.id + ' ' + $t.data.title
}

