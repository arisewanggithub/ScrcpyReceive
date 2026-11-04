param(
    [switch]$SelfTest,
    [string]$App,
    [string]$TestApk,
    [ValidateSet('org.screencopy.receiver')][string]$ApplicationId
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Get-DexClasses([byte[]]$data) {
    if ($data.Length -lt 112 -or [Text.Encoding]::ASCII.GetString($data, 0, 4) -cne "dex`n" -or $data[7] -ne 0) { throw 'Invalid DEX header' }
    if ([BitConverter]::ToUInt32($data, 32) -ne $data.Length) { throw 'DEX size mismatch' }
    $strings = [BitConverter]::ToUInt32($data, 56)
    $stringOffset = [BitConverter]::ToUInt32($data, 60)
    $types = [BitConverter]::ToUInt32($data, 64)
    $typeOffset = [BitConverter]::ToUInt32($data, 68)
    $classes = [BitConverter]::ToUInt32($data, 96)
    $classOffset = [BitConverter]::ToUInt32($data, 100)
    foreach ($table in @(@($strings, $stringOffset, 4), @($types, $typeOffset, 4), @($classes, $classOffset, 32))) {
        if ($table[1] -gt $data.Length -or $table[0] -gt [Math]::Floor(($data.Length - $table[1]) / $table[2])) { throw 'DEX table outside file' }
    }
    $result = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    for ($index = 0; $index -lt $classes; $index++) {
        $typeIndex = [BitConverter]::ToUInt32($data, $classOffset + $index * 32)
        if ($typeIndex -ge $types) { throw 'Invalid DEX type index' }
        $stringIndex = [BitConverter]::ToUInt32($data, $typeOffset + $typeIndex * 4)
        if ($stringIndex -ge $strings) { throw 'Invalid DEX string index' }
        $cursor = [BitConverter]::ToUInt32($data, $stringOffset + $stringIndex * 4)
        $terminatedLength = $false
        for ($byteIndex = 0; $byteIndex -lt 5; $byteIndex++) {
            if ($cursor -ge $data.Length) { throw 'Invalid DEX string length' }
            if ($data[$cursor++] -lt 128) { $terminatedLength = $true; break }
        }
        if (!$terminatedLength) { throw 'Invalid DEX string length' }
        $end = $cursor
        while ($end -lt $data.Length -and $data[$end] -ne 0) { $end++ }
        if ($end -ge $data.Length) { throw 'Unterminated DEX descriptor' }
        $descriptor = [Text.Encoding]::UTF8.GetString($data, $cursor, $end - $cursor)
        if (!$descriptor.StartsWith('L') -or !$descriptor.EndsWith(';') -or !$result.Add($descriptor)) { throw 'Invalid or duplicate DEX class' }
    }
    return ,$result
}

function Get-ApkClasses([string]$path) {
    $classes = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $path))
    try {
        $dexFiles = @($archive.Entries | Where-Object { $_.FullName -cmatch '^classes(?:[2-9][0-9]*|1[0-9]+)?\.dex$' })
        if (!$dexFiles.Count) { throw 'APK has no DEX' }
        foreach ($entry in $dexFiles) {
            $stream = $entry.Open()
            $memory = [IO.MemoryStream]::new()
            try { $stream.CopyTo($memory); $data = $memory.ToArray() }
            finally { $stream.Dispose(); $memory.Dispose() }
            foreach ($descriptor in (Get-DexClasses $data)) {
                if (!$classes.Add($descriptor)) { throw 'Class duplicated across APK DEX files' }
            }
        }
    } finally { $archive.Dispose() }
    return ,$classes
}

function Assert-AppNativeLibraries($entries) {
    $expected = @('lib/arm64-v8a/libscreencopy_opus.so', 'lib/armeabi-v7a/libscreencopy_opus.so',
        'lib/x86_64/libscreencopy_opus.so')
    $actual = @($entries | Where-Object { $_ -cmatch '^lib/.+\.so$' } | Sort-Object)
    if (@(Compare-Object -CaseSensitive ($expected | Sort-Object) $actual).Count -ne 0) {
        throw 'Receiver APK must contain only the pinned Opus library for its three supported ABIs'
    }
}

function Assert-Separation($appClasses, $testClasses, [string]$id) {
    foreach ($descriptor in $testClasses) {
        if ($descriptor -cin $appClasses) { throw "App implementation duplicated in test APK: $descriptor" }
        if ($descriptor -cmatch '^L(org/bouncycastle|org/conscrypt|io/github/muntashirakon)/') { throw 'Unminified app library packaged into test APK' }
        if ($descriptor -cmatch '^L(dev/screencopy/sender|org/screencopy/receiver|dev/scrcpy/update)/') {
            throw "App wrapper or unsafe test packaged into test APK: $descriptor"
        }
    }
    $roots = @('Lorg/screencopy/receiver/media/OpusDecoder;')
    foreach ($type in $roots) {
        if ($type -cnotin $appClasses -or $type -cin $testClasses) { throw 'JNI/reflection roots must exist exclusively in optimized app DEX' }
    }
    if ('Ldev/scrcpy/update/UpdateTestActivity;' -cin $appClasses) { throw 'Debug updater Activity leaked into release' }
}

if ($SelfTest) {
    $nativeEntries = @('lib/arm64-v8a/libscreencopy_opus.so', 'lib/armeabi-v7a/libscreencopy_opus.so',
        'lib/x86_64/libscreencopy_opus.so')
    Assert-AppNativeLibraries $nativeEntries
    foreach ($invalid in @(@($nativeEntries + 'lib/x86_64/libother.so'), @($nativeEntries | Select-Object -First 2))) {
        $rejected = $false
        try { Assert-AppNativeLibraries $invalid } catch { $rejected = $true }
        if (!$rejected) { throw 'Unexpected or missing app native library was accepted' }
    }
    $roots = @('Lorg/screencopy/receiver/media/OpusDecoder;')
    Assert-Separation $roots @('Ltest/Test;') 'org.screencopy.receiver'
    Assert-Separation $roots @('Ldev/scrcpy/releasecheck/NativeOpusCheck;') 'org.screencopy.receiver'
    foreach ($badTest in @('Lorg/conscrypt/Conscrypt;', 'Ldev/screencopy/sender/LocalAdb;',
        'Ldev/screencopy/sender/CodecConfig;', 'Ldev/screencopy/sender/MainActivity;',
        'Ldev/screencopy/sender/Secrets;', 'Ldev/screencopy/sender/Wire;',
        'Lorg/screencopy/receiver/test/Wire;', $roots[0])) {
        $rejected = $false
        try { Assert-Separation $roots @($badTest) 'org.screencopy.receiver' } catch { $rejected = $true }
        if (!$rejected) { throw 'Invalid test implementation was accepted' }
    }
    $rejected = $false
    try { Assert-Separation ($roots + 'Ldev/scrcpy/update/UpdateTestActivity;') @() 'org.screencopy.receiver' } catch { $rejected = $true }
    if (!$rejected) { throw 'Debug fixture was accepted' }
    $descriptor = [Text.Encoding]::UTF8.GetBytes('Lsample/A;')
    $data = [byte[]]::new(153 + $descriptor.Length + 1)
    [Text.Encoding]::ASCII.GetBytes("dex`n035`0").CopyTo($data, 0)
    foreach ($pair in @(@(32, $data.Length), @(56, 1), @(60, 112), @(64, 1), @(68, 116), @(96, 1), @(100, 120), @(112, 152))) {
        [BitConverter]::GetBytes([uint32]$pair[1]).CopyTo($data, $pair[0])
    }
    $data[152] = $descriptor.Length
    $descriptor.CopyTo($data, 153)
    if ('Lsample/A;' -cnotin (Get-DexClasses $data)) { throw 'DEX descriptor parsing failed' }
    [BitConverter]::GetBytes([uint32]1).CopyTo($data, 120)
    $rejected = $false
    try { Get-DexClasses $data | Out-Null } catch { $rejected = $true }
    if (!$rejected) { throw 'Out-of-range DEX type was accepted' }
    Write-Output 'PASS DEX policy: descriptor parsing, bounds, app/test separation, unminified libraries and debug fixture rejection'
} else {
    if (!$App -or !$TestApk -or !$ApplicationId) { throw 'App, TestApk and ApplicationId are required' }
    $harness = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $TestApk))
    try {
        if ($harness.Entries | Where-Object { $_.FullName -cmatch '^lib/.+\.so$' }) { throw 'Harness must load app JNI libraries, never package native copies' }
    } finally { $harness.Dispose() }
    $application = [IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $App))
    try { Assert-AppNativeLibraries @($application.Entries | ForEach-Object FullName) }
    finally { $application.Dispose() }
    Assert-Separation (Get-ApkClasses $App) (Get-ApkClasses $TestApk) $ApplicationId
    Write-Output 'PASS optimized app/test DEX separation and JNI/reflection roots'
}
