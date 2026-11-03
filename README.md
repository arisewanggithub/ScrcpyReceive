# ScrcpyReceive

Android receiver for [ScrcpySend](https://github.com/arisewanggithub/ScrcpySend)
on a trusted family LAN. **SRT / fragmented MP4** playback uses **libsrt1.5.7**
for transport and **Media3 ExoPlayer1.11.1** for extraction, codecs and timing.
A **custom TCP** compatibility path uses MediaCodec and AudioTrack directly.
These are independent applications, not official scrcpy applications.

The Android app and release download are named **ScrcpyReceive** and
**ScrcpyReceive.apk**; its application ID remains `org.screencopy.receiver`.
The repository is [ScrcpyReceive](https://github.com/arisewanggithub/ScrcpyReceive). Releases publish only
`ScrcpyReceive.apk`; older versions expecting `ScrcpyRecv.apk` need a manual
installation to migrate. The on-wire
`SCPY` framing uses **wire protocol version 3**, a compatibility discriminator
separate from application release versions. Session IDs and track generations
identify internal resources.

## Architecture and supported formats

- SRT supports **H.264, H.265 or AV1**, with **Opus, AAC-LC or audio off**.
  Media3 handles the standard fragmented MP4 container and playback clocks;
  libsrt supplies a cancellable transport, not an alternative decoder.
- Custom TCP supports the negotiated video formats plus Opus/AAC/PCM audio.
  Bundled **libopus1.5.2** handles Opus initialization, pre-skip, gain and decode.
- Selected settings and actual codec metadata are validated before admission.
  Unsupported codecs/configurations fail explicitly; requested audio is not
  silently disabled, and media is not silently converted to another transport.
- Debug and release builds include SRT and custom TCP by default; SRT needs no
  opt-in flag. The legacy `srtTrial` build-option name is retained for script
  compatibility, not to designate SRT as experimental. `-PsrtTrial=false`
  explicitly selects the TCP-only compatibility-test profile.
- The waiting screen is replaced by aspect-fit video only after actual rendered
  video and the required audio readiness. A socket connection alone is not
  playback success.

See [DESIGN.md](DESIGN.md) and [PROTOCOL.md](PROTOCOL.md) for pipeline ownership,
exact schemas, packet validation and resource bounds.

## Trust and interoperability

Use a reachable **trusted private LAN**. Discovery, control and media are
unencrypted and unauthenticated; SRT encryption is disabled. Names are labels,
not trusted identities. The sender selects the actual UDP-source IP/TCP port,
not an advertised redirect or matching name. Do not expose the listener to the
internet or assume protection against LAN observers or spoofed discovery.

Both applications must implement **wire protocol version 3**. Positive internal session
IDs, track generations and readiness checks are mandatory. One cast is admitted;
another receives `BUSY`. UDP discovery uses port **27183**, and the default
control TCP listener uses **27184**. Wi-Fi/AP client isolation may block
discovery or connections.

## Install and receive

1. Install ScrcpyReceive normally and connect it to the same reachable LAN as
   ScrcpySend. Receiving needs no ADB, root, Developer options or microphone
   permission.
2. Open ScrcpyReceive and keep its screen visible. The waiting page shows actual
   listening IP addresses and the bound TCP port (for example,
   `192.168.1.3 : 46756`), concise status and remote-accessible idle actions.
   Spaces around the colon are display-only; connection endpoints remain `IP:port`.
3. Select the discovered receiver in ScrcpySend and choose Play. ScrcpySend
   owns capture settings; ScrcpyReceive accepts or rejects them explicitly.
4. Stop on the sender. After owned cleanup, the receiver returns to Ready.
   Exit and ordinary Android Back/Home finish/leave through normal lifecycle
   cleanup, not a process kill.

The UI supports native portrait/landscape layouts, D-pad/Center/Enter focus,
window/cutout insets and overscan-safe margins. Playback is aspect-fit and
letterboxed rather than stretched or cropped again. The visible Activity stays
awake; it does not change system power or keyguard settings.

Home shows the installed app version and initially focuses passive content, so
the first remote OK does not accidentally activate Exit. Updates are checked
silently once per fresh launch; only a newer version shows a two-action popup.
There is no receiver Settings button.

The sender may request receiver orientation `auto`, `landscape` or `portrait`
for a session. It affects presentation only, not capture dimensions, media
rotation or timestamps. The previous Activity policy is restored on teardown.
Fixed-orientation hardware, multi-window and large-screen OS policies may ignore
an orientation request.

## Current limitations

- Real-device **H.265 capture/Stop/recast safety on the sender remains
  unverified** because kernel-panic risk has been reported. Successful builds
  and synthetic codec tests do not establish safety of real screen capture.
- H.264/H.265 in-band configuration handling requires compatible decoder
  adaptation. AV1/audio configuration changes require a new supported session.
  AAC/AV1 hardware playback, audio routes and interruption cleanup need
  device validation.
- Endurance, A/V offset and glass-to-glass latency require measurements;
  transport/buffer settings are not performance guarantees.
- No recording, remote input injection, internet traversal, IPv6 discovery or
  background receiving is provided. Network changes end the cast and refresh
  addresses. Android TV/store or device-specific certification is not claimed.
- Media is not recorded; backups/device transfer are excluded and screenshots
  are blocked while media is visible. Update traffic has the separate HTTPS
  privacy/security contract documented in [UPDATES.md](UPDATES.md).

## Build and verification

Use **JDK21**, public **Android SDK36 / Build Tools36.0.0**,
**NDK27.2.12479018** and **CMake3.22.1**. CI also provisions **Node22**.
The **Gradle9.8.1** wrapper is checksum-pinned; **AGP9.4.1** supplies built-in
Kotlin support and JVM17 targets. Minimum SDK is **26**; target/compile SDK is
**36**. Native ABIs are **arm64-v8a**, **armeabi-v7a** and **x86_64**, not
hardware compatibility certifications.

From your checkout, with `JAVA_HOME` set to JDK21:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" # Or your existing SDK location.
.\gradlew.bat :app:checkDebugAarMetadata :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console plain
```

Also check the TCP-only compatibility profile with `-PsrtTrial=false`. Debug outputs are
`app\build\outputs\apk\debug\app-debug.apk` and
`app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk`.
The optional `.\tools\interop.ps1 -SenderRoot ..\ScrcpySend` reads sender
sources without building/writing that checkout; `SCRCPY_SEND_ROOT` can
override the sibling default.

PR CI runs JVM tests, lint, debug builds and unsigned optimized release assembly
in both profiles. Release assembly runs R8/resource shrinking before the
publisher can use signing secrets; it does not prove runtime compatibility.
Its disposable Linux
**API35 x86_64** emulator runs only
`dev.scrcpy.update.AppUpdateRemoteUiTest`, not capture or codec acceptance
tests. APKs are built before emulator boot. Failure/timeout retains partial
instrumentation output, targeted test logcat and the original exit status;
that emulator job uploads only disposable UI diagnostics. Verification claims must use the
current run, not historical totals.
CI also retains app JUnit XML when available for exact failure messages/stacks,
without uploading APKs or signing material.

### Release optimization

Only `release` enables R8 code optimization/obfuscation and resource shrinking,
using `proguard-android-optimize.txt` plus targeted SRT/Opus JNI declaring-class
and native-method rules. Manifest components use AGP-generated keeps and Media3
uses upstream AAR consumer rules. Debug/test fixtures remain unchanged; there
are no whole app/updater/Media3 keeps or added warning suppressions. Required
licenses/source assets, native libraries, all three ABIs and 16 KiB link options
are unchanged.

For runner validation, in a shell with **no release-signing environment variables
or Gradle signing properties configured**, run in both transport profiles:

```powershell
.\tools\test-publication.ps1
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug :app:lintRelease :app:assembleRelease -PrequireReleaseSigning=false --no-daemon --console plain
```

Repeat with `-PsrtTrial=false`. `requireReleaseSigning=false` permits unsigned
assembly; it does **not** override any supplied signing configuration.
Inspect `app\build\outputs\apk\release\app-release-unsigned.apk` and
`app\build\outputs\mapping\release\mapping.txt`: confirm JNI declaring/method
names, manifest components, absence of `UpdateTestActivity`, required assets
and resources. Check SRT/Opus JNI exports and 16 KiB ELF alignment on every ABI.
AGP's release CMake variant normally uses optimized `RelWithDebInfo`; confirm
the generated flags rather than forcing global `-O3`/LTO. Opus already has its
targeted `-O2` setting.

Unsigned assembly and the existing **debug-only** updater emulator tests are
not optimized-release smoke tests. The separate `optimized-native-smoke` CI job
builds the actual non-debuggable release with R8/resource shrinking and a
disposable test signer. Publication waits for that job as well as existing checks.
See [UPDATES.md](UPDATES.md) for coverage, limitations and mapping retention.

To assemble its safe APK cohort only (no emulator/device operation), from a
shell with no production signing variables/properties:

```powershell
.\gradlew.bat :app:assembleRelease :release-check:assembleRelease -PreleaseSmokeTest=true -PrequireReleaseSigning=false --no-daemon --console plain
.\tools\verify-release-smoke-apks.ps1 -App app\build\outputs\apk\release\app-release.apk -TestApk release-check\build\outputs\apk\release\release-check-release.apk -ApplicationId org.screencopy.receiver
```

The strict property defaults to false. Smoke mode rejects publication signing,
any supplied production-signing configuration (even empty/masked paths), and
non-SRT builds. It changes only the app release signer and conditionally includes
the `release-check` module; the app's normal debug AndroidTest cohort is untouched.
The standalone `com.android.test` module uses AGP's self-instrumenting mode:
no app compile dependency/mapping or runtime dependency subtraction. Its Kotlin
runtime is independently minified/repackaged, not borrowed from the optimized app.
All app code is invoked through the actual target package's distinct classloader.
The harness contains no app implementations or native libraries.

**Optimized coverage: six tests** — three actual SRT JNI loopback/cancellation/
stale-handle tests, two actual Opus JNI decode/error tests, and one actual manifest
resource/launcher check. CI separately launches the real app process without
starting playback and verifies it survives startup. The original debug
instrumentation tests remain available separately; their higher-level pipeline
assertions are not relabeled as optimized-native coverage.
The shared transcript checker requires distinct per-test status **0**, one exact
JUnit OK summary and one final instrumentation code **-1**. Missing/duplicate
cases, crashes, failures and skips are rejected by executable fixture tests.
Disposable CI release/debug/update-UI AVD configs use `disk.dataPartition.size=2G`, set after
creation and before startup. This is the userdata property, not a global
partition flag. CI checks 3 GiB free and retains filesystem diagnostics; no
SDK/system directories are deleted.

Only the test module supplies Error Prone annotations 2.41.0 and suppresses the
compiler-only `javax.lang.model.element.Modifier` reference. No production Kotlin
keeps, test hooks, JDK compiler stubs or production dependencies are added.
Standalone AGP test components consume `testProguardFiles`, not ordinary
`proguardFiles`; optimize defaults and the custom harness rules use that API.
DEX policy rejects any app implementation, duplicate class definition or native
library in the harness. Opus's existing two-argument construction boundary is
retained precisely for reflected JNI lifecycle checks, without field/package keeps.

## Updates and signed releases

The D-pad-friendly updater checks the latest stable release without interrupting
an active cast. The receiver's **Download in browser** action opens that release's
pinned GitHub page, following PartialCast's browser handoff flow. Download
**ScrcpyReceive.apk** there and open it to update; the browser and Android manage
permissions and installation. The receiver does not download or verify the
browser's APK or automatically launch an installer. **Later**/Back never opens
the browser, and a missing/blocked browser shows an actionable error. The retained
in-app updater remains available to other callers. See [UPDATES.md](UPDATES.md).
Update dialog text uses the dialog theme's primary text color on both light and
dark surfaces. Local Robolectric regressions enforce at least 4.5:1 text contrast
and preserve single-tap activation, D-pad focus, and explicit update consent.

CI supplies `releaseVersionCode=10000+github.run_number` and a version name
from the stable tag without `v`, or `dev-<short-sha>` for main. Local defaults
are versionCode1/versionName0.1.0. Preserve run-number continuity or choose
a higher offset than installed versions when replacing a workflow/repository.

Stable tags are `vMAJOR.MINOR.PATCH`. Main prereleases additionally require
`ENABLE_MAIN_PRERELEASES=true` and are excluded from latest-stable auto-updates.
**Both binary release paths are blocked by default** until the user manually
sets `BINARY_RELEASE_APPROVED=true` after reviewing the original-code
license/distribution choice and third-party obligations. This includes
corresponding-source requirements and the sender's LGPL application source,
build scripts and relinking/recombination materials; see its
[THIRD_PARTY.md](https://github.com/arisewanggithub/ScrcpySend/blob/main/THIRD_PARTY.md).
Adding a root LICENSE or configuring signing secrets is not approval or a
compliance guarantee. Source export and CI checks are unaffected.

Publication also requires manually configured Actions secrets
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_PASSWORD`. Approval is checked before secret decoding. The
runner privately restores/cleans the keystore, never publishes an unsigned or
debug fallback, and never uploads signing material. Manual signed builds use
`ANDROID_KEYSTORE_PATH` or `-PreleaseSigningKeystorePath=...`, the three signing
environment variables, and `-PrequireReleaseSigning=true`. Ordinary debug
development needs no release key.

In-place updates require the **same application ID and signing certificate**
and a higher versionCode. Existing debug-key installs need the matching key,
or an explicitly planned one-time migration/backup. A changed certificate
cannot preserve an in-place installation; there is no automatic uninstall
workaround.

Release assets are exactly **ScrcpyReceive.apk** and **update.json**. No legacy
APK copy is published. Older versions expecting `ScrcpyRecv.apk` need a manual
installation with the same application ID and signing certificate to migrate.
Mandatory fields:
`schema:1`, `applicationId:"org.screencopy.receiver"`, integer `versionCode`,
`versionName`, `apkAssetName:"ScrcpyReceive.apk"`, lowercase 64-hex `sha256`,
lowercase 64-hex `signingCertificateSha256`, and `protocolVersion:3`.
The manifest generator verifies the actual signed APK's identity/version.
The sender uses the same schema with `dev.screencopy.sender` and `ScrcpySend.apk`.

## Source export and licensing

Run `.\tools\export-publication.ps1` for the path/size/SHA-256 inventory.
Add `-Destination .\publication-snapshot` to copy into a **new** directory,
then review after changes stabilize. Hidden CI files, pinned archives/checksums,
updater sources, wrapper JAR and licensing materials are included. History,
caches, SDK/IDE/device settings, outputs and signing material are excluded
even if tracked.

No root license or reuse grant has been selected for original application code;
public visibility alone does not grant permission to reuse it. Existing
copyright notices remain unchanged. Third-party code retains its own licenses
and distribution obligations; the exporter invents no original-code license
and does not require one for technical source export.

Preserve all notices and required matching sources with distribution.
`third_party\opus-1.5.2.tar.gz` and `third_party\srt-1.5.7.tar.gz` have adjacent
SHA-256 pins enforced by CMake. Notices/license materials remain in the archives
and APK, including [OPUS-NOTICE.txt](app/src/main/assets/OPUS-NOTICE.txt) and
[SRT-NOTICE.txt](app/src/main/assets/SRT-NOTICE.txt).
Media3 and other components retain their own terms. Distributors must supply
the necessary notices/license texts and corresponding sources; codec patents,
store policy and legal/security review remain separate from successful builds.
