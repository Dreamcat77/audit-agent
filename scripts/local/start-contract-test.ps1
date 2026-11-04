[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$contractRepo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$pin=Join-Path $contractRepo '.runtime\contract-repair-test\validated-sha.txt'
if(-not(Test-Path -LiteralPath $pin)){Write-Output 'DAILY_START=BLOCKED FULL_ACCEPTANCE_NOT_YET_VERIFIED';exit 1}
$validatedSha=(Get-Content -LiteralPath $pin -Raw -Encoding UTF8).Trim()
if($validatedSha -notmatch '^[a-f0-9]{40}$'){Write-Output 'DAILY_START=BLOCKED INVALID_VALIDATED_SHA';exit 1}
$portableReceipt=Join-Path $contractRepo '.runtime\contract-repair-test\redis\launch-receipt.json'
$startOptions=@{Action='Start';ExpectedSha=$validatedSha;Validated=$true}
if(Test-Path -LiteralPath $portableReceipt -PathType Leaf){$startOptions['PortableRedisConfig']=$portableReceipt}
& (Join-Path $PSScriptRoot 'contract-test.ps1') @startOptions
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
