$ErrorActionPreference='Stop'
$semanticRepo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$semanticRoot=$semanticRepo
. (Join-Path $PSScriptRoot 'contract-test-guards.ps1')
function Import-SemanticTestFunctions([string]$Path){
    $tokens=$null;$errors=$null;$tree=[Management.Automation.Language.Parser]::ParseFile($Path,[ref]$tokens,[ref]$errors)
    if($errors.Count){throw 'OFFLINE_SCRIPT_PARSE_FAILED'}
    foreach($function in $tree.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst]},$true)){Invoke-Expression $function.Extent.Text}
}
# Import function definitions only; never run either maintenance wrapper.
$tokens=$null;$errors=$null;$tree=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'maintain-word97-local.ps1'),[ref]$tokens,[ref]$errors)
$definition=$tree.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-Word97StoppedState'},$true)
Invoke-Expression $definition[0].Extent.Text
$semanticWrapper=Join-Path $PSScriptRoot 'maintain-semantic-checkpoints-local.ps1'
if(Test-Path -LiteralPath $semanticWrapper){
    $tokens=$null;$errors=$null;$tree=[Management.Automation.Language.Parser]::ParseFile($semanticWrapper,[ref]$tokens,[ref]$errors)
    if($errors.Count){throw 'OFFLINE_WRAPPER_PARSE_FAILED'}
    foreach($name in @('Assert-SemanticStoppedState','Get-SemanticSourceDigest')){
        $definition=$tree.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
        if($definition.Count){Invoke-Expression $definition[0].Extent.Text}
    }
}
function Assert-SemanticReject([scriptblock]$Operation){$rejected=$false;try{& $Operation}catch{$rejected=$true};if(-not $rejected){throw 'OFFLINE_EXPECTED_WRITER_REJECTION_ABSENT'}}
$otherBackend=[pscustomobject]@{Name='java.exe';ProcessId=9123;CommandLine='java.exe -jar "D:\other-worktree\backend\target\audit-backend.jar"';CreationDate=[datetime]::UtcNow}
if(Get-Command Assert-SemanticStoppedState -ErrorAction SilentlyContinue){Assert-SemanticReject {Assert-SemanticStoppedState $semanticRepo @($otherBackend) @() @()}}
else {Assert-SemanticReject {Assert-Word97StoppedState $semanticRepo @($otherBackend) @() @()}}
Write-Output 'SEMANTIC_WRITER_OFFLINE=PASS OTHER_WORKTREE_BACKEND_REJECTED NO_PROCESSES_STOPPED'
Assert-SemanticReject {Assert-SemanticStoppedState $semanticRepo @([pscustomobject]@{Name='java.exe';CommandLine=$null}) @() @()}
Assert-SemanticReject {Assert-SemanticStoppedState $semanticRepo @() @([pscustomobject]@{LocalPort=8888}) @()}
Assert-SemanticStoppedState $semanticRepo @([pscustomobject]@{Name='java.exe';CommandLine='java.exe -cp D:\offline-tests P5FocusedRunner'}) @() @()
$semanticOpaqueFailures=0
foreach($command in @('java.exe @D:\synthetic\launch.args','java.exe "@D:\synthetic path\launch.args"','javaw.exe @"D:\synthetic path\launch.args"','java.exe -m audit/Main','java.exe --module audit/Main','java.exe --module=audit/Main','java.exe -p D:\synthetic\modules Main','java.exe --module-path=D:\synthetic\modules Main','java.exe --upgrade-module-path D:\synthetic\modules Main','java.exe --patch-module=audit=D:\synthetic Main')){
    try {Assert-SemanticReject {Assert-SemanticStoppedState $semanticRepo @([pscustomobject]@{Name='java.exe';CommandLine=$command}) @() @()}}
    catch {$semanticOpaqueFailures++;Write-Output ('FAIL SEMANTIC_OPAQUE_JAVA_CASE='+$semanticOpaqueFailures)}
}
if($semanticOpaqueFailures){throw 'SEMANTIC_OPAQUE_JAVA_LAUNCH_ACCEPTED'}
Write-Output 'SEMANTIC_OPAQUE_JAVA_GUARD=PASS ARGFILES_AND_MODULE_LAUNCHERS_REJECTED NO_ARGFILE_READS'
$semanticClasses=Join-Path $semanticRepo '.runtime\contract-repair-test\closeout-20261016\p6-classes'
$javaSource=& java.exe -cp $semanticClasses SemanticCheckpointMaintenanceTest $semanticRepo --source
if($LASTEXITCODE -ne 0 -or $javaSource -cne (Get-SemanticSourceDigest)){throw 'SEMANTIC_CROSS_LANGUAGE_SOURCE_BINDING_DRIFT'}
Write-Output 'SEMANTIC_SOURCE_BINDING=PASS WRAPPER_AND_JAVA_IDENTICAL'
$tokens=$null;$errors=$null;$tree=[Management.Automation.Language.Parser]::ParseFile((Join-Path $semanticRepo 'scripts/local/contract-test.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'SEMANTIC_LAUNCHER_PARSE_FAILED'}
$definition=$tree.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Invoke-ContractBuild'},$true)
Invoke-Expression $definition[0].Extent.Text
function Assert-ContractGit {} function Assert-ContractPython {}
function Get-ContractEntries {return @([pscustomobject]@{service='Backend';pid=12345})}
function Get-ContractObserved([int]$ProcessId){return [pscustomobject]@{ProcessId=$ProcessId}}
function Invoke-ContractNative {throw 'OFFLINE_UNEXPECTED_NATIVE_BUILD'}
$gate=$null;try{Invoke-ContractBuild}catch{$gate=$_.Exception.Message}
if($gate -ne 'RUNNING_JAR_CANONICAL_BUILD_FORBIDDEN'){throw 'OFFLINE_RUNNING_JAR_BUILD_NOT_BLOCKED'}
Write-Output 'SEMANTIC_CANONICAL_BUILD_GUARD=PASS NO_RUNNING_JAR_WRITE'
$definition=$tree.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-ContractBuildManifest'},$true)
Invoke-Expression $definition[0].Extent.Text
$contractRoot=$semanticRepo
$contractManifest=Join-Path $semanticRepo ('.runtime\contract-repair-test\closeout-20261016\p6-manifest-'+[guid]::NewGuid().ToString('N')+'.json')
$oldFlag=[Environment]::GetEnvironmentVariable('AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED','Process')
try {
    $env:AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED='true'
    [IO.File]::WriteAllText($contractManifest,'{"format":1}',[Text.UTF8Encoding]::new($false))
    $gate=$null;try{Assert-ContractBuildManifest}catch{$gate=$_.Exception.Message}
    if($gate -ne 'SEMANTIC_BUILD_FLAG_UNVERIFIED'){throw 'OFFLINE_OLD_MANIFEST_INCORRECTLY_ACCEPTED'}
    [IO.File]::WriteAllText($contractManifest,'{"semanticCheckpointsEnabled":true}',[Text.UTF8Encoding]::new($false))
    $gate=$null;try{Assert-ContractBuildManifest}catch{$gate=$_.Exception.Message}
    if($gate -ne 'SEMANTIC_BUILD_DDL_SOURCE_DRIFT'){throw 'OFFLINE_MISSING_DDL_HASH_INCORRECTLY_ACCEPTED'}
} finally {
    if(Test-Path -LiteralPath $contractManifest){Remove-Item -LiteralPath $contractManifest}
    [Environment]::SetEnvironmentVariable('AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED',$oldFlag,'Process')
}
Write-Output 'SEMANTIC_MANIFEST_GUARD=PASS OLD_FLAG_OR_DDL_HASH_CANNOT_IMPERSONATE_NEW_CANDIDATE'
