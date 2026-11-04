[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidateSet('Preflight','Build','Start','Status','Stop','InitializeFreshStorage')][string]$Action,
    [string]$ExpectedSha,
    [switch]$Validated,
    [string]$PrivateConfigPath,
    [string]$PortableRedisConfig,
    [string]$SemanticBoundaryProofPath,
    [ValidateSet('STRICT','OBSERVE')][string]$SemanticMoneyMode='STRICT',
    [string]$SemanticObserveProjectIds,
    [string]$SemanticObserveWindowPath
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$contractRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$contractCanonical='D:\code\audit_agent_1D\.worktrees\integration-pre-main-20261010'
$contractBranch='integration/pre-main-20261010'
$contractRedisDistribution='Ubuntu-22.04'
$contractRuntime=Join-Path $contractRoot '.runtime\contract-repair-test'
$contractManifest=Join-Path $contractRuntime 'build-manifest.json'
$contractLedger=Join-Path $contractRuntime 'pids.json'
$contractStorage=Join-Path $contractRuntime 'storage'
$contractStorageProof=Join-Path $contractRuntime 'fresh-storage-proof.json'
$contractPointer=Join-Path $contractRoot '.runtime\parallel-diagnostics\storage-root.txt'
$contractPython=Join-Path (Split-Path $contractRoot) 'ai-ocr-project-intake-demo\.runtime\venvs\audit-ai\Scripts\python.exe'
$contractDriver=Join-Path $env:USERPROFILE '.m2\repository\com\dameng\DmJdbcDriver18\8.1.3.140\DmJdbcDriver18-8.1.3.140.jar'
. (Join-Path $contractRoot 'tools\local-test\contract-test-guards.ps1')

function Write-ContractJson([string]$Path,$Value) {
    Assert-ContractControlledPath $Path
    [IO.Directory]::CreateDirectory((Split-Path $Path)) | Out-Null
    $contractTemporary=$Path+'.'+[Guid]::NewGuid().ToString('N')+'.tmp'
    try {
        [IO.File]::WriteAllText($contractTemporary,($Value | ConvertTo-Json -Depth 8),[Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath $contractTemporary -Destination $Path -Force
    } finally {if(Test-Path -LiteralPath $contractTemporary){Remove-Item -LiteralPath $contractTemporary}}
}
function Assert-ContractControlledPath([string]$Path) {
    $resolved=[IO.Path]::GetFullPath($Path)
    if($resolved -ne $contractRoot -and -not $resolved.StartsWith($contractRoot+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'RUNTIME_PATH_OUTSIDE_CANONICAL_SOURCE'}
    $scan=$resolved
    while($scan.Length -ge $contractRoot.Length) {
        if(Test-Path -LiteralPath $scan){$item=Get-Item -LiteralPath $scan -Force;if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'RUNTIME_REPARSE_POINT'}}
        if($scan -eq $contractRoot){break};$scan=Split-Path $scan
    }
}
function Assert-ContractGit {
    $dirty=@(& git.exe -C $contractRoot status --porcelain --untracked-files=all)
    Assert-ContractSourceIdentity $contractRoot $contractCanonical (& git.exe -C $contractRoot branch --show-current) $contractBranch (& git.exe -C $contractRoot rev-parse HEAD) $ExpectedSha $dirty
    Assert-ContractControlledPath $contractRoot
}
function Get-ContractAggregate([string]$Root,[string]$Pattern='*') {
    $files=@(Get-ChildItem -LiteralPath $Root -File -Recurse -Filter $Pattern | Where-Object {$_.FullName -notmatch '\\__pycache__\\'} | Sort-Object FullName)
    if(-not $files.Count){throw 'SOURCE_FINGERPRINT_FILES_MISSING'}
    $entries=foreach($file in $files){Assert-ContractControlledPath $file.FullName;$relative=$file.FullName.Substring($Root.TrimEnd('\').Length).TrimStart('\').Replace('\','/');$relative+'='+((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant())}
    Get-ContractTextHash ($entries -join "`n")
}
function Import-ContractEnvironment {
    if(-not $PrivateConfigPath){$script:PrivateConfigPath=Join-Path (Split-Path $contractRoot) 'ai-ocr-project-intake-demo\.runtime\local-demo.env.integration.legacy.ps1'}
    $allowed=@('AUDIT_DB_URL','AUDIT_DB_USERNAME','AUDIT_DB_PASSWORD','AUDIT_REDIS_HOST','AUDIT_REDIS_PORT','AUDIT_REDIS_PASSWORD','AUDIT_MODEL_BASE_URL','AUDIT_MODEL_API_KEY','AUDIT_MODEL_NAME','AUDIT_MODEL_CHUNK_CHARS','AUDIT_MODEL_MAX_CHUNKS','AUDIT_MODEL_READ_TIMEOUT_SECONDS','AUDIT_MODEL_CONNECT_TIMEOUT_SECONDS','AUDIT_MODEL_TOTAL_BUDGET_SECONDS','AUDIT_CONTRACT_MODEL_READ_TIMEOUT_SECONDS','AUDIT_SEMANTIC_STRUCTURED_TRANSPORT','AUDIT_AI_CONNECT_TIMEOUT_MS','AUDIT_AI_READ_TIMEOUT_MS','AUDIT_OCR_CONNECT_TIMEOUT_MS','AUDIT_OCR_READ_TIMEOUT_MS','AUDIT_SESSION_TIMEOUT','AUDIT_AI_MAX_UPLOAD_BYTES','AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED')
    $settings=Read-ContractLiteralEnvironment $PrivateConfigPath $allowed @('AUDIT_DB_URL','AUDIT_DB_USERNAME','AUDIT_DB_PASSWORD','AUDIT_MODEL_BASE_URL','AUDIT_MODEL_API_KEY')
    foreach($key in $settings.Keys){[Environment]::SetEnvironmentVariable($key,$settings[$key],'Process')}
    $contractDbIdentity=Read-ContractApprovedDatabaseIdentity $contractRoot
    Assert-ContractDatabaseIdentity $contractDbIdentity $env:AUDIT_DB_URL $env:AUDIT_DB_USERNAME $contractDbIdentity.schema
    if(-not $settings.ContainsKey('AUDIT_REDIS_HOST')){$env:AUDIT_REDIS_HOST='127.0.0.1'}
    if(-not $settings.ContainsKey('AUDIT_REDIS_PORT')){$env:AUDIT_REDIS_PORT='6379'}
    if($env:AUDIT_REDIS_HOST -ne '127.0.0.1' -or $env:AUDIT_REDIS_PORT -notmatch '^[0-9]{1,5}$'){throw 'REDIS_OUTSIDE_LOOPBACK_SCOPE'}
    $choicePath=Join-Path $contractRuntime 'model-choice.json'
    if(Test-Path -LiteralPath $choicePath){
        $choice=Get-Content -LiteralPath $choicePath -Raw -Encoding UTF8 | ConvertFrom-Json
        $provider=Get-ContractTextHash ($env:AUDIT_MODEL_BASE_URL.Trim().TrimEnd('/').ToLowerInvariant())
        if($choice.verified -ne 'PROTOCOL_PROBE_PASS' -or $choice.providerIdentity -ne $provider -or [string]::IsNullOrWhiteSpace($choice.modelName)){throw 'MODEL_CHOICE_NOT_VERIFIED_FOR_PROVIDER'}
        $env:AUDIT_MODEL_NAME=$choice.modelName
    }
    if([string]::IsNullOrWhiteSpace($env:AUDIT_MODEL_NAME)){throw 'MODEL_NAME_MISSING'}
    $env:AUDIT_FILE_STORAGE_ROOT=$contractStorage
    $env:AUDIT_AI_TEMP_ROOT=Join-Path $contractRuntime 'ai-temp'
    $env:AUDIT_SESSION_REDIS_NAMESPACE='audit-agent:contract-repair-test:integration-20261010'
    $env:AUDIT_SESSION_COOKIE_SECURE='false'
    $env:AUDIT_CORS_ALLOWED_ORIGINS='http://127.0.0.1:5173,http://localhost:5173'
    $env:AUDIT_AI_BASE_URL='http://127.0.0.1:8001'
    $env:AUDIT_OCR_BASE_URL='http://127.0.0.1:8001'
    $env:AUDIT_VITE_API_PROXY_TARGET='http://127.0.0.1:8888'
    $env:AUDIT_OCR_CPU_THREADS='1';$env:AUDIT_OCR_ENABLE_MKLDNN='false';$env:PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK='True'
    $env:AUDIT_OCR_READ_TIMEOUT_MS=[string](Resolve-ContractOcrReadTimeout $env:AUDIT_OCR_READ_TIMEOUT_MS)
    $env:AUDIT_AI_DOCUMENT_ROLE_ENABLED='true';$env:AUDIT_AI_CONTRACT_FACT_EXTRACTION_ENABLED='true'
    $env:AUDIT_CONTRACT_REVIEW_WORKER_ENABLED='true';$env:AUDIT_CONTRACT_FILE_PROCESSING_WORKER_ENABLED='true'
    $semanticFlag=if($settings.ContainsKey('AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED')){$settings['AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED']}else{$null}
    $env:AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED=(Resolve-ContractSemanticCheckpointFlag $semanticFlag).ToString().ToLowerInvariant()
    $env:AUDIT_PROCUREMENT_MATERIAL_WORKER_ENABLED='false';$env:AUDIT_BUDGET_MATERIAL_WORKER_ENABLED='false'
    $env:AUDIT_MODEL_DEMO_RELAX_BREACH_MONETARY='false';$env:AUDIT_TECH_LOG_LEVEL='INFO';$env:AUDIT_FLOW_DEBUG_LOG_LEVEL='INFO';$env:AUDIT_OBSERVABILITY_FLOW_DEBUG_ENABLED='false'
    # No inherited or private legacy flag silently enables the wider local mode.
    $script:contractMoney=Resolve-ContractSemanticMoneyConfiguration $SemanticMoneyMode $SemanticObserveProjectIds $SemanticObserveWindowPath $contractRuntime
    if($Validated -and $contractMoney.mode -cne 'STRICT'){throw 'VALIDATED_START_REQUIRES_STRICT'}
    if($contractMoney.window){Assert-ContractControlledPath $contractMoney.window}
    $env:AUDIT_CONTRACT_SEMANTIC_MONEY_MODE=$contractMoney.mode
    $env:AUDIT_CONTRACT_SEMANTIC_OBSERVE_PROJECT_IDS=($contractMoney.projects -join ',')
    $env:AUDIT_CONTRACT_SEMANTIC_OBSERVE_WINDOW_PATH=$contractMoney.window
    $env:PYTHONPATH=Join-Path $contractRoot 'services\audit-ai\src';$env:PYTHONDONTWRITEBYTECODE='1';$env:CONTRACT_TEST_PYTHON_SOURCE=$env:PYTHONPATH
    $privateModelHost=([uri]$env:AUDIT_MODEL_BASE_URL).DnsSafeHost
    $approvedNoProxy=[Environment]::GetEnvironmentVariable('NO_PROXY','Process')
    $env:NO_PROXY=(@(($approvedNoProxy+',127.0.0.1,localhost,'+$privateModelHost) -split ',' | Where-Object {$_} | Sort-Object -Unique) -join ',');$env:no_proxy=$env:NO_PROXY
}
function Assert-ContractPython {
    if(-not(Test-Path -LiteralPath $contractPython -PathType Leaf)){throw 'APPROVED_PYTHON_VENV_MISSING'}
    & $contractPython (Join-Path $contractRoot 'tools\local-test\contract-test-python-identity.py')
    if($LASTEXITCODE -ne 0){throw 'PYTHON_SOURCE_IDENTITY_MISMATCH'}
}
function Invoke-ContractDm8([string]$Mode='Status') {
    $classes=Join-Path $contractRuntime 'runtime-probe-classes';[IO.Directory]::CreateDirectory($classes)|Out-Null
    $probeSources=@('ResetReviewTestData.java','ContractTestRuntimeProbe.java','ContractTestStorageProbe.java','ContractLocalMigration.java','Word97LocalMigration.java','Word97Maintenance.java','SemanticCheckpointCatalog.java') | ForEach-Object {Join-Path $contractRoot ('tools\local-test\'+$_)}
    Invoke-ContractNative 'javac.exe' (@('-encoding','UTF-8','-d',$classes)+$probeSources) 'RUNTIME_PROBE_COMPILE_FAILED'
    & java.exe -cp ($classes+[IO.Path]::PathSeparator+$contractDriver) ContractTestRuntimeProbe $Mode
    if($LASTEXITCODE -ne 0){throw 'DM8_AUTH_SCHEMA_OR_WORKER_GATE_BLOCKED'}
}
function Invoke-ContractRedis {
    $client=New-Object Net.Sockets.TcpClient
    try {
        $pending=$client.BeginConnect('127.0.0.1',[int]$env:AUDIT_REDIS_PORT,$null,$null)
        if(-not $pending.AsyncWaitHandle.WaitOne(3000,$false)){throw 'REDIS_NOT_REACHABLE'}
        $client.EndConnect($pending);$stream=$client.GetStream();$stream.ReadTimeout=3000;$stream.WriteTimeout=3000
        $commands=@();if($env:AUDIT_REDIS_PASSWORD){$commands+=,@('AUTH',$env:AUDIT_REDIS_PASSWORD)};$commands+=,@('PING')
        foreach($parts in $commands){
            $payload='*'+$parts.Count+"`r`n";foreach($part in $parts){$payload+='$'+[Text.Encoding]::UTF8.GetByteCount($part)+"`r`n"+$part+"`r`n"};$bytes=[Text.Encoding]::UTF8.GetBytes($payload);$stream.Write($bytes,0,$bytes.Length)
            $line=New-Object Collections.Generic.List[byte];while($true){$b=$stream.ReadByte();if($b -lt 0){throw 'REDIS_CONNECTION_CLOSED'};if($b -eq 10){break};if($line.Count -gt 2048){throw 'REDIS_REPLY_INVALID'};$line.Add([byte]$b)}
            $reply=[Text.Encoding]::UTF8.GetString($line.ToArray()).TrimEnd("`r")
            if(($parts[0] -eq 'AUTH' -and $reply -ne '+OK') -or ($parts[0] -eq 'PING' -and $reply -ne '+PONG')){throw 'REDIS_AUTH_OR_PROTOCOL_FAILED'}
        }
        Write-Output 'REDIS=AUTHENTICATED_PING_PASS'
    } catch {throw 'REDIS_AUTHENTICATED_CHECK_BLOCKED'} finally {$client.Dispose()}
}
function Start-ContractPortableRedis {
    if(-not $PortableRedisConfig){throw 'REDIS_NOT_READY_PORTABLE_CONFIG_NOT_SUPPLIED'}
    Assert-ContractControlledPath $PortableRedisConfig
    if(-not(Test-Path -LiteralPath $PortableRedisConfig -PathType Leaf)){throw 'PORTABLE_REDIS_CONFIG_MISSING'}
    $config=Get-Content -LiteralPath $PortableRedisConfig -Raw -Encoding UTF8|ConvertFrom-Json
    if($config.format -ne 1 -or [string]::IsNullOrWhiteSpace($config.serverPath) -or [string]::IsNullOrWhiteSpace($config.configPath)){throw 'PORTABLE_REDIS_LAUNCH_RECEIPT_INVALID'}
    Assert-ContractWslDistribution $config.distribution
    foreach($path in @($config.serverPath,$config.configPath)){Assert-ContractControlledPath $path;if(-not(Test-Path -LiteralPath $path -PathType Leaf)){throw 'PORTABLE_REDIS_ARTIFACT_MISSING'}}
    if($config.serverSha256 -ne (Get-FileHash -LiteralPath $config.serverPath -Algorithm SHA256).Hash.ToLowerInvariant() -or $config.configSha256 -ne (Get-FileHash -LiteralPath $config.configPath -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'PORTABLE_REDIS_ARTIFACT_DRIFT'}
    $privateRedisConfig=Get-Content -LiteralPath $config.configPath -Raw -Encoding UTF8
    if(@(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object {$_.LocalPort -eq [int]$env:AUDIT_REDIS_PORT}).Count){throw 'PORTABLE_REDIS_PORT_ALREADY_OWNED'}
    $linuxRoot=(& wsl.exe --distribution $contractRedisDistribution --exec wslpath -a $contractRoot 2>$null) -join ''
    if($LASTEXITCODE -ne 0){throw 'PORTABLE_REDIS_WSL_PATH_FAILED'}
    Assert-ContractPortableRedisConfig $privateRedisConfig $linuxRoot ([int]$env:AUDIT_REDIS_PORT)
    $linuxServer=(& wsl.exe --distribution $contractRedisDistribution --exec wslpath -a $config.serverPath 2>$null) -join ''
    if($LASTEXITCODE -ne 0){throw 'PORTABLE_REDIS_WSL_PATH_FAILED'}
    $linuxConfig=(& wsl.exe --distribution $contractRedisDistribution --exec wslpath -a $config.configPath 2>$null) -join ''
    if($LASTEXITCODE -ne 0){throw 'PORTABLE_REDIS_WSL_PATH_FAILED'}
    $null=& wsl.exe --distribution $contractRedisDistribution --exec $linuxServer $linuxConfig 2>$null
    if($LASTEXITCODE -ne 0){throw 'PORTABLE_REDIS_START_BLOCKED'}
    Invoke-ContractRedis
    Write-Output 'PORTABLE_REDIS=DEDICATED_CONFIG_STARTED_LOOPBACK NO_FLUSH_NO_SYSTEM_INSTALL'
}
function Assert-ContractModel {
    try{$models=Invoke-RestMethod -Method Get -Uri ($env:AUDIT_MODEL_BASE_URL.TrimEnd('/')+'/models') -Headers @{Authorization=('Bearer '+$env:AUDIT_MODEL_API_KEY)} -TimeoutSec 10}catch{throw 'MODEL_METADATA_UNAVAILABLE'}
    if(@($models.data | ForEach-Object {$_.id}) -notcontains $env:AUDIT_MODEL_NAME){throw 'CONFIGURED_MODEL_NOT_AVAILABLE'}
    Write-Output 'MODEL=VERIFIED_AVAILABLE_NAME'
}
function Assert-ContractResetReceipt {
    $path=Join-Path $contractRuntime 'data-reset-applied.txt'
    if(-not(Test-Path -LiteralPath $path)){throw 'DATA_RESET_RECEIPT_MISSING'}
    $receipt=Get-Content -LiteralPath $path -Raw -Encoding UTF8
    if($receipt -notmatch '(?m)^DATA_RESET=PASS\s*$' -or $receipt -notmatch '(?m)^PLAN_SHA256=([a-f0-9]{64})\s*$'){throw 'DATA_RESET_RECEIPT_INVALID'}
    return [regex]::Match($receipt,'(?m)^PLAN_SHA256=([a-f0-9]{64})\s*$').Groups[1].Value
}
function Initialize-ContractStorage {
    Assert-ContractGit
    $resetIdentity=Assert-ContractResetReceipt
    if(Test-Path -LiteralPath $contractStorageProof){throw 'FRESH_STORAGE_ALREADY_INITIALIZED'}
    foreach($port in @(8001,8888,5173)){if(@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object {$_.LocalPort -eq $port}).Count){throw 'FRESH_STORAGE_WRITER_ACTIVE'}}
    Assert-ContractControlledPath $contractStorage
    Invoke-ContractDm8 'FreshGate'
    $classes=Join-Path $contractRuntime 'runtime-probe-classes'
    & java.exe -cp $classes ContractTestStorageProbe $contractStorage
    if($LASTEXITCODE -ne 0){throw 'FRESH_STORAGE_PLATFORM_PROOF_FAILED'}
    Write-ContractJson $contractStorageProof ([ordered]@{root=$contractStorage;resetPlan=$resetIdentity;sourceSha=$ExpectedSha;hardLinkCreateOnly=$true;readShaVerified=$true;createdUtc=[datetime]::UtcNow.ToString('o')})
    [IO.Directory]::CreateDirectory((Split-Path $contractPointer))|Out-Null
    Assert-ContractControlledPath $contractPointer
    [IO.File]::WriteAllText($contractPointer,$contractStorage+[Environment]::NewLine,[Text.UTF8Encoding]::new($false))
    Write-Output 'FRESH_FILE_STORAGE=PASS STORAGE_POINTER_INITIALIZED'
}
function Assert-ContractStorage {
    if(-not(Test-Path -LiteralPath $contractStorageProof) -or -not(Test-Path -LiteralPath $contractPointer)){throw 'FRESH_STORAGE_PROOF_OR_POINTER_MISSING'}
    $proof=Get-Content -LiteralPath $contractStorageProof -Raw -Encoding UTF8|ConvertFrom-Json
    if($proof.root -ne $contractStorage -or -not $proof.hardLinkCreateOnly -or -not $proof.readShaVerified -or (Get-Content -LiteralPath $contractPointer -Raw -Encoding UTF8).Trim() -ne $contractStorage -or $proof.resetPlan -ne (Assert-ContractResetReceipt)){throw 'STORAGE_POINTER_OR_RESET_IDENTITY_CHANGED'}
    Assert-ContractControlledPath $contractStorage
}
function Invoke-ContractBuild {
    Assert-ContractGit
    Assert-ContractPython
    foreach($entry in @(Get-ContractEntries)){
        if($entry.service -eq 'Backend' -and (Get-ContractObserved ([int]$entry.pid))){throw 'RUNNING_JAR_CANONICAL_BUILD_FORBIDDEN'}
    }
    Assert-ContractCanonicalJarStopped (Join-Path $contractRoot 'backend\target\audit-backend-0.1.0-SNAPSHOT.jar') @(Get-CimInstance Win32_Process -ErrorAction Stop)
    if(@(Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object {$_.LocalPort -eq 8888}).Count){throw 'BACKEND_LISTENER_CANONICAL_BUILD_FORBIDDEN'}
    Push-Location (Join-Path $contractRoot 'backend')
    try{Invoke-ContractIsolatedBuildEnvironment {Invoke-ContractNative '.\mvnw.cmd' @('clean','verify') 'JAVA_CLEAN_VERIFY_FAILED'}}finally{Pop-Location}
    Push-Location (Join-Path $contractRoot 'frontend')
    try{if(-not(Test-Path -LiteralPath 'node_modules\vite\bin\vite.js')){throw 'FRONTEND_DEPENDENCIES_MISSING'};Invoke-ContractIsolatedBuildEnvironment {Invoke-ContractNative 'npm.cmd' @('run','build') 'FRONTEND_BUILD_FAILED'}}finally{Pop-Location}
    Assert-ContractGit
    $jar=Join-Path $contractRoot 'backend\target\audit-backend-0.1.0-SNAPSHOT.jar'
    $manifest=[ordered]@{format=1;codeRoot=$contractRoot;branch=$contractBranch;sourceSha=$ExpectedSha;sourceTree=(& git.exe -C $contractRoot rev-parse 'HEAD^{tree}');jar=$jar;jarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant();frontendSha256=(Get-ContractAggregate (Join-Path $contractRoot 'frontend\dist'));frontendSourceSha256=(Get-ContractAggregate (Join-Path $contractRoot 'frontend\src'));pythonSourceSha256=(Get-ContractAggregate (Join-Path $contractRoot 'services\audit-ai\src') '*.py');privateConfigSha256=(Get-FileHash -LiteralPath $PrivateConfigPath -Algorithm SHA256).Hash.ToLowerInvariant();modelIdentity=(Get-ContractTextHash ($env:AUDIT_MODEL_BASE_URL.TrimEnd('/')+'|'+$env:AUDIT_MODEL_NAME));javaCleanVerify=$true;frontendBuild=$true;reviewWorker=$true;fileProcessingWorker=$true;createdUtc=[datetime]::UtcNow.ToString('o')}
    $manifest['semanticCheckpointsEnabled']=$true
    $manifest['supportedSemanticMoneyModes']=@('STRICT','OBSERVE')
    $manifest['semanticDdlSha256']=(Get-FileHash -LiteralPath (Join-Path $contractRoot 'db\dm8\CONTRACT__contract_semantic_checkpoints.sql') -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-ContractJson $contractManifest $manifest
    Write-Output ('BUILD=PASS SOURCE_SHA='+$ExpectedSha)
}
function Assert-ContractBuildManifest {
    if(-not(Test-Path -LiteralPath $contractManifest)){throw 'BUILD_MANIFEST_MISSING'}
    $manifest=Get-Content -LiteralPath $contractManifest -Raw -Encoding UTF8|ConvertFrom-Json
    if(-not $manifest.PSObject.Properties['semanticCheckpointsEnabled'] -or -not $manifest.semanticCheckpointsEnabled -or $env:AUDIT_CONTRACT_SEMANTIC_CHECKPOINTS_ENABLED -cne 'true'){throw 'SEMANTIC_BUILD_FLAG_UNVERIFIED'}
    if(-not $manifest.PSObject.Properties['semanticDdlSha256'] -or $manifest.semanticDdlSha256 -cne (Get-FileHash -LiteralPath (Join-Path $contractRoot 'db\dm8\CONTRACT__contract_semantic_checkpoints.sql') -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'SEMANTIC_BUILD_DDL_SOURCE_DRIFT'}
    if($manifest.format -ne 1 -or $manifest.codeRoot -ne $contractRoot -or $manifest.branch -ne $contractBranch -or $manifest.sourceSha -ne $ExpectedSha -or -not $manifest.javaCleanVerify -or -not $manifest.frontendBuild){throw 'BUILD_MANIFEST_SOURCE_MISMATCH'}
    Assert-ContractControlledPath $manifest.jar
    if($manifest.jar -ne (Join-Path $contractRoot 'backend\target\audit-backend-0.1.0-SNAPSHOT.jar') -or $manifest.jarSha256 -ne (Get-FileHash -LiteralPath $manifest.jar -Algorithm SHA256).Hash.ToLowerInvariant() -or $manifest.frontendSha256 -ne (Get-ContractAggregate (Join-Path $contractRoot 'frontend\dist')) -or $manifest.frontendSourceSha256 -ne (Get-ContractAggregate (Join-Path $contractRoot 'frontend\src')) -or $manifest.pythonSourceSha256 -ne (Get-ContractAggregate (Join-Path $contractRoot 'services\audit-ai\src') '*.py')){throw 'BUILD_ARTIFACT_OR_SOURCE_DRIFT'}
    if($manifest.privateConfigSha256 -ne (Get-FileHash -LiteralPath $PrivateConfigPath -Algorithm SHA256).Hash.ToLowerInvariant() -or $manifest.modelIdentity -ne (Get-ContractTextHash ($env:AUDIT_MODEL_BASE_URL.TrimEnd('/')+'|'+$env:AUDIT_MODEL_NAME))){throw 'PRIVATE_RUNTIME_IDENTITY_CHANGED'}
    return $manifest
}
function Assert-ContractValidated {
    $pin=Join-Path $contractRuntime 'validated-sha.txt';$receipt=Join-Path $contractRuntime 'acceptance-receipt.json'
    if(-not(Test-Path -LiteralPath $pin) -or -not(Test-Path -LiteralPath $receipt)){throw 'FULL_ACCEPTANCE_RECEIPT_MISSING'}
    $validatedSha=(Get-Content -LiteralPath $pin -Raw -Encoding UTF8).Trim();$acceptance=Get-Content -LiteralPath $receipt -Raw -Encoding UTF8|ConvertFrom-Json
    if($validatedSha -ne $ExpectedSha -or $acceptance.sourceSha -ne $validatedSha -or $acceptance.result -ne 'PASS' -or $acceptance.buildManifestSha256 -ne (Get-FileHash -LiteralPath $contractManifest -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'VALIDATED_BUILD_RECEIPT_MISMATCH'}
}
function Get-ContractEntries {
    if(-not(Test-Path -LiteralPath $contractLedger)){return @()}
    $ledger=Get-Content -LiteralPath $contractLedger -Raw -Encoding UTF8|ConvertFrom-Json
    if($ledger.codeRoot -ne $contractRoot -or $ledger.format -ne 1){throw 'PID_LEDGER_OUTSIDE_TASK'}
    return @($ledger.entries)
}
function Get-ContractObserved([int]$ProcessId){Get-CimInstance Win32_Process -Filter ('ProcessId='+$ProcessId) -ErrorAction Stop}
function New-ContractProcessRecord([string]$Service,$Process,[int]$Port,[string]$Marker) {
    $observed=Get-ContractObserved $Process.Id
    if($null -eq $observed -or [string]::IsNullOrWhiteSpace($observed.ExecutablePath) -or $observed.CommandLine.IndexOf($Marker,[StringComparison]::OrdinalIgnoreCase) -lt 0){throw 'STARTED_PROCESS_SOURCE_NOT_PROVEN'}
    return [ordered]@{service=$Service;pid=[int]$observed.ProcessId;creationUtc=$observed.CreationDate.ToUniversalTime().ToString('o');executable=$observed.ExecutablePath;executableSha256=(Get-FileHash -LiteralPath $observed.ExecutablePath -Algorithm SHA256).Hash.ToLowerInvariant();commandHash=(Get-ContractTextHash $observed.CommandLine);sourceMarker=$Marker;codeRoot=$contractRoot;sourceSha=$ExpectedSha;port=$Port;semanticMoneyMode=$contractMoney.mode;observeProjectsSha256=(Get-ContractTextHash ($contractMoney.projects -join ','));observeWindowPath=$contractMoney.window}
}
function Assert-ContractOwned($Entry,[switch]$RequireListener) {
    $observed=Get-ContractObserved ([int]$Entry.pid)
    Assert-ContractProcessRecord $Entry $observed $contractRoot
    if($Entry.executableSha256 -ne (Get-FileHash -LiteralPath $Entry.executable -Algorithm SHA256).Hash.ToLowerInvariant()){throw 'OWNED_EXECUTABLE_BYTES_CHANGED'}
    if($RequireListener){$owners=@(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object {$_.LocalPort -eq [int]$Entry.port}|Select-Object -ExpandProperty OwningProcess -Unique);if($owners.Count -ne 1 -or [int]$owners[0] -ne [int]$Entry.pid){throw 'OWNED_LISTENER_MISMATCH'}}
}
function Resolve-ContractListenerRecord($Initial) {
    $owners=@(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object {$_.LocalPort -eq [int]$Initial.port}|Select-Object -ExpandProperty OwningProcess -Unique)
    if($owners.Count -ne 1){throw 'STARTED_LISTENER_OWNER_AMBIGUOUS'}
    if([int]$owners[0] -eq [int]$Initial.pid){return $Initial}
    Assert-ContractOwned ([pscustomobject]$Initial)
    $owner=Get-ContractObserved ([int]$owners[0])
    if($null -eq $owner -or [int]$owner.ParentProcessId -ne [int]$Initial.pid){throw 'STARTED_LISTENER_IS_NOT_OWNED_CHILD'}
    $actual=New-ContractProcessRecord $Initial.service ([pscustomobject]@{Id=[int]$owner.ProcessId}) ([int]$Initial.port) $Initial.sourceMarker
    $actual['launcher']=$Initial
    return $actual
}
function Wait-ContractHttp([int]$Port,[string]$Path,[int]$Seconds) {
    $deadline=[datetime]::UtcNow.AddSeconds($Seconds)
    do{try{$response=Invoke-WebRequest -UseBasicParsing -Uri ('http://127.0.0.1:'+$Port+$Path) -TimeoutSec 3;if($response.StatusCode -eq 200){return}}catch{};Start-Sleep -Milliseconds 300}while([datetime]::UtcNow -lt $deadline)
    throw 'SERVICE_HTTP_HEALTH_TIMEOUT'
}
function Get-ContractSemanticBoundaryArguments {
    if([string]::IsNullOrWhiteSpace($SemanticBoundaryProofPath)){return @()}
    if($Action -cne 'Start' -or $Validated){throw 'BOUNDARY_CANDIDATE_START_ONLY'}
    $resolved=[IO.Path]::GetFullPath($SemanticBoundaryProofPath)
    if(-not $resolved.StartsWith($contractRuntime+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'BOUNDARY_PROOF_OUTSIDE_RUNTIME'}
    if([IO.Path]::GetFileName($resolved) -cnotmatch '^semantic-boundary-[a-z0-9-]+\.json$'){throw 'BOUNDARY_PROOF_NAME_INVALID'}
    Assert-ContractControlledPath $resolved
    if(-not(Test-Path -LiteralPath (Split-Path $resolved) -PathType Container)){throw 'BOUNDARY_PROOF_PARENT_MISSING'}
    if(Test-Path -LiteralPath $resolved){throw 'BOUNDARY_PROOF_ALREADY_EXISTS'}
    return @('--audit.contract.semantic-checkpoints.local-boundary-proof="'+$resolved+'"')
}
function Invoke-ContractStart {
    Assert-ContractGit;Assert-ContractStorage;Assert-ContractPython
    $manifest=Assert-ContractBuildManifest;if($Validated){Assert-ContractValidated}
    Invoke-ContractDm8 'StartPreflight'
    try{Invoke-ContractRedis}catch{if($PortableRedisConfig){Start-ContractPortableRedis}else{throw}}
    Assert-ContractModel
    $entries=@(Get-ContractEntries)
    if($entries.Count){
        if($SemanticBoundaryProofPath){throw 'BOUNDARY_REQUIRES_CONTROLLED_COLD_START'}
        foreach($entry in $entries){
            Assert-ContractOwned $entry -RequireListener;Assert-ContractRunningSha $entry $ExpectedSha
            $runningMode=if($entry.PSObject.Properties['semanticMoneyMode']){$entry.semanticMoneyMode}else{'STRICT'}
            if($runningMode -cne $contractMoney.mode){throw 'RUNNING_MONEY_MODE_REQUIRES_CONTROLLED_RESTART'}
            if($runningMode -ceq 'OBSERVE' -and ($entry.observeProjectsSha256 -cne (Get-ContractTextHash ($contractMoney.projects -join ',')) -or $entry.observeWindowPath -cne $contractMoney.window)){throw 'RUNNING_OBSERVE_SCOPE_REQUIRES_CONTROLLED_RESTART'}
        }
        if($entries.Count -ne 3){throw 'PARTIAL_OWNED_SERVICE_SET'}
        Write-Output ('SERVICE_STATE=ACTIVE SOURCE_SHA='+$ExpectedSha);if($Validated){Start-Process 'http://127.0.0.1:5173/'};return
    }
    foreach($port in @(8001,8888,5173)){if(@(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object {$_.LocalPort -eq $port}).Count){throw 'UNOWNED_APPLICATION_LISTENER_EXISTS'}}
    $logs=Join-Path $contractRuntime 'logs';[IO.Directory]::CreateDirectory($logs)|Out-Null;[IO.Directory]::CreateDirectory($env:AUDIT_AI_TEMP_ROOT)|Out-Null
    $services=@(
        @{name='FastAPI';exe=$contractPython;args=@('-m','uvicorn','audit_ai.main:app','--app-dir',('"'+$env:PYTHONPATH+'"'),'--host','127.0.0.1','--port','8001');dir=(Join-Path $contractRoot 'services\audit-ai');marker=$env:PYTHONPATH;port=8001;path='/health';wait=60},
        @{name='Backend';exe=(Get-Command java.exe -ErrorAction Stop).Source;args=(@('-jar',('"'+$manifest.jar+'"'),'--spring.profiles.active=local-dev,local-ai','--server.port=8888')+@(Get-ContractSemanticBoundaryArguments));dir=(Join-Path $contractRoot 'backend');marker=$manifest.jar;port=8888;path='/api/v1/health';wait=60},
        @{name='Frontend';exe=(Get-Command node.exe -ErrorAction Stop).Source;args=@(('"'+(Join-Path $contractRoot 'frontend\node_modules\vite\bin\vite.js')+'"'),'--host','127.0.0.1','--port','5173','--strictPort');dir=(Join-Path $contractRoot 'frontend');marker=(Join-Path $contractRoot 'frontend\node_modules\vite\bin\vite.js');port=5173;path='/';wait=60}
    )
    foreach($service in $services){
        $process=Start-Process -FilePath $service.exe -ArgumentList $service.args -WorkingDirectory $service.dir -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs ($service.name+'.stdout.log')) -RedirectStandardError (Join-Path $logs ($service.name+'.stderr.log'))
        $entry=New-ContractProcessRecord $service.name $process $service.port $service.marker;$entries+=,$entry
        Write-ContractJson $contractLedger ([ordered]@{format=1;codeRoot=$contractRoot;sourceSha=$ExpectedSha;entries=$entries})
        Wait-ContractHttp $service.port $service.path $service.wait
        $entry=Resolve-ContractListenerRecord $entry;$entries[$entries.Count-1]=$entry
        Write-ContractJson $contractLedger ([ordered]@{format=1;codeRoot=$contractRoot;sourceSha=$ExpectedSha;entries=$entries})
        Assert-ContractOwned ([pscustomobject]$entry) -RequireListener
        Write-Output ($service.name.ToUpperInvariant()+'=ACTIVE PID='+$process.Id)
    }
    Write-Output ('SERVICE_STATE=ACTIVE SOURCE_SHA='+$ExpectedSha+' REVIEW_WORKER=true FILE_PROCESSING_WORKER=true SEMANTIC_CHECKPOINTS=true SEMANTIC_MONEY_MODE='+$contractMoney.mode)
    if($Validated){Start-Process 'http://127.0.0.1:5173/'}
}
function Invoke-ContractStatus {
    $entries=@(Get-ContractEntries);$active=0;$invalid=0;$buildValid=$false
    if(Test-Path -LiteralPath $contractManifest){try{$statusManifest=Get-Content -LiteralPath $contractManifest -Raw -Encoding UTF8|ConvertFrom-Json;$script:ExpectedSha=$statusManifest.sourceSha;Assert-ContractGit;$null=Assert-ContractBuildManifest;$buildValid=$true;Write-Output ('SOURCE_BUILD=VERIFIED SHA='+$ExpectedSha)}catch{Write-Output 'SOURCE_BUILD=BLOCKED_DRIFT'}}else{Write-Output 'SOURCE_BUILD=NOT_BUILT'}
    foreach($port in @(8001,8888,5173)){$owners=@(Get-NetTCPConnection -State Listen -ErrorAction Stop|Where-Object {$_.LocalPort -eq $port}|Select-Object -ExpandProperty OwningProcess -Unique);Write-Output ('PORT_'+$port+'='+$(if($owners.Count){'LISTENING'}else{'FREE'}))}
    foreach($entry in $entries){try{Assert-ContractOwned $entry -RequireListener;Assert-ContractRunningSha $entry $ExpectedSha;$active++;Write-Output ($entry.service.ToUpperInvariant()+'=ACTIVE PID='+$entry.pid+' SOURCE_SHA='+$entry.sourceSha)}catch{$invalid++;Write-Output ($entry.service.ToUpperInvariant()+'=BLOCKED_IDENTITY')}}
    $runningModes=@($entries|ForEach-Object{if($_.PSObject.Properties['semanticMoneyMode']){$_.semanticMoneyMode}else{'STRICT'}}|Sort-Object -Unique)
    if($runningModes.Count -eq 1){Write-Output ('SEMANTIC_MONEY_MODE='+$runningModes[0]+' SOURCE=OWNED_PID_LEDGER_FROZEN_RUN_METADATA_IS_AUTHORITATIVE')}elseif($runningModes.Count){$invalid++;Write-Output 'SEMANTIC_MONEY_MODE=BLOCKED_MIXED_RUNTIME'}
    $dependencies=$true;try{Invoke-ContractDm8}catch{$dependencies=$false;Write-Output 'DM8_OR_WORKER_SCHEMA=BLOCKED'};try{Invoke-ContractRedis}catch{$dependencies=$false;Write-Output 'REDIS=BLOCKED'}
    Write-Output ('SERVICE_STATE='+$(if($active -eq 3 -and -not $invalid -and $dependencies -and $buildValid){'ACTIVE'}elseif(-not $entries.Count){'STOPPED'}else{'BLOCKED'}))
}
function Invoke-ContractStop {
    $entries=@(Get-ContractEntries)
    if(-not $entries.Count){Write-Output 'SERVICE_STATE=STOPPED_NO_LEDGER';return}
    $stopEntries=@()
    foreach($entry in $entries){$stopEntries+=,$entry;if($entry.PSObject.Properties['launcher']){$stopEntries+=,$entry.launcher}}
    foreach($entry in $stopEntries){$observed=Get-ContractObserved ([int]$entry.pid);if($observed){Assert-ContractOwned $entry}}
    [array]::Reverse($entries)
    foreach($entry in $entries){
        $owned=@($entry);if($entry.PSObject.Properties['launcher']){$owned+=,$entry.launcher}
        foreach($record in $owned){$observed=Get-ContractObserved ([int]$record.pid);if($observed){Stop-ContractPinnedProcess $record {param($candidate)Assert-ContractOwned $candidate};Write-Output ($record.service.ToUpperInvariant()+'=STOPPED OWNED_PID='+$record.pid)}}
    }
    $archive=Join-Path $contractRuntime ('pids.stopped.'+[datetime]::UtcNow.ToString('yyyyMMdd-HHmmssfff')+'.json');Move-Item -LiteralPath $contractLedger -Destination $archive
    Write-Output 'SERVICE_STOP=PASS DM8_REDIS_UNTOUCHED'
}

try {
    if($contractRoot -ne $contractCanonical){throw 'CANONICAL_WORKTREE_REQUIRED'}
    Assert-ContractControlledPath $contractRuntime
    if($SemanticBoundaryProofPath){$null=Get-ContractSemanticBoundaryArguments}
    if($Action -in @('Status','Stop')) {
        if($Action -eq 'Status'){Import-ContractEnvironment;Invoke-ContractStatus}else{Invoke-ContractStop}
    } else {
        if([string]::IsNullOrWhiteSpace($ExpectedSha)){throw 'EXPLICIT_SOURCE_SHA_REQUIRED'}
        Assert-ContractGit;Import-ContractEnvironment
        switch($Action){
            'Preflight'{Assert-ContractPython;Invoke-ContractDm8 'StartPreflight';try{Invoke-ContractRedis}catch{if($PortableRedisConfig){Start-ContractPortableRedis}else{throw}};Assert-ContractModel;Write-Output 'PREFLIGHT=PASS'}
            'Build'{Invoke-ContractBuild}
            'Start'{Invoke-ContractStart}
            'InitializeFreshStorage'{Initialize-ContractStorage}
        }
    }
} catch {
    $code=$_.Exception.Message;if($code -notmatch '^[A-Z0-9_]{1,100}$'){$code='UNKNOWN_SUPPRESSED_ERROR'}
    Write-Output ('CONTRACT_TEST=BLOCKED gate='+$code)
    exit 1
}
