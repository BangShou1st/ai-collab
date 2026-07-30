param(
    [string]$OpenApiPath = "docs/api/openapi.yaml"
)

$ErrorActionPreference = "Stop"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$validator = Join-Path $scriptDirectory "validate_openapi.py"

& python $validator $OpenApiPath
exit $LASTEXITCODE
