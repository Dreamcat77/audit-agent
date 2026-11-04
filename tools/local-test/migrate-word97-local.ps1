[CmdletBinding()]
param([ValidateSet('Plan','Verify','Apply')][string]$Mode='Plan')
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
# Apply is deliberately unavailable, regardless of writer state or old reset receipts.
if($Mode -eq 'Apply'){Write-Output 'WORD97_MIGRATION=BLOCKED NOT_APPLIED gate=WORD97_APPLY_UNAVAILABLE_MAINTENANCE_BACKUP_JOURNAL_REQUIRED';exit 1}
$word97Repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
. (Join-Path $PSScriptRoot 'contract-test-guards.ps1')
$word97Environment=@{}
try {
    if($word97Repo -ne 'D:\code\audit_agent_1D\.worktrees\integration-pre-main-20261010' -or (& git.exe -C $word97Repo branch --show-current) -ne 'integration/pre-main-20261010'){throw 'WORD97_CANONICAL_SOURCE_REQUIRED'}
    $word97Keys=@('AUDIT_DB_URL','AUDIT_DB_USERNAME','AUDIT_DB_PASSWORD')
    foreach($word97Key in $word97Keys){$word97Environment[$word97Key]=[Environment]::GetEnvironmentVariable($word97Key,'Process')}
    $word97Config=Join-Path (Split-Path $word97Repo) 'ai-ocr-project-intake-demo\.runtime\local-demo.env.integration.legacy.ps1'
    $word97Values=Read-ContractLiteralEnvironment $word97Config $word97Keys $word97Keys
    $word97Expected=Read-ContractApprovedDatabaseIdentity $word97Repo
    Assert-ContractDatabaseIdentity $word97Expected $word97Values['AUDIT_DB_URL'] $word97Values['AUDIT_DB_USERNAME'] $word97Expected.schema
    foreach($word97Key in $word97Keys){[Environment]::SetEnvironmentVariable($word97Key,$word97Values[$word97Key],'Process')}
    $word97Classes=Join-Path $word97Repo '.runtime\contract-repair-test\repair-20261015\task-d\migration-classes'
    [IO.Directory]::CreateDirectory($word97Classes)|Out-Null
    $word97Sources=@('ResetReviewTestData.java','ContractTestStorageProbe.java','ContractLocalMigration.java','ContractTestRuntimeProbe.java','Word97LocalMigration.java','Word97LocalMigrationTest.java')|ForEach-Object {Join-Path $PSScriptRoot $_}
    Invoke-ContractNative 'javac.exe' (@('-encoding','UTF-8','-d',$word97Classes)+$word97Sources) 'WORD97_TOOL_COMPILE_FAILED'
    Invoke-ContractNative 'java.exe' @('-ea','-cp',$word97Classes,'Word97LocalMigrationTest',$word97Repo) 'WORD97_TOOL_TEST_FAILED'
    foreach($word97Key in @('AUDIT_DB_URL','AUDIT_DB_USERNAME','AUDIT_DB_PASSWORD')){if(-not[Environment]::GetEnvironmentVariable($word97Key,'Process')){throw 'WORD97_APPROVED_DB_ENV_REQUIRED'}}
    $word97Driver=Join-Path $env:USERPROFILE '.m2\repository\com\dameng\DmJdbcDriver18\8.1.3.140\DmJdbcDriver18-8.1.3.140.jar'
    Invoke-ContractNative 'java.exe' @('-cp',($word97Classes+[IO.Path]::PathSeparator+$word97Driver),'Word97LocalMigration',$Mode,$word97Repo) 'WORD97_READONLY_GATES_BLOCKED'
} catch {$word97Code=$_.Exception.Message;if($word97Code -notmatch '^[A-Z0-9_]{1,100}$'){$word97Code='UNKNOWN_SUPPRESSED_ERROR'};Write-Output ('WORD97_MIGRATION=BLOCKED NOT_APPLIED gate='+$word97Code);exit 1}
finally {foreach($word97Key in $word97Environment.Keys){[Environment]::SetEnvironmentVariable($word97Key,$word97Environment[$word97Key],'Process')}}
