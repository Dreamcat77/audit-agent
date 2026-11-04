[CmdletBinding()]
param(
    [ValidateSet('DryRun','Apply','Verify')][string]$Mode='Verify',
    [string]$BackupDirectory,
    [string]$PrivateConfigPath,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-f0-9]{40}$')][string]$ExpectedSourceSha,
    [switch]$WritersStopped,
    [switch]$ApplyApproved,
    [switch]$WritersProofOnly,
    [switch]$CatalogReaderRevisionApproved
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$semanticRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$word97Root=$semanticRoot
. (Join-Path $PSScriptRoot 'contract-test-guards.ps1')
# Reuse reviewed filesystem/ACL and ledger guards, without executing Word97
# Snapshot/Apply or importing that script's executable entry point.
$semanticTokens=$null;$semanticErrors=$null
$semanticTree=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'maintain-word97-local.ps1'),[ref]$semanticTokens,[ref]$semanticErrors)
if($semanticErrors.Count){throw 'SEMANTIC_REUSED_GUARD_PARSE_FAILED'}
foreach($name in @('Assert-Word97StoppedState','Assert-Word97NoReparse','Set-Word97RestrictedDirectory','Assert-Word97RestrictedDirectory')){
    $definition=$semanticTree.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
    if($definition.Count -ne 1){throw 'SEMANTIC_REUSED_GUARD_MISSING'}
    Invoke-Expression $definition[0].Extent.Text
}
function ConvertTo-SemanticJdtDrivePath([string]$Path){
    # The drive designator is a Windows alias, independently of directory case
    # sensitivity. Do not case-fold any directory, file name, or UNC component.
    if($Path -cmatch '^[A-Za-z]:\\'){return $Path.Substring(0,1).ToUpperInvariant()+$Path.Substring(1)}
    return $Path
}
function Get-SemanticJdtProofKey($Proof){
    $names=@('format','processId','creationUtc','executable','executableSha256','commandHash','launcherJar','launcherJarSha256','extensionRoot','parentPid','parentCreationUtc','parentExecutable','parentExecutableSha256','parentCommandHash','parentSignerThumbprint','parentSignerPublisher','tcpConnections')
    if($null -eq $Proof -or @($Proof.PSObject.Properties.Name).Count -ne $names.Count -or @($Proof.PSObject.Properties.Name|Where-Object {$_ -notin $names}).Count){throw 'SEMANTIC_JDT_PROOF_SCHEMA_UNKNOWN'}
    if($Proof.format -cne 'SEMANTIC_JDT_NONWRITER_V1' -or $Proof.tcpConnections -ne 0 -or $Proof.parentSignerPublisher -cne 'Microsoft Corporation'){throw 'SEMANTIC_JDT_PROOF_UNKNOWN'}
    foreach($field in @('executableSha256','commandHash','launcherJarSha256','parentExecutableSha256','parentCommandHash')){if($Proof.$field -cnotmatch '^[a-f0-9]{64}$'){throw 'SEMANTIC_JDT_PROOF_HASH_UNKNOWN'}}
    if($Proof.parentSignerThumbprint -cnotmatch '^[a-f0-9]{40}$'){throw 'SEMANTIC_JDT_SIGNER_UNKNOWN'}
    $canonical=[ordered]@{};foreach($name in ($names|Sort-Object)){$canonical[$name]=$Proof.$name}
    Get-ContractTextHash ($canonical|ConvertTo-Json -Depth 3 -Compress)
}
function Assert-SemanticJdtProcessIdentity($Process,$Proof){
    $null=Get-SemanticJdtProofKey $Proof
    if($Process.Name -cne 'java.exe' -or [long]$Process.ProcessId -ne $Proof.processId -or [long]$Process.ParentProcessId -ne $Proof.parentPid -or
       $Process.CreationDate -isnot [datetime] -or $Process.CreationDate.ToUniversalTime().ToString('o') -cne $Proof.creationUtc -or
       (ConvertTo-SemanticJdtDrivePath $Process.ExecutablePath) -cne $Proof.executable -or (Get-ContractTextHash $Process.CommandLine) -cne $Proof.commandHash){throw 'SEMANTIC_JDT_PROCESS_IDENTITY_CHANGED'}
}
function New-SemanticJdtNonwriterProof($Process,$Parent,[array]$Connections,[string]$ExtensionsRoot,$Files,$Signature){
    # Only the single Root-identified JDT tool. This is not a user allowlist or
    # evidence that arbitrary Java tools cannot write; JDBC ownership still gates.
    if($Process.Name -cne 'java.exe' -or $Process.CreationDate -isnot [datetime] -or $Parent.CreationDate -isnot [datetime] -or
       [long]$Process.ProcessId -le 0 -or [long]$Process.ParentProcessId -ne [long]$Parent.ProcessId -or $Parent.Name -cne 'Code.exe' -or
       $Parent.CreationDate.ToUniversalTime() -gt $Process.CreationDate.ToUniversalTime() -or
       [IO.Path]::GetFileName($Parent.ExecutablePath) -cne 'Code.exe'){throw 'SEMANTIC_JDT_PARENT_OR_CREATION_UNKNOWN'}
    Assert-ContractJavaLaunchTransparent $Process.CommandLine 'SEMANTIC_JDT_LAUNCH_UNKNOWN'
    $arguments=@([regex]::Matches($Process.CommandLine,'"[^"]*"|\S+')|ForEach-Object {$_.Value.Trim('"')})
    # Windows may preserve an absolute argv0 without '.exe' in CIM CommandLine.
    # Only that suffix alias of the exact observed executable is admissible;
    # all file identity and hash reads still use CIM's actual .exe path.
    $executableCompared=ConvertTo-SemanticJdtDrivePath $Process.ExecutablePath
    $argvCompared=if($arguments.Count -gt 0){ConvertTo-SemanticJdtDrivePath $arguments[0]}else{''}
    $sameExecutable=$arguments.Count -gt 0 -and $argvCompared -ceq $executableCompared
    if(-not $sameExecutable -and $arguments.Count -gt 0 -and [IO.Path]::IsPathRooted($arguments[0]) -and
       [IO.Path]::GetFullPath($arguments[0]) -ceq $arguments[0] -and [IO.Path]::GetFileName($arguments[0]) -ceq 'java'){
        $sameExecutable=($argvCompared+'.exe').Equals($executableCompared,[StringComparison]::Ordinal)
    }
    if($arguments.Count -lt 5 -or -not $sameExecutable -or
       @($arguments|Where-Object {$_ -ceq '-Declipse.application=org.eclipse.jdt.ls.core.id1'}).Count -ne 1 -or
       @($arguments|Where-Object {$_ -clike '-Declipse.application=*'}).Count -ne 1 -or
       @($arguments|Where-Object {$_ -ceq '-Declipse.product=org.eclipse.jdt.ls.core.product'}).Count -ne 1 -or
       @($arguments|Where-Object {$_ -clike '-Declipse.product=*'}).Count -ne 1 -or
       $Process.CommandLine -match 'com\.fuquan\.audit|spring-boot:run'){throw 'SEMANTIC_JDT_EXACT_MARKERS_UNKNOWN'}
    $flags=@(for($n=0;$n -lt $arguments.Count;$n++){if($arguments[$n] -ceq '-jar'){$n}})
    if($flags.Count -ne 1 -or $flags[0]+1 -ge $arguments.Count){throw 'SEMANTIC_JDT_LAUNCHER_UNKNOWN'}
    $jarObserved=$arguments[$flags[0]+1];$jar=ConvertTo-SemanticJdtDrivePath $jarObserved
    $base=ConvertTo-SemanticJdtDrivePath ([IO.Path]::GetFullPath($ExtensionsRoot).TrimEnd('\'))
    $pattern='^('+[regex]::Escape($base)+'\\redhat\.java-[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.]+)*)\\server\\plugins\\org\.eclipse\.equinox\.launcher_[A-Za-z0-9._-]+\.jar$'
    $match=[regex]::Match($jar,$pattern)
    if(-not $match.Success -or [IO.Path]::GetFullPath($jarObserved) -cne $jarObserved){throw 'SEMANTIC_JDT_EXTENSION_LAYOUT_UNKNOWN'}
    $extension=$match.Groups[1].Value
    if(-not $executableCompared.StartsWith(($extension+'\jre\'),[StringComparison]::Ordinal) -or
       [IO.Path]::GetFileName($Process.ExecutablePath) -cne 'java.exe' -or [IO.Path]::GetFullPath($Process.ExecutablePath) -cne $Process.ExecutablePath -or
       -not $Files.pathsNoReparse){throw 'SEMANTIC_JDT_JRE_OR_REPARSE_UNKNOWN'}
    if(@($Connections|Where-Object {[long]$_.OwningProcess -eq [long]$Process.ProcessId}).Count){throw 'SEMANTIC_JDT_TCP_ACTIVE_OR_UNKNOWN'}
    if($Signature.Status -cne 'Valid' -or $Signature.Publisher -cne 'Microsoft Corporation' -or
       (ConvertTo-SemanticJdtDrivePath $Signature.Path) -cne (ConvertTo-SemanticJdtDrivePath $Parent.ExecutablePath) -or
       $Signature.Thumbprint -cnotmatch '^[a-f0-9]{40}$'){throw 'SEMANTIC_JDT_PARENT_SIGNATURE_UNKNOWN'}
    $proof=[pscustomobject][ordered]@{format='SEMANTIC_JDT_NONWRITER_V1';processId=[long]$Process.ProcessId;creationUtc=$Process.CreationDate.ToUniversalTime().ToString('o');
        executable=$executableCompared;executableSha256=$Files.executableSha256;commandHash=(Get-ContractTextHash $Process.CommandLine);launcherJar=$jar;launcherJarSha256=$Files.launcherJarSha256;extensionRoot=$extension;
        parentPid=[long]$Parent.ProcessId;parentCreationUtc=$Parent.CreationDate.ToUniversalTime().ToString('o');parentExecutable=(ConvertTo-SemanticJdtDrivePath $Parent.ExecutablePath);
        parentExecutableSha256=$Files.parentExecutableSha256;parentCommandHash=(Get-ContractTextHash $Parent.CommandLine);parentSignerThumbprint=$Signature.Thumbprint;
        parentSignerPublisher=$Signature.Publisher;tcpConnections=0}
    $null=Get-SemanticJdtProofKey $proof;return $proof
}
function Assert-SemanticJdtRegularFile([string]$Path){
    if(-not [IO.Path]::IsPathRooted($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)){throw 'SEMANTIC_JDT_FILE_UNKNOWN'}
    $scan=[IO.Path]::GetFullPath($Path)
    while($scan){$item=Get-Item -LiteralPath $scan -Force -ErrorAction Stop;if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'SEMANTIC_JDT_FILE_REPARSE'};$scan=Split-Path $scan}
}
function Get-SemanticJdtNonwriterProof($Process,[array]$Processes,[array]$Connections){
    $parents=@($Processes|Where-Object {[long]$_.ProcessId -eq [long]$Process.ParentProcessId});if($parents.Count -ne 1){throw 'SEMANTIC_JDT_PARENT_UNKNOWN'}
    $parent=$parents[0]
    # Parse only to locate the fixed launcher. No argfile or command execution.
    Assert-ContractJavaLaunchTransparent $Process.CommandLine 'SEMANTIC_JDT_LAUNCH_UNKNOWN'
    $argsObserved=@([regex]::Matches($Process.CommandLine,'"[^"]*"|\S+')|ForEach-Object {$_.Value.Trim('"')})
    $jars=@(for($n=0;$n -lt $argsObserved.Count-1;$n++){if($argsObserved[$n] -ceq '-jar'){$argsObserved[$n+1]}})
    if($jars.Count -ne 1){throw 'SEMANTIC_JDT_LAUNCHER_UNKNOWN'}
    $paths=@($Process.ExecutablePath,$jars[0],$parent.ExecutablePath)
    foreach($file in $paths){Assert-SemanticJdtRegularFile $file}
    $hashes=@($paths|ForEach-Object {(Get-FileHash -LiteralPath $_ -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()})
    $sig=Get-AuthenticodeSignature -LiteralPath $parent.ExecutablePath -ErrorAction Stop
    if($null -eq $sig.SignerCertificate){throw 'SEMANTIC_JDT_PARENT_SIGNATURE_UNKNOWN'}
    $signature=[pscustomobject]@{Status=[string]$sig.Status;Path=$sig.Path;Publisher=$sig.SignerCertificate.GetNameInfo([Security.Cryptography.X509Certificates.X509NameType]::SimpleName,$false);Thumbprint=$sig.SignerCertificate.Thumbprint.ToLowerInvariant()}
    $files=[pscustomobject]@{executableSha256=$hashes[0];launcherJarSha256=$hashes[1];parentExecutableSha256=$hashes[2];pathsNoReparse=$true}
    $proof=New-SemanticJdtNonwriterProof $Process $parent $Connections (Join-Path $env:USERPROFILE '.vscode\extensions') $files $signature
    # Reject PID reuse/file replacement while collecting the proof.
    $fresh=Get-CimInstance Win32_Process -Filter ('ProcessId='+$proof.processId) -ErrorAction Stop
    $freshParent=Get-CimInstance Win32_Process -Filter ('ProcessId='+$proof.parentPid) -ErrorAction Stop
    $freshTcp=@(Get-NetTCPConnection -ErrorAction Stop)
    foreach($file in $paths){Assert-SemanticJdtRegularFile $file}
    $next=@($paths|ForEach-Object {(Get-FileHash -LiteralPath $_ -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()})
    if(($hashes -join ':') -cne ($next -join ':')){throw 'SEMANTIC_JDT_FILE_CHANGED_DURING_PROOF'}
    $freshProof=New-SemanticJdtNonwriterProof $fresh $freshParent $freshTcp (Join-Path $env:USERPROFILE '.vscode\extensions') $files $signature
    if((Get-SemanticJdtProofKey $proof) -cne (Get-SemanticJdtProofKey $freshProof)){throw 'SEMANTIC_JDT_CHANGED_DURING_PROOF'}
    return $proof
}
function Get-SemanticApprovedJdtProof {
    $path=Join-Path $semanticRoot '.runtime\contract-repair-test\semantic-maintenance\jdt-nonwriter-proof.json'
    $script:semanticJdtProofDigest='NONE';$proof=$null
    if(Test-Path -LiteralPath $path){
        Assert-Word97NoReparse $path;Assert-Word97RestrictedDirectory (Split-Path $path)
        if((Get-Item -LiteralPath $path).Length -gt 16384){throw 'SEMANTIC_JDT_APPROVAL_BOUND_EXCEEDED'}
        $firstHash=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        $proof=Get-Content -LiteralPath $path -Raw -Encoding UTF8|ConvertFrom-Json
        $null=Get-SemanticJdtProofKey $proof
        $script:semanticJdtProofDigest=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        if($firstHash -cne $script:semanticJdtProofDigest){throw 'SEMANTIC_JDT_APPROVAL_CHANGED_DURING_READ'}
    }
    if($BackupDirectory -and (Test-Path -LiteralPath (Join-Path $BackupDirectory 'writers-stopped.properties'))){
        $p=Get-Content -LiteralPath (Join-Path $BackupDirectory 'writers-stopped.properties') -Raw -Encoding UTF8|ConvertFrom-StringData
        if(-not $p.ContainsKey('jdt.proof.sha256') -or $p['jdt.proof.sha256'] -cne $script:semanticJdtProofDigest){throw 'SEMANTIC_JDT_APPROVAL_CHANGED_AFTER_STOP_PROOF'}
    }
    return $proof
}
function Assert-SemanticStoppedState([string]$Root,[array]$Processes,[array]$Listeners,[array]$Entries,[array]$Nonwriters=@()){
    # Reject another audit-capable Java backend even outside this checkout;
    # connection ownership is checked independently by the JDBC helper.
    foreach($process in $Processes){
        if($process.Name -notmatch '^(java|javaw)(\.exe)?$'){continue}
        if([string]::IsNullOrWhiteSpace($process.CommandLine)){throw 'SEMANTIC_OTHER_BACKEND_SCOPE_UNKNOWN'}
        Assert-ContractJavaLaunchTransparent $process.CommandLine 'SEMANTIC_OTHER_BACKEND_SCOPE_UNKNOWN'
        $matching=@($Nonwriters|Where-Object {[long]$_.processId -eq [long]$process.ProcessId})
        if($matching.Count){
            if($matching.Count -ne 1 -or @($Entries|Where-Object {[long]$_.pid -eq [long]$process.ProcessId}).Count){throw 'SEMANTIC_JDT_NONWRITER_SCOPE_UNKNOWN'}
            Assert-SemanticJdtProcessIdentity $process $matching[0];continue
        }
        if($process.CommandLine -match '(?i)(?:^|\s)-jar(?:\s|$)|(?:^|\s)com\.fuquan\.audit(?:\.|\s|$)|spring-boot:run'){
            throw 'SEMANTIC_OTHER_OR_UNKNOWN_BACKEND_ACTIVE'
        }
    }
    $remaining=@($Processes|Where-Object {$candidate=$_;-not @($Nonwriters|Where-Object {[long]$_.processId -eq [long]$candidate.ProcessId}).Count})
    Assert-Word97StoppedState $Root $remaining $Listeners $Entries
}
function Assert-SemanticWriters {
    $entries=@();$runtime=Join-Path $semanticRoot '.runtime\contract-repair-test'
    foreach($ledger in @(Get-ChildItem -LiteralPath $runtime -Filter 'pids*.json' -File)){
        Assert-Word97NoReparse $ledger.FullName
        $record=Get-Content -LiteralPath $ledger.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        if($record.format -ne 1 -or $record.codeRoot -ne $semanticRoot){throw 'SEMANTIC_LEDGER_SCOPE_UNKNOWN'}
        $entries+=@($record.entries)
    }
    $processes=@(Get-CimInstance Win32_Process -ErrorAction Stop);$connections=@(Get-NetTCPConnection -ErrorAction Stop)
    $approved=Get-SemanticApprovedJdtProof;$nonwriters=@()
    if($null -ne $approved){
        $observed=@($processes|Where-Object {[long]$_.ProcessId -eq [long]$approved.processId})
        if($observed.Count -ne 1){throw 'SEMANTIC_APPROVED_JDT_PROCESS_UNKNOWN'}
        $fresh=Get-SemanticJdtNonwriterProof $observed[0] $processes $connections
        if((Get-SemanticJdtProofKey $fresh) -cne (Get-SemanticJdtProofKey $approved)){throw 'SEMANTIC_JDT_APPROVED_IDENTITY_CHANGED'}
        $nonwriters=@($fresh)
    }
    Assert-SemanticStoppedState $semanticRoot $processes @($connections|Where-Object {$_.State -eq 'Listen'}) $entries $nonwriters
}
function Get-SemanticSourceDigest {
    $names=@('tools/local-test/ResetReviewTestData.java','tools/local-test/ContractTestStorageProbe.java','tools/local-test/ContractLocalMigration.java','tools/local-test/Word97LocalMigration.java','tools/local-test/Word97Maintenance.java','tools/local-test/maintain-word97-local.ps1','tools/local-test/SemanticCheckpointCatalog.java','tools/local-test/SemanticCheckpointMaintenance.java','tools/local-test/maintain-semantic-checkpoints-local.ps1','tools/local-test/contract-test-guards.ps1')
    $text='';foreach($name in $names){$text+=$name+'|'+(Get-FileHash -LiteralPath (Join-Path $semanticRoot $name) -Algorithm SHA256).Hash.ToLowerInvariant()+"`n"}
    $text+=(Get-FileHash -LiteralPath (Join-Path $semanticRoot 'db/dm8/CONTRACT__contract_semantic_checkpoints.sql') -Algorithm SHA256).Hash.ToLowerInvariant()
    Get-ContractTextHash $text
}
function Assert-SemanticReaderSource {
    $stopPath=Join-Path $BackupDirectory 'writers-stopped.properties'
    if(-not(Test-Path -LiteralPath $stopPath)){return}
    $stop=Get-Content -LiteralPath $stopPath -Raw -Encoding UTF8|ConvertFrom-StringData
    $current=Get-SemanticSourceDigest
    $revisionPath=Join-Path $BackupDirectory 'catalog-reader-revision.properties'
    if($stop['source.sha256'] -ceq $current -and $stop['head'] -ceq $ExpectedSourceSha){
        if(Test-Path -LiteralPath $revisionPath){throw 'SEMANTIC_READER_DOWNGRADE_FORBIDDEN'};return
    }
    $oldHead='f69be8eae55d1b54185de8805d92478e1d1c64d6'
    $oldSource='4dea4c0f977acd9ad08a74ccc892ab622a31fb5a033e111d616d015bfc211ba3'
    $ddl='c377f0da3de48e9f57439081f9fade7126b7e7776e54026e4e8c842208d363dd'
    if($stop['format'] -cne 'SEMANTIC_WRITERS_STOPPED_V1' -or $stop['source.sha256'] -cne $oldSource -or $stop['head'] -cne $oldHead -or
       $ExpectedSourceSha -ceq $oldHead -or (Get-FileHash -LiteralPath (Join-Path $semanticRoot 'db/dm8/CONTRACT__contract_semantic_checkpoints.sql') -Algorithm SHA256).Hash.ToLowerInvariant() -cne $ddl){throw 'SEMANTIC_READER_FIXED_PREDECESSOR_REQUIRED'}
    $scope=@('tools/local-test/ResetReviewTestData.java','tools/local-test/ContractTestStorageProbe.java','tools/local-test/ContractLocalMigration.java','tools/local-test/Word97LocalMigration.java','tools/local-test/Word97Maintenance.java','tools/local-test/maintain-word97-local.ps1','tools/local-test/SemanticCheckpointCatalog.java','tools/local-test/SemanticCheckpointMaintenance.java','tools/local-test/maintain-semantic-checkpoints-local.ps1','tools/local-test/contract-test-guards.ps1')
    $allowed=@('tools/local-test/SemanticCheckpointCatalog.java','tools/local-test/SemanticCheckpointMaintenance.java','tools/local-test/maintain-semantic-checkpoints-local.ps1')
    $changed=@(& git.exe -C $semanticRoot diff --name-only $oldHead $ExpectedSourceSha -- $scope)
    if($LASTEXITCODE -ne 0 -or -not $changed.Count -or @($changed|Where-Object {$_ -cnotin $allowed}).Count){throw 'SEMANTIC_READER_CHANGE_SCOPE_EXCEEDED'}
    if(-not(Test-Path -LiteralPath $revisionPath)){
        if(-not $CatalogReaderRevisionApproved -or $Mode -cne 'Verify'){throw 'SEMANTIC_READER_EXPLICIT_APPROVAL_REQUIRED'}
        return # Zero-DDL inspection only; Java must prove prefix1, unchanged rows, and original INTENT before issuing.
    }
    Assert-Word97NoReparse $revisionPath
    # Java Properties escaping is decoded with the same Java reader below. This
    # wrapper only gates hashes/identities without pretending PS has that grammar.
    $revision=Get-Content -LiteralPath $revisionPath -Raw -Encoding UTF8|ConvertFrom-StringData
    $fields=@('format','reason','backup.path','snapshot.sha256','writers.sha256','original.source','original.head','reader.source','reader.head','ddl','journal.prefix.bytes','journal.prefix.sha256','journal.sequence','journal.tip')
    if($revision.Count -ne $fields.Count -or @($revision.Keys|Where-Object {$_ -cnotin $fields}).Count -or
       $revision['format'] -cne 'SEMANTIC_CATALOG_READER_REVISION_V1' -or $revision['reason'] -cne 'DM8_CATALOG_DISPLAY_R1' -or
       $revision['backup.path'] -cne $BackupDirectory -or
       $revision['reader.source'] -cne $current -or $revision['reader.head'] -cne $ExpectedSourceSha -or $revision['original.head'] -cne $oldHead -or
       $revision['original.source'] -cne $oldSource -or $revision['ddl'] -cne $ddl -or
       $revision['snapshot.sha256'] -cne (Get-FileHash -LiteralPath (Join-Path $BackupDirectory 'snapshot.properties') -Algorithm SHA256).Hash.ToLowerInvariant() -or
       $revision['writers.sha256'] -cne (Get-FileHash -LiteralPath $stopPath -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'SEMANTIC_READER_REVISION_BINDING_DRIFT'}
    if($revision['journal.prefix.bytes'] -cnotmatch '^[1-9][0-9]{0,4}$' -or [int]$revision['journal.prefix.bytes'] -gt 16384 -or
       $revision['journal.sequence'] -cne '1' -or $revision['journal.tip'] -cnotmatch '^[a-f0-9]{64}$'){throw 'SEMANTIC_READER_JOURNAL_PREFIX_BOUND'}
    $journalPath=Join-Path $BackupDirectory 'journal.log';Assert-Word97NoReparse $journalPath
    $prefixBytes=New-Object byte[] ([int]$revision['journal.prefix.bytes'])
    $journalStream=[IO.File]::OpenRead($journalPath)
    try{$read=0;while($read -lt $prefixBytes.Length){$count=$journalStream.Read($prefixBytes,$read,$prefixBytes.Length-$read);if($count -le 0){throw 'SEMANTIC_READER_JOURNAL_PREFIX_DRIFT'};$read+=$count}}finally{$journalStream.Dispose()}
    $journalPrefix=[Text.Encoding]::UTF8.GetString($prefixBytes)
    $journalLines=$journalPrefix -split "`n"
    if((Get-ContractTextHash $journalPrefix) -cne $revision['journal.prefix.sha256'] -or $journalLines.Count -ne 3 -or $journalLines[2] -cne '' -or
       ($journalLines[1] -split '\|').Count -ne 8 -or ($journalLines[1] -split '\|')[7] -cne $revision['journal.tip']){throw 'SEMANTIC_READER_JOURNAL_PREFIX_DRIFT'}
}
$semanticSaved=@{}
try {
    $branch=& git.exe -C $semanticRoot branch --show-current;if($LASTEXITCODE -ne 0){throw 'SEMANTIC_GIT_BRANCH_UNAVAILABLE'}
    $head=& git.exe -C $semanticRoot rev-parse HEAD;if($LASTEXITCODE -ne 0){throw 'SEMANTIC_GIT_HEAD_UNAVAILABLE'}
    $dirty=@(& git.exe -C $semanticRoot status --porcelain --untracked-files=all);if($LASTEXITCODE -ne 0){throw 'SEMANTIC_GIT_STATUS_UNAVAILABLE'}
    Assert-ContractSourceIdentity $semanticRoot 'D:\code\audit_agent_1D\.worktrees\integration-pre-main-20261010' $branch 'integration/pre-main-20261010' $head $ExpectedSourceSha $dirty
    if($Mode -eq 'Apply' -and (-not $ApplyApproved -or -not $WritersStopped)){throw 'SEMANTIC_ROOT_APPROVED_APPLY_AND_STOP_PROOF_REQUIRED'}
    $area=Join-Path $semanticRoot '.runtime\contract-repair-test\semantic-maintenance'
    if(-not $BackupDirectory){if($Mode -ne 'DryRun' -or $WritersProofOnly){throw 'SEMANTIC_SAME_BACKUP_REQUIRED'};$BackupDirectory=Join-Path $area ([datetime]::UtcNow.ToString('yyyyMMdd-HHmmssfff'))}
    $BackupDirectory=[IO.Path]::GetFullPath($BackupDirectory).TrimEnd('\')
    if((Split-Path $BackupDirectory) -ne $area -or (Split-Path $BackupDirectory -Leaf) -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,80}$'){throw 'SEMANTIC_BACKUP_OUTSIDE_CONTROLLED_RUNTIME'}
    Assert-Word97NoReparse $BackupDirectory
    & git.exe -C $semanticRoot check-ignore --quiet -- $BackupDirectory
    if($LASTEXITCODE -ne 0){throw 'SEMANTIC_BACKUP_NOT_IGNORED'}
    Assert-SemanticReaderSource
    Assert-SemanticWriters
    if($WritersProofOnly){Assert-Word97RestrictedDirectory $BackupDirectory;Write-Output 'SEMANTIC_WRITERS_STOPPED=PROVEN';exit 0}
    if($Mode -eq 'DryRun'){
        if(-not $WritersStopped -or (Test-Path -LiteralPath $BackupDirectory)){throw 'SEMANTIC_STOPPED_WRITERS_AND_NEW_BACKUP_REQUIRED'}
        [IO.Directory]::CreateDirectory($area)|Out-Null;Assert-Word97NoReparse $area
        [IO.Directory]::CreateDirectory($BackupDirectory)|Out-Null;Set-Word97RestrictedDirectory $BackupDirectory
        $proof="format=SEMANTIC_WRITERS_STOPPED_V1`nsource.sha256=$(Get-SemanticSourceDigest)`nhead=$ExpectedSourceSha`njdt.proof.sha256=$script:semanticJdtProofDigest`ncreated=$([datetime]::UtcNow.ToString('o'))`n"
        [IO.File]::WriteAllText((Join-Path $BackupDirectory 'writers-stopped.properties'),$proof,[Text.UTF8Encoding]::new($false))
    }
    Assert-Word97RestrictedDirectory $BackupDirectory
    $keys=@('AUDIT_DB_URL','AUDIT_DB_USERNAME','AUDIT_DB_PASSWORD')
    foreach($key in $keys){$semanticSaved[$key]=[Environment]::GetEnvironmentVariable($key,'Process')}
    if(-not $PrivateConfigPath){$PrivateConfigPath=Join-Path (Split-Path $semanticRoot) 'ai-ocr-project-intake-demo\.runtime\local-demo.env.integration.legacy.ps1'}
    $values=Read-ContractLiteralEnvironment $PrivateConfigPath $keys $keys
    $identity=Read-ContractApprovedDatabaseIdentity $semanticRoot
    Assert-ContractDatabaseIdentity $identity $values['AUDIT_DB_URL'] $values['AUDIT_DB_USERNAME'] $identity.schema
    foreach($key in $keys){[Environment]::SetEnvironmentVariable($key,$values[$key],'Process')}
    $classes=Join-Path $area 'offline-classes';[IO.Directory]::CreateDirectory($classes)|Out-Null
    $sources=@('ResetReviewTestData.java','ContractTestStorageProbe.java','ContractLocalMigration.java','Word97LocalMigration.java','Word97Maintenance.java','SemanticCheckpointCatalog.java','SemanticCheckpointMaintenance.java') | ForEach-Object {Join-Path $PSScriptRoot $_}
    Invoke-ContractNative 'javac.exe' (@('-encoding','UTF-8','-d',$classes)+$sources) 'SEMANTIC_MAINTENANCE_COMPILE_FAILED'
    $driver=Join-Path $env:USERPROFILE '.m2\repository\com\dameng\DmJdbcDriver18\8.1.3.140\DmJdbcDriver18-8.1.3.140.jar'
    if(-not(Test-Path -LiteralPath $driver -PathType Leaf)){throw 'SEMANTIC_DM8_DRIVER_MISSING'}
    Assert-SemanticWriters
    Write-Output ('SEMANTIC_BACKUP_DIRECTORY='+$BackupDirectory)
    $arguments=@('-cp',($classes+[IO.Path]::PathSeparator+$driver),'SemanticCheckpointMaintenance',$Mode,$semanticRoot,$BackupDirectory,$ExpectedSourceSha)
    if($CatalogReaderRevisionApproved){$arguments+='CATALOG_READER_REVISION_APPROVED'}
    Invoke-ContractNative 'java.exe' $arguments 'SEMANTIC_MAINTENANCE_REOPEN_SAME_BACKUP_REQUIRED'
} catch {
    $code=$_.Exception.Message;if($code -notmatch '^[A-Z0-9_]{1,100}$'){$code='UNKNOWN_SUPPRESSED_ERROR'}
    Write-Output ('SEMANTIC_MAINTENANCE=BLOCKED OUTCOME=UNKNOWN_UNTIL_SAME_JOURNAL_REOPEN gate='+$code);exit 1
} finally {foreach($key in $semanticSaved.Keys){[Environment]::SetEnvironmentVariable($key,$semanticSaved[$key],'Process')}}
