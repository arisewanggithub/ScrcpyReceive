# App updates

ScrcpySend and ScrcpyReceive use the stable Android application IDs `dev.screencopy.sender` and `org.screencopy.receiver`, respectively. In-place upgrades preserve app data only with the same application ID and signing certificate. They are independent projects, not official scrcpy applications; upstream attribution is provided in the third-party notices.

## Touch and remote flow

Both home screens show the actual installed app version, without update status controls. Each fresh launch checks silently once; rotation and ordinary resume do not start another check. Only a successful check for a newer version opens the popup, after casting is idle. **Later** or Back dismisses it for that launch. Failed checks are logged without a popup; GitHub cooldowns still apply. The receiver has no Settings/update entry. Sender capture Settings still provides a manual check. D-pad/Center/OK navigation has visible focus.

Touch taps activate buttons on the first tap; they do not first select them.
Buttons retain normal focus for D-pad/keyboard navigation but are not focusable
in Android touch mode. Android switches between these input modes automatically,
including when a remote and touchscreen are used on the same device.

### Receiver browser downloads

The receiver popup has exactly two actions: **Download in browser** and **Later**.
Following PartialCast, the primary action opens the newer stable release's
GitHub page with a browsable HTTPS intent, only after an explicit tap/remote
press while foreground and idle. The page is derived from the validated APK
asset URL and pinned to `arisewanggithub/ScrcpyReceive`; it is not an arbitrary
URL or an unpinned latest-release redirect. Download **ScrcpyReceive.apk** on that
page, then open the downloaded file to install it. The browser and Android handle
download/install permissions and confirmations. The receiver does not download,
checksum/signature-verify the browser's file, or launch the installer.

Later/Back suppresses the prompt for that launch and never opens a browser.
Opening the browser also handles the prompt; returning does not automatically
open it again or imply a successful download/install. The installed version is
reread on return. Missing or blocked browser activities leave the dialog open
with a retryable error. Legacy in-app installer/permission state cannot cause an
installer launch through this receiver flow. An active cast is never stopped
to open the browser. Light/dark dialog contrast and single-tap/D-pad behavior
are preserved.

### Retained in-app download and installer flow

Other callers retain the in-app updater. Its popup has exactly two actions: **Download and install update** and **Later**. One explicit primary-action press downloads, verifies, and opens Android's installer when permitted and idle. The same primary button represents install permission or retry when needed. Later/Back also cancels an in-flight download. Consent survives rotation within the running process, never process restart or a fresh launch. Partial files are never installable. After a user-initiated installation attempt, the completed APK is retained across process restart but must be verified again on explicit retry.

Installing requires an idle cast; the updater never stops capture or playback. **Allow installs** opens Android's app-scoped permission settings. Returning with permission granted continues only the existing explicit request in that process; after process loss a new action is required. Android's installer and Play Protect confirmations remain under user control. This is not a root/Shizuku/silent installer. Missing OEM activities show actionable errors. No notification permission or browser is needed.

A pending or interrupted installer attempt takes precedence over the launch check: its explicit retry status and retained file are preserved until a manual operation.

Sender Settings remains available before Shizuku is ready for manual update checks; capture settings still require readiness and an idle cast.

Installer launch requests a standard activity result (`EXTRA_RETURN_RESULT`, request code 7312). Only public `RESULT_OK`, `RESULT_CANCELED`, and `RESULT_FIRST_USER` outcomes are used; no hidden installer extras or reflection are needed. The private pending-attempt record and activity UI state survive recreation/process restore without automatically installing again. Android cancellation, rejection, and an unavailable/unconfirmed result show explicit status plus **Retry installation** and **Later**; Back dismisses without retrying. A launch or even `RESULT_OK` alone is never reported as success: the app rereads its actually installed version code and current signing certificate against the verified attempt's signer. Returning during an active cast updates status without opening a focus-stealing dialog. Retried launches still require fresh APK verification and a final idle-cast guard.

A new check, download, or verification takes precedence over historical installer status. Completed installer outcomes are cleared before that operation, including cooldown/retry errors, while a genuinely pending installer record is preserved. Verification errors remove the invalid APK and show the fresh verification error rather than an old cancellation message. Retention messages check whether the APK actually exists; activity recreation cannot revive a cleared old outcome.

### Receiver home focus

The receiver initially focuses its passive home content, so the first remote OK cannot accidentally activate Exit. Down/Right deliberately selects Exit; Up/Left returns home. Back closes the update popup without closing the receiver. The landscape address/status composition remains intact.

Exit activates on the first touchscreen tap. It remains focusable for deliberate
D-pad/Enter activation, but does not take focus first in touch mode. Passive home
focus and the safe initial remote-OK behavior are unchanged in both layouts.

## Public release contract

The production endpoints are pinned to:

- `https://api.github.com/repos/arisewanggithub/ScrcpySend/releases/latest`
- `https://api.github.com/repos/arisewanggithub/ScrcpyReceive/releases/latest`

Only stable, non-draft releases qualify. Each release must contain exactly one `update.json` and its exactly named APK (`ScrcpySend.apk` or `ScrcpyReceive.apk`). No PAT, credentials, device identifiers, capture details, or signing keys are sent. GitHub receives the ordinary HTTPS request metadata and public updater User-Agent. HTTP 403/429 rate limits honor bounded server cooldowns; manual retries do not bypass them.

Receiver releases publish only **ScrcpyReceive.apk**, with no **ScrcpyRecv.apk**
compatibility copy. The app updater and `update.json` use the new filename.
Older installed receiver versions require a one-time manual installation to
migrate; use the same application ID and signing certificate to preserve data.
The repository is now `arisewanggithub/ScrcpyReceive`; its application ID,
signing certificate, version and update verification rules are unchanged.

`update.json` is at most 64 KiB and has these mandatory fields:

```json
{
  "schema": 1,
  "applicationId": "dev.screencopy.sender",
  "versionCode": 2,
  "versionName": "1.1",
  "apkAssetName": "ScrcpySend.apk",
  "sha256": "<64 lowercase hexadecimal characters>",
  "signingCertificateSha256": "<64 lowercase hexadecimal characters>",
  "protocolVersion": 3
}
```

For the receiver use `org.screencopy.receiver` and `ScrcpyReceive.apk`. `protocolVersion:3` identifies wire compatibility, not an application release number; `schema:1` identifies the update manifest schema. Version codes must be positive integers no greater than 2147483647; names are not used for ordering. The placeholders above are documentation, not a valid manifest. Optional extra fields do not authorize protocol or signer changes.

Assets initially belong to the pinned repository's GitHub release download path. Metadata and in-app download HTTPS redirects are bounded and restricted to the literal hosts `github.com`, `api.github.com`, `objects.githubusercontent.com`, and `release-assets.githubusercontent.com`. In-app APK downloads are bounded to 250 MiB. The retained in-app updater verifies SHA-256, archive application ID, archive version against the manifest, strictly newer version code, manifest signer fingerprint, and equality with the currently installed app's single current signer. It verifies again before granting the installer read access to the private APK through its narrowly scoped, non-exported FileProvider. These file-verification and size limits do not apply to the receiver's external browser download; Android enforces installed-package signing compatibility.

Different signing keys, missing signers, multi-signer packages, or certificate rotations are refused rather than uninstalling or risking data loss. A CI release cannot update a debug-key installation unless its signing certificate is the same. Publication therefore still requires manually configured permanent signing with the intended existing certificate; neither these apps nor their tests create or upload a signing key.

Binary publication additionally requires the user's explicit repository variable
`BINARY_RELEASE_APPROVED=true` after license/distribution and third-party
corresponding-source/relink review. This is separate from signing configuration
and does not create a license or compliance guarantee. Source export and CI
checks do not require binary publication approval.

## Optimized future releases

### PR validation versus publication

The workflow separates jobs by event. PRs alone run the TCP receiver JVM,
lint, debug, remote UI (15 tests) and optimized runtime suites.
After all checks pass, **Validated PR source proof** records the PR head and
tested merge tree as a small 90-day Actions artifact. This is a required merge
check alongside the three existing validation checks.

Ordinary main pushes do not rebuild or rerun the suite. Stable tags and opted-in
main prereleases first require an exact merged protected-main PR commit, a
successful PR workflow with every required job executed, and a retained proof
whose source tree matches the release checkout. Missing/expired/mismatched proof
fails closed; revalidate the PR instead of bypassing checks. Matching trees
support GitHub's merge/squash commit metadata without substituting different code.
Only then does publication build/sign the actual APK, verify its package/version,
signature and checksum, create the updater manifest, and publish. Signing,
binary-distribution approval, exact asset names and mapping retention are unchanged.

**Run workflow** manually performs read-only release-proof verification on the
selected main revision. It never builds, signs, or publishes an APK.

Disposable CI emulators are stopped by their owning shell only. Shutdown bounds
TERM to 10 seconds and KILL to another 5 seconds; the shell reaps only after the
process has exited. Cleanup failure preserves a failed step and cannot turn a
passing test result into an indefinitely running job. Console shutdown requests
also have a hard timeout. These helpers never target physical phones.

Release builds use R8 and resource shrinking; debug behavior is unchanged.
Optimization does not change application IDs, updater SHA/package/version/signer
checks, host allowlists, version assignment, signing requirements or binary
distribution approval. Use a **new future version** with the same permanent key
configured by the user. Do not move/force existing `v1.0.0` tags or replace any
already published APK. No key regeneration, secret setup or old-release repair
is part of optimization.

The signed publisher retains its exact `mapping.txt` for 30 days as a named
Actions artifact tied to commit/run/attempt, not as a public release asset.
Mapping contains code structure, not signing material; archive it through
appropriate workflow-artifact access before expiry for retracing release crash
stacks. Never print signing secrets or include keystores in diagnostics.

The [README](README.md#release-optimization) gives unsigned R8 assembly and APK,
mapping, resource and native-export inspection targets. Script contracts only
guard configuration; a successful optimized assembly is still required.

The separate `optimized-native-smoke` job uses a disposable Linux API35 x86_64
emulator and Gradle's runner-generated debug signer only for that test run.
`releaseSmokeTest=true` selects a standalone self-instrumenting test module and
the app's temporary test signer without enabling app debuggability or weakening
R8/resource shrinking. Any production signing
configuration or `requireReleaseSigning=true` conflicts with smoke mode.
The publisher explicitly uses smoke=false and rebuilds with the user-configured
permanent signer; smoke APKs, debug keystores and AVD storage are never artifacts.

The standalone cohort has **three tests**: two actual Opus JNI
decode/error checks and one actual manifest-resource/launcher check. Target
classes/native libraries are loaded from the actual installed app package through
its separate classloader, not compiled or packaged into the harness. CI launches
the real app process separately without starting playback.
No capture, installer operation, Shizuku permission grant or physical phone is
involved. Sender-side BC identity, SPAKE2 and Conscrypt factory/reflection checks
run in the sender's separate job, not this receiver cohort. Established-socket
Conscrypt TLS export and actual scrcpy/Shizuku deployment remain outside scope.

CI installs only on its isolated emulator after DEX verification rejects
duplicate app implementations, crypto/ADB/native wrapper classes, copied native
libraries and release debug fixtures. The runner additionally requires an
explicit `releaseSmoke=true` argument and non-debuggable target. The shell driver
requires Linux Actions and checks emulator/API35 identity before installation.
Every selected test must complete successfully; ignored/assumption-skipped tests
or incomplete execution fail the job. Only result/log/policy files and mappings
are retained, for seven days.

The harness owns an independently optimized/repackaged Kotlin runtime. Supported
self-instrumenting mode avoids app dependency subtraction and app mapping; no
production stdlib functions are retained merely for tests. The previous shared
AndroidTest harness crashed before executing tests and is retired; its former
eight-test selection is not claimed as successful optimized coverage. Original
debug instrumentation tests remain distinct higher-level pipeline coverage.
No real-phone capture/Stop, especially H.265, is part of R8 acceptance.
Ephemeral signing does not validate an in-place production-signer update.

## Test targets

Both repositories contain pure JVM `dev.scrcpy.update.ReleasePolicyTest` and `dev.scrcpy.update.ReleaseClientTest`.

Updater production code, debug fixtures and tests are Kotlin. The sender's
`verifyKotlinSources` invariant covers every source set under `app/src` and the
protocol harness. Use results from the current test run, not historical totals.

Both debug APKs include the non-exported `dev.scrcpy.update.UpdateTestActivity`. The instrumentation class `dev.scrcpy.update.AppUpdateRemoteUiTest` exercises actual D-pad key events with an in-memory updater and installer callback:

- `remoteCheckDownloadCancelAndBackNeedNoTouch`
- `installPermissionReturnRetainsDownloadAndRequiresExplicitInstall`
- `activeCastNeverStoppedOrInstalled`
- `missingOemSettingsAndInstallerRemainCancellable`
- `failureRetryIsExplicitAndDpadReachable`
- `dpadDirectionsPreserveVisibleFocusAndBackCancels`
- `installerCancellationShowsExplicitDpadRetryWithoutRedownload`
- `installerFailureShowsRetryAndBackDoesNotInstallAgain`
- `pendingInstallRestoreNeedsExplicitRetryWithoutAutoLaunch`
- `installerResultOkDoesNotClaimSuccessWithoutInstalledVersionChange`
- `cancelledInstallThenFailedVerificationShowsFreshErrorAfterRestore`
- `cancelledInstallThenFailedCheckKeepsFileAndShowsFreshError`

Run only that instrumentation selector for the Linux official API 35 x86_64 emulator updater job. It does not start capture, contact GitHub, download an APK, open OEM permission settings, or launch a real installer. Test injection is constructor/package-scoped and has no exported broadcast, intent-extra, URL override, or production verification bypass.

Receiver provides `dev.scrcpy.update.AppUpdateRemoteUiLocalTest` using
Robolectric4.16.1/resources for widget focus, Center key handling, activity
results, lifecycle restoration and explicit installation retries.
`dev.scrcpy.update.UpdateOperationOutcomeLocalTest` covers failed verification/
check/download after cancellation, missing retained files and pending installer
records using controlled connections and private fixtures. These tests do not
read production preferences or launch a real installer.

Receiver Robolectric classes use SDK28, Conscrypt OFF, LEGACY graphics and a
paused main looper with real app resources. This Java-shadow configuration avoids
Robolectric native graphics unsupported on Windows ARM64. It is not physical
rendering or API35 platform evidence; the separate official Linux API35 emulator
job supplies that instrumentation target.

`dev.scrcpy.update.ReceiverMainActivityUpdateEntryTest` additionally creates the actual receiver MainActivity and waiting-screen resources/lifecycle on a compact landscape fixture. It injects key events through Robolectric's focused-window input dispatch, rather than manually choosing focus targets. Cases `initialExitDpadDownChecksUpdatesAndBackKeepsReceiverOpen` and `visibleUpdateDpadDownAndUpReturnsToIntentionalExit` verify entry, automatic scroll visibility, optional-update navigation, dialog Back cancellation, and deliberate Exit. Only the in-process, debug-guarded updater factory is injected; the fixture leaves the video surface unavailable so no receiver sockets, discovery, playback, capture, settings, or installer are started. There is no intent-extra backdoor or global key interception.

Dialog cancellation/dismissal callbacks are bound to their originating dialog instance. A queued dismissal from before an install-permission round trip cannot clear or cancel the replacement dialog. `AppUpdateRemoteUiLocalTest.permissionReturnReplacementDialogSurvivesQueuedOldDismiss` covers that lifecycle race while preserving explicit Install selection, the verified file, read grants, ClipData, and activity-result return flags.
