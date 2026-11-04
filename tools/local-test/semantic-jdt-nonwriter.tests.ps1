$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'contract-test-guards.ps1')
$testRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
foreach($source in @('maintain-word97-local.ps1','maintain-semantic-checkpoints-local.ps1')){
    $tokens=$null;$errors=$null;$tree=[Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $source),[ref]$tokens,[ref]$errors)
    if($errors.Count){throw 'JDT_OFFLINE_PARSE_FAILED'}
    foreach($definition in $tree.FindAll({param($node)$node -is [Management.Automation.Language.FunctionDefinitionAst]},$true)){Invoke-Expression $definition.Extent.Text}
}
function Assert-JdtTest([bool]$Value){if(-not $Value){throw 'JDT_EXPECTED_BEHAVIOR_ABSENT'}}
function Assert-JdtReject([scriptblock]$Operation){$rejected=$false;try{& $Operation}catch{$rejected=$true};if(-not $rejected){throw 'JDT_UNPROVEN_PROCESS_ACCEPTED'}}
$extensionBase=Join-Path $env:USERPROFILE '.vscode\extensions'
$extension=Join-Path $extensionBase 'redhat.java-1.50.0-win32-x64'
$java=Join-Path $extension 'jre\21.0.8-win32-x86_64\bin\java.exe'
$jar=Join-Path $extension 'server\plugins\org.eclipse.equinox.launcher_1.7.0.v20250519.jar'
$command='"'+$java+'" -Declipse.application=org.eclipse.jdt.ls.core.id1 -Declipse.product=org.eclipse.jdt.ls.core.product -jar "'+$jar+'" -configuration D:\synthetic-config -data D:\synthetic-workspace'
$child=[pscustomobject]@{Name='java.exe';ProcessId=15996;ParentProcessId=2776;CreationDate=[datetime]'2026-10-16T00:01:00Z';ExecutablePath=$java;CommandLine=$command}
$parent=[pscustomobject]@{Name='Code.exe';ProcessId=2776;ParentProcessId=1000;CreationDate=[datetime]'2026-10-16T00:00:00Z';ExecutablePath='D:\synthetic-editor\Code.exe';CommandLine='"D:\synthetic-editor\Code.exe"'}
$files=[pscustomobject]@{executableSha256=('a'*64);launcherJarSha256=('b'*64);parentExecutableSha256=('c'*64);pathsNoReparse=$true}
$signature=[pscustomobject]@{Status='Valid';Publisher='Microsoft Corporation';Thumbprint=('d'*40);Path=$parent.ExecutablePath}
$script:jdtTests=0
function Test-Jdt([string]$Name,[scriptblock]$Body){& $Body;$script:jdtTests++;Write-Output ('PASS '+$Name)}
function Copy-JdtObject($Value){$Value | ConvertTo-Json -Depth 4 | ConvertFrom-Json}
Test-Jdt 'single exact JDT process can be classified without opening a socket or stopping it' {
    $script:proof=New-SemanticJdtNonwriterProof $child $parent @() $extensionBase $files $signature
    Assert-JdtTest ($proof.format -eq 'SEMANTIC_JDT_NONWRITER_V1')
    Assert-JdtTest ($proof.commandHash -eq (Get-ContractTextHash $command))
    Assert-SemanticStoppedState $testRoot @($child,$parent) @() @() @($proof)
}
Test-Jdt 'Windows argv0 may omit only the exact CIM executable exe suffix' {
    $withoutSuffix=$java.Substring(0,$java.Length-4)
    $mutated=Copy-JdtObject $child;$mutated.CreationDate=$child.CreationDate
    $mutated.CommandLine=$command.Replace(('"'+$java+'"'),('"'+$withoutSuffix+'"'))
    $observed=New-SemanticJdtNonwriterProof $mutated $parent @() $extensionBase $files $signature
    Assert-JdtTest ($observed.executable -ceq $java -and $observed.executableSha256 -ceq $files.executableSha256)
    foreach($wrong in @('java','D:\other-extension\jre\bin\java',($withoutSuffix+'-fake'),($java+'.exe'),('..\'+[IO.Path]::GetFileName($withoutSuffix)))){
        $changed=Copy-JdtObject $child;$changed.CreationDate=$child.CreationDate
        $changed.CommandLine=$command.Replace(('"'+$java+'"'),('"'+$wrong+'"'))
        Assert-JdtReject {New-SemanticJdtNonwriterProof $changed $parent @() $extensionBase $files $signature}
    }
}
Test-Jdt 'only absolute drive-letter aliases normalize while directory casing remains exact' {
    $lowJava=$java.Substring(0,1).ToLowerInvariant()+$java.Substring(1)
    $lowJar=$jar.Substring(0,1).ToLowerInvariant()+$jar.Substring(1)
    $mutated=Copy-JdtObject $child;$mutated.CreationDate=$child.CreationDate
    $mutated.CommandLine=$command.Replace($java,$lowJava.Substring(0,$lowJava.Length-4)).Replace($jar,$lowJar)
    $observed=New-SemanticJdtNonwriterProof $mutated $parent @() $extensionBase $files $signature
    Assert-JdtTest ($observed.executable -ceq $java -and $observed.launcherJar -ceq $jar)
    foreach($bad in @($command.Replace($jar,$jar.Replace('.vscode','.VSCODE')),$command.Replace($java,$java.Replace('.vscode','.VSCODE')))){
        $changed=Copy-JdtObject $child;$changed.CreationDate=$child.CreationDate;$changed.CommandLine=$bad
        Assert-JdtReject {New-SemanticJdtNonwriterProof $changed $parent @() $extensionBase $files $signature}
    }
}
Test-Jdt 'spoofed marker launcher extension JRE or hidden argfile is never an exception' {
    foreach($changed in @($command.Replace('org.eclipse.jdt.ls.core.id1','org.eclipse.jdt.ls.core.id1.fake'),$command.Replace('org.eclipse.jdt.ls.core.product','evil.product'),$command.Replace('redhat.java-1.50.0-win32-x64','fake.java-1.50.0'),($command+' @D:\synthetic\launch.args'),($command+' --module=audit/Main'),($command+' -Declipse.application=org.eclipse.jdt.ls.core.id1'))){
        $mutated=Copy-JdtObject $child;$mutated.CreationDate=$child.CreationDate;$mutated.CommandLine=$changed
        Assert-JdtReject {New-SemanticJdtNonwriterProof $mutated $parent @() $extensionBase $files $signature}
    }
    $mutated=Copy-JdtObject $child;$mutated.CreationDate=$child.CreationDate;$mutated.ExecutablePath='D:\synthetic-jdk\bin\java.exe'
    Assert-JdtReject {New-SemanticJdtNonwriterProof $mutated $parent @() $extensionBase $files $signature}
}
Test-Jdt 'PID reuse creation executable command or parent drift fails against approved proof' {
    foreach($field in @('ProcessId','CreationDate','ExecutablePath','CommandLine','ParentProcessId')){
        $mutated=Copy-JdtObject $child;$mutated.CreationDate=$child.CreationDate
        if($field -eq 'ProcessId'){$mutated.ProcessId=15997}elseif($field -eq 'CreationDate'){$mutated.CreationDate=$child.CreationDate.AddSeconds(1)}elseif($field -eq 'ParentProcessId'){$mutated.ParentProcessId=2777}else{$mutated.$field='D:\synthetic\changed'}
        Assert-JdtReject {Assert-SemanticJdtProcessIdentity $mutated $proof}
    }
}
Test-Jdt 'changed executable JAR parent hash or parent creation cannot match frozen proof' {
    foreach($field in @('executableSha256','launcherJarSha256','parentExecutableSha256')){
        $changed=Copy-JdtObject $files;$changed.$field=('e'*64)
        $fresh=New-SemanticJdtNonwriterProof $child $parent @() $extensionBase $changed $signature
        Assert-JdtTest ((Get-SemanticJdtProofKey $fresh) -cne (Get-SemanticJdtProofKey $proof))
    }
    $changed=Copy-JdtObject $parent;$changed.CreationDate=$parent.CreationDate.AddSeconds(1)
    $fresh=New-SemanticJdtNonwriterProof $child $changed @() $extensionBase $files $signature
    Assert-JdtTest ((Get-SemanticJdtProofKey $fresh) -cne (Get-SemanticJdtProofKey $proof))
}
Test-Jdt 'wrong unsigned Microsoft-spoofed or reused parent and unknown file proof are rejected' {
    foreach($field in @('Status','Publisher','Path','Thumbprint')){
        $changed=Copy-JdtObject $signature;$changed.$field='UNPROVEN'
        Assert-JdtReject {New-SemanticJdtNonwriterProof $child $parent @() $extensionBase $files $changed}
    }
    foreach($field in @('ProcessId','Name','CreationDate')){
        $changed=Copy-JdtObject $parent;$changed.CreationDate=$parent.CreationDate
        if($field -eq 'ProcessId'){$changed.ProcessId=2777}elseif($field -eq 'Name'){$changed.Name='fake.exe'}else{$changed.CreationDate=$child.CreationDate.AddSeconds(1)}
        Assert-JdtReject {New-SemanticJdtNonwriterProof $child $changed @() $extensionBase $files $signature}
    }
    $changed=Copy-JdtObject $files;$changed.pathsNoReparse=$false
    Assert-JdtReject {New-SemanticJdtNonwriterProof $child $parent @() $extensionBase $changed $signature}
}
Test-Jdt 'all owned TCP states prevent JDT nonwriter proof including listener and established' {
    foreach($state in @('Established','Listen','Bound','SynSent','CloseWait')){
        Assert-JdtReject {New-SemanticJdtNonwriterProof $child $parent @([pscustomobject]@{OwningProcess=15996;State=$state}) $extensionBase $files $signature}
    }
}
Test-Jdt 'proof is single exact identity not a generic JAR or application whitelist' {
    $backend=[pscustomobject]@{Name='java.exe';ProcessId=9999;CommandLine='java.exe -jar D:\synthetic\audit-backend.jar'}
    Assert-JdtReject {Assert-SemanticStoppedState $testRoot @($child,$parent,$backend) @() @() @($proof)}
    Assert-JdtReject {Assert-SemanticStoppedState $testRoot @($child,$parent) @() @() @()}
    Assert-JdtReject {Assert-SemanticStoppedState $testRoot @($child,$parent) @() @([pscustomobject]@{pid=15996}) @($proof)}
    $changed=Copy-JdtObject $proof;$changed | Add-Member NoteProperty arbitraryAllowlist 'unsafe'
    Assert-JdtReject {Get-SemanticJdtProofKey $changed}
}
Test-Jdt 'frozen approval hash rejects replaced deleted or unbound proof before the next phase' {
    $fixture=Join-Path $testRoot ('.runtime\contract-repair-test\closeout-20261016\p6-jdt-fixture-'+[guid]::NewGuid().ToString('N'))
    $area=Join-Path $fixture '.runtime\contract-repair-test\semantic-maintenance'
    $snapshot=Join-Path $area 'snapshot';$null=New-Item -ItemType Directory -Path $snapshot -Force
    $proofFile=Join-Path $area 'jdt-nonwriter-proof.json';$stopFile=Join-Path $snapshot 'writers-stopped.properties'
    $semanticRoot=$fixture;$word97Root=$testRoot;$BackupDirectory=$snapshot
    # ACL enforcement is tested by existing real-platform maintenance gates;
    # this test isolates actual file hash binding, without changing host ACLs.
    function Assert-Word97RestrictedDirectory([string]$Path){}
    try {
        [IO.File]::WriteAllText($proofFile,($proof|ConvertTo-Json -Depth 4),[Text.UTF8Encoding]::new($false))
        $hash=(Get-FileHash -LiteralPath $proofFile -Algorithm SHA256).Hash.ToLowerInvariant()
        [IO.File]::WriteAllText($stopFile,('jdt.proof.sha256='+$hash),[Text.UTF8Encoding]::new($false))
        $read=Get-SemanticApprovedJdtProof
        Assert-JdtTest ((Get-SemanticJdtProofKey $read) -ceq (Get-SemanticJdtProofKey $proof))
        [IO.File]::AppendAllText($proofFile,"`n")
        Assert-JdtReject {Get-SemanticApprovedJdtProof}
        Remove-Item -LiteralPath $proofFile
        Assert-JdtReject {Get-SemanticApprovedJdtProof}
        [IO.File]::WriteAllText($stopFile,'format=old-proof',[Text.UTF8Encoding]::new($false))
        Assert-JdtReject {Get-SemanticApprovedJdtProof}
    } finally {
        foreach($file in @($proofFile,$stopFile)){if(Test-Path -LiteralPath $file){Remove-Item -LiteralPath $file}}
        foreach($directory in @($snapshot,$area,(Split-Path $area),(Split-Path (Split-Path $area)),$fixture)){Remove-Item -LiteralPath $directory}
    }
}
Test-Jdt 'live producer rechecks creation file hashes and full TCP observation instead of trusting input proof' {
    # Synthetic boundaries replace only external read operations. No real user
    # argfile, CIM, TCP, extension file, certificate or process is accessed.
    function Assert-SemanticJdtRegularFile([string]$Path){}
    $script:jdtCase='stable';$script:jdtHashReads=0
    function Get-FileHash([string]$LiteralPath,[string]$Algorithm){
        $script:jdtHashReads++
        $hash=if($LiteralPath -ceq $java){'a'*64}elseif($LiteralPath -ceq $jar){'b'*64}elseif($LiteralPath -ceq $parent.ExecutablePath){'c'*64}else{throw 'UNEXPECTED_SYNTHETIC_HASH_PATH'}
        if($script:jdtCase -eq 'hash' -and $script:jdtHashReads -gt 3){$hash='e'*64}
        [pscustomobject]@{Hash=$hash}
    }
    function Get-AuthenticodeSignature([string]$LiteralPath){
        $certificate=[pscustomobject]@{Thumbprint=('d'*40)}
        $certificate|Add-Member ScriptMethod GetNameInfo {param($kind,$issuer)return 'Microsoft Corporation'}
        [pscustomobject]@{Status='Valid';Path=$LiteralPath;SignerCertificate=$certificate}
    }
    function Get-CimInstance([string]$ClassName,[string]$Filter){
        if($Filter -eq 'ProcessId=2776'){return $parent}
        if($Filter -ne 'ProcessId=15996'){throw 'UNEXPECTED_SYNTHETIC_PID'}
        if($script:jdtCase -eq 'creation'){$changed=Copy-JdtObject $child;$changed.CreationDate=$child.CreationDate.AddSeconds(1);return $changed}
        return $child
    }
    function Get-NetTCPConnection {
        if($script:jdtCase -eq 'tcp-unknown'){throw 'SYNTHETIC_TCP_QUERY_FAILED'}
        if($script:jdtCase -eq 'tcp-active'){return [pscustomobject]@{OwningProcess=15996;State='Established'}}
        return @()
    }
    $fresh=Get-SemanticJdtNonwriterProof $child @($child,$parent) @()
    Assert-JdtTest ((Get-SemanticJdtProofKey $fresh) -ceq (Get-SemanticJdtProofKey $proof))
    foreach($case in @('hash','creation','tcp-unknown','tcp-active')){
        $script:jdtCase=$case;$script:jdtHashReads=0
        Assert-JdtReject {Get-SemanticJdtNonwriterProof $child @($child,$parent) @()}
    }
}
Write-Output ('SEMANTIC_JDT_OFFLINE=PASS TESTS='+$script:jdtTests+' REAL_CIM=0 REAL_TCP=0 REAL_EXTENSION_HASH_READS=0 SYNTHETIC_HASH_BINDING_TESTED=true PROCESSES_STOPPED=0')
