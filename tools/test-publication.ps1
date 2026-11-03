$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
function Assert($condition, $message) {
    if (!$condition) { throw $message }
}
foreach ($file in (Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -Force)) {
    $tokens = $null
    $errors = $null
    [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$errors) | Out-Null
    Assert (!$errors) "PowerShell syntax failed: $($file.Name)"
}
$inventory = (& (Join-Path $PSScriptRoot 'export-publication.ps1') | ConvertFrom-Json)
Assert ($inventory.files.Count -gt 100) 'Snapshot unexpectedly omitted project sources'
Assert ('gradle/wrapper/gradle-wrapper.jar' -in $inventory.files.path) 'Wrapper missing'
Assert ('.github/workflows/build-release.yml' -in $inventory.files.path) 'CI workflow missing'
if (Test-Path (Join-Path $root 'app/build.gradle')) {
    Assert ('app/src/main/java/dev/screencopy/sender/Secrets.kt' -in $inventory.files.path) 'Privacy helper source must not be confused with credentials'
}
foreach ($entry in $inventory.files) {
    Assert ($entry.sha256 -cmatch '^[0-9a-f]{64}$') 'Inventory hash format changed'
    Assert ($entry.path -notmatch '(^|/)(\.git|build|\.gradle|\.idea|\.vscode|\.copilot|node_modules)(/|$)|local\.properties$|\.(apk|jks|keystore)$') 'Forbidden snapshot path'
}
$workflow = Get-Content -LiteralPath (Join-Path $root '.github/workflows/build-release.yml') -Raw
Assert ($workflow.Contains('-ApkAssetName ScrcpyReceive.apk')) 'Receiver manifest generation must use the renamed APK'
Assert ($workflow.Contains('gh release upload "$RELEASE_TAG" publication-output/ScrcpyReceive.apk publication-output/update.json --clobber')) 'Receiver publication must upload only the renamed APK and updater manifest'
$mainActivity = Get-Content -LiteralPath (Join-Path $root 'app/src/main/java/org/screencopy/receiver/MainActivity.kt') -Raw
Assert ($mainActivity.Contains('UpdateController.get(this, "ScrcpyReceive", "ScrcpyReceive.apk")')) 'Receiver updater must use the renamed repository and APK'
Assert ($workflow.Contains("if: github.repository == 'arisewanggithub/ScrcpyReceive'") -and
    $workflow.Contains('name: ScrcpyReceive-pr-validation-')) 'Release gating and proof artifacts must use the renamed repository'
& (Join-Path $PSScriptRoot 'pr-validation.ps1') -Mode SelfTest
foreach ($job in @('checks', 'update-ui', 'optimized-native-smoke', 'record-validation')) {
    Assert ($workflow -match "(?m)^  ${job}:\r?\n    (?:name:[^\r\n]*\r?\n    )?if: github.event_name == 'pull_request'") 'Full validation and proof generation must run only on PRs'
}
Assert ($workflow -match 'retention-days: 90' -and $workflow -match 'pr-validation.ps1 -Mode Record') 'Successful PR source proof must be retained'
Assert ($workflow -match 'fetch-depth: 0' -and $workflow -match 'pr-validation.ps1 -Mode Locate' -and $workflow -match 'pr-validation.ps1 -Mode Verify') 'Release must verify a merged-main PR and identical tested tree'
Assert ($workflow -match 'artifact-ids: \$\{\{ steps.validation.outputs.artifact_id \}\}' -and $workflow -match 'actions: read') 'Proof download must select the verified artifact with read-only permissions'
Assert ($workflow -match 'pull-requests: read') 'Release validation must read the associated merged PR without write permissions'
$appBuild = Get-Content -LiteralPath (Join-Path $root 'app/build.gradle.kts') -Raw
$releaseBlock = [regex]::Match($appBuild, '(?s)buildTypes\s*\{\s*getByName\("release"\)\s*\{(?<body>.*?)\}')
Assert $releaseBlock.Success 'Explicit release build type missing'
Assert ($releaseBlock.Groups['body'].Value -match 'isMinifyEnabled = true' -and $releaseBlock.Groups['body'].Value -match 'isShrinkResources = true') 'Release must enable R8 and resource shrinking'
Assert ($releaseBlock.Groups['body'].Value -match 'getDefaultProguardFile\("proguard-android-optimize.txt"\)' -and $releaseBlock.Groups['body'].Value -match '"proguard-rules.pro"') 'Release must register optimized defaults and custom rules'
$rules = Get-Content -LiteralPath (Join-Path $root 'app/proguard-rules.pro') -Raw
foreach ($type in @('org.screencopy.receiver.media.SrtNative', 'org.screencopy.receiver.media.OpusDecoder')) {
    Assert ($rules.Contains("class $type {")) "JNI declaring class missing: $type"
}
Assert ([regex]::Matches($rules, 'native <methods>;').Count -eq 2) 'Both JNI bindings must retain native methods'
Assert ($rules -notmatch '(?m)^-(dontwarn|dontobfuscate|dontoptimize)\b|(?m)^-keep[^\r\n]*class [^\r\n]*\*') 'Custom rules must not blanket-keep packages or suppress R8 failures'
$mainManifest = Get-Content (Join-Path $root 'app/src/main/AndroidManifest.xml') -Raw
$debugManifest = Get-Content (Join-Path $root 'app/src/debug/AndroidManifest.xml') -Raw
Assert ($mainManifest -notmatch 'UpdateTestActivity' -and $debugManifest -match 'UpdateTestActivity') 'Updater test Activity must remain debug-only'
Assert ($workflow -match ':app:assembleRelease -PsrtTrial=\$\{\{ matrix.srt \}\} -PrequireReleaseSigning=false') 'Both profiles need unsigned optimized release compilation before publication'
Assert ($workflow -match 'path: app/build/outputs/mapping/release/mapping.txt') 'Exact signed-release mapping must be retained outside public release assets'
Assert ($appBuild -match 'gradleProperty\("releaseSmokeTest"\).map \{ it.toBooleanStrict\(\) \}' -and $appBuild -match '!releaseSmokeTest \|\| \(!requireReleaseSigning && !productionSigningConfigured\)') 'Strict smoke/publication signing exclusion missing'
Assert ($appBuild -match 'signingConfig = signingConfigs.getByName\("debug"\)') 'Explicit release test signing configuration missing'
Assert ($releaseBlock.Groups['body'].Value -match 'isDebuggable = false') 'Optimized release must not become debuggable'
Assert ($appBuild -notmatch 'testBuildType|prepareReleaseSmokeSources|src/releaseSmokeTest|testProguardFiles') 'App AndroidTest must remain the ordinary debug cohort'
$module = Get-Content (Join-Path $root 'release-check/build.gradle.kts') -Raw
$targetResourceCheck = Get-Content (Join-Path $root 'release-check/src/main/java/dev/scrcpy/releasecheck/TargetResourcesCheck.kt') -Raw
Assert ($targetResourceCheck.Contains('assertEquals("ScrcpyReceive", app.context.getText(label).toString())')) 'Optimized resource smoke check must expect the renamed app label'
Assert ($module -match 'id\("com.android.test"\)' -and $module -match 'self-instrumenting"\] = true') 'Standalone self-instrumenting harness required'
Assert ($module -match 'targetProjectPath = ":app"' -and $module -notmatch '(implementation|api|compileOnly)\(project') 'Harness must consume only the target APK, never app code'
Assert ($module -match 'isMinifyEnabled = true' -and $module -match 'isDebuggable = false') 'Harness must independently optimize its own runtime'
Assert ($module -match 'testProguardFiles\(getDefaultProguardFile\("proguard-android-optimize.txt"\), "proguard-rules.pro"\)') 'Standalone TestCreationConfig must register optimize defaults/custom metadata and entry rules through testProguardFiles'
Assert ($module -notmatch '(?m)^\s+proguardFiles\b') 'Ordinary proguardFiles are ignored by standalone test R8'
Assert ($module -match 'implementation\("com.google.errorprone:error_prone_annotations:2.41.0"\)') 'Harness annotation pin missing'
Assert ($appBuild -notmatch '(?m)^\s*(implementation|api|compileOnly)\("com.google.errorprone:') 'Error Prone annotation fix must remain test-only'
$testRules = Get-Content (Join-Path $root 'release-check/proguard-rules.pro') -Raw
$testWarnings = @([regex]::Matches($testRules, '(?m)^-dontwarn ([^\r\n]+)') | ForEach-Object { $_.Groups[1].Value.Trim() })
Assert (($testWarnings -join ',') -ceq 'javax.lang.model.element.Modifier') 'Only Error Prone compiler metadata may suppress this exact missing test-only enum'
Assert ($rules -notmatch 'javax.lang.model') 'Compiler model suppression must never affect production R8'
Assert ($testRules -match '(?m)^-repackageclasses dev.scrcpy.releasecheck.internal' -and $rules -notmatch 'keep[^\r\n]*kotlin') 'Harness runtime must be isolated without production Kotlin keeps'
Assert ($workflow -match '-PrequireReleaseSigning=true -PreleaseSmokeTest=false') 'Publisher must explicitly disable smoke signing'
$smokeScript = Get-Content (Join-Path $PSScriptRoot 'run-release-smoke.sh') -Raw
Assert ($smokeScript -match 'NativeSrtCheck,dev.scrcpy.releasecheck.NativeOpusCheck,dev.scrcpy.releasecheck.TargetResourcesCheck' -and $smokeScript -notmatch 'AppUpdateRemoteUiTest|connectedAndroidTest') 'Release smoke must select only standalone checks'
Assert ($smokeScript -match 'bash tools/check-instrumentation-result.sh' -and $smokeScript -match 'expected=6') 'Smoke must use the tested result checker and exact count'
$resultChecker = Get-Content (Join-Path $PSScriptRoot 'check-instrumentation-result.sh') -Raw
Assert ($resultChecker.Contains('^INSTRUMENTATION_STATUS_CODE: 0$') -and $resultChecker.Contains('^INSTRUMENTATION_CODE: -1$') -and $resultChecker -match 'INSTRUMENTATION_STATUS_CODE: -\[234\]') 'Per-test success is 0; final success is -1; failures/skips must remain rejected'
$parserBash = if ($env:OS -eq 'Windows_NT') {
    Join-Path (Split-Path (Split-Path (Get-Command git).Source -Parent) -Parent) 'bin/bash.exe'
} else { (Get-Command bash).Source }
& $parserBash --noprofile --norc (Join-Path $PSScriptRoot 'test-run-release-results.sh').Replace('\', '/')
Assert ($LASTEXITCODE -eq 0) 'Instrumentation parser regression fixtures failed'
& $parserBash --noprofile --norc (Join-Path $PSScriptRoot 'test-ci-avd-userdata.sh').Replace('\', '/')
Assert ($LASTEXITCODE -eq 0) 'CI AVD userdata policy fixtures failed'
Assert ($workflow -match 'bash tools/set-ci-avd-userdata.sh "\$ANDROID_AVD_HOME/update-ui.avd/config.ini"') 'Updater UI AVD must also receive the bounded userdata config'
Assert ($workflow.IndexOf('create avd --force --name update-ui') -lt $workflow.IndexOf('bash tools/set-ci-avd-userdata.sh "$ANDROID_AVD_HOME/update-ui.avd/config.ini"')) 'Updater userdata config must follow its AVD creation'
Assert ($smokeScript.IndexOf('create avd') -lt $smokeScript.IndexOf('bash tools/set-ci-avd-userdata.sh') -and $smokeScript.IndexOf('bash tools/set-ci-avd-userdata.sh') -lt $smokeScript.IndexOf('"$ANDROID_HOME/emulator/emulator" -avd')) 'CI userdata sizing must precede emulator launch and follow AVD creation'
$nativeCheck = Get-Content (Join-Path $root 'release-check/src/main/java/dev/scrcpy/releasecheck/NativeSrtCheck.kt') -Raw
Assert ($nativeCheck -match 'does not match the accepted control session' -and $nativeCheck -match 'has not connected' -and $nativeCheck -notmatch 'assertThrows\(ExecutionException') 'Wrong-SID proof must validate listener rejection/unusable I/O, not race-dependent caller failure'
$smokeTestCount = 0
foreach ($file in (Get-ChildItem (Join-Path $root 'release-check/src/main') -Recurse -File -Filter '*.kt')) {
    $content = Get-Content $file.FullName -Raw
    Assert ($content -notmatch 'import (dev\.screencopy\.sender|org\.screencopy\.receiver|org\.conscrypt|org\.bouncycastle|io\.github\.muntashirakon)') 'Harness must reflect target code, never compile app implementation'
    $smokeTestCount += [regex]::Matches($content, '@Test\s+fun\s+').Count
}
Assert ($smokeTestCount -eq 6) 'Standalone Receiver cohort must have exactly six tests'
$runner = Get-Content (Join-Path $root 'release-check/src/main/java/dev/scrcpy/releasecheck/ReleaseCheckRunner.kt') -Raw
$target = Get-Content (Join-Path $root 'release-check/src/main/java/dev/scrcpy/releasecheck/TargetApp.kt') -Raw
Assert ($runner -match 'releaseSmoke' -and $runner -match 'Build.VERSION.SDK_INT == 35' -and $target -match 'FLAG_DEBUGGABLE' -and $target -match 'loader !== harness.classLoader') 'Independent target loader and emulator/non-debuggable guards required'
& (Join-Path $PSScriptRoot 'verify-release-smoke-apks.ps1') -SelfTest
$smokeJob = [regex]::Match($workflow, '(?ms)^  optimized-native-smoke:.*?(?=^  publish:)').Value
Assert ($smokeJob -match 'releaseSmokeTest=true' -and $smokeJob -notmatch 'secrets\.|publication\.keystore|release upload') 'Smoke job must not use production signing or publication'
Assert ($smokeJob -match ':release-check:assembleRelease' -and $smokeJob -notmatch ':app:assembleReleaseAndroidTest') 'Optimized CI must build the standalone APK, not the retired shared-runtime test APK'
Assert ($workflow -notmatch 'pull_request_target|connectedAndroidTest|adb connect') 'Unsafe CI trigger/device test'
Assert ($workflow -match "srt: \['false', 'true'\]") 'Both transport build modes must be checked'
Assert ($workflow -match 'requireReleaseSigning=true') 'Publication signing guard missing'
Assert ($workflow -match '-e class dev\.scrcpy\.update\.AppUpdateRemoteUiTest ') 'Only the agreed remote UI instrumentation class may run'
Assert ($workflow -match '(?m)^  publish:\r?\n    needs: \[verify-validation\]') 'Release must depend on verified PR evidence, not repeat PR jobs'
Assert ($workflow -match 'protocolVersion' -or (Get-Content (Join-Path $PSScriptRoot 'make-update-manifest.ps1') -Raw) -match 'protocolVersion = 3') 'Manifest protocol missing'
Assert ($workflow -match 'if: always\(\)') 'Keystore cleanup must run on failure'
Assert ($workflow.IndexOf('name: Build unsigned-development app and UI test APKs') -lt $workflow.IndexOf('name: Run only AppUpdateRemoteUiTest')) 'APK compilation must precede emulator launch'
Assert ($workflow -match 'timeout 180 "\$adb" -s emulator-5554 wait-for-device') 'Boot wait must retain its bounded 180-second budget'
Assert ($workflow -match 'ScrcpyUpdateUiTest:V' -and $workflow -match 'boot-status\.txt') 'Boot status and updater test markers must survive failures'
$diagnosticsBlock = [regex]::Match($workflow, '(?ms)^          diagnostics\(\) \{\r?\n.*?^          \}')
Assert $diagnosticsBlock.Success 'Failure diagnostics trap missing'
$publishIndex = $workflow.IndexOf("`n  publish:")
Assert ($publishIndex -gt 0) 'Publish job missing'
Assert ($workflow.Substring(0, $publishIndex) -notmatch 'BINARY_RELEASE_APPROVED') 'Binary approval must not gate source/CI checks'
$gateIndex = $workflow.IndexOf('name: Require explicit binary distribution approval', $publishIndex)
$decodeIndex = $workflow.IndexOf('base64 --decode', $publishIndex)
Assert ($gateIndex -gt $publishIndex -and $decodeIndex -gt $gateIndex) 'Binary approval must run before signing-secret decoding'
Assert ($workflow -match 'BINARY_RELEASE_APPROVED: \$\{\{ vars\.BINARY_RELEASE_APPROVED \}\}') 'Binary approval must use the explicit repository variable'
$gateBlock = [regex]::Match($workflow, '(?ms)      - name: Require explicit binary distribution approval.*?        run: \|\r?\n(?<body>(?:          [^\r\n]*\r?\n)+)')
Assert $gateBlock.Success 'Binary approval shell block missing'
$gateCode = $gateBlock.Groups['body'].Value -replace '(?m)^          ', '' -replace "`r`n", "`n"
$bash = if ($env:OS -eq 'Windows_NT') {
    Join-Path (Split-Path (Split-Path (Get-Command git).Source -Parent) -Parent) 'bin/bash.exe'
} else { (Get-Command bash).Source }
$previousApproval = $env:BINARY_RELEASE_APPROVED
try {
    foreach ($approval in @('', 'false', 'TRUE', 'true')) {
        $env:BINARY_RELEASE_APPROVED = $approval
        $gateCode | & $bash --noprofile --norc | Out-Null
        Assert (($LASTEXITCODE -eq 0) -eq ($approval -ceq 'true')) 'Binary approval must fail closed except for literal true'
    }
} finally { $env:BINARY_RELEASE_APPROVED = $previousApproval }

# Fake SDK command output exercises manifest plumbing, never real signing keys/APKs.
$scratch = Join-Path $root ('publication-output/test-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch | Out-Null
try {
    $windowsHost = $env:OS -eq 'Windows_NT'
    $bashDirectory = $scratch.Replace('\', '/')
    $diagnosticsFunction = $diagnosticsBlock.Value -replace '(?m)^          ', '' -replace "`r`n", "`n"
    foreach ($expectedExit in @(23, 124)) {
        $diagnosticsCase = @(
            'set -e'
            "cd '$bashDirectory'"
            'output=diagnostics-fixture'
            'mkdir -p "$output"'
            'printf "PARTIAL-JUNIT-FIXTURE\n" > "$output/instrumentation-result.txt"'
            'printf "BOOT-FIXTURE\n" > "$output/boot-environment.txt"'
            'printf "EMULATOR-FIXTURE\n" > "$output/emulator.log"'
            'adb=missing-publication-adb-fixture'
            $diagnosticsFunction
            'trap diagnostics EXIT'
            "exit $expectedExit"
        ) -join "`n"
        $diagnosticsOutput = @($diagnosticsCase | & $bash --noprofile --norc 2>&1)
        Assert ($LASTEXITCODE -eq $expectedExit) 'Diagnostics must preserve the original failure/timeout exit code'
        Assert (($diagnosticsOutput -join "`n") -match 'PARTIAL-JUNIT-FIXTURE') 'Partial instrumentation output must be printed even on timeout'
        Assert ((Get-Content (Join-Path $scratch 'diagnostics-fixture/exit-status.txt') -Raw).Trim() -eq "$expectedExit") 'Original exit status must be retained in artifacts'
    }
    $applicationId = if (Test-Path (Join-Path $root 'app/build.gradle')) { 'dev.screencopy.sender' } else { 'org.screencopy.receiver' }
    $asset = if ($applicationId -eq 'dev.screencopy.sender') { 'ScrcpySend.apk' } else { 'ScrcpyReceive.apk' }
    $certificate = 'ab' * 32
    $signerText = "Signer #1 certificate SHA-256 digest: $certificate"
    $packageText = "package: name='$applicationId' versionCode='10001' versionName='1.2.3'"
    $signerPath = Join-Path $scratch $(if ($windowsHost) { 'apksigner.bat' } else { 'apksigner' })
    $aaptPath = Join-Path $scratch $(if ($windowsHost) { 'aapt2.exe' } else { 'aapt2' })
    if ($windowsHost) {
        Set-Content -LiteralPath $signerPath -Value "@echo off`necho $signerText`nexit /b 0" -Encoding ASCII
        # aapt2.exe is a command name, so use a test function rather than create an executable.
        Set-Item -Path "Function:global:$aaptPath" -Value ([scriptblock]::Create("`$global:LASTEXITCODE = 0; `"$packageText`""))
    } else {
        [IO.File]::WriteAllText($signerPath, "#!/bin/sh`nprintf '%s\n' '$signerText'`n", [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllText($aaptPath, "#!/bin/sh`nprintf '%s\n' `"${packageText}`"`n", [Text.UTF8Encoding]::new($false))
        & chmod +x $signerPath $aaptPath
        Assert ($LASTEXITCODE -eq 0) 'Cannot initialize fake SDK fixture'
    }
    $apk = Join-Path $scratch 'fixture.apk'
    [IO.File]::WriteAllText($apk, 'not-an-android-package; mock SDK test fixture')
    $parameters = @{
        Apk = $apk; ApplicationId = $applicationId; VersionCode = 10001; VersionName = '1.2.3'
        ApkAssetName = $asset; BuildTools = $scratch; OutputDirectory = (Join-Path $scratch 'publication-output')
    }
    & (Join-Path $PSScriptRoot 'make-update-manifest.ps1') @parameters
    $result = Get-Content -LiteralPath (Join-Path $parameters.OutputDirectory 'update.json') -Raw | ConvertFrom-Json
    Assert (($result.PSObject.Properties.Name -join ',') -eq 'schema,applicationId,versionCode,versionName,apkAssetName,sha256,signingCertificateSha256,protocolVersion') 'Exact updater schema changed'
    Assert ($result.versionCode -is [int] -or $result.versionCode -is [long]) 'versionCode must be an integer'
    Assert ($result.schema -eq 1 -and $result.protocolVersion -eq 3) 'Manifest schema/protocol mismatch'
    Assert ($result.signingCertificateSha256 -ceq $certificate) 'Certificate digest mismatch'
    Assert ($result.sha256 -ceq (Get-FileHash $apk).Hash.ToLowerInvariant()) 'APK digest mismatch'
    Assert ((Get-FileHash (Join-Path $parameters.OutputDirectory $asset)).Hash -ceq (Get-FileHash $apk).Hash) 'Published APK must match the verified input'
    Assert ($result.apkAssetName -ceq $asset) 'Updater manifest must use the renamed APK'
    $publishedNames = @((Get-ChildItem -LiteralPath $parameters.OutputDirectory -File).Name | Sort-Object)
    Assert (($publishedNames -join ',') -ceq "$asset,update.json") 'Publication must contain exactly the named APK and one updater manifest'
    foreach ($invalid in @(
        @{ VersionCode = 1 }, @{ VersionCode = 10002 }, @{ VersionName = 'wrong' },
        @{ ApplicationId = 'invalid.package' },
        @{ ApkAssetName = $(if ($asset -eq 'ScrcpySend.apk') { 'ScrcpyReceive.apk' } else { 'ScrcpySend.apk' }) },
        @{ ApkAssetName = 'ScrcpyRecv.apk' }
    )) {
        $badParameters = $parameters.Clone()
        foreach ($key in $invalid.Keys) { $badParameters[$key] = $invalid[$key] }
        $failed = $false
        try { & (Join-Path $PSScriptRoot 'make-update-manifest.ps1') @badParameters } catch { $failed = $true }
        Assert $failed 'Invalid APK/version/asset must fail closed'
    }
    if ($windowsHost) {
        Set-Content -LiteralPath $signerPath -Value '@echo off', 'exit /b 1' -Encoding ASCII
    } else {
        [IO.File]::WriteAllText($signerPath, "#!/bin/sh`nexit 1`n", [Text.UTF8Encoding]::new($false))
    }
    $unsignedRejected = $false
    try { & (Join-Path $PSScriptRoot 'make-update-manifest.ps1') @parameters } catch { $unsignedRejected = $true }
    Assert $unsignedRejected 'Signature verification failure must forbid a manifest'
    $existingDestinationRejected = $false
    try { & (Join-Path $PSScriptRoot 'export-publication.ps1') -Destination $scratch | Out-Null } catch { $existingDestinationRejected = $true }
    Assert $existingDestinationRejected 'Exporter must never overwrite an existing directory'
    $exportPath = Join-Path $scratch 'snapshot'
    $export = (& (Join-Path $PSScriptRoot 'export-publication.ps1') -Destination $exportPath | ConvertFrom-Json)
    $copied = @(Get-ChildItem -LiteralPath $exportPath -Recurse -File -Force)
    Assert ($copied.Count -eq $export.files.Count) 'Export file count differs from allowlist'
    Assert (!(Test-Path (Join-Path $exportPath '.git'))) 'Git history must never be exported'
    foreach ($entry in $export.files) {
        Assert ((Get-FileHash -LiteralPath (Join-Path $exportPath $entry.path)).Hash.ToLowerInvariant() -ceq $entry.sha256) 'Copied file hash differs from inventory'
    }
    # A synthetic Git enumeration includes private paths, proving exclusion before content reads.
    $privateFixtures = @('.git/private', '.gradle/cache.txt', '.cxx/cache.txt', '.idea/settings.txt',
        '.vscode/settings.json', '.copilot/state.json', 'local.properties', '.env',
        'tools/credentials.json', 'app/src/main/assets/private.keystore')
    foreach ($relative in $privateFixtures) {
        $fixturePath = Join-Path $exportPath $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $fixturePath -Parent) | Out-Null
        [IO.File]::WriteAllText($fixturePath, 'blocked fixture marker: ' + '-----BEGIN ' + 'PRIVATE KEY-----')
    }
    $attributesPath = Join-Path $exportPath '.gitattributes'
    if (!(Test-Path -LiteralPath $attributesPath)) { [IO.File]::WriteAllText($attributesPath, "* text=auto`n") }
    if ($windowsHost) {
        foreach ($hiddenPath in @($attributesPath, (Join-Path $exportPath '.github'))) {
            [IO.File]::SetAttributes($hiddenPath, [IO.File]::GetAttributes($hiddenPath) -bor [IO.FileAttributes]::Hidden)
        }
    }
    $fixtureCandidates = @($export.files.path) + @('.gitattributes') + $privateFixtures
    $fixtureDestination = Join-Path $scratch 'hidden-fixture-export'
    $fixtureExport = & {
        function git {
            $global:LASTEXITCODE = 0
            $fixtureCandidates
        }
        & (Join-Path $exportPath 'tools/export-publication.ps1') -Destination $fixtureDestination | ConvertFrom-Json
    }
    foreach ($requiredHidden in @('.gitattributes', '.github/workflows/build-release.yml')) {
        Assert ($requiredHidden -in $fixtureExport.files.path) 'Required hidden source/config was omitted'
        Assert (Test-Path -LiteralPath (Join-Path $fixtureDestination $requiredHidden)) 'Hidden file was not copied'
    }
    foreach ($privatePath in $privateFixtures) {
        Assert ($privatePath -in $fixtureExport.excluded) 'Private candidate must be explicitly excluded'
        Assert (!(Test-Path -LiteralPath (Join-Path $fixtureDestination $privatePath))) 'Private fixture was copied'
    }
    Write-Output "PASS publication scripts: optimized release policy; verified export $($export.files.Count) files; exact renamed APK/schema, hashes, metadata and seven rejection cases"
} finally {
    if ($env:OS -eq 'Windows_NT') { Remove-Item -LiteralPath "Function:global:$aaptPath" -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $scratch -Recurse -Force
}
& $parserBash --noprofile --norc (Join-Path $PSScriptRoot 'test-stop-ci-emulator.sh').Replace('\', '/')
Assert ($LASTEXITCODE -eq 0) 'Owned emulator shutdown must be bounded and regression tested'
$cleanupScript = Get-Content (Join-Path $PSScriptRoot 'run-release-smoke.sh') -Raw
Assert ($cleanupScript -match 'source tools/stop-ci-emulator.sh' -and $cleanupScript -match 'stop_ci_emulator "\$emulator_pid"') 'Disposable emulator must use owned, bounded shutdown'
Assert ($cleanupScript -notmatch 'wait "\$emulator_pid"') 'Unbounded emulator wait must not return'
