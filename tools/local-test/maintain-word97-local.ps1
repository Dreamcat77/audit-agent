[CmdletBinding()]
param(
    [ValidateSet('Snapshot','Apply','Verify')][string]$Mode='Verify',
    [string]$BackupDirectory,
    [string]$PrivateConfigPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{40}$')][string]$ExpectedSourceSha,
    [switch]$WritersStopped,
    [switch]$WritersProofOnly
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$word97Root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $PSScriptRoot 'contract-test-guards.ps1')

function Assert-Word97SourceIdentity([string]$Root,[string]$Branch,[string]$Head,[string]$ApprovedSha,[string[]]$DirtyPaths=@()) {
    Assert-ContractSourceIdentity $Root 'D:\code\audit_agent_1D\.worktrees\integration-pre-main-20261010' $Branch 'integration/pre-main-20261010' $Head $ApprovedSha $DirtyPaths
}
function Assert-Word97StoppedState([string]$Root,[array]$Processes,[array]$Listeners,[array]$Entries) {
    if(@($Listeners | Where-Object {$_.LocalPort -in @(8001,8888,5173)}).Count){throw 'WORD97_APPLICATION_LISTENER_ACTIVE'}
    foreach($word97Process in $Processes){
        if($word97Process.Name -notmatch '^(java|javaw|python|pythonw|node)(\.exe)?$'){continue}
        if([string]::IsNullOrWhiteSpace($word97Process.CommandLine)){throw 'WORD97_APPLICATION_PROCESS_SCOPE_UNKNOWN'}
        $word97Command=$word97Process.CommandLine
        $word97Arguments=@([regex]::Matches($word97Command,'"[^"]*"|\S+') | ForEach-Object {$_.Value.Trim('"')})
        $word97JarFlags=@(for($word97Index=0;$word97Index -lt $word97Arguments.Count;$word97Index++){if($word97Arguments[$word97Index] -ceq '-jar'){$word97Index}})
        $word97Uvicorn=$word97Command -match '(?i)(?:^|[\s\\/])uvicorn(?:\.exe)?(?:[\s\\/:]|$)'
        $word97Vite=$word97Command -match '(?i)(?:^|[\s\\/])vite(?:\.[cm]?js)?(?:[\s\\/]|$)'
        $word97Worker=$word97Command -match '(?i)\b(?:contract[-_.]?)?(?:review|file(?:[-_.]?processing)?)[-_.a-z0-9]*worker(?:\.py)?\b'
        $word97Main=$word97Command -match '(?i)(?:^|\s)com\.fuquan\.audit(?:\.|\s|$)'
        $word97Spring=$word97Command -match '(?i)(?:^|\s)spring-boot:run(?:\s|$)'
        if(-not($word97JarFlags.Count -or $word97Uvicorn -or $word97Vite -or $word97Worker -or $word97Main -or $word97Spring)){continue}
        # Classify the actual code argument, never a jar mentioned inside a classpath.
        $word97Sources=@()
        if($word97JarFlags.Count){
            if($word97JarFlags.Count -ne 1 -or $word97JarFlags[0]+1 -ge $word97Arguments.Count){throw 'WORD97_APPLICATION_PROCESS_SCOPE_UNKNOWN'}
            $word97Sources=@($word97Arguments[$word97JarFlags[0]+1])
        } elseif($word97Uvicorn){
            for($word97Index=0;$word97Index -lt $word97Arguments.Count;$word97Index++){
                if($word97Arguments[$word97Index] -ceq '--app-dir' -and $word97Index+1 -lt $word97Arguments.Count){$word97Sources+=@($word97Arguments[$word97Index+1])}
                elseif($word97Arguments[$word97Index].StartsWith('--app-dir=',[StringComparison]::Ordinal)){$word97Sources+=@($word97Arguments[$word97Index].Substring(10))}
            }
            if($word97Sources.Count -ne 1){throw 'WORD97_APPLICATION_PROCESS_SCOPE_UNKNOWN'}
        } elseif($word97Vite -or $word97Worker){
            $word97Sources=@($word97Arguments | Where-Object {$_ -match '(?i)(?:[\\/]vite\.[cm]?js|(?:review|file)[-_.a-z0-9]*worker\.py)$'})
        } elseif($word97Main){
            for($word97Index=0;$word97Index -lt $word97Arguments.Count-1;$word97Index++){if($word97Arguments[$word97Index] -cin @('-cp','-classpath','--class-path')){$word97Sources+=@($word97Arguments[$word97Index+1] -split ';')}}
        } elseif($word97Spring){
            foreach($word97Argument in $word97Arguments){if($word97Argument.StartsWith('-Dmaven.multiModuleProjectDirectory=',[StringComparison]::Ordinal)){$word97Sources+=@($word97Argument.Substring('-Dmaven.multiModuleProjectDirectory='.Length))}}
            for($word97Index=0;$word97Index -lt $word97Arguments.Count-1;$word97Index++){if($word97Arguments[$word97Index] -cin @('-f','--file')){$word97Sources+=@($word97Arguments[$word97Index+1])}}
        }
        if(-not $word97Sources.Count){throw 'WORD97_APPLICATION_PROCESS_SCOPE_UNKNOWN'}
        foreach($word97Source in $word97Sources){
            if($word97Source -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\/]+[\\/][^\\/]+[\\/])'){throw 'WORD97_APPLICATION_PROCESS_SCOPE_UNKNOWN'}
            $word97Absolute=[IO.Path]::GetFullPath($word97Source).TrimEnd('\')
            if($word97Absolute -eq $Root -or $word97Absolute.StartsWith($Root+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'WORD97_THIS_WORKTREE_WRITER_ACTIVE'}
        }
    }
    foreach($word97Entry in $Entries){
        if([IO.Path]::GetFullPath($word97Entry.codeRoot) -ne $Root){throw 'WORD97_LEDGER_SOURCE_UNKNOWN'}
        $word97Observed=@($Processes | Where-Object {[int]$_.ProcessId -eq [int]$word97Entry.pid})
        if($word97Observed.Count -gt 1){throw 'WORD97_LEDGER_PROCESS_UNKNOWN'}
        if($word97Observed.Count -eq 1){
            if($null -eq $word97Observed[0].CreationDate){throw 'WORD97_LEDGER_CREATION_UNKNOWN'}
            if($word97Observed[0].CreationDate.ToUniversalTime().ToString('o') -eq $word97Entry.creationUtc){throw 'WORD97_RECORDED_OLD_SERVICE_STILL_ACTIVE'}
        }
    }
}
function Assert-Word97NoReparse([string]$Path) {
    $word97Scan=[IO.Path]::GetFullPath($Path)
    if($word97Scan -ne $word97Root -and -not $word97Scan.StartsWith($word97Root+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'WORD97_PATH_OUTSIDE_WORKTREE'}
    while($word97Scan.Length -ge $word97Root.Length){
        if(Test-Path -LiteralPath $word97Scan){if(((Get-Item -LiteralPath $word97Scan -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'WORD97_REPARSE_PATH_REJECTED'}}
        if($word97Scan -eq $word97Root){break};$word97Scan=Split-Path $word97Scan
    }
}
function Set-Word97RestrictedDirectory([string]$Path) {
    $word97Acl=New-Object Security.AccessControl.DirectorySecurity
    $word97Owner=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $word97Acl.SetOwner($word97Owner);$word97Acl.SetAccessRuleProtection($true,$false)
    foreach($word97Sid in @($word97Owner,(New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))){
        $word97Acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($word97Sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))
    }
    Set-Acl -LiteralPath $Path -AclObject $word97Acl
}
function Assert-Word97RestrictedDirectory([string]$Path) {
    Assert-Word97NoReparse $Path
    $word97Owner=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $word97Allowed=@($word97Owner,'S-1-5-18')
    $word97Items=@((Get-Item -LiteralPath $Path -Force))+@(Get-ChildItem -LiteralPath $Path -Force -Recurse)
    foreach($word97Item in $word97Items){
        Assert-Word97NoReparse $word97Item.FullName
        $word97Acl=Get-Acl -LiteralPath $word97Item.FullName
        if($word97Item.FullName -eq $Path -and -not $word97Acl.AreAccessRulesProtected){throw 'WORD97_BACKUP_ACL_INHERITANCE_ACTIVE'}
        if($word97Acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $word97Owner){throw 'WORD97_BACKUP_OWNER_DRIFT'}
        $word97Full=@{}
        foreach($word97Rule in $word97Acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){
            if($word97Rule.AccessControlType -eq 'Allow'){
                if($word97Allowed -notcontains $word97Rule.IdentityReference.Value){throw 'WORD97_BACKUP_ACL_UNEXPECTED_PRINCIPAL'}
                if(($word97Rule.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq [Security.AccessControl.FileSystemRights]::FullControl){$word97Full[$word97Rule.IdentityReference.Value]=$true}
            }
        }
        if(-not $word97Full.ContainsKey($word97Owner) -or -not $word97Full.ContainsKey('S-1-5-18')){throw 'WORD97_BACKUP_ACL_FULLCONTROL_MISSING'}
    }
}
function Assert-Word97Writers {
    $word97Entries=@()
    $word97Runtime=Join-Path $word97Root '.runtime\contract-repair-test'
    if(Test-Path -LiteralPath $word97Runtime){
        foreach($word97Ledger in @(Get-ChildItem -LiteralPath $word97Runtime -Filter 'pids*.json' -File)){
            Assert-Word97NoReparse $word97Ledger.FullName
            $word97Record=Get-Content -LiteralPath $word97Ledger.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
            if($word97Record.format -ne 1 -or $word97Record.codeRoot -ne $word97Root){throw 'WORD97_PID_LEDGER_SCOPE_UNKNOWN'}
            $word97Entries+=@($word97Record.entries)
        }
    }
    Assert-Word97StoppedState $word97Root @(Get-CimInstance Win32_Process -ErrorAction Stop) @(Get-NetTCPConnection -State Listen -ErrorAction Stop) $word97Entries
}
function Get-Word97SourceDigest {
    $word97Names=@('tools/local-test/ResetReviewTestData.java','tools/local-test/ContractLocalMigration.java','tools/local-test/Word97LocalMigration.java','tools/local-test/Word97Maintenance.java','tools/local-test/maintain-word97-local.ps1','tools/local-test/contract-test-guards.ps1')
    $word97Text=''
    foreach($word97Name in $word97Names){$word97Text+=$word97Name+'|'+(Get-FileHash -LiteralPath (Join-Path $word97Root $word97Name) -Algorithm SHA256).Hash.ToLowerInvariant()+"`n"}
    $word97Text+=(Get-FileHash -LiteralPath (Join-Path $word97Root 'db/dm8/CONTRACT__project_file_word97_method.sql') -Algorithm SHA256).Hash.ToLowerInvariant()
    return (Get-ContractTextHash $word97Text)
}
$word97Saved=@{}
try {
    $word97Dirty=@(& git.exe -C $word97Root status --porcelain --untracked-files=all)
    if($LASTEXITCODE -ne 0){throw 'WORD97_SOURCE_STATUS_UNAVAILABLE'}
    Assert-Word97SourceIdentity $word97Root (& git.exe -C $word97Root branch --show-current) (& git.exe -C $word97Root rev-parse HEAD) $ExpectedSourceSha $word97Dirty
    if(-not $BackupDirectory){
        if($Mode -ne 'Snapshot' -or $WritersProofOnly){throw 'WORD97_SAME_BACKUP_DIRECTORY_REQUIRED'}
        $BackupDirectory=Join-Path $word97Root ('.runtime\contract-repair-test\word97-maintenance\'+[datetime]::UtcNow.ToString('yyyyMMdd-HHmmssfff'))
    }
    $BackupDirectory=[IO.Path]::GetFullPath($BackupDirectory).TrimEnd('\')
    $word97Area=Join-Path $word97Root '.runtime\contract-repair-test\word97-maintenance'
    if((Split-Path $BackupDirectory) -ne $word97Area -or (Split-Path $BackupDirectory -Leaf) -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,80}$'){throw 'WORD97_BACKUP_OUTSIDE_CANONICAL_RUNTIME'}
    Assert-Word97NoReparse $BackupDirectory
    & git.exe -C $word97Root check-ignore --quiet -- $BackupDirectory
    if($LASTEXITCODE -ne 0){throw 'WORD97_BACKUP_NOT_IGNORED'}
    Assert-Word97Writers
    if($WritersProofOnly){Assert-Word97RestrictedDirectory $BackupDirectory;Write-Output 'WORD97_WRITERS_STOPPED=PROVEN';exit 0}
    if($Mode -eq 'Snapshot'){
        if(-not $WritersStopped){throw 'WORD97_EXPLICIT_WRITERS_STOPPED_REQUIRED'}
        if(Test-Path -LiteralPath $BackupDirectory){throw 'WORD97_SNAPSHOT_DIRECTORY_MUST_BE_NEW'}
        [IO.Directory]::CreateDirectory($word97Area)|Out-Null
        Assert-Word97NoReparse $word97Area
        [IO.Directory]::CreateDirectory($BackupDirectory)|Out-Null
        Set-Word97RestrictedDirectory $BackupDirectory
        Assert-Word97RestrictedDirectory $BackupDirectory
        $word97Proof="format=WORD97_WRITERS_STOPPED_V1`nroot=$($word97Root.Replace('\','/'))`nsource.sha256=$(Get-Word97SourceDigest)`nhead=$ExpectedSourceSha`ncreated=$([datetime]::UtcNow.ToString('o'))`n"
        [IO.File]::WriteAllText((Join-Path $BackupDirectory 'writers-stopped.properties'),$word97Proof,(New-Object Text.UTF8Encoding($false)))
    }
    Assert-Word97RestrictedDirectory $BackupDirectory
    $word97Keys=@('AUDIT_DB_URL','AUDIT_DB_USERNAME','AUDIT_DB_PASSWORD')
    foreach($word97Key in $word97Keys){$word97Saved[$word97Key]=[Environment]::GetEnvironmentVariable($word97Key,'Process')}
    if(-not $PrivateConfigPath){$PrivateConfigPath=Join-Path (Split-Path $word97Root) 'ai-ocr-project-intake-demo\.runtime\local-demo.env.integration.legacy.ps1'}
    $word97Values=Read-ContractLiteralEnvironment $PrivateConfigPath $word97Keys $word97Keys
    $word97Identity=Read-ContractApprovedDatabaseIdentity $word97Root
    Assert-ContractDatabaseIdentity $word97Identity $word97Values['AUDIT_DB_URL'] $word97Values['AUDIT_DB_USERNAME'] $word97Identity.schema
    foreach($word97Key in $word97Keys){[Environment]::SetEnvironmentVariable($word97Key,$word97Values[$word97Key],'Process')}
    $word97Classes=Join-Path $word97Area 'offline-classes'
    [IO.Directory]::CreateDirectory($word97Classes)|Out-Null
    $word97Sources=@('ResetReviewTestData.java','ContractTestStorageProbe.java','ContractLocalMigration.java','Word97LocalMigration.java','Word97Maintenance.java','Word97MaintenanceTest.java')|ForEach-Object {Join-Path $PSScriptRoot $_}
    Invoke-ContractNative 'javac.exe' (@('-encoding','UTF-8','-d',$word97Classes)+$word97Sources) 'WORD97_MAINTENANCE_COMPILE_FAILED'
    Invoke-ContractNative 'java.exe' @('-ea','-cp',$word97Classes,'Word97MaintenanceTest',$word97Root) 'WORD97_MAINTENANCE_OFFLINE_TEST_FAILED'
    $word97Driver=Join-Path $env:USERPROFILE '.m2\repository\com\dameng\DmJdbcDriver18\8.1.3.140\DmJdbcDriver18-8.1.3.140.jar'
    if(-not(Test-Path -LiteralPath $word97Driver -PathType Leaf)){throw 'WORD97_DM8_DRIVER_MISSING'}
    Assert-Word97Writers
    Write-Output ('WORD97_BACKUP_DIRECTORY='+$BackupDirectory)
    Invoke-ContractNative 'java.exe' @('-cp',($word97Classes+[IO.Path]::PathSeparator+$word97Driver),'Word97Maintenance',$Mode,$word97Root,$BackupDirectory,$ExpectedSourceSha) 'WORD97_MAINTENANCE_BLOCKED_SAME_JOURNAL_REQUIRED'
} catch {
    $word97Code=$_.Exception.Message
    if($word97Code -notmatch '^[A-Z0-9_]{1,100}$'){$word97Code='UNKNOWN_SUPPRESSED_ERROR'}
    Write-Output ('WORD97_MAINTENANCE=BLOCKED OUTCOME=UNKNOWN_UNTIL_SAME_JOURNAL_REOPEN gate='+$word97Code);exit 1
} finally {
    foreach($word97Key in $word97Saved.Keys){[Environment]::SetEnvironmentVariable($word97Key,$word97Saved[$word97Key],'Process')}
}
