$login = Invoke-RestMethod http://localhost:8080/api/v1/auth/login -Method Post -ContentType 'application/json' -Headers @{Origin='http://localhost:5173'} -Body '{"username":"owner","password":"12345678"}'
$h = @{Authorization=('Bearer ' + $login.data.accessToken); Origin='http://localhost:5173'}
$pid = '70a68d4f-c53a-40f2-8cd1-cb5455ffb58b'
$ms = Invoke-RestMethod http://localhost:8080/api/v1/projects/$pid/milestones -Method Post -ContentType 'application/json' -Headers $h -Body '{"name":"M1","description":"","targetDate":null,"status":"ACTIVE"}'
'milestone=' + $ms.data.id
1..3 | ForEach-Object {
  $t = Invoke-RestMethod http://localhost:8080/api/v1/projects/$pid/tasks -Method Post -ContentType 'application/json' -Headers $h -Body ('{"title":"冒烟任务' + $_ + '","description":"","priority":"HIGH","milestoneId":null}')
  'task=' + $t.data.id
}

