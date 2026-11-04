$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'contract-test-guards.ps1')
$contractRepo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$contractFixtureDir=Join-Path $contractRepo ('.runtime\contract-repair-test\process-test-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $contractFixtureDir | Out-Null
$fixture=Join-Path $contractFixtureDir 'owned-harmless-sleeper.ps1'
[IO.File]::WriteAllText($fixture,'Start-Sleep -Seconds 15',[Text.UTF8Encoding]::new($false))
$helper=$null
try {
    $helper=Start-Process -FilePath (Get-Command powershell.exe).Source -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$fixture+'"')) -WindowStyle Hidden -PassThru
    $observed=Get-CimInstance Win32_Process -Filter ('ProcessId='+$helper.Id) -ErrorAction Stop
    $entry=[pscustomobject]@{service='Frontend';pid=$helper.Id;creationUtc=$observed.CreationDate.ToUniversalTime().ToString('o');executable=$observed.ExecutablePath;commandHash=(Get-ContractTextHash $observed.CommandLine);sourceMarker=$fixture;codeRoot=$contractRepo}
    Assert-ContractProcessRecord $entry $observed $contractRepo
    $wrong=[pscustomobject]@{pid=$entry.pid;creationUtc=$observed.CreationDate.ToUniversalTime().AddSeconds(1).ToString('o')}
    $rejected=$false;try{Stop-ContractPinnedProcess $wrong {throw 'MUST_NOT_REACH_VERIFIER'}}catch{$rejected=$true}
    if(-not $rejected -or $helper.HasExited){throw 'CREATION_MISMATCH_DID_NOT_PROTECT_PROCESS'}
    Stop-ContractPinnedProcess $entry {param($candidate)
        $actual=Get-CimInstance Win32_Process -Filter ('ProcessId='+$candidate.pid) -ErrorAction Stop
        Assert-ContractProcessRecord $candidate $actual $contractRepo
    }
    if(-not $helper.WaitForExit(5000)){throw 'OWNED_HELPER_WAS_NOT_TERMINATED'}
    Write-Output 'REAL_WINDOWS_PINNED_PROCESS_HANDLE_TEST=PASS CREATION_MISMATCH_BLOCKED OWNED_HELPER_ONLY_TERMINATED'
} catch {Write-Output ('REAL_WINDOWS_PINNED_PROCESS_HANDLE_TEST=FAIL type='+$_.Exception.GetType().Name);exit 1}
finally {
    if($helper -and -not $helper.HasExited){$helper.Kill();$null=$helper.WaitForExit(5000)}
    if($helper){$helper.Dispose()}
    Remove-Item -LiteralPath $fixture
    Remove-Item -LiteralPath $contractFixtureDir
}
