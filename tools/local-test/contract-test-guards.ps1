Set-StrictMode -Version Latest
function ConvertFrom-ContractPrivateIdentity([string]$Text){
    $values=@{}
    foreach($line in ($Text -split '\r?\n')){if(-not $line){continue};$eq=$line.IndexOf('=');if($eq -lt 1){throw 'PRIVATE_IDENTITY_SYNTAX'};$key=$line.Substring(0,$eq);if($key -cnotin @('format','url','user','schema') -or $values.ContainsKey($key)){throw 'PRIVATE_IDENTITY_UNKNOWN_OR_DUPLICATE_FIELD'};$values[$key]=$line.Substring($eq+1)}
    if($values.Count -ne 4 -or $values['format'] -cne 'CONTRACT_LOCAL_DB_IDENTITY_V1' -or $values['url'] -cne 'jdbc:dm://127.0.0.1:5236'){throw 'PRIVATE_IDENTITY_FORMAT_OR_SCOPE'}
    foreach($key in @('user','schema')){if($values[$key] -cnotmatch '^[A-Z][A-Z0-9_]{0,127}$' -or $values[$key] -in @('SYSDBA','SYSAUDITOR','SYS','SYSTEM')){throw 'PRIVATE_IDENTITY_UNSAFE_OR_PRIVILEGED'}}
    return $values
}
function Assert-ContractDatabaseIdentity($Expected,[string]$Url,[string]$User,[string]$Schema){
    if($null -eq $Expected -or $Expected.url -cne $Url -or $Expected.user -cne $User -or $Expected.schema -cne $Schema){throw 'LOCAL_DM8_IDENTITY_MISMATCH'}
}
function Read-ContractIdentityText([string]$Path,[string]$Root,[string]$Sha256){
    if($Sha256 -cnotmatch '^[a-f0-9]{64}$' -or -not $Path.StartsWith($Root+'\',[StringComparison]::OrdinalIgnoreCase) -or -not(Test-Path -LiteralPath $Path -PathType Leaf)){throw 'PRIVATE_IDENTITY_FILE_MISSING_OR_PATH'}
    $scan=$Path;while($scan.Length -ge $Root.Length){if(((Get-Item -LiteralPath $scan -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'PRIVATE_IDENTITY_REPARSE_POINT'};if($scan -eq $Root){break};$scan=Split-Path $scan}
    if((Get-Item -LiteralPath $Path).Length -gt 1048576){throw 'PRIVATE_IDENTITY_FILE_TOO_LARGE'}
    $bytes=[IO.File]::ReadAllBytes($Path);$digest=[Security.Cryptography.SHA256]::Create()
    try{$actual=([BitConverter]::ToString($digest.ComputeHash($bytes))).Replace('-','').ToLowerInvariant()}finally{$digest.Dispose()}
    if($actual -cne $Sha256){throw 'PRIVATE_IDENTITY_AUTHORITY_HASH_MISMATCH'}
    return [Text.Encoding]::UTF8.GetString($bytes)
}
function Read-ContractIdentityAuthority([string]$Root,[string]$ExpectedRoot,[string]$ManifestSha256,[string]$ReceiptSha256,[string]$IdentitySha256){
    $Root=[IO.Path]::GetFullPath($Root).TrimEnd('\');if($Root -ne [IO.Path]::GetFullPath($ExpectedRoot).TrimEnd('\')){throw 'PRIVATE_IDENTITY_SOURCE_MISMATCH'}
    $backup=Join-Path $Root '.runtime\contract-repair-test\backups\20261014-182117-590'
    $manifest=Read-ContractIdentityText (Join-Path $backup 'manifest.properties') $Root $ManifestSha256
    $receipt=Read-ContractIdentityText (Join-Path $backup 'identity-reset-applied.txt') $Root $ReceiptSha256
    $fields=@{};foreach($line in ($receipt -split '\r?\n')){if(-not $line){continue};$eq=$line.IndexOf('=');if($eq -lt 1 -or $fields.ContainsKey($line.Substring(0,$eq))){throw 'PRIVATE_IDENTITY_RECEIPT_SYNTAX'};$fields[$line.Substring(0,$eq)]=$line.Substring($eq+1)}
    $plan=[regex]::Match($manifest,'(?m)^plan\.sha256=(.*)\r?$').Groups[1].Value.TrimEnd("`r")
    if($manifest -notmatch '(?m)^format=AUDIT_DM8_TYPED_BACKUP_V1\r?$' -or $fields['DATA_RESET'] -cne 'PASS' -or -not $plan -or $fields['PLAN_SHA256'] -cne $plan -or -not $fields['BACKUP'] -or [IO.Path]::GetFullPath($fields['BACKUP']) -ne $backup){throw 'PRIVATE_IDENTITY_BACKUP_RECEIPT_MISMATCH'}
    return (ConvertFrom-ContractPrivateIdentity (Read-ContractIdentityText (Join-Path $backup 'local-identity.properties') $Root $IdentitySha256))
}
function Read-ContractApprovedDatabaseIdentity([string]$Root){
    return (Read-ContractIdentityAuthority $Root 'D:\code\audit_agent_1D\.worktrees\integration-pre-main-20261010' 'eed50b07548e6e252a5f3063bc4fe8cc0de50b5feb9c4ad12653fa0b499ae90c' '417750fd8ecfd7f75a8ea7938b4c2613b06ecff9937dace0b7f5285c43fca117' '2a7dc75f8104c4e14799c351e940da00bfd183b76ae004e003067187120c4ea9')
}
function Read-ContractLiteralEnvironment {
    param([string]$Path,[string[]]$Allowed,[string[]]$Required=@())
    $contractTokens=$null; $contractErrors=$null
    $contractAst=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$contractTokens,[ref]$contractErrors)
    if($contractErrors.Count){throw 'PRIVATE_LITERAL_PARSE_ERROR'}
    $contractValues=@{}
    foreach($assignment in $contractAst.EndBlock.Statements) {
        if($assignment -isnot [Management.Automation.Language.AssignmentStatementAst]){continue}
        if($assignment.Left -isnot [Management.Automation.Language.VariableExpressionAst]){continue}
        $contractName=$assignment.Left.VariablePath.UserPath
        if(-not $contractName.StartsWith('env:',[StringComparison]::OrdinalIgnoreCase)){continue}
        $contractName=$contractName.Substring(4).ToUpperInvariant()
        if($Allowed -notcontains $contractName){continue}
        if($assignment.Operator -ne 'Equals'){throw 'PRIVATE_LITERAL_ASSIGNMENT_OPERATOR'}
        $right=$assignment.Right
        if($right -is [Management.Automation.Language.PipelineAst] -and $right.PipelineElements.Count -eq 1){$right=$right.PipelineElements[0]}
        if($right -isnot [Management.Automation.Language.CommandExpressionAst] -or $right.Expression -isnot [Management.Automation.Language.StringConstantExpressionAst]){throw 'PRIVATE_NON_LITERAL_SETTING'}
        if($contractValues.ContainsKey($contractName)){throw 'PRIVATE_DUPLICATE_LITERAL'}
        $contractValues[$contractName]=$right.Expression.Value
    }
    foreach($name in $Required){if(-not $contractValues.ContainsKey($name) -or [string]::IsNullOrWhiteSpace($contractValues[$name])){throw 'PRIVATE_REQUIRED_LITERAL_MISSING'}}
    return $contractValues
}
function Assert-ContractSourceIdentity {
    param([string]$ActualRoot,[string]$ExpectedRoot,[string]$Branch,[string]$ExpectedBranch,[string]$Head,[string]$ExpectedSha,[string[]]$DirtyPaths=@())
    if([IO.Path]::GetFullPath($ActualRoot).TrimEnd('\') -ne [IO.Path]::GetFullPath($ExpectedRoot).TrimEnd('\')){throw 'CANONICAL_SOURCE_MISMATCH'}
    if($Branch -ne $ExpectedBranch){throw 'BRANCH_MISMATCH'}
    if($ExpectedSha -notmatch '^[a-f0-9]{40}$' -or $Head -ne $ExpectedSha){throw 'SOURCE_SHA_MISMATCH'}
    if($DirtyPaths.Count){throw 'SOURCE_NOT_CLEAN'}
}
function Assert-ContractProcessRecord {
    param($Entry,$Observed,[string]$ExpectedRoot)
    if($null -eq $Observed){throw 'LEDGER_PROCESS_NOT_RUNNING'}
    if($Entry.service -notin @('Backend','FastAPI','Frontend')){throw 'UNOWNED_SERVICE_TYPE'}
    if([IO.Path]::GetFullPath($Entry.codeRoot).TrimEnd('\') -ne [IO.Path]::GetFullPath($ExpectedRoot).TrimEnd('\')){throw 'LEDGER_SOURCE_MISMATCH'}
    if([int]$Entry.pid -ne [int]$Observed.ProcessId){throw 'LEDGER_PID_MISMATCH'}
    if($null -eq $Observed.CreationDate -or $Observed.CreationDate.ToUniversalTime().ToString('o') -ne $Entry.creationUtc){throw 'LEDGER_PID_REUSED'}
    if([string]::IsNullOrWhiteSpace($Observed.ExecutablePath) -or [IO.Path]::GetFullPath($Observed.ExecutablePath) -ne [IO.Path]::GetFullPath($Entry.executable)){throw 'LEDGER_EXECUTABLE_MISMATCH'}
    if([string]::IsNullOrWhiteSpace($Observed.CommandLine) -or (Get-ContractTextHash $Observed.CommandLine) -ne $Entry.commandHash){throw 'LEDGER_COMMAND_MISMATCH'}
    if([string]::IsNullOrWhiteSpace($Entry.sourceMarker) -or -not $Entry.sourceMarker.StartsWith($ExpectedRoot+'\',[StringComparison]::OrdinalIgnoreCase) -or $Observed.CommandLine.IndexOf($Entry.sourceMarker,[StringComparison]::OrdinalIgnoreCase) -lt 0){throw 'LEDGER_SOURCE_PATH_NOT_PROVEN'}
}
function Get-ContractTextHash([string]$Text) {
    $contractDigest=[Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($contractDigest.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-','').ToLowerInvariant() } finally { $contractDigest.Dispose() }
}
function Assert-ContractPortableRedisConfig {
    param([string]$ConfigText,[string]$LinuxRoot,[int]$Port)
    if($ConfigText -notmatch '(?m)^bind\s+127\.0\.0\.1\s*$' -or $ConfigText -notmatch '(?m)^protected-mode\s+yes\s*$' -or $ConfigText -notmatch '(?m)^daemonize\s+yes\s*$' -or $ConfigText -notmatch ('(?m)^port\s+'+$Port+'\s*$')){throw 'PORTABLE_REDIS_NOT_SCOPED_LOOPBACK'}
    foreach($directive in @('bind','protected-mode','daemonize','port','dir','pidfile')){if([regex]::Matches($ConfigText,('(?m)^'+$directive+'\s+')).Count -ne 1){throw 'PORTABLE_REDIS_AMBIGUOUS_SCOPE_DIRECTIVE'}}
    if($ConfigText -match '(?m)^(include|loadmodule|replicaof|slaveof|unixsocket)\s+' -or $ConfigText -match '(?m)^cluster-enabled\s+yes\s*$'){throw 'PORTABLE_REDIS_UNAPPROVED_EXTERNAL_DEPENDENCY'}
    foreach($directive in @('dir','pidfile','logfile')){
        $match=[regex]::Match($ConfigText,('(?m)^'+$directive+'\s+(?:"([^"\r\n]*)"|([^\s"\r\n]+))\s*$'))
        if(-not $match.Success -and $directive -ne 'logfile'){throw 'PORTABLE_REDIS_SCOPE_PATH_MISSING'}
        if($match.Success){$value=if($match.Groups[1].Success){$match.Groups[1].Value}else{$match.Groups[2].Value};if($value -and (-not $value.StartsWith($LinuxRoot+'/.runtime/',[StringComparison]::Ordinal) -or $value.Contains('/../'))){throw 'PORTABLE_REDIS_PATH_OUTSIDE_TASK'}}
    }
}
function Stop-ContractPinnedProcess {
    param($Entry,[scriptblock]$Verify)
    $contractProcess=[Diagnostics.Process]::GetProcessById([int]$Entry.pid)
    try {
        # Opening the long-lived handle pins the kernel process object across PID reuse.
        $null=$contractProcess.Handle
        $expectedTime=([datetime]$Entry.creationUtc).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.ffffffZ')
        if($contractProcess.StartTime.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.ffffffZ') -ne $expectedTime){throw 'STOP_HANDLE_CREATION_MISMATCH'}
        & $Verify $Entry
        $contractProcess.Kill()
        if(-not $contractProcess.WaitForExit(5000)){throw 'OWNED_PROCESS_TERMINATION_NOT_CONFIRMED'}
    } finally {$contractProcess.Dispose()}
}
function Assert-ContractWslDistribution([string]$Distribution) {
    if($Distribution -ne 'Ubuntu-22.04'){throw 'UNAPPROVED_REDIS_WSL_DISTRIBUTION'}
}
function Assert-ContractRunningSha($Entry,[string]$ExpectedSha){
    if($ExpectedSha -notmatch '^[a-f0-9]{40}$' -or -not $Entry.PSObject.Properties['sourceSha'] -or $Entry.sourceSha -ne $ExpectedSha){throw 'RUNNING_SOURCE_SHA_MISMATCH'}
}
function Invoke-ContractNative {
    param([string]$Executable,[string[]]$Arguments,[string]$FailureCode)
    $null=Get-Command $Executable -ErrorAction Stop
    $contractPreviousPreference=$ErrorActionPreference
    try {
        $ErrorActionPreference='Continue'
        & $Executable @Arguments
        $contractNativeExit=$LASTEXITCODE
    } finally {$ErrorActionPreference=$contractPreviousPreference}
    if($contractNativeExit -ne 0){throw $FailureCode}
}
function Invoke-ContractIsolatedBuildEnvironment([scriptblock]$Operation) {
    # Verification must use its test seams, not live credentials/worker/model switches.
    $contractBuildEnvironment=@{}
    foreach($name in [Environment]::GetEnvironmentVariables('Process').Keys) {
        if(([string]$name).StartsWith('AUDIT_',[StringComparison]::OrdinalIgnoreCase)) {
            $contractBuildEnvironment[[string]$name]=[Environment]::GetEnvironmentVariable([string]$name,'Process')
        }
    }
    try {
        foreach($name in $contractBuildEnvironment.Keys){[Environment]::SetEnvironmentVariable($name,$null,'Process')}
        & $Operation
    } finally {
        foreach($name in [Environment]::GetEnvironmentVariables('Process').Keys) {
            if(([string]$name).StartsWith('AUDIT_',[StringComparison]::OrdinalIgnoreCase)) {
                [Environment]::SetEnvironmentVariable([string]$name,$null,'Process')
            }
        }
        foreach($name in $contractBuildEnvironment.Keys){[Environment]::SetEnvironmentVariable($name,$contractBuildEnvironment[$name],'Process')}
    }
}
function Resolve-ContractOcrReadTimeout([string]$Configured) {
    if([string]::IsNullOrWhiteSpace($Configured)){return 300000}
    if($Configured -notmatch '^[0-9]{1,6}$' -or [int]$Configured -le 0 -or [int]$Configured -gt 300000){throw 'OCR_READ_BUDGET_OUTSIDE_APPROVED_BOUND'}
    # Windows CPU1/MKLDNN-off 8-page OCR measured 828536ms, not a fast mock.
    # Single-page requests receive measured headroom; the document has its own hard deadline.
    return 300000
}
function Resolve-ContractSemanticCheckpointFlag([string]$Configured) {
    # This launcher is the new candidate entry point; the application itself
    # still defaults to false for legacy/local use outside this launcher.
    if([string]::IsNullOrWhiteSpace($Configured)){return $true}
    $value=$Configured.Trim().ToLowerInvariant()
    if($value -notin @('true','false')){throw 'SEMANTIC_CHECKPOINT_FLAG_NOT_BOOLEAN'}
    if($value -ne 'true'){throw 'NEW_CANDIDATE_REQUIRES_SEMANTIC_CHECKPOINTS'}
    return $true
}
function Assert-ContractJavaLaunchTransparent([string]$CommandLine,[string]$Failure) {
    # JDK 17 expands @argfiles before selecting the main class/JAR. Module
    # launchers also cannot be attributed to a JAR from this process record.
    # Never open an unowned argfile: absent ownership proof, refuse the action.
    $arguments=@([regex]::Matches($CommandLine,'"[^"]*"|\S+') | ForEach-Object {$_.Value.Trim('"')})
    foreach($argument in $arguments){
        if($argument.StartsWith('@') -or $argument -match '^(?:-m|-p|--module|--module-path|--upgrade-module-path|--patch-module)(?:=|$)'){
            throw $Failure
        }
    }
}
function Resolve-ContractSemanticMoneyConfiguration([string]$Mode,[string]$ProjectIds,[string]$WindowPath,[string]$RuntimeRoot) {
    if($Mode -cnotin @('STRICT','OBSERVE')){throw 'SEMANTIC_MONEY_MODE_INVALID'}
    $projects=@($ProjectIds -split ',' | Where-Object {$_} | ForEach-Object {$_.Trim()})
    if($Mode -ceq 'STRICT'){
        if($projects.Count -or -not [string]::IsNullOrWhiteSpace($WindowPath)){throw 'STRICT_MONEY_MODE_CONFLICT'}
        return [pscustomobject]@{mode='STRICT';projects=@();window=''}
    }
    if(-not $projects.Count -or ($projects|Sort-Object -Unique|Measure-Object).Count -ne $projects.Count -or
       @($projects|Where-Object {$_ -cnotmatch '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'}).Count -or
       [string]::IsNullOrWhiteSpace($WindowPath)){throw 'OBSERVE_EXPLICIT_PROJECTS_AND_WINDOW_REQUIRED'}
    $runtime=[IO.Path]::GetFullPath($RuntimeRoot).TrimEnd('\')
    $window=[IO.Path]::GetFullPath($WindowPath)
    if(-not $window.StartsWith($runtime+'\',[StringComparison]::OrdinalIgnoreCase) -or [IO.Path]::GetExtension($window) -cne '.json'){throw 'OBSERVE_WINDOW_OUTSIDE_RUNTIME'}
    return [pscustomobject]@{mode='OBSERVE';projects=$projects;window=$window}
}

function Assert-ContractCanonicalJarStopped([string]$Jar,[array]$Processes) {
    foreach($process in $Processes){
        if($process.Name -notmatch '^(java|javaw)(\.exe)?$'){continue}
        if([string]::IsNullOrWhiteSpace($process.CommandLine)){throw 'JAVA_BUILD_PROCESS_SCOPE_UNKNOWN'}
        Assert-ContractJavaLaunchTransparent $process.CommandLine 'JAVA_BUILD_PROCESS_SCOPE_UNKNOWN'
        $arguments=@([regex]::Matches($process.CommandLine,'"[^"]*"|\S+') | ForEach-Object {$_.Value.Trim('"')})
        $jarFlags=@(for($index=0;$index -lt $arguments.Count;$index++){if($arguments[$index] -ceq '-jar'){$index}})
        if(-not $jarFlags.Count){continue}
        if($jarFlags.Count -ne 1 -or $jarFlags[0]+1 -ge $arguments.Count){throw 'JAVA_BUILD_JAR_ARGUMENT_UNKNOWN'}
        $source=$arguments[$jarFlags[0]+1]
        if($source -notmatch '^(?:[A-Za-z]:[\\/]|\\\\[^\\/]+[\\/][^\\/]+[\\/])'){throw 'JAVA_BUILD_JAR_PATH_UNKNOWN'}
        if([IO.Path]::GetFullPath($source) -eq [IO.Path]::GetFullPath($Jar)){throw 'RUNNING_JAR_CANONICAL_BUILD_FORBIDDEN'}
    }
}
