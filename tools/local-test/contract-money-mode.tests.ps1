$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'contract-test-guards.ps1')
$contractCases=0
function Assert-Mode([bool]$Pass){if(-not $Pass){throw 'MONEY_MODE_ASSERTION_FAILED'};$script:contractCases++}
function Reject-Mode([scriptblock]$Code){$rejected=$false;try{& $Code|Out-Null}catch{$rejected=$true};Assert-Mode $rejected}
$contractFixtureRoot=Join-Path ([IO.Path]::GetTempPath()) ('contract-mode-'+[guid]::NewGuid().ToString('N'))
$contractProject='11111111-1111-4111-8111-111111111111'
$contractWindow=Join-Path $contractFixtureRoot 'round/window.json'
$contractStrict=Resolve-ContractSemanticMoneyConfiguration 'STRICT' '' '' $contractFixtureRoot
Assert-Mode ($contractStrict.mode -ceq 'STRICT' -and $contractStrict.projects.Count -eq 0)
$contractObserve=Resolve-ContractSemanticMoneyConfiguration 'OBSERVE' $contractProject $contractWindow $contractFixtureRoot
Assert-Mode ($contractObserve.mode -ceq 'OBSERVE' -and $contractObserve.projects[0] -ceq $contractProject -and $contractObserve.window -ceq [IO.Path]::GetFullPath($contractWindow))
Reject-Mode {Resolve-ContractSemanticMoneyConfiguration 'DISABLED' '' '' $contractFixtureRoot}
Reject-Mode {Resolve-ContractSemanticMoneyConfiguration 'OBSERVE' '' $contractWindow $contractFixtureRoot}
Reject-Mode {Resolve-ContractSemanticMoneyConfiguration 'OBSERVE' $contractProject '' $contractFixtureRoot}
Reject-Mode {Resolve-ContractSemanticMoneyConfiguration 'OBSERVE' ($contractProject+','+$contractProject) $contractWindow $contractFixtureRoot}
Reject-Mode {Resolve-ContractSemanticMoneyConfiguration 'OBSERVE' 'not-project-id' $contractWindow $contractFixtureRoot}
Reject-Mode {Resolve-ContractSemanticMoneyConfiguration 'OBSERVE' $contractProject ($contractFixtureRoot+'-sibling/window.json') $contractFixtureRoot}
Reject-Mode {Resolve-ContractSemanticMoneyConfiguration 'STRICT' $contractProject $contractWindow $contractFixtureRoot}
Write-Output ('CONTRACT_MONEY_MODE=PASS CASES='+$contractCases+' NO_PROCESS_HTTP_OR_WINDOW_CREATED')
