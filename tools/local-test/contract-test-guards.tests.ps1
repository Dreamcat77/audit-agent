$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'contract-test-guards.ps1')
$script:contractTests=0; $script:contractFailures=0
function Test-ContractGuard([string]$Name,[scriptblock]$Body) {
    $script:contractTests++
    try { & $Body; Write-Output ('PASS '+$Name) } catch { $script:contractFailures++; Write-Output ('FAIL '+$Name) }
}
function Assert-ContractTest([bool]$Value) { if(-not $Value){throw 'TEST_ASSERTION'} }
function Assert-ContractReject([scriptblock]$Body) { $rejected=$false;try{& $Body}catch{$rejected=$true};if(-not $rejected){throw 'UNSAFE_GUARD_ACCEPTED'} }
$contractRepo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$contractFixtureDir=Join-Path $contractRepo ('.runtime\contract-repair-test\guard-test-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $contractFixtureDir -Force | Out-Null
function Invoke-ContractFixture([string]$Text,[scriptblock]$Check) {
    $fixture=Join-Path $contractFixtureDir ([Guid]::NewGuid().ToString('N')+'.ps1')
    [IO.File]::WriteAllText($fixture,$Text,[Text.UTF8Encoding]::new($false))
    try{& $Check $fixture}finally{Remove-Item -LiteralPath $fixture}
}
try {
    Test-ContractGuard 'new semantic candidate accepts only an explicit safe true boolean' {
        Assert-ContractTest (Resolve-ContractSemanticCheckpointFlag '')
        Assert-ContractTest (Resolve-ContractSemanticCheckpointFlag 'true')
        Assert-ContractTest (Resolve-ContractSemanticCheckpointFlag 'TRUE')
        foreach($invalid in @('false','1','yes','true; Write-Output unsafe')){Assert-ContractReject {Resolve-ContractSemanticCheckpointFlag $invalid}}
        Invoke-ContractFixture '$env:AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED = ''true''; throw ''MUST_NOT_EXECUTE''' {param($p)
            $values=Read-ContractLiteralEnvironment $p @('AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED') @('AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED')
            Assert-ContractTest (Resolve-ContractSemanticCheckpointFlag $values['AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED'])
        }
    }
    Test-ContractGuard 'canonical build rejects an unrecorded running jar at another port' {
        $jar='D:\synthetic\backend\target\audit-backend.jar'
        Assert-ContractReject {Assert-ContractCanonicalJarStopped $jar @([pscustomobject]@{Name='java.exe';CommandLine='java -jar "D:\synthetic\backend\target\audit-backend.jar" --server.port=9999'})}
        Assert-ContractReject {Assert-ContractCanonicalJarStopped $jar @([pscustomobject]@{Name='java.exe';CommandLine='java -jar relative.jar'})}
        Assert-ContractReject {Assert-ContractCanonicalJarStopped $jar @([pscustomobject]@{Name='java.exe';CommandLine=$null})}
        Assert-ContractCanonicalJarStopped $jar @([pscustomobject]@{Name='java.exe';CommandLine='java -jar "D:\other\service.jar"'})
    }
    Test-ContractGuard 'canonical build rejects opaque Java argfiles without reading them' {
        foreach($command in @('java.exe @D:\synthetic\launch.args','java.exe "@D:\synthetic path\launch.args"','javaw.exe @"D:\synthetic path\launch.args"')){
            Assert-ContractReject {Assert-ContractCanonicalJarStopped 'D:\synthetic\backend\target\audit-backend.jar' @([pscustomobject]@{Name='java.exe';CommandLine=$command})}
        }
    }
    Test-ContractGuard 'canonical build rejects unproven module launcher forms' {
        foreach($option in @('-m audit/com.fuquan.audit.Main','--module audit/Main','--module=audit/Main','-p D:\synthetic\modules Main','--module-path=D:\synthetic\modules Main','--upgrade-module-path D:\synthetic\modules Main','--patch-module=audit=D:\synthetic Main')){
            Assert-ContractReject {Assert-ContractCanonicalJarStopped 'D:\synthetic\backend\target\audit-backend.jar' @([pscustomobject]@{Name='java.exe';CommandLine=('java.exe '+$option)})}
        }
    }
    Test-ContractGuard 'PS51 CommandExpressionAst literal is read without execution' {
        Invoke-ContractFixture '$env:AUDIT_DB_URL = ''safe-value''; throw ''MUST_NOT_EXECUTE''' {param($p)
            $values=Read-ContractLiteralEnvironment $p @('AUDIT_DB_URL') @('AUDIT_DB_URL');Assert-ContractTest ($values['AUDIT_DB_URL'] -eq 'safe-value')
        }
    }
    Test-ContractGuard 'nested assignments do not overwrite approved top-level value' {
        Invoke-ContractFixture '$env:AUDIT_DB_URL=''top''; function Evil { $env:AUDIT_DB_URL=''nested'' }' {param($p)
            $values=Read-ContractLiteralEnvironment $p @('AUDIT_DB_URL') @('AUDIT_DB_URL');Assert-ContractTest ($values['AUDIT_DB_URL'] -eq 'top')
        }
    }
    Test-ContractGuard 'command and interpolated credential expressions are rejected' {
        foreach($text in @('$env:AUDIT_DB_URL=(Get-Date)','$env:AUDIT_DB_URL="$env:USERNAME"','$env:AUDIT_DB_URL=("a"+"b")')) {
            Invoke-ContractFixture $text {param($p)Assert-ContractReject {Read-ContractLiteralEnvironment $p @('AUDIT_DB_URL') @('AUDIT_DB_URL')}}
        }
    }
    Test-ContractGuard 'duplicate and missing database literals fail closed' {
        Invoke-ContractFixture '$env:AUDIT_DB_URL=''a'';$env:AUDIT_DB_URL=''b''' {param($p)Assert-ContractReject {Read-ContractLiteralEnvironment $p @('AUDIT_DB_URL') @('AUDIT_DB_URL')}}
        Invoke-ContractFixture '$env:IGNORED=''x''' {param($p)Assert-ContractReject {Read-ContractLiteralEnvironment $p @('AUDIT_DB_URL') @('AUDIT_DB_URL')}}
    }
    Test-ContractGuard 'unallowlisted setting cannot enter result or process environment' {
        Invoke-ContractFixture '$env:CONTRACT_TEST_UNALLOWLISTED=''unsafe'';$env:AUDIT_DB_URL=''ok''' {param($p)
            $values=Read-ContractLiteralEnvironment $p @('AUDIT_DB_URL');Assert-ContractTest (-not $values.ContainsKey('CONTRACT_TEST_UNALLOWLISTED'));Assert-ContractTest ($null -eq [Environment]::GetEnvironmentVariable('CONTRACT_TEST_UNALLOWLISTED','Process'))
        }
    }
    $testSha='0123456789abcdef0123456789abcdef01234567'
    Test-ContractGuard 'canonical root exact branch SHA and clean build are enforced' {
        Assert-ContractSourceIdentity $contractRepo $contractRepo 'integration/pre-main-20261010' 'integration/pre-main-20261010' $testSha $testSha @()
        Assert-ContractReject {Assert-ContractSourceIdentity ($contractRepo+'-other') $contractRepo 'integration/pre-main-20261010' 'integration/pre-main-20261010' $testSha $testSha @()}
        Assert-ContractReject {Assert-ContractSourceIdentity $contractRepo $contractRepo 'main' 'integration/pre-main-20261010' $testSha $testSha @()}
        Assert-ContractReject {Assert-ContractSourceIdentity $contractRepo $contractRepo 'integration/pre-main-20261010' 'integration/pre-main-20261010' $testSha ('f'*40) @()}
        Assert-ContractReject {Assert-ContractSourceIdentity $contractRepo $contractRepo 'integration/pre-main-20261010' 'integration/pre-main-20261010' $testSha $testSha @('source-dirty')}
    }
    $entry=[pscustomobject]@{service='Backend';pid=12345;creationUtc='2026-10-14T01:02:03.0000000Z';executable='C:\Program Files\Java\bin\java.exe';commandHash=(Get-ContractTextHash ('java -jar '+$contractRepo+'\.runtime\contract-repair-test\release\audit.jar'));sourceMarker=($contractRepo+'\.runtime\contract-repair-test\release\audit.jar');codeRoot=$contractRepo}
    $observed=[pscustomobject]@{ProcessId=12345;CreationDate=[datetime]'2026-10-14T01:02:03Z';ExecutablePath=$entry.executable;CommandLine=('java -jar '+$entry.sourceMarker)}
    Test-ContractGuard 'owned process proof accepts exact command creation time and executable' {Assert-ContractProcessRecord $entry $observed $contractRepo}
    Test-ContractGuard 'PID reuse command drift and other source cannot be stopped' {
        foreach($change in @('CreationDate','CommandLine','ExecutablePath')) {
            $mutated=[pscustomobject]@{ProcessId=$observed.ProcessId;CreationDate=$observed.CreationDate;ExecutablePath=$observed.ExecutablePath;CommandLine=$observed.CommandLine}
            if($change -eq 'CreationDate'){$mutated.CreationDate=$mutated.CreationDate.AddSeconds(1)}elseif($change -eq 'CommandLine'){$mutated.CommandLine='java -jar another.jar'}else{$mutated.ExecutablePath='C:\another\java.exe'}
            Assert-ContractReject {Assert-ContractProcessRecord $entry $mutated $contractRepo}
        }
        Assert-ContractReject {Assert-ContractProcessRecord $entry $observed ($contractRepo+'-other')}
    }
    Test-ContractGuard 'portable Redis rejects external bind path include and duplicate scope' {
        $valid=@('bind 127.0.0.1','protected-mode yes','daemonize yes','port 6379','dir /mnt/d/test/repo/.runtime/redis/data','pidfile /mnt/d/test/repo/.runtime/redis/redis.pid','logfile ""') -join "`n"
        Assert-ContractPortableRedisConfig $valid '/mnt/d/test/repo' 6379
        foreach($bad in @($valid.Replace('127.0.0.1','0.0.0.0'),$valid.Replace('/mnt/d/test/repo/.runtime/redis/data','/var/lib/redis'),($valid+"`nbind 0.0.0.0"),($valid+"`ninclude /etc/redis.conf"),($valid+"`nreplicaof example.test 6379"),$valid.Replace('.runtime/redis/data','.runtime/../outside'))) {Assert-ContractReject {Assert-ContractPortableRedisConfig $bad '/mnt/d/test/repo' 6379}}
    }
    Test-ContractGuard 'only approved installed Ubuntu-22.04 can launch portable Redis' {
        Assert-ContractWslDistribution 'Ubuntu-22.04'
        foreach($bad in @('Ubuntu','Debian','Ubuntu-22.04 --exec unexpected')){Assert-ContractReject {Assert-ContractWslDistribution $bad}}
    }
    Test-ContractGuard 'native stderr warning uses exit code and restores Stop preference' {
        $original=$ErrorActionPreference
        $native=(Get-Command powershell.exe -ErrorAction Stop).Source
        $null=Invoke-ContractNative $native @('-NoProfile','-Command',"[Console]::Error.WriteLine('EXPECTED_NATIVE_WARNING'); exit 0") 'TEST_NATIVE_FAILED' 2>&1
        Assert-ContractTest ($ErrorActionPreference -eq $original)
        Assert-ContractReject {$null=Invoke-ContractNative $native @('-NoProfile','-Command','exit 7') 'TEST_NATIVE_FAILED' 2>&1}
        Assert-ContractTest ($ErrorActionPreference -eq $original)
    }
    Test-ContractGuard 'Status refuses an old process under a new build manifest SHA' {
        $oldSha='0123456789abcdef0123456789abcdef01234567';$newSha='fedcba9876543210fedcba9876543210fedcba98'
        $record=[pscustomobject]@{sourceSha=$oldSha};Assert-ContractRunningSha $record $oldSha
        Assert-ContractReject {Assert-ContractRunningSha $record $newSha}
        Assert-ContractReject {Assert-ContractRunningSha $record ''}
    }
    Test-ContractGuard 'measured slow OCR uses a bounded five minute per-page read budget' {
        Assert-ContractTest ((Resolve-ContractOcrReadTimeout '120000') -eq 300000)
        Assert-ContractTest ((Resolve-ContractOcrReadTimeout '') -eq 300000)
        Assert-ContractTest ((Resolve-ContractOcrReadTimeout '300000') -eq 300000)
        foreach($invalid in @('0','-1','600000','unbounded')){Assert-ContractReject {Resolve-ContractOcrReadTimeout $invalid}}
    }
    Test-ContractGuard 'build verification child cannot inherit runtime credentials or feature flags' {
        $names=@('AUDIT_DB_PASSWORD','AUDIT_AI_CONTRACT_FACT_EXTRACTION_ENABLED','CONTRACT_BUILD_TEST_KEEP')
        $before=@{};foreach($name in $names){$before[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
        try {
            $env:AUDIT_DB_PASSWORD='FIXTURE_ONLY_NOT_A_CREDENTIAL';$env:AUDIT_AI_CONTRACT_FACT_EXTRACTION_ENABLED='true'
            $env:CONTRACT_BUILD_TEST_KEEP='fixture-preserved'
            Invoke-ContractIsolatedBuildEnvironment {
                Invoke-ContractNative 'powershell.exe' @('-NoProfile','-Command',"if([Environment]::GetEnvironmentVariables('Process').Keys | Where-Object { [string]`$_ -like 'AUDIT_*' }){exit 6}; if(`$env:CONTRACT_BUILD_TEST_KEEP -ne 'fixture-preserved'){exit 7}; exit 0") 'BUILD_TEST_ENVIRONMENT_LEAK'
            }
            Assert-ContractTest ($env:AUDIT_DB_PASSWORD -eq 'FIXTURE_ONLY_NOT_A_CREDENTIAL')
            Assert-ContractTest ($env:AUDIT_AI_CONTRACT_FACT_EXTRACTION_ENABLED -eq 'true')
            Assert-ContractTest ($env:CONTRACT_BUILD_TEST_KEEP -eq 'fixture-preserved')
        } finally {foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$before[$name],'Process')}}
    }
    Test-ContractGuard 'build verification failure restores runtime environment and removes new audit variables' {
        $name='AUDIT_BUILD_TEST_FIXTURE';$added='AUDIT_BUILD_TEST_ADDED'
        $before=[Environment]::GetEnvironmentVariable($name,'Process');$addedBefore=[Environment]::GetEnvironmentVariable($added,'Process')
        try {
            $env:AUDIT_BUILD_TEST_FIXTURE='fixture-original';[Environment]::SetEnvironmentVariable($added,$null,'Process')
            $caught=$null;$script:contractIsolationEntered=$false
            try {Invoke-ContractIsolatedBuildEnvironment {
                    $script:contractIsolationEntered=$true
                    Assert-ContractTest (-not [Environment]::GetEnvironmentVariable($name,'Process'))
                    $env:AUDIT_BUILD_TEST_ADDED='fixture-added';throw 'EXPECTED_BUILD_FAILURE'
                }} catch {$caught=$_.Exception.Message}
            Assert-ContractTest ($script:contractIsolationEntered -and $caught -eq 'EXPECTED_BUILD_FAILURE')
            Assert-ContractTest ($env:AUDIT_BUILD_TEST_FIXTURE -eq 'fixture-original')
            Assert-ContractTest (-not [Environment]::GetEnvironmentVariable($added,'Process'))
        } finally {[Environment]::SetEnvironmentVariable($name,$before,'Process');[Environment]::SetEnvironmentVariable($added,$addedBefore,'Process')}
    }
    Test-ContractGuard 'private approved identity rejects current env substitutions and privileged fields' {
        $text="format=CONTRACT_LOCAL_DB_IDENTITY_V1`nurl=jdbc:dm://127.0.0.1:5236`nuser=FIXTURE_BUSINESS`nschema=FIXTURE_SCHEMA`n"
        $approved=ConvertFrom-ContractPrivateIdentity $text
        Assert-ContractTest ($null -ne $approved)
        Assert-ContractDatabaseIdentity $approved 'jdbc:dm://127.0.0.1:5236' 'FIXTURE_BUSINESS' 'FIXTURE_SCHEMA'
        foreach($user in @('SYSDBA','SYSAUDITOR','OTHER_BUSINESS')){Assert-ContractReject {Assert-ContractDatabaseIdentity $approved 'jdbc:dm://127.0.0.1:5236' $user 'FIXTURE_SCHEMA'}}
        Assert-ContractReject {Assert-ContractDatabaseIdentity $approved 'jdbc:dm://127.0.0.1:5236' 'FIXTURE_BUSINESS' 'OTHER'}
        foreach($bad in @(($text+"password=unexpected`n"),($text+"user=DUPLICATE`n"),$text.Replace("schema=FIXTURE_SCHEMA`n",''),$text.Replace('FIXTURE_BUSINESS','SYSDBA'),$text.Replace('FIXTURE_SCHEMA','SYSAUDITOR'),$text.Replace('127.0.0.1','192.0.2.1'))){Assert-ContractReject {ConvertFrom-ContractPrivateIdentity $bad}}
    }
    Test-ContractGuard 'latest receipt advances but immutable authority rejects tampering and sibling paths' {
        $root=Join-Path $contractFixtureDir 'identity-authority';$runtime=Join-Path $root '.runtime\contract-repair-test';$backupRoot=Join-Path $runtime 'backups';$backup=Join-Path $backupRoot '20261014-182117-590'
        [IO.Directory]::CreateDirectory($backup)|Out-Null
        $manifest=Join-Path $backup 'manifest.properties';$sidecar=Join-Path $backup 'local-identity.properties';$receipt=Join-Path $backup 'identity-reset-applied.txt';$latest=Join-Path $runtime 'data-reset-applied.txt'
        $encoding=[Text.UTF8Encoding]::new($false)
        try {
            [IO.File]::WriteAllText($manifest,"format=AUDIT_DM8_TYPED_BACKUP_V1`nplan.sha256=fixture-plan`n",$encoding)
            [IO.File]::WriteAllText($sidecar,"format=CONTRACT_LOCAL_DB_IDENTITY_V1`nurl=jdbc:dm://127.0.0.1:5236`nuser=FIXTURE_BUSINESS`nschema=FIXTURE_SCHEMA`n",$encoding)
            [IO.File]::WriteAllText($receipt,"DATA_RESET=PASS`nPLAN_SHA256=fixture-plan`nBACKUP=$backup`n",$encoding)
            [IO.File]::Copy($receipt,$latest)
            $mh=(Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant();$rh=(Get-FileHash -LiteralPath $receipt -Algorithm SHA256).Hash.ToLowerInvariant();$ih=(Get-FileHash -LiteralPath $sidecar -Algorithm SHA256).Hash.ToLowerInvariant()
            $approved=Read-ContractIdentityAuthority $root $root $mh $rh $ih;Assert-ContractTest ($null -ne $approved -and $approved.user -eq 'FIXTURE_BUSINESS')
            [IO.File]::WriteAllText($latest,"DATA_RESET=PASS`nPLAN_SHA256=later-authorized-plan`nBACKUP=$($backup+'-later')`n",$encoding)
            $reopened=Read-ContractIdentityAuthority $root $root $mh $rh $ih;Assert-ContractTest ($reopened.user -eq 'FIXTURE_BUSINESS')
            Assert-ContractReject {Read-ContractIdentityAuthority $root ($root+'-other') $mh $rh $ih}
            foreach($file in @($manifest,$receipt,$sidecar)){$original=[IO.File]::ReadAllText($file);[IO.File]::WriteAllText($file,$original+'tamper',$encoding);Assert-ContractReject {Read-ContractIdentityAuthority $root $root $mh $rh $ih};[IO.File]::WriteAllText($file,$original,$encoding)}
            [IO.File]::WriteAllText($receipt,"DATA_RESET=PASS`nPLAN_SHA256=fixture-plan`nBACKUP=$($backup+'-sibling')`n",$encoding);$siblingHash=(Get-FileHash -LiteralPath $receipt -Algorithm SHA256).Hash.ToLowerInvariant()
            Assert-ContractReject {Read-ContractIdentityAuthority $root $root $mh $siblingHash $ih}
            Remove-Item -LiteralPath $sidecar;Assert-ContractReject {Read-ContractIdentityAuthority $root $root $mh $siblingHash $ih}
        } finally {
            foreach($p in @($sidecar,$manifest,$receipt,$latest)){if(Test-Path -LiteralPath $p){Remove-Item -LiteralPath $p}}
            foreach($p in @($backup,$backupRoot,$runtime,(Join-Path $root '.runtime'),$root)){Remove-Item -LiteralPath $p}
        }
    }
} finally {Remove-Item -LiteralPath $contractFixtureDir}
Write-Output ('CONTRACT_GUARD_TESTS='+$script:contractTests+' FAILURES='+$script:contractFailures)
if($script:contractFailures){exit 1}
