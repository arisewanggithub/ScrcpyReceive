package dev.scrcpy.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class AppUpdateRemoteUiLocalTest {
    private lateinit var lifecycle: ActivityController<UpdateTestActivity>
    private lateinit var activity: UpdateTestActivity
    private lateinit var ui: UpdateUi
    private lateinit var backend: FakeBackend
    private lateinit var installs: FakeInstalls
    private var casting = false
    private val storage = "update_test_local_ui"
    private inner class FakeBackend : UpdateController(requireNotNull(activity.applicationContext), "ScrcpyReceive", "ScrcpyReceive.apk", storage) {
        private var observer: Observer? = null
        var checks = 0
        var downloads = 0
        var cancels = 0
        var reportedVersion = installedCode
        var signerMatches = true
        var automatic = false
        var pendingCheck = false
        var checkFailure = false
        init {
            val hash = "a".repeat(64)
            release = ReleasePolicy.Manifest(activity.packageName, "Fixture", "ScrcpyReceive.apk", hash, hash, installedCode.toInt() + 1)
            status = "Update available: Fixture"
            apk.outputStream().use { it.write("controlled updater fixture".toByteArray(Charsets.UTF_8)) }
        }
        fun emit() { observer?.changed() }
        override fun attach(value: Observer) { observer = value; emit() }
        override fun detach() { observer = null; cancel() }
        override fun foregroundCheck() { if (automatic) check() }
        override fun check() {
            clearCompletedInstallOutcome()
            checks++
            checkSucceeded = !pendingCheck && !checkFailure
            busy = pendingCheck
            status = if (checkFailure) "Controlled update check failed." else "Update available: Fixture"
            emit()
        }
        fun completeCheck(newer: Boolean = true) {
            release = release?.copy(versionCode = installedCode.toInt() + if (newer) 1 else 0)
            checkSucceeded = true
            busy = false
            emit()
        }
        override fun download() { clearCompletedInstallOutcome(); downloads++; busy = true; status = "Downloading update…"; emit() }
        override fun cancel() { cancels++; busy = false; emit() }
        override fun verifyForInstall(ready: () -> Unit) { clearCompletedInstallOutcome(); verified = true; ready() }
        override fun refreshInstalledVersion(): Boolean { installedCode = reportedVersion; return true }
        override fun installedSignerMatches(expected: String): Boolean = signerMatches && expected == release?.signingCertificateSha256
    }
    private inner class FakeInstalls : UpdateUi.InstallActions {
        var allowed = false
        var missingSettings = false
        var missingInstaller = false
        var settings = 0
        var installs = 0
        var intent: Intent? = null
        var requestCode = 0
        override fun allowed(): Boolean = allowed
        override fun openSettings() {
            if (missingSettings) throw ActivityNotFoundException()
            settings++
        }
        @Suppress("DEPRECATION")
        override fun install(intent: Intent, requestCode: Int) {
            if (missingInstaller) throw ActivityNotFoundException()
            installs++
            this.intent = intent
            this.requestCode = requestCode
            activity.startActivityForResult(intent, requestCode)
        }
    }
    @Before fun setup() {
        lifecycle = Robolectric.buildActivity(UpdateTestActivity::class.java).setup().visible()
        activity = lifecycle.get()
        activity.getSharedPreferences(storage, Activity.MODE_PRIVATE).edit().clear().commit()
        backend = FakeBackend()
        installs = FakeInstalls()
        ui = UpdateUi(activity, backend, { casting }, installs)
        activity.updatesForTests = ui
        ui.mount(activity.content); ui.start(); ui.focusCheck()
        ShadowLooper.idleMainLooper()
    }
    @After fun cleanup() { ui.stop(); lifecycle.pause().stop().destroy() }
    private fun findButton(view: View?, prefix: String): Button? {
        if (view is Button && view.text.toString().startsWith(prefix)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findButton(view.getChildAt(i), prefix)?.let { return it }
        }
        return null
    }
    private fun find(view: View?, prefix: String): Button = requireNotNull(findButton(view, prefix)) { "Missing button: $prefix" }
    private fun hasText(view: View?, expected: String): Boolean {
        if (view is TextView && view.text.toString().contains(expected)) return true
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            if (hasText(view.getChildAt(i), expected)) return true
        }
        return false
    }
    private fun center(button: Button) {
        assertTrue(button.requestFocus())
        button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        ShadowLooper.idleMainLooper()
    }
    private fun open() = center(find(activity.content, "Check"))
    private fun freshAutomaticLaunch() {
        ui.stop()
        backend.automatic = true
        ui = UpdateUi(activity, backend, { casting }, installs)
        activity.updatesForTests = ui
        ui.start()
        ShadowLooper.idleMainLooper()
    }
    @Test fun automaticCheckIsOncePerLaunchAndLaterSuppressesRepeatPrompts() {
        freshAutomaticLaunch()
        assertEquals(1, backend.checks)
        assertNotNull(ui.dialogViewForTests())
        center(find(ui.dialogViewForTests(), "Later"))
        ui.resume(); ui.refresh(); ui.stop(); ui.start()
        assertEquals(1, backend.checks)
        assertNull(ui.dialogViewForTests())
        val saved = ui.saveState()
        ui.stop()
        ui = UpdateUi(activity, backend, { casting }, installs)
        activity.updatesForTests = ui
        ui.restoreState(saved); ui.start(); ui.resume()
        assertEquals(1, backend.checks)
        assertNull(ui.dialogViewForTests())
        freshAutomaticLaunch()
        assertEquals(2, backend.checks)
        assertNotNull(ui.dialogViewForTests())
    }
    @Test fun asynchronousAutomaticCheckDoesNotPromptBeforeSuccessfulResult() {
        backend.pendingCheck = true
        freshAutomaticLaunch()
        assertEquals(1, backend.checks)
        assertNull(ui.dialogViewForTests())
        backend.completeCheck()
        assertNotNull(ui.dialogViewForTests())
        assertEquals(0, installs.installs)
        assertEquals(0, backend.downloads)
    }
    @Test fun currentVersionAndFailedCheckDoNotOpenAutomaticDialogs() {
        backend.pendingCheck = true
        freshAutomaticLaunch()
        backend.completeCheck(newer = false)
        assertNull(ui.dialogViewForTests())
        backend.pendingCheck = false
        backend.checkFailure = true
        backend.release = backend.release?.copy(versionCode = backend.installedCode.toInt() + 1)
        assertTrue(backend.available())
        freshAutomaticLaunch()
        assertEquals(2, backend.checks)
        assertNull(ui.dialogViewForTests())
    }
    @Test fun automaticPromptDefersUntilCastEndsAndNeverStopsTheCast() {
        casting = true
        freshAutomaticLaunch()
        assertNull(ui.dialogViewForTests())
        assertTrue(casting)
        casting = false
        ui.refresh()
        assertNotNull(ui.dialogViewForTests())
        casting = true
        ui.refresh()
        assertNull(ui.dialogViewForTests())
        casting = false
        ui.refresh()
        center(find(ui.dialogViewForTests(), "Later"))
        ui.refresh()
        assertNull(ui.dialogViewForTests())
        assertEquals(1, backend.checks)
        assertEquals(0, installs.installs)
    }
    @Test fun backDismissesAutomaticPromptForThisLaunchWithoutDownloading() {
        freshAutomaticLaunch()
        val dialog = requireNotNull(ui.dialogViewForTests())
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        ShadowLooper.idleMainLooper()
        assertNull(ui.dialogViewForTests())
        ui.resume(); ui.refresh()
        assertNull(ui.dialogViewForTests())
        assertEquals(1, backend.checks)
        assertEquals(0, backend.downloads)
        assertFalse(activity.isFinishing)
    }
    private fun result(code: Int, data: Intent? = null) {
        Shadows.shadowOf(activity).receiveResult(requireNotNull(installs.intent), code, data)
        ShadowLooper.idleMainLooper()
    }
    @Test fun nativeCenterFocusNavigationAndDownloadCancel() {
        open()
        assertEquals(1, backend.checks)
        val root = requireNotNull(ui.dialogViewForTests())
        val update = find(root, "Download and install")
        val later = find(root, "Later")
        assertTrue(update.hasFocus())
        assertSame(later, update.focusSearch(View.FOCUS_DOWN))
        assertTrue(later.requestFocus())
        assertSame(update, later.focusSearch(View.FOCUS_UP))
        assertNotNull(update.background)
        center(update)
        assertEquals(1, backend.downloads)
        val cancel = find(root, "Later")
        assertTrue(cancel.hasFocus())
        center(cancel)
        assertFalse(backend.busy)
        assertNull(ui.dialogViewForTests())
        assertTrue(find(activity.content, "Update available").hasFocus())
    }
    @Suppress("DEPRECATION")
    @Test fun permissionReturnKeepsVerifiedDownloadAndInstallerUsesReadGrant() {
        backend.verified = true
        open()
        center(find(ui.dialogViewForTests(), "Allow installs"))
        assertEquals(1, installs.settings)
        assertEquals(0, installs.installs)
        ui.stop(); installs.allowed = true; ui.start(); ShadowLooper.idleMainLooper()
        assertTrue(backend.verified)
        assertNull(ui.dialogViewForTests())
        assertEquals(1, installs.installs)
        val intent = requireNotNull(installs.intent)
        assertEquals("content", intent.data?.scheme)
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, intent.action)
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
        assertEquals(UpdateUi.INSTALL_REQUEST, installs.requestCode)
    }
    @Test fun activeCastAndMissingOemActivitiesAreActionableNotSilent() {
        backend.verified = true; installs.allowed = true; casting = true
        open(); center(find(ui.dialogViewForTests(), "Install"))
        assertEquals(0, installs.installs)
        assertTrue(casting)
        assertTrue(hasText(ui.dialogViewForTests(), "Stop casting before installing"))
        casting = false; installs.missingInstaller = true
        center(find(ui.dialogViewForTests(), "Install"))
        assertTrue(hasText(ui.dialogViewForTests(), "No Android package installer"))
        installs.allowed = false; installs.missingSettings = true; backend.emit()
        center(find(ui.dialogViewForTests(), "Allow installs"))
        assertTrue(hasText(ui.dialogViewForTests(), "no app-scoped install settings"))
        center(find(ui.dialogViewForTests(), "Later"))
        assertNull(ui.dialogViewForTests())
    }
    @Test fun errorRetryAndDetachedUiNeverReopenDialog() {
        open()
        backend.status = "Update check/download failed. Check internet access and retry."; backend.emit()
        assertTrue(hasText(ui.dialogViewForTests(), "failed"))
        center(find(ui.dialogViewForTests(), "Later"))
        open()
        assertEquals(2, backend.checks)
        ui.stop(); backend.busy = false; backend.status = "Late callback"; backend.emit()
        assertNull(ui.dialogViewForTests())
    }
    @Test fun twoButtonsDownloadVerifyAndInstallAfterOneExplicitClick() {
        installs.allowed = true
        open()
        val root = requireNotNull(ui.dialogViewForTests())
        assertEquals(2, countButtons(root))
        center(find(root, "Download and install"))
        assertTrue(backend.finishUpdateRequested)
        assertEquals(1, backend.downloads)
        assertEquals(0, installs.installs)
        backend.verified = true; backend.busy = false; backend.emit()
        assertEquals(1, installs.installs)
        assertFalse(backend.finishUpdateRequested)
        assertNull(ui.dialogViewForTests())
    }
    @Test fun laterCancelsConsentAndLateDownloadCannotLaunchInstaller() {
        installs.allowed = true
        open()
        center(find(ui.dialogViewForTests(), "Download and install"))
        center(find(ui.dialogViewForTests(), "Later"))
        backend.verified = true; backend.busy = false; backend.emit()
        assertEquals(0, installs.installs)
        assertFalse(backend.finishUpdateRequested)
        assertNull(ui.dialogViewForTests())
    }
    private fun countButtons(view: View): Int = if (!view.isShown) 0 else if (view is Button) 1
        else if (view is ViewGroup) (0 until view.childCount).sumOf { countButtons(view.getChildAt(it)) }
        else 0
    @Test fun androidCancellationAndFailureResultsOfferExplicitRetry() {
        backend.verified = true; installs.allowed = true
        open(); center(find(ui.dialogViewForTests(), "Install"))
        assertTrue(backend.installPending())
        assertEquals(1, installs.installs)
        result(Activity.RESULT_CANCELED)
        assertTrue(hasText(ui.dialogViewForTests(), "Installation cancelled"))
        assertTrue(backend.verified)
        assertFalse(backend.installPending())
        center(find(ui.dialogViewForTests(), "Retry installation"))
        assertEquals(2, installs.installs)
        assertEquals(0, backend.downloads)
        result(Activity.RESULT_FIRST_USER)
        assertTrue(hasText(ui.dialogViewForTests(), "Android rejected"))
        center(find(ui.dialogViewForTests(), "Later"))
        assertEquals(2, installs.installs)
    }
    @Test fun activityRecreationRestoresPendingAttemptWithoutAutoInstall() {
        backend.verified = true; installs.allowed = true
        open(); center(find(ui.dialogViewForTests(), "Install"))
        ui.stop()
        lifecycle.recreate()
        activity = lifecycle.get()
        ui = UpdateUi(activity, backend, { casting }, installs)
        activity.updatesForTests = ui
        ui.restoreState(activity.restoredUpdateStateForTests)
        ui.mount(activity.content); ui.start(); ui.resume(); ShadowLooper.idleMainLooper()
        assertEquals(1, installs.installs)
        assertEquals(0, backend.downloads)
        assertTrue(hasText(ui.dialogViewForTests(), "has not been confirmed"))
        center(find(ui.dialogViewForTests(), "Retry installation"))
        assertEquals(2, installs.installs)
    }
    @Test fun processRestoreRetainsFileButRequiresFreshVerificationAndExplicitRetry() {
        val bytes = "controlled APK fixture, not installable".toByteArray(Charsets.UTF_8)
        val hash = ReleasePolicy.hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val manifest = JSONObject().put("schema", 1).put("applicationId", activity.packageName)
            .put("versionCode", requireNotNull(backend.release).versionCode).put("versionName", "Fixture")
            .put("apkAssetName", "ScrcpyReceive.apk").put("sha256", hash)
            .put("signingCertificateSha256", hash).put("protocolVersion", 3)
        activity.getSharedPreferences(storage, Activity.MODE_PRIVATE).edit()
            .putString("manifest", manifest.toString())
            .putString("apk_url", "https://github.com/arisewanggithub/ScrcpyReceive/releases/download/v2/ScrcpyReceive.apk")
            .putLong("last_success", System.currentTimeMillis()).commit()
        backend.apk.outputStream().use { it.write(bytes) }
        backend.verified = true; backend.beginInstall()
        val saved = ui.saveState()
        ui.stop()
        val restored = object : UpdateController(requireNotNull(activity.applicationContext), "ScrcpyReceive", "ScrcpyReceive.apk", storage) {}
        assertTrue(restored.installPending())
        assertTrue(restored.apk.isFile)
        assertTrue(restored.downloadReady())
        assertFalse(restored.verified)
        activity.content.removeAllViews()
        ui = UpdateUi(activity, restored, { casting }, installs)
        activity.updatesForTests = ui
        ui.restoreState(saved); ui.mount(activity.content); ui.start(); ui.resume(); ShadowLooper.idleMainLooper()
        assertEquals(0, installs.installs)
        assertTrue(restored.apk.isFile)
        assertFalse(restored.verified)
        assertTrue(hasText(ui.dialogViewForTests(), "has not been confirmed"))
    }
    @Test fun resultOkNeedsActualInstalledVersionAndFirstUserIsFailure() {
        backend.verified = true; installs.allowed = true
        open(); center(find(ui.dialogViewForTests(), "Install"))
        result(Activity.RESULT_FIRST_USER)
        assertTrue(hasText(ui.dialogViewForTests(), "Android rejected"))
        center(find(ui.dialogViewForTests(), "Retry installation"))
        result(Activity.RESULT_OK)
        assertTrue(hasText(ui.dialogViewForTests(), "has not been confirmed"))
        center(find(ui.dialogViewForTests(), "Retry installation"))
        backend.reportedVersion = requireNotNull(backend.release).versionCode.toLong()
        result(Activity.RESULT_OK)
        assertTrue(backend.displayStatus().contains("Update installed"))
        assertNull(ui.dialogViewForTests())
    }
    @Test fun resultOkWithChangedVersionButDifferentCurrentSignerIsUnconfirmed() {
        backend.verified = true; installs.allowed = true
        open(); center(find(ui.dialogViewForTests(), "Install"))
        backend.reportedVersion = requireNotNull(backend.release).versionCode.toLong()
        backend.signerMatches = false
        result(Activity.RESULT_OK)
        assertTrue(hasText(ui.dialogViewForTests(), "has not been confirmed"))
        assertFalse(hasText(ui.dialogViewForTests(), "Update installed"))
        assertEquals(1, installs.installs)
    }
    @Test fun permissionReturnReplacementDialogSurvivesQueuedOldDismiss() {
        backend.verified = true
        open()
        val oldRoot = requireNotNull(ui.dialogViewForTests())
        center(find(oldRoot, "Allow installs"))
        assertEquals(1, installs.settings)
        ui.stop()
        installs.allowed = true
        ui.start()
        assertEquals(1, installs.installs)
        assertNull(ui.dialogViewForTests())
        ui.resume()
        val replacement = requireNotNull(ui.dialogViewForTests())
        assertNotSame(oldRoot, replacement)
        ShadowLooper.idleMainLooper()
        assertSame(replacement, ui.dialogViewForTests())
        assertTrue(hasText(replacement, "has not been confirmed"))
        assertTrue(backend.verified)
        assertTrue(backend.apk.isFile)
        assertEquals(1, installs.installs)
        val intent = requireNotNull(installs.intent)
        assertEquals("content", intent.data?.scheme)
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNotNull(intent.clipData)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_RETURN_RESULT, false))
        assertEquals(UpdateUi.INSTALL_REQUEST, installs.requestCode)
    }
}
