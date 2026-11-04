$ErrorActionPreference='Stop'
$contractRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$contractRuntime=Join-Path $contractRoot '.runtime/contract-repair-test'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'contract-test.ps1'),[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'LAUNCHER_PARSE_FAILED'}
foreach($name in @('Assert-ContractControlledPath','Get-ContractSemanticBoundaryArguments','Invoke-ContractStart')){
 $definitions=@($ast.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true))
 if($definitions.Count -ne 1){throw ('BOUNDARY_FUNCTION_MISSING_'+$name)}
 Invoke-Expression $definitions[0].Extent.Text
}
$Action='Start';$Validated=$false;$SemanticBoundaryProofPath=$null
if(@(Get-ContractSemanticBoundaryArguments).Count -ne 0){throw 'ORDINARY_START_CHANGED'}
$SemanticBoundaryProofPath=Join-Path $contractRuntime ('semantic-boundary-offline-'+[guid]::NewGuid().ToString('N')+'.json')
$arguments=@(Get-ContractSemanticBoundaryArguments)
if($arguments.Count -ne 1 -or $arguments[0] -cnotmatch '^--audit\.contract\.semantic-checkpoints\.local-boundary-proof="[^"]+"$'){throw 'EXPLICIT_BOUNDARY_NOT_BOUND'}
function Expect-Blocked([scriptblock]$Run,[string]$Expected){
 $actual=$null;try{& $Run}catch{$actual=$_.Exception.Message}
 if($actual -cne $Expected){throw ('EXPECTED_'+$Expected)}
}
$Validated=$true;Expect-Blocked {Get-ContractSemanticBoundaryArguments} 'BOUNDARY_CANDIDATE_START_ONLY'
$Validated=$false;$Action='Build';Expect-Blocked {Get-ContractSemanticBoundaryArguments} 'BOUNDARY_CANDIDATE_START_ONLY'
$Action='Start';$SemanticBoundaryProofPath=Join-Path $contractRoot 'outside-runtime.json'
Expect-Blocked {Get-ContractSemanticBoundaryArguments} 'BOUNDARY_PROOF_OUTSIDE_RUNTIME'
$SemanticBoundaryProofPath=Join-Path $contractRuntime 'wrong-name.json'
Expect-Blocked {Get-ContractSemanticBoundaryArguments} 'BOUNDARY_PROOF_NAME_INVALID'
$SemanticBoundaryProofPath=Join-Path $contractRuntime ('semantic-boundary-offline-'+[guid]::NewGuid().ToString('N')+'.json')
function Assert-ContractGit{}
function Assert-ContractStorage{}
function Assert-ContractPython{}
function Assert-ContractBuildManifest{return @{}}
function Invoke-ContractDm8{}
function Invoke-ContractRedis{}
function Assert-ContractModel{}
function Get-ContractEntries{return @(@{},@{},@{})}
function Assert-ContractOwned{}
function Assert-ContractRunningSha{}
Expect-Blocked {Invoke-ContractStart} 'BOUNDARY_REQUIRES_CONTROLLED_COLD_START'
Write-Output 'SEMANTIC_BOUNDARY_LAUNCHER_TESTS=PASS CASES=7 NO_MODEL_NO_DATABASE_NO_SERVICE'
