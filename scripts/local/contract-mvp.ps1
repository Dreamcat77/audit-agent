[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Preflight', 'Build', 'Start', 'Status', 'Stop')]
    [string]$Action,
    [Parameter(Mandatory = $true)]
    [string]$EnvFile
)

$ErrorActionPreference = 'Stop'
$script:CodeRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$script:RuntimeRoot = Join-Path $script:CodeRoot '.runtime\final-closeout\integration'
$script:StorageRoot = Join-Path $script:RuntimeRoot 'storage'
$script:AiTempRoot = Join-Path $script:RuntimeRoot 'ai-temp'
$script:LogRoot = Join-Path $script:RuntimeRoot 'logs'
$script:ReleaseRoot = Join-Path $script:RuntimeRoot 'releases'
$script:PidLedger = Join-Path $script:RuntimeRoot 'pids.json'
$script:ReleaseLedger = Join-Path $script:RuntimeRoot 'current-release.json'
$script:SessionNamespace = 'audit-agent:contract-final-closeout:d743a15'
$script:ExpectedBranch = 'feature/contract-final-closeout-20260923'
$script:ExpectedEnvHash = '1221AA61449609CD2DA399F0103F71292A751CBB72F5A99B7A65CC70008FB3D5'
$script:ExpectedProviderIdentity = 'b771b7f040d2f39faab937adea0f6ddc7f91a0331aa705ebc96b843b1ee02b50'
$script:ApprovedModel = 'audit-qwen-27b-16k:latest'
$script:Python = 'D:\code\audit_agent_1D\.worktrees\ai-ocr-project-intake-demo\.runtime\venvs\audit-ai\Scripts\python.exe'
$script:OriginalsParent = 'D:\code\audit-agent-demo'
$script:DriverJar = Join-Path $env:USERPROFILE '.m2\repository\com\dameng\DmJdbcDriver18\8.1.3.140\DmJdbcDriver18-8.1.3.140.jar'

function Get-Sha256([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash
}

function Resolve-OriginalsRoot {
    if (-not (Test-Path -LiteralPath $script:OriginalsParent -PathType Container)) {
        throw 'Original-material parent directory is missing.'
    }
    $matches = @(Get-ChildItem -LiteralPath $script:OriginalsParent -Directory |
        Where-Object { $_.Name -like '*_20260917' })
    if ($matches.Count -ne 1) { throw 'Expected exactly one approved 20260917 original-material set.' }
    return $matches[0].FullName
}

function Get-TextSha256([string]$Value) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace('-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
}

function Import-ReferenceEnvironment {
    if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
        throw 'Reference environment file is missing.'
    }
    if ((Get-Sha256 $EnvFile) -ne $script:ExpectedEnvHash) {
        throw 'Reference environment identity changed; refusing to continue.'
    }
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        $EnvFile, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -ne 0) { throw 'Reference environment parse failed.' }
    $allowed = @(
        'AUDIT_AI_CONNECT_TIMEOUT_MS', 'AUDIT_AI_MAX_UPLOAD_BYTES',
        'AUDIT_AI_READ_TIMEOUT_MS', 'AUDIT_CORS_ALLOWED_ORIGINS',
        'AUDIT_DB_PASSWORD', 'AUDIT_DB_URL', 'AUDIT_DB_USERNAME',
        'AUDIT_MODEL_API_KEY', 'AUDIT_MODEL_BASE_URL', 'AUDIT_MODEL_CHUNK_CHARS',
        'AUDIT_MODEL_CONNECT_TIMEOUT_SECONDS', 'AUDIT_MODEL_MAX_CHUNKS',
        'AUDIT_MODEL_READ_TIMEOUT_SECONDS', 'AUDIT_MODEL_TOTAL_BUDGET_SECONDS',
        'AUDIT_OCR_CONNECT_TIMEOUT_MS', 'AUDIT_OCR_CPU_THREADS',
        'AUDIT_OCR_ENABLE_MKLDNN', 'AUDIT_OCR_READ_TIMEOUT_MS',
        'AUDIT_REDIS_HOST', 'AUDIT_REDIS_PASSWORD', 'AUDIT_REDIS_PORT',
        'AUDIT_SESSION_COOKIE_SECURE', 'AUDIT_SESSION_TIMEOUT',
        'NO_PROXY', 'PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK'
    )
    $seen = @{}
    $assignments = $ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.AssignmentStatementAst]
    }, $true)
    foreach ($assignment in $assignments) {
        $left = $assignment.Left.Extent.Text
        if ($left -cnotmatch '^\$env:([A-Z0-9_]+)$') { continue }
        $name = $matches[1]
        if ($allowed -notcontains $name) { continue }
        $expression = $assignment.Right.Expression
        if ($expression -isnot [System.Management.Automation.Language.StringConstantExpressionAst]) {
            throw "Reference environment key $name is not a literal string."
        }
        [Environment]::SetEnvironmentVariable($name, $expression.Value, 'Process')
        $seen[$name] = $true
    }
    foreach ($required in @('AUDIT_DB_URL', 'AUDIT_DB_USERNAME', 'AUDIT_DB_PASSWORD',
            'AUDIT_REDIS_HOST', 'AUDIT_REDIS_PORT', 'AUDIT_MODEL_BASE_URL', 'AUDIT_MODEL_API_KEY')) {
        if (-not $seen.ContainsKey($required)) { throw "Reference environment is missing $required." }
    }
    $env:no_proxy = $env:NO_PROXY
    $env:AUDIT_FILE_STORAGE_ROOT = $script:StorageRoot
    $env:AUDIT_AI_TEMP_ROOT = $script:AiTempRoot
    $env:AUDIT_SESSION_REDIS_NAMESPACE = $script:SessionNamespace
    $env:AUDIT_AI_BASE_URL = 'http://127.0.0.1:8001'
    $env:AUDIT_OCR_BASE_URL = 'http://127.0.0.1:8001'
    $env:AUDIT_MODEL_NAME = $script:ApprovedModel
    $env:AUDIT_AI_DOCUMENT_ROLE_ENABLED = 'true'
    $env:AUDIT_AI_CONTRACT_FACT_EXTRACTION_ENABLED = 'true'
    $env:AUDIT_CONTRACT_REVIEW_WORKER_ENABLED = 'true'
    $env:AUDIT_MODEL_DEMO_RELAX_BREACH_MONETARY = 'false'
    $env:AUDIT_TECH_LOG_LEVEL = 'INFO'
    $env:AUDIT_FLOW_DEBUG_LOG_LEVEL = 'INFO'
    $env:AUDIT_OBSERVABILITY_FLOW_DEBUG_ENABLED = 'false'
    $env:AUDIT_VITE_API_PROXY_TARGET = 'http://127.0.0.1:8888'
    $env:PYTHONPATH = Join-Path $script:CodeRoot 'services\audit-ai\src'
}

function Get-Listener([int]$Port) {
    return @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -eq $Port })
}

function Assert-PathsAndTools {
    if ((git -C $script:CodeRoot branch --show-current) -ne $script:ExpectedBranch) {
        throw 'The worktree is on the wrong branch.'
    }
    $originalsRoot = Resolve-OriginalsRoot
    foreach ($path in @($script:CodeRoot, $script:Python, $originalsRoot,
            $script:DriverJar, (Join-Path $script:CodeRoot 'backend\mvnw.cmd'),
            (Join-Path $script:CodeRoot 'frontend\package-lock.json'),
            (Join-Path $script:CodeRoot 'services\audit-ai\src\audit_ai\main.py'))) {
        if (-not (Test-Path -LiteralPath $path)) { throw "Required path is missing: $path" }
    }
    $sets = @(Get-ChildItem -LiteralPath $originalsRoot -Directory |
        Where-Object { $_.Name -match '^(01|02|03|04|05|06)(\D|$)' })
    if ($sets.Count -lt 6) { throw 'Approved original-material set 01-06 is incomplete.' }
    foreach ($command in @('git.exe', 'java.exe', 'node.exe', 'npm.cmd')) {
        if ($null -eq (Get-Command $command -ErrorAction SilentlyContinue)) {
            throw "Required command is missing: $command"
        }
    }
}

function Test-Tcp([string]$HostName, [int]$Port) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(3000, $false)) { return $false }
        $client.EndConnect($pending)
        return $true
    } catch { return $false } finally { $client.Close() }
}

function Invoke-Dm8Preflight {
    $source = Join-Path $script:CodeRoot 'scripts\local\ContractMvpPreflight.java'
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $result = & java.exe --class-path $script:DriverJar $source 2>&1
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    $text = $result -join "`n"
    if ($exitCode -ne 0) { throw "DM8 preflight failed:`n$text" }
    return $text
}

function Assert-ProviderIdentity {
    $normalized = $env:AUDIT_MODEL_BASE_URL.Trim().TrimEnd('/').ToLowerInvariant()
    if ((Get-TextSha256 $normalized) -ne $script:ExpectedProviderIdentity) {
        throw 'Private provider host/port/service identity changed.'
    }
    $headers = @{ Authorization = "Bearer $($env:AUDIT_MODEL_API_KEY)" }
    try {
        $models = Invoke-RestMethod -Method Get -Uri ($env:AUDIT_MODEL_BASE_URL.TrimEnd('/') + '/models') `
            -Headers $headers -TimeoutSec 10
    } catch { throw 'Private provider metadata endpoint is unavailable.' }
    $ids = @($models.data | ForEach-Object { $_.id })
    if ($ids -notcontains $script:ApprovedModel) { throw 'Approved exact model ID is unavailable.' }
    Write-Output 'PROVIDER_IDENTITY=VERIFIED_APPROVED_PRIVATE_ENDPOINT'
    Write-Output "PROVIDER_MODEL=$($script:ApprovedModel)"
}

function Invoke-Preflight {
    Import-ReferenceEnvironment
    Assert-PathsAndTools
    if (-not (Test-Path -LiteralPath $script:StorageRoot -PathType Container)) {
        throw 'Fixed integration storage is missing.'
    }
    foreach ($port in @(5173, 8888, 8001)) {
        $listeners = Get-Listener $port
        if ($listeners.Count -gt 0) {
            Write-Output "PORT_$port=LISTENING"
        } else { Write-Output "PORT_$port=FREE" }
    }
    $redisReachable = Test-Tcp $env:AUDIT_REDIS_HOST ([int]$env:AUDIT_REDIS_PORT)
    Write-Output $(if ($redisReachable) { 'REDIS=REACHABLE' } else { 'REDIS=UNREACHABLE' })
    $dm8 = Invoke-Dm8Preflight
    Write-Output $dm8
    Assert-ProviderIdentity
    Write-Output "BRANCH=$script:ExpectedBranch"
    Write-Output "HEAD=$(git -C $script:CodeRoot rev-parse HEAD)"
    Write-Output "ENV_SHA256=$(Get-Sha256 $EnvFile)"
    Write-Output "STORAGE_ROOT=$script:StorageRoot"
    Write-Output "SESSION_NAMESPACE=$script:SessionNamespace"
    if (-not $redisReachable) { throw 'Configured integration Redis is unreachable.' }
    Write-Output 'PREFLIGHT=PASS'
}

function Get-AggregateHash([string]$Root, [string[]]$Files) {
    $entries = foreach ($file in $Files | Sort-Object) {
        $relative = $file.Substring($Root.TrimEnd('\').Length).TrimStart('\').Replace('\', '/')
        "$relative=$(Get-Sha256 $file)"
    }
    return Get-TextSha256 ($entries -join "`n")
}

function Invoke-Build {
    Import-ReferenceEnvironment
    Assert-PathsAndTools
    Push-Location (Join-Path $script:CodeRoot 'backend')
    try { & .\mvnw.cmd -q clean package -DskipTests } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
    Push-Location (Join-Path $script:CodeRoot 'frontend')
    try {
        if (-not (Test-Path -LiteralPath 'node_modules')) { & npm.cmd ci }
        & npm.cmd run build
    } finally { Pop-Location }
    if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    foreach ($path in @($script:RuntimeRoot, $script:ReleaseRoot, $script:LogRoot,
            $script:StorageRoot, $script:AiTempRoot)) {
        New-Item -ItemType Directory -Force -Path $path | Out-Null
    }
    $head = git -C $script:CodeRoot rev-parse --short=12 HEAD
    $tree = git -C $script:CodeRoot rev-parse 'HEAD^{tree}'
    $trackedDiff = git -C $script:CodeRoot diff --binary HEAD
    $trackedDiffSha256 = Get-TextSha256 ($trackedDiff -join "`n")
    $buildId = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $head + '-' + $trackedDiffSha256.Substring(0, 12)
    $release = Join-Path $script:ReleaseRoot $buildId
    if (Test-Path -LiteralPath $release) { throw 'Release directory already exists.' }
    New-Item -ItemType Directory -Path $release | Out-Null
    $jarSource = Join-Path $script:CodeRoot 'backend\target\audit-backend-0.1.0-SNAPSHOT.jar'
    $jar = Join-Path $release 'audit-backend.jar'
    Copy-Item -LiteralPath $jarSource -Destination $jar
    Copy-Item -LiteralPath (Join-Path $script:CodeRoot 'frontend\dist') `
        -Destination (Join-Path $release 'frontend-dist') -Recurse
    $frontendFiles = @(Get-ChildItem (Join-Path $release 'frontend-dist') -File -Recurse |
        Select-Object -ExpandProperty FullName)
    $fastApiFiles = @(Get-ChildItem (Join-Path $script:CodeRoot 'services\audit-ai\src') `
        -File -Recurse -Filter '*.py' | Select-Object -ExpandProperty FullName)
    $identity = [ordered]@{
        buildId = $buildId
        branch = git -C $script:CodeRoot branch --show-current
        head = git -C $script:CodeRoot rev-parse HEAD
        sourceTree = $tree
        trackedDirty = [bool](git -C $script:CodeRoot diff --name-only HEAD)
        trackedDiffSha256 = $trackedDiffSha256
        jar = $jar
        jarSha256 = Get-Sha256 $jar
        frontendDist = Join-Path $release 'frontend-dist'
        frontendSha256 = Get-AggregateHash (Join-Path $release 'frontend-dist') $frontendFiles
        fastApiSourceSha256 = Get-AggregateHash $script:CodeRoot $fastApiFiles
        createdAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    $identity | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $script:ReleaseLedger -Encoding UTF8
    Write-Output "BUILD_ID=$buildId"
    Write-Output "BACKEND_JAR_SHA256=$($identity.jarSha256)"
    Write-Output "FRONTEND_BUNDLE_SHA256=$($identity.frontendSha256)"
    Write-Output "FASTAPI_SOURCE_SHA256=$($identity.fastApiSourceSha256)"
    Write-Output 'BUILD=PASS'
}

function Wait-Http([string]$Uri, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return }
        } catch { Start-Sleep -Milliseconds 500 }
    } while ((Get-Date) -lt $deadline)
    throw "Service health timeout for $Uri"
}

function Assert-NoListeners {
    foreach ($port in @(5173, 8888, 8001)) {
        if ((Get-Listener $port).Count -ne 0) { throw "Port $port is already listening." }
    }
}

function Resolve-ListenerPid([int]$Port, [int]$LauncherPid) {
    $deadline = (Get-Date).AddSeconds(10)
    do {
        $listeners = @(Get-Listener $Port)
        $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
        if ($owners.Count -eq 1) { break }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    if ($owners.Count -ne 1) { throw "Expected exactly one listener owner on $Port." }
    $owner = [int]$owners[0]
    if ($owner -ne $LauncherPid) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$owner"
        if ($null -eq $process -or $process.ParentProcessId -ne $LauncherPid) {
            throw "Listener $Port is not owned by the started process."
        }
    }
    return $owner
}

function Invoke-Start {
    $dm8 = Invoke-Preflight
    if (($dm8 -join "`n") -notmatch 'CONTRACT_FACT_V2_MIGRATION_STATE=APPLIED') {
        throw 'The approved contract fact v2 migration is not applied.'
    }
    if (($dm8 -join "`n") -notmatch 'CONTRACT_MODEL_CALL_OWNER_UNIQUE_STATE=APPLIED') {
        throw 'The approved model-call owner uniqueness fix is not applied.'
    }
    Assert-NoListeners
    if (-not (Test-Path -LiteralPath $script:ReleaseLedger)) {
        throw 'No built release ledger exists. Run Build first.'
    }
    $release = Get-Content -LiteralPath $script:ReleaseLedger -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not (Test-Path -LiteralPath $release.jar) -or (Get-Sha256 $release.jar) -ne $release.jarSha256) {
        throw 'Built backend release identity is invalid.'
    }
    foreach ($path in @($script:RuntimeRoot, $script:LogRoot, $script:StorageRoot, $script:AiTempRoot)) {
        New-Item -ItemType Directory -Force -Path $path | Out-Null
    }
    if (Test-Path -LiteralPath $script:PidLedger) {
        $archive = Join-Path $script:RuntimeRoot ('pids.stale.' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
        Move-Item -LiteralPath $script:PidLedger -Destination $archive
    }
    $node = (Get-Command node.exe -ErrorAction Stop).Source
    $vite = Join-Path $script:CodeRoot 'frontend\node_modules\vite\bin\vite.js'
    $started = @()
    try {
        $fastApi = Start-Process -FilePath $script:Python -ArgumentList @(
            '-m', 'uvicorn', 'audit_ai.main:app', '--app-dir',
            (Join-Path $script:CodeRoot 'services\audit-ai\src'),
            '--host', '127.0.0.1', '--port', '8001') `
            -WorkingDirectory (Join-Path $script:CodeRoot 'services\audit-ai') -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $script:LogRoot 'fastapi.stdout.log') `
            -RedirectStandardError (Join-Path $script:LogRoot 'fastapi.stderr.log')
        $started += $fastApi
        Wait-Http 'http://127.0.0.1:8001/health' 60
        $fastApiPid = Resolve-ListenerPid 8001 $fastApi.Id

        $backend = Start-Process -FilePath 'java.exe' -ArgumentList @(
            '-jar', $release.jar, '--spring.profiles.active=local-dev,local-ai', '--server.port=8888') `
            -WorkingDirectory (Join-Path $script:CodeRoot 'backend') -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $script:LogRoot 'backend.stdout.log') `
            -RedirectStandardError (Join-Path $script:LogRoot 'backend.stderr.log')
        $started += $backend
        Wait-Http 'http://127.0.0.1:8888/api/v1/health' 120
        $backendPid = Resolve-ListenerPid 8888 $backend.Id

        $frontend = Start-Process -FilePath $node -ArgumentList @(
            $vite, '--host', '127.0.0.1', '--port', '5173', '--strictPort') `
            -WorkingDirectory (Join-Path $script:CodeRoot 'frontend') -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $script:LogRoot 'frontend.stdout.log') `
            -RedirectStandardError (Join-Path $script:LogRoot 'frontend.stderr.log')
        $started += $frontend
        Wait-Http 'http://127.0.0.1:5173/' 60
        $frontendPid = Resolve-ListenerPid 5173 $frontend.Id

        @(
            [ordered]@{ service='FastAPI'; pid=$fastApiPid; launcherPid=$fastApi.Id; port=8001;
                codeRoot=$script:CodeRoot; identity='current-worktree-fastapi-source' },
            [ordered]@{ service='Backend'; pid=$backendPid; launcherPid=$backend.Id; port=8888;
                codeRoot=$script:CodeRoot; identity=$release.jarSha256 },
            [ordered]@{ service='Frontend'; pid=$frontendPid; launcherPid=$frontend.Id; port=5173;
                codeRoot=$script:CodeRoot; identity='current-worktree-vite-source' }
        ) | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $script:PidLedger -Encoding UTF8
        Write-Output 'SERVICE_HEALTH=PASS'
        Write-Output "PID_LEDGER=$script:PidLedger"
    } catch {
        foreach ($process in $started) {
            if ($null -ne (Get-Process -Id $process.Id -ErrorAction SilentlyContinue)) {
                Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
            }
        }
        throw
    }
}

function Assert-ProcessIdentity($Entry) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($Entry.pid)"
    if ($null -eq $process) { throw "$($Entry.service) ledger PID is not running." }
    if ($process.CommandLine -notlike "*$($Entry.codeRoot)*") {
        throw "$($Entry.service) command line does not belong to this worktree."
    }
    $listeners = @(Get-Listener ([int]$Entry.port))
    $owners = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($owners.Count -ne 1 -or [int]$owners[0] -ne [int]$Entry.pid) {
        throw "$($Entry.service) listener identity is inconsistent."
    }
    return $process
}

function Invoke-Status {
    if (-not (Test-Path -LiteralPath $script:PidLedger)) {
        Write-Output 'SERVICE_STATE=STOPPED_NO_LEDGER'
        return
    }
    $parsedLedger = Get-Content -LiteralPath $script:PidLedger -Raw -Encoding UTF8 | ConvertFrom-Json
    $ledger = @()
    foreach ($item in $parsedLedger) { $ledger += $item }
    foreach ($entry in $ledger) {
        try {
            $process = Assert-ProcessIdentity $entry
            Write-Output "$($entry.service.ToUpperInvariant())=RUNNING pid=$($entry.pid) port=$($entry.port) started=$($process.CreationDate)"
        } catch { Write-Output "$($entry.service.ToUpperInvariant())=NOT_VERIFIED" }
    }
    foreach ($uri in @('http://127.0.0.1:8001/health',
            'http://127.0.0.1:8888/api/v1/health', 'http://127.0.0.1:5173/')) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 3
            Write-Output "HEALTH_$($uri.Split(':')[-1].Split('/')[0])=$($response.StatusCode)"
        } catch { Write-Output "HEALTH_$($uri.Split(':')[-1].Split('/')[0])=FAILED" }
    }
    if (Test-Path -LiteralPath $script:ReleaseLedger) {
        $release = Get-Content -LiteralPath $script:ReleaseLedger -Raw -Encoding UTF8 | ConvertFrom-Json
        Write-Output "BUILD_ID=$($release.buildId)"
        Write-Output "BACKEND_JAR_SHA256=$($release.jarSha256)"
        Write-Output "FRONTEND_BUNDLE_SHA256=$($release.frontendSha256)"
        Write-Output "FASTAPI_SOURCE_SHA256=$($release.fastApiSourceSha256)"
    }
    Write-Output "SESSION_NAMESPACE=$script:SessionNamespace"
    Write-Output "STORAGE_ROOT=$script:StorageRoot"
}

function Invoke-Stop {
    Import-ReferenceEnvironment
    if (-not (Test-Path -LiteralPath $script:PidLedger)) {
        Write-Output 'SERVICE_STOP=NO_LEDGER'
        return
    }
    $dm8 = Invoke-Dm8Preflight
    if ($dm8 -match 'running=[1-9]' -or $dm8 -match 'validLease=[1-9]' -or
            $dm8 -match 'factRecent=[1-9]') {
        throw 'Active durable work exists; refusing to stop services.'
    }
    $parsedLedger = Get-Content -LiteralPath $script:PidLedger -Raw -Encoding UTF8 | ConvertFrom-Json
    $ledger = @()
    foreach ($item in $parsedLedger) { $ledger += $item }
    $verified = @{}
    foreach ($entry in $ledger) {
        Assert-ProcessIdentity $entry | Out-Null
        $verified[$entry.service] = $entry
    }
    foreach ($name in @('Frontend', 'Backend', 'FastAPI')) {
        $entry = $verified[$name]
        if ($null -eq $entry) { continue }
        $target = Get-Process -Id ([int]$entry.pid) -ErrorAction Stop
        Stop-Process -InputObject $target
        if (-not $target.WaitForExit(20000)) {
            throw "$name did not stop within the safe timeout."
        }
        if ([int]$entry.launcherPid -ne [int]$entry.pid) {
            $launcher = Get-CimInstance Win32_Process -Filter "ProcessId=$($entry.launcherPid)" `
                -ErrorAction SilentlyContinue
            if ($null -ne $launcher) {
                if ($launcher.CommandLine -notlike "*$($entry.codeRoot)*") {
                    throw "$name launcher no longer belongs to this worktree."
                }
                Stop-Process -Id ([int]$entry.launcherPid)
            }
        }
        Write-Output "$($name.ToUpperInvariant())=STOPPED"
    }
    foreach ($port in @(5173, 8888, 8001)) {
        if ((Get-Listener $port).Count -ne 0) { throw "Port $port is still listening." }
        Write-Output "PORT_$port=FREE"
    }
    $archive = Join-Path $script:RuntimeRoot ('pids.stopped.' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.json')
    Move-Item -LiteralPath $script:PidLedger -Destination $archive
    Write-Output 'SERVICE_STOP=PASS'
}

switch ($Action) {
    'Preflight' { Invoke-Preflight }
    'Build' { Invoke-Build }
    'Start' { Invoke-Start }
    'Status' { Invoke-Status }
    'Stop' { Invoke-Stop }
}
