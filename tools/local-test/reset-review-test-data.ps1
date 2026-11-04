[CmdletBinding()]
param(
    [ValidateSet('DryRun','Apply','Restore')][string]$Mode = 'DryRun',
    [string]$BackupDirectory,
    [string]$PrivateConfigPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$resetRepo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$resetRuntime = Join-Path $resetRepo '.runtime\contract-repair-test'
$allowedTarget = 'D:\code\audit_agent_1D\.worktrees\integration-pre-main-20261010'
if ($resetRepo -ne $allowedTarget) { throw 'RESET_WORKTREE_MISMATCH' }
. (Join-Path $PSScriptRoot 'contract-test-guards.ps1')
if ((& git.exe -C $resetRepo branch --show-current) -ne 'integration/pre-main-20261010') { throw 'RESET_BRANCH_MISMATCH' }
if (-not $PrivateConfigPath) {
    $PrivateConfigPath = Join-Path (Split-Path $resetRepo) 'ai-ocr-project-intake-demo\.runtime\local-demo.env.integration.legacy.ps1'
}
function Assert-NoResetWriters {
    $resetListeners = @(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -in @(8001,8888,5173) })
    if ($resetListeners.Count) { throw 'RESET_APPLICATION_LISTENER_ACTIVE' }
    $resetProcesses = @(Get-CimInstance Win32_Process -ErrorAction Stop | Where-Object {
        $_.Name -match '^(java|python|node)(\.exe)?$' -and $_.CommandLine -and
        $_.CommandLine -match '(audit.*\.jar|com\.fuquan\.audit|spring-boot:run|uvicorn|vite|contract-review.*worker|file-processing.*worker)'
    })
    if ($resetProcesses.Count) { throw 'RESET_APPLICATION_WRITER_PROCESS_ACTIVE' }
    Write-Output 'LOCAL_TARGET_WRITERS=NONE'
}
function Assert-NoResetReparse([string]$Path) {
    $resetPath = [IO.Path]::GetFullPath($Path)
    while ($resetPath.Length -ge $resetRepo.Length) {
        if (Test-Path -LiteralPath $resetPath) {
            $resetItem = Get-Item -LiteralPath $resetPath -Force
            if (($resetItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'RESET_REPARSE_POINT' }
        }
        if ($resetPath -eq $resetRepo) { break }
        $resetPath = Split-Path $resetPath
    }
}
$resetAllowed = @('AUDIT_DB_URL','AUDIT_DB_USERNAME','AUDIT_DB_PASSWORD')
$resetOriginal = @{}
foreach ($name in $resetAllowed) { $resetOriginal[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
try {
    Assert-NoResetWriters
    $resetTokens = $null; $resetParseErrors = $null
    $resetTree = [Management.Automation.Language.Parser]::ParseFile($PrivateConfigPath,[ref]$resetTokens,[ref]$resetParseErrors)
    if ($resetParseErrors.Count) { throw 'PRIVATE_CONFIG_PARSE_ERROR' }
    $resetFound = @{}
    foreach ($assignment in $resetTree.EndBlock.Statements) {
        if ($assignment -isnot [Management.Automation.Language.AssignmentStatementAst] -or $assignment.Operator -ne 'Equals') { continue }
        if ($assignment.Left -isnot [Management.Automation.Language.VariableExpressionAst]) { continue }
        $name = $assignment.Left.VariablePath.UserPath
        if (-not $name.StartsWith('env:')) { continue }
        $name = $name.Substring(4).ToUpperInvariant()
        if ($resetAllowed -notcontains $name) { continue }
        $right = $assignment.Right
        if ($right -is [Management.Automation.Language.PipelineAst] -and $right.PipelineElements.Count -eq 1) { $right = $right.PipelineElements[0] }
        if ($right -isnot [Management.Automation.Language.CommandExpressionAst] -or $right.Expression -isnot [Management.Automation.Language.StringConstantExpressionAst]) { throw 'NON_LITERAL_ALLOWLIST_SETTING' }
        if ($resetFound.ContainsKey($name)) { throw 'DUPLICATE_ALLOWLIST_SETTING' }
        $resetFound[$name] = $true
        [Environment]::SetEnvironmentVariable($name,$right.Expression.Value,'Process')
    }
    if ($resetFound.Count -ne 3) { throw 'MISSING_PRIVATE_DATABASE_SETTING' }
    $resetDbIdentity=Read-ContractApprovedDatabaseIdentity $resetRepo
    Assert-ContractDatabaseIdentity $resetDbIdentity $env:AUDIT_DB_URL $env:AUDIT_DB_USERNAME $resetDbIdentity.schema
    $resetDriver = Join-Path $env:USERPROFILE '.m2\repository\com\dameng\DmJdbcDriver18\8.1.3.140\DmJdbcDriver18-8.1.3.140.jar'
    if (-not (Test-Path -LiteralPath $resetDriver)) { throw 'DM8_DRIVER_MISSING' }
    $resetClasses = Join-Path $resetRuntime 'reset-classes'
    New-Item -ItemType Directory -Force -Path $resetClasses | Out-Null
    Invoke-ContractNative 'javac.exe' @('-encoding','UTF-8','-d',$resetClasses,(Join-Path $PSScriptRoot 'ResetReviewTestData.java'),(Join-Path $PSScriptRoot 'ResetReviewTestDataTest.java')) 'RESET_TOOL_COMPILE_FAILED'
    & java.exe -ea -cp $resetClasses ResetReviewTestDataTest
    if ($LASTEXITCODE -ne 0) { throw 'RESET_TOOL_TEST_FAILED' }
    if ($Mode -eq 'DryRun') {
        if ($BackupDirectory) { throw 'DRYRUN_BACKUP_IS_GENERATED' }
        $BackupDirectory = Join-Path $resetRuntime ('backups\' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
        Assert-NoResetReparse $BackupDirectory
        New-Item -ItemType Directory -Path $BackupDirectory | Out-Null
        $resetIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $resetSystem = New-Object Security.Principal.SecurityIdentifier('S-1-5-18')
        $resetAcl = New-Object Security.AccessControl.DirectorySecurity
        $resetAcl.SetOwner($resetIdentity)
        $resetAcl.SetAccessRuleProtection($true,$false)
        foreach ($resetSid in @($resetIdentity,$resetSystem)) {
            $resetRule = New-Object Security.AccessControl.FileSystemAccessRule($resetSid,'FullControl','ContainerInherit,ObjectInherit','None','Allow')
            $resetAcl.AddAccessRule($resetRule)
        }
        Set-Acl -LiteralPath $BackupDirectory -AclObject $resetAcl
    } elseif (-not $BackupDirectory) { throw 'VERIFIED_BACKUP_DIRECTORY_REQUIRED' }
    $BackupDirectory = [IO.Path]::GetFullPath($BackupDirectory)
    $resetBackupRoot = [IO.Path]::GetFullPath((Join-Path $resetRuntime 'backups')) + [IO.Path]::DirectorySeparatorChar
    if (-not $BackupDirectory.StartsWith($resetBackupRoot,[StringComparison]::OrdinalIgnoreCase)) { throw 'BACKUP_OUTSIDE_RESET_RUNTIME' }
    Assert-NoResetReparse $BackupDirectory
    & git.exe -C $resetRepo check-ignore --quiet -- $BackupDirectory
    if ($LASTEXITCODE -ne 0) { throw 'BACKUP_NOT_GIT_IGNORED' }
    $resetAclCheck = Get-Acl -LiteralPath $BackupDirectory
    if (-not $resetAclCheck.AreAccessRulesProtected) { throw 'BACKUP_ACL_NOT_RESTRICTED' }
    $resetAllowedSids = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value,'S-1-5-18')
    foreach ($resetRule in $resetAclCheck.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
        if ($resetRule.AccessControlType -eq 'Allow' -and $resetAllowedSids -notcontains $resetRule.IdentityReference.Value) { throw 'BACKUP_ACL_UNEXPECTED_PRINCIPAL' }
    }
    Assert-NoResetWriters
    & java.exe -cp ($resetClasses + [IO.Path]::PathSeparator + $resetDriver) ResetReviewTestData $Mode $resetRuntime $BackupDirectory
    if ($LASTEXITCODE -ne 0) { throw 'RESET_GATES_BLOCKED' }
} catch {
    $resetCode = $_.Exception.Message
    if ($resetCode -notmatch '^[A-Z0-9_]{1,80}$') { $resetCode = 'UNKNOWN_SUPPRESSED_ERROR' }
    Write-Output ('DATA_RESET_BLOCKED gate=' + $resetCode)
    exit 1
} finally {
    foreach ($name in $resetAllowed) { [Environment]::SetEnvironmentVariable($name,$resetOriginal[$name],'Process') }
}
