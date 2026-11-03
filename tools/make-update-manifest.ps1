param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$ApplicationId,
    [Parameter(Mandatory)][int]$VersionCode,
    [Parameter(Mandatory)][string]$VersionName,
    [Parameter(Mandatory)][ValidateSet('ScrcpySend.apk', 'ScrcpyReceive.apk')][string]$ApkAssetName,
    [Parameter(Mandatory)][string]$BuildTools,
    [string]$OutputDirectory = 'publication-output'
)
$ErrorActionPreference = 'Stop'
if ($VersionCode -le 1) { throw 'Publication versionCode must exceed the original installed version 1' }
if ($ApplicationId -notin @('dev.screencopy.sender', 'org.screencopy.receiver')) { throw 'Unexpected application ID' }
$expectedAsset = if ($ApplicationId -eq 'dev.screencopy.sender') { 'ScrcpySend.apk' } else { 'ScrcpyReceive.apk' }
if ($ApkAssetName -ne $expectedAsset) { throw 'Application ID/asset name mismatch' }
$suffix = if ($IsWindows -or $env:OS -eq 'Windows_NT') { '.bat' } else { '' }
$certificateOutput = @(& (Join-Path $BuildTools "apksigner$suffix") verify --verbose --print-certs $Apk 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'Release APK signature verification failed' }
$certificates = @($certificateOutput | ForEach-Object {
    if ($_ -match '^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]{64})$') { $Matches[1].ToLowerInvariant() }
} | Sort-Object -Unique)
if ($certificates.Count -ne 1) { throw 'Expected exactly one signing certificate for in-place updates' }
$aapt = Join-Path $BuildTools $(if ($suffix) { 'aapt2.exe' } else { 'aapt2' })
$badging = @(& $aapt dump badging $Apk 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read signed APK version metadata' }
$packageLine = @($badging | Where-Object { $_ -match "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'" })
if ($packageLine.Count -ne 1 -or $packageLine[0] -notmatch "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'") {
    throw 'Missing APK package metadata'
}
if ($Matches[1] -ne $ApplicationId -or [int]$Matches[2] -ne $VersionCode -or $Matches[3] -ne $VersionName) {
    throw 'Signed APK identity/version differs from the intended manifest'
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$target = Join-Path $OutputDirectory $ApkAssetName
Copy-Item -LiteralPath $Apk -Destination $target -Force
$manifest = [ordered]@{
    schema = 1
    applicationId = $ApplicationId
    versionCode = $VersionCode
    versionName = $VersionName
    apkAssetName = $ApkAssetName
    sha256 = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    signingCertificateSha256 = $certificates[0]
    protocolVersion = 3
}
$json = $manifest | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path (Resolve-Path $OutputDirectory) 'update.json'), $json + "`n", [Text.UTF8Encoding]::new($false))
