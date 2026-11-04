[CmdletBinding()]
param([ValidateSet('Preflight','Apply','Verify')][string]$Mode='Preflight',[string]$BackupDirectory)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$migrationRepo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$migrationRuntime=Join-Path $migrationRepo '.runtime\contract-repair-test'
if($migrationRepo -ne 'D:\code\audit_agent_1D\.worktrees\integration-pre-main-20261010'){throw 'MIGRATION_WORKTREE_MISMATCH'}
. (Join-Path $PSScriptRoot 'contract-test-guards.ps1')
$migrationAllowed=@('AUDIT_DB_URL','AUDIT_DB_USERNAME','AUDIT_DB_PASSWORD');$migrationOriginal=@{}
foreach($name in $migrationAllowed){$migrationOriginal[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    if((& git.exe -C $migrationRepo branch --show-current) -ne 'integration/pre-main-20261010'){throw 'MIGRATION_BRANCH_MISMATCH'}
    $writers=@(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object {$_.LocalPort -in @(8001,8888,5173)})
    if($writers.Count){throw 'MIGRATION_APPLICATION_WRITER_LISTENER_ACTIVE'}
    $processWriters=@(Get-CimInstance Win32_Process -ErrorAction Stop|Where-Object {$_.Name -match '^(java|python|node)(\.exe)?$' -and $_.CommandLine -and $_.CommandLine -match '(audit.*\.jar|com\.fuquan\.audit|spring-boot:run|uvicorn|vite)'})
    if($processWriters.Count){throw 'MIGRATION_APPLICATION_WRITER_PROCESS_ACTIVE'}
    $resetReceipt=Join-Path $migrationRuntime 'data-reset-applied.txt'
    if(-not(Test-Path -LiteralPath $resetReceipt) -or (Get-Content -LiteralPath $resetReceipt -Raw -Encoding UTF8) -notmatch '(?m)^DATA_RESET=PASS\s*$'){throw 'MIGRATION_RESET_RECEIPT_REQUIRED'}
    $legacy=Join-Path (Split-Path $migrationRepo) 'ai-ocr-project-intake-demo\.runtime\local-demo.env.integration.legacy.ps1'
    $settings=Read-ContractLiteralEnvironment $legacy $migrationAllowed $migrationAllowed
    foreach($key in $migrationAllowed){[Environment]::SetEnvironmentVariable($key,$settings[$key],'Process')}
    $migrationDbIdentity=Read-ContractApprovedDatabaseIdentity $migrationRepo
    Assert-ContractDatabaseIdentity $migrationDbIdentity $env:AUDIT_DB_URL $env:AUDIT_DB_USERNAME $migrationDbIdentity.schema
    if(-not $BackupDirectory){if($Mode -ne 'Preflight'){throw 'VERIFIED_MIGRATION_BACKUP_REQUIRED'};$BackupDirectory=Join-Path $migrationRuntime ('migrations\'+[datetime]::UtcNow.ToString('yyyyMMdd-HHmmssfff'));[IO.Directory]::CreateDirectory($BackupDirectory)|Out-Null
        $owner=[Security.Principal.WindowsIdentity]::GetCurrent().User;$system=New-Object Security.Principal.SecurityIdentifier('S-1-5-18');$acl=New-Object Security.AccessControl.DirectorySecurity;$acl.SetOwner($owner);$acl.SetAccessRuleProtection($true,$false)
        foreach($sid in @($owner,$system)){$acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')))};Set-Acl -LiteralPath $BackupDirectory -AclObject $acl
}
    $BackupDirectory=[IO.Path]::GetFullPath($BackupDirectory);$migrationRoot=[IO.Path]::GetFullPath((Join-Path $migrationRuntime 'migrations'))+'\'
    if(-not $BackupDirectory.StartsWith($migrationRoot,[StringComparison]::OrdinalIgnoreCase)){throw 'MIGRATION_BACKUP_OUTSIDE_RUNTIME'}
    $scan=$BackupDirectory;while($scan.Length -ge $migrationRepo.Length){if(Test-Path -LiteralPath $scan){if(((Get-Item -LiteralPath $scan -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'MIGRATION_REPARSE_POINT'}};if($scan -eq $migrationRepo){break};$scan=Split-Path $scan}
    & git.exe -C $migrationRepo check-ignore --quiet -- $BackupDirectory;if($LASTEXITCODE -ne 0){throw 'MIGRATION_BACKUP_NOT_IGNORED'}
    $acl=Get-Acl -LiteralPath $BackupDirectory;if(-not $acl.AreAccessRulesProtected){throw 'MIGRATION_BACKUP_ACL_NOT_RESTRICTED'}
    $allowedSids=@([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18');foreach($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])){if($rule.AccessControlType -eq 'Allow' -and $allowedSids -notcontains $rule.IdentityReference.Value){throw 'MIGRATION_BACKUP_ACL_EXTRA_PRINCIPAL'}}
    $classes=Join-Path $migrationRuntime 'migration-classes';[IO.Directory]::CreateDirectory($classes)|Out-Null
    Invoke-ContractNative 'javac.exe' @('-encoding','UTF-8','-d',$classes,(Join-Path $PSScriptRoot 'ResetReviewTestData.java'),(Join-Path $PSScriptRoot 'ContractTestStorageProbe.java'),(Join-Path $PSScriptRoot 'ContractLocalMigration.java'),(Join-Path $PSScriptRoot 'ContractLocalMigrationTest.java')) 'MIGRATION_TOOL_COMPILE_FAILED'
    Invoke-ContractNative 'java.exe' @('-ea','-cp',$classes,'ContractLocalMigrationTest') 'MIGRATION_TOOL_TEST_FAILED'
    $driver=Join-Path $env:USERPROFILE '.m2\repository\com\dameng\DmJdbcDriver18\8.1.3.140\DmJdbcDriver18-8.1.3.140.jar'
    Invoke-ContractNative 'java.exe' @('-cp',($classes+[IO.Path]::PathSeparator+$driver),'ContractLocalMigration',$Mode,$migrationRepo,$BackupDirectory) 'LOCAL_MIGRATION_GATES_BLOCKED'
} catch {$code=$_.Exception.Message;if($code -notmatch '^[A-Z0-9_]{1,100}$'){$code='UNKNOWN_SUPPRESSED_ERROR'};Write-Output ('LOCAL_MIGRATION=BLOCKED gate='+$code);exit 1}
finally{foreach($name in $migrationAllowed){[Environment]::SetEnvironmentVariable($name,$migrationOriginal[$name],'Process')}}
