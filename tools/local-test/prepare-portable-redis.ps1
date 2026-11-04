[CmdletBinding()]
param([string]$Distribution='Ubuntu-22.04',[string]$PrivateConfigPath)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if($Distribution -cne 'Ubuntu-22.04'){throw 'PORTABLE_REDIS_DISTRIBUTION_NOT_APPROVED'}
$portableRepo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$portableCanonical='D:\code\audit_agent_1D\.worktrees\integration-pre-main-20261010'
if($portableRepo -ne $portableCanonical -or (& git.exe -C $portableRepo branch --show-current) -ne 'integration/pre-main-20261010'){throw 'PORTABLE_REDIS_SOURCE_MISMATCH'}
. (Join-Path $PSScriptRoot 'contract-test-guards.ps1')
$portableRoot=Join-Path $portableRepo '.runtime\contract-repair-test\redis'
$portableVersion='7.4.11'
$portableSha='3c266ece0abd54ed3b1c912c6eb86b7508cf382cb690ee6649d3843f018f6357'
function Assert-PortablePath([string]$Path) {
    $full=[IO.Path]::GetFullPath($Path)
    if(-not $full.StartsWith($portableRepo+'\',[StringComparison]::OrdinalIgnoreCase)){throw 'PORTABLE_REDIS_PATH_OUTSIDE_SOURCE'}
    $scan=$full
    while($scan.Length -ge $portableRepo.Length){
        if(Test-Path -LiteralPath $scan){$item=Get-Item -LiteralPath $scan -Force;if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'PORTABLE_REDIS_REPARSE_POINT'}}
        if($scan -eq $portableRepo){break};$scan=Split-Path $scan
    }
}
function Convert-PortableWslPath([string]$Path) {
    Assert-PortablePath $Path
    $full=[IO.Path]::GetFullPath($Path)
    return '/mnt/'+$full.Substring(0,1).ToLowerInvariant()+$full.Substring(2).Replace('\','/')
}
Assert-PortablePath $portableRoot
if(-not(Test-Path -LiteralPath $portableRoot)){New-Item -ItemType Directory -Path $portableRoot|Out-Null}
$portableOwner=[Security.Principal.WindowsIdentity]::GetCurrent().User
$portableDirectory=[IO.DirectoryInfo]::new($portableRoot)
$portableAcl=$portableDirectory.GetAccessControl([Security.AccessControl.AccessControlSections]::Access)
$portableAcl.SetAccessRuleProtection($true,$false)
foreach($rule in @($portableAcl.GetAccessRules($true,$false,[Security.Principal.SecurityIdentifier]))){
    $portableAcl.RemoveAccessRuleSpecific($rule)
}
foreach($sid in @($portableOwner,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'))){
    $portableAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
}
# Persist only the DACL; no system audit/ownership privilege is required.
$portableDirectory.SetAccessControl($portableAcl)
& git.exe -C $portableRepo check-ignore --quiet -- $portableRoot
if($LASTEXITCODE -ne 0){throw 'PORTABLE_REDIS_NOT_IGNORED'}
$archive=Join-Path $portableRoot ('redis-'+$portableVersion+'.tar.gz')
if(-not(Test-Path -LiteralPath $archive)){
    & curl.exe -fL --retry 2 --connect-timeout 15 --max-time 90 --output $archive ('https://download.redis.io/releases/redis-'+$portableVersion+'.tar.gz')
    if($LASTEXITCODE -ne 0){throw 'PORTABLE_REDIS_DOWNLOAD_FAILED'}
}
if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $portableSha){throw 'PORTABLE_REDIS_DOWNLOAD_SHA_MISMATCH'}
Write-Output 'PORTABLE_REDIS_SOURCE_HASH=PASS'
$source=Join-Path $portableRoot ('redis-'+$portableVersion)
if(-not(Test-Path -LiteralPath $source)){
    $entries=@(& tar.exe -tzf $archive)
    if($LASTEXITCODE -ne 0){throw 'PORTABLE_REDIS_ARCHIVE_INVALID'}
    foreach($entry in $entries){if($entry -notlike ('redis-'+$portableVersion+'/*') -or $entry -match '(^|/)\.\.(/|$)' -or $entry.Contains('\')){throw 'PORTABLE_REDIS_ARCHIVE_PATH_REJECTED'}}
    & tar.exe -xf $archive -C $portableRoot
    if($LASTEXITCODE -ne 0){throw 'PORTABLE_REDIS_ARCHIVE_EXTRACT_FAILED'}
}
$binary=Join-Path $source 'src\redis-server'
if(-not(Test-Path -LiteralPath $binary)){
    $compileLog=Join-Path $portableRoot 'build.log'
    $compilePreference=$ErrorActionPreference
    try {
        # PS5.1 surfaces ordinary compiler stderr as NativeCommandError.
        # The native exit code, not the output stream, determines build success.
        $ErrorActionPreference='Continue'
        & wsl.exe -d $Distribution --exec /usr/bin/make -C (Convert-PortableWslPath $source) -j 2 MALLOC=libc *> $compileLog
        $compileExit=$LASTEXITCODE
    } finally {$ErrorActionPreference=$compilePreference}
    if($compileExit -ne 0){throw 'PORTABLE_REDIS_COMPILE_FAILED_CHECK_PRIVATE_BUILD_LOG'}
}
& wsl.exe -d $Distribution --exec (Convert-PortableWslPath $binary) --version
if($LASTEXITCODE -ne 0){throw 'PORTABLE_REDIS_BINARY_NOT_EXECUTABLE'}
if(-not $PrivateConfigPath){$PrivateConfigPath=Join-Path (Split-Path $portableRepo) 'ai-ocr-project-intake-demo\.runtime\local-demo.env.integration.legacy.ps1'}
$settings=Read-ContractLiteralEnvironment $PrivateConfigPath @('AUDIT_REDIS_PASSWORD') @()
if(-not $settings.ContainsKey('AUDIT_REDIS_PASSWORD')){throw 'PORTABLE_REDIS_PASSWORD_LITERAL_MISSING'}
$password=$settings['AUDIT_REDIS_PASSWORD']
if($password -match '[\r\n\x00]'){throw 'PORTABLE_REDIS_PASSWORD_FORMAT_REJECTED'}
$data=Join-Path $portableRoot 'data';if(-not(Test-Path -LiteralPath $data)){New-Item -ItemType Directory -Path $data|Out-Null}
$configFile=Join-Path $portableRoot 'redis.conf'
$configuration=@('bind 127.0.0.1','port 6379','protected-mode yes','daemonize yes',
    ('dir "'+(Convert-PortableWslPath $data)+'"'),('pidfile "'+(Convert-PortableWslPath (Join-Path $portableRoot 'redis.pid'))+'"'),
    ('logfile "'+(Convert-PortableWslPath (Join-Path $portableRoot 'redis.log'))+'"'),
    'save ""','appendonly no','maxmemory 256mb','maxmemory-policy noeviction')
if($password.Length){$configuration+=('requirepass "'+$password.Replace('\','\\').Replace('"','\"')+'"')}
$configText=($configuration -join "`n")+"`n"
if(Test-Path -LiteralPath $configFile){
    if((Get-Content -LiteralPath $configFile -Raw -Encoding UTF8) -cne $configText){throw 'PORTABLE_REDIS_EXISTING_CONFIG_MISMATCH'}
}else{[IO.File]::WriteAllText($configFile,$configText,[Text.UTF8Encoding]::new($false))}
$receipt=@{format=1;distribution=$Distribution;serverPath=$binary;configPath=$configFile;
    serverSha256=(Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash.ToLowerInvariant();
    configSha256=(Get-FileHash -LiteralPath $configFile -Algorithm SHA256).Hash.ToLowerInvariant()}
$receiptFile=Join-Path $portableRoot 'launch-receipt.json'
[IO.File]::WriteAllText($receiptFile,($receipt|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
Write-Output ('PORTABLE_REDIS_PREPARED=PASS VERSION='+$portableVersion)
Write-Output ('PORTABLE_REDIS_RECEIPT='+$receiptFile)
Write-Output 'PORTABLE_REDIS_NOT_STARTED=TRUE SYSTEM_INSTALL_NOT_PERFORMED=TRUE'
