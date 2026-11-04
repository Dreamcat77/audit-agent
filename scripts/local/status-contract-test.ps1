[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'contract-test.ps1') -Action Status
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
