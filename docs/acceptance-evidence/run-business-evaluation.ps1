param([switch]$RunTests,[string]$ReportsDirectory='ai-collab-backend/target/surefire-reports')
$ErrorActionPreference='Stop'
$repoRoot=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$cases=Get-Content (Join-Path $PSScriptRoot 'business-evaluation-cases.json') -Raw|ConvertFrom-Json
$classes=@($cases.testClass|Sort-Object -Unique)
# Validate selectors before Maven: a misspelled class must not silently disappear from the run.
$sources=Get-ChildItem (Join-Path $repoRoot 'ai-collab-backend/src/test/java') -Recurse -Filter '*Test.java'
foreach($name in $classes) {if(!($sources|Where-Object BaseName -eq $name)){throw "Missing test class: $name"}}
$date=(Get-Date).ToString('yyyy-MM-dd');$evidence=Join-Path $PSScriptRoot $date
New-Item $evidence -ItemType Directory -Force|Out-Null
if($RunTests) {
  Push-Location (Join-Path $repoRoot 'ai-collab-backend')
  try { & .\mvnw.cmd ("-Dtest="+($classes-join ',')) test *> (Join-Path $evidence 'business-evaluation.log');if($LASTEXITCODE-ne 0){throw 'Business evaluation tests failed'} } finally {Pop-Location}
}
$reports=(Resolve-Path (Join-Path $repoRoot $ReportsDirectory)).Path
$executions=@(Get-ChildItem $reports -Filter 'TEST-*.xml' | ForEach-Object {
  [xml]$xml=Get-Content $_.FullName -Raw
  foreach($test in $xml.testsuite.testcase){
    $status=if($test.failure){'FAILED'}elseif($test.error){'ERROR'}elseif($test.skipped){'SKIPPED'}else{'PASSED'}
    @{class=$test.classname;name=$test.name;status=$status;durationSeconds=$test.time;reportUpdated=$_.LastWriteTime.ToString('o')}
  }
})
$results=@(foreach($case in $cases){
  $matches=@($executions|Where-Object {$_.class.EndsWith('.'+$case.testClass)-and $_.name -like $case.testMethod})
  $status=if(!$matches.Count){'NOT_EXECUTED'}elseif($matches.status-contains 'FAILED'-or $matches.status-contains 'ERROR'){'FAILED'}elseif($matches.status-contains 'SKIPPED'){'SKIPPED'}else{'PASSED'}
  @{id=$case.id;scenario=$case.scenario;gate=$case.gate;layer='LOCAL_DETERMINISTIC_OR_POSTGRES_WITH_SCRIPTED_MODEL';status=$status;tests=$matches}
})
@{executionDate=$date;reportsDirectory=$reports;providerQuality='separate real-provider evidence; never inferred from this suite';results=$results}|ConvertTo-Json -Depth 15|Set-Content (Join-Path $evidence 'business-evaluation-results.json')
$results|Group-Object status|Select-Object Name,Count
if($results.status-contains 'NOT_EXECUTED'-or $results.status-contains 'FAILED'-or $results.status-contains 'SKIPPED'){throw 'One or more business evaluation gates were not passed'}
