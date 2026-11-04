param([string]$Destination)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$candidates = @(& git -C $root -c core.quotePath=false ls-files --cached --others --exclude-standard)
if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate the current Git snapshot' }
$required = @('.gitignore', '.github/workflows/build-release.yml', 'gradlew', 'gradlew.bat',
    'tools/pr-validation.ps1',
    'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
    'release-check/src/main/AndroidManifest.xml', 'release-check/proguard-rules.pro',
    'release-check/src/main/java/dev/scrcpy/releasecheck/NativeOpusCheck.kt',
    'third_party/opus-1.5.2.tar.gz', 'third_party/opus-1.5.2.sha256')
$binaryAllowlist = @($required | Where-Object { $_ -match '\.(jar|gz|zip)$|scrcpy-server-' })
$entries = @()
$excluded = @()
foreach ($relative in ($candidates | Sort-Object -Unique)) {
    $allowedRoot = $relative -match '^(app|release-check|protocol|third_party|streaming-muxer|tools|gradle)/' -or
        $relative -eq '.github/workflows/build-release.yml' -or
        $relative -match '^(\.gitignore|\.gitattributes|[A-Z_]+\.md|LICENSE[^/]*|COPYING[^/]*|NOTICE[^/]*|build\.gradle(\.kts)?|settings\.gradle(\.kts)?|gradle\.properties|gradlew(\.bat)?)$'
    $privateOrGenerated = $relative -match '(?i)(^|/)(\.git|\.gradle|\.kotlin|\.cxx|\.idea|\.vscode|\.copilot|node_modules|build|gst-android-build|publication-output|publication-snapshot[^/]*)(/|$)' -or
        $relative -match '(?i)(^|/)(local\.properties|\.env[^/]*|[^/]*(credential|keystore|secret)[^/]*\.(json|properties|ya?ml|txt)|id_rsa|id_ed25519)(/|$)' -or
        $relative -match '(?i)\.(jks|keystore|p12|pfx|pem|key|apk|aab|iml|log|tmp|bak)$'
    if (!$allowedRoot -or $privateOrGenerated) { $excluded += $relative; continue }
    $path = Join-Path $root $relative
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { continue }
    $parent = Get-Item -LiteralPath $path -Force
    while ($parent.FullName -ne $root) {
        if ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Snapshot refuses links: $relative" }
        $parent = Get-Item -LiteralPath (Split-Path $parent.FullName -Parent) -Force
    }
    $bytes = [IO.File]::ReadAllBytes($path)
    $binary = $bytes -contains 0 -or $relative -match '\.(jar|gz|zip|png|jpg|webp|mp4)$|scrcpy-server-'
    if ($binary -and $relative -notin $binaryAllowlist) { throw "Unreviewed binary requires an explicit allowlist entry: $relative" }
    if (!$binary) {
        $text = [Text.Encoding]::UTF8.GetString($bytes)
        if ($text -match '(?i)-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|AKIA[A-Z0-9]{16}') {
            throw "Potential secret; publication blocked pending a redacted review: $relative"
        }
        if ($text -match '(?i)[A-Z]:\\(Users|gitpersonal)\\') { throw "Personal workstation path requires sanitation: $relative" }
    }
    $entries += [ordered]@{ path = $relative; bytes = $bytes.LongLength; sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() }
}
foreach ($name in $required) {
    if ($name -notin $entries.path) { throw "Required pinned source/asset missing from snapshot: $name" }
}
if ($Destination) {
    $destinationPath = [IO.Path]::GetFullPath($Destination)
    if (Test-Path -LiteralPath $destinationPath) { throw 'Export destination must not already exist; no existing files will be overwritten' }
    New-Item -ItemType Directory -Path $destinationPath | Out-Null
    foreach ($entry in $entries) {
        $source = Join-Path $root $entry.path
        if ((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) {
            throw "Source changed after inventory; discard this incomplete export and rerun: $($entry.path)"
        }
        $target = Join-Path $destinationPath $entry.path
        New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
        Copy-Item -LiteralPath $source -Destination $target -Force
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $entry.sha256) {
            throw "Export verification failed: $($entry.path)"
        }
    }
}
[ordered]@{ schema = 1; files = $entries; excluded = $excluded } | ConvertTo-Json -Depth 5
