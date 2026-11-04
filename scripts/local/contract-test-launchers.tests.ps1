$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$fixture=Join-Path $repo ('.runtime\contract-repair-test\v2-final-acceptance-20261017\launcher-fixtures\'+[Guid]::NewGuid().ToString('N'))
$scripts=Join-Path $fixture 'scripts\local'
$runtime=Join-Path $fixture '.runtime\contract-repair-test'
New-Item -ItemType Directory -Path $scripts,$runtime | Out-Null
foreach($name in @('start-contract-test.ps1','status-contract-test.ps1','stop-contract-test.ps1')) {
    [IO.File]::Copy((Join-Path $PSScriptRoot $name),(Join-Path $scripts $name))
}
$stub=@'
param($Action,$ExpectedSha,[switch]$Validated,$PortableRedisConfig)
[pscustomobject]@{Action=$Action;ExpectedSha=$ExpectedSha;Validated=[bool]$Validated;PortableRedisConfig=$PortableRedisConfig} |
    ConvertTo-Json -Compress | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'observed.json') -Encoding UTF8
exit 23
'@
[IO.File]::WriteAllText((Join-Path $scripts 'contract-test.ps1'),$stub,[Text.UTF8Encoding]::new($false))
$observed=Join-Path $scripts 'observed.json'
$shell=(Get-Command powershell.exe -ErrorAction Stop).Source
function Invoke-Launcher([string]$name) {
    $result=& $shell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $scripts $name) 2>&1
    [pscustomobject]@{Code=$LASTEXITCODE;Output=($result -join "`n")}
}
function Assert-True([bool]$value,[string]$code) {if(-not $value){throw $code}}
$result=Invoke-Launcher 'start-contract-test.ps1'
Assert-True ($result.Code -eq 1 -and $result.Output -eq 'DAILY_START=BLOCKED FULL_ACCEPTANCE_NOT_YET_VERIFIED' -and -not(Test-Path -LiteralPath $observed)) 'MISSING_PIN_DISPATCHED_START'
$pin=Join-Path $runtime 'validated-sha.txt'
[IO.File]::WriteAllText($pin,'not-a-validated-sha',[Text.UTF8Encoding]::new($false))
$result=Invoke-Launcher 'start-contract-test.ps1'
Assert-True ($result.Code -eq 1 -and $result.Output -eq 'DAILY_START=BLOCKED INVALID_VALIDATED_SHA' -and -not(Test-Path -LiteralPath $observed)) 'MALFORMED_PIN_DISPATCHED_START'
[IO.File]::WriteAllText($pin,('a'*40),[Text.UTF8Encoding]::new($false))
$result=Invoke-Launcher 'start-contract-test.ps1'
$value=Get-Content -LiteralPath $observed -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-True ($result.Code -eq 23 -and $value.Action -eq 'Start' -and $value.ExpectedSha -eq ('a'*40) -and $value.Validated -eq $true -and -not $value.PortableRedisConfig) 'VALIDATED_START_IDENTITY_OR_EXIT_LOST'
$redis=Join-Path $runtime 'redis'
New-Item -ItemType Directory -Path $redis | Out-Null
[IO.File]::WriteAllText((Join-Path $redis 'launch-receipt.json'),'{}',[Text.UTF8Encoding]::new($false))
$result=Invoke-Launcher 'start-contract-test.ps1'
$value=Get-Content -LiteralPath $observed -Raw -Encoding UTF8 | ConvertFrom-Json
Assert-True ($result.Code -eq 23 -and $value.Validated -eq $true -and $value.PortableRedisConfig -eq (Join-Path $redis 'launch-receipt.json')) 'PORTABLE_REDIS_RECEIPT_NOT_FORWARDED'
foreach($entry in @(@('status-contract-test.ps1','Status'),@('stop-contract-test.ps1','Stop'))) {
    $result=Invoke-Launcher $entry[0]
    $value=Get-Content -LiteralPath $observed -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-True ($result.Code -eq 23 -and $value.Action -eq $entry[1] -and -not $value.Validated -and -not $value.ExpectedSha) 'STATUS_STOP_FORWARDING_OR_EXIT_LOST'
}
# Execute the existing documented/browser commands with only process creation replaced.
# This checks the URL at the OS-launch boundary without opening a browser or service.
$commands=@()
foreach($item in @(@((Join-Path $PSScriptRoot 'README-contract-test.md'),$true),@((Join-Path $PSScriptRoot 'contract-test.ps1'),$false))) {
    $source=Get-Content -LiteralPath $item[0] -Raw -Encoding UTF8
    if($item[1]){$source=([regex]::Matches($source,'(?s)```powershell\s*\r?\n(.*?)```') | ForEach-Object {$_.Groups[1].Value}) -join "`n"}
    $tokens=$null;$errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseInput($source,[ref]$tokens,[ref]$errors)
    Assert-True ($errors.Count -eq 0) 'LAUNCH_DOCUMENT_OR_SCRIPT_PARSE_FAILED'
    $commands+=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Start-Process' -and $node.Extent.Text.Contains('5173')},$true))
}
Assert-True ($commands.Count -eq 3) 'EXPECTED_EXISTING_BROWSER_LAUNCH_BOUNDARIES_MISSING'
$script:launched=$null
function Start-Process {param([string]$FilePath) $script:launched=$FilePath}
foreach($command in $commands) {
    $script:launched=$null
    & ([scriptblock]::Create($command.Extent.Text))
    Assert-True ($script:launched -ceq 'http://127.0.0.1:5173/') 'BROWSER_LAUNCH_URL_IS_NOT_PLAIN_LOOPBACK'
}
Write-Output 'CONTRACT_LAUNCHERS=PASS CASES=9 NO_BROWSER_OR_SERVICE_STARTED'
