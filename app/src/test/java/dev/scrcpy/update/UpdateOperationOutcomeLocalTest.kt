package dev.scrcpy.update

import android.app.Activity
import android.content.Intent
import android.os.Build
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateOperationOutcomeLocalTest {
    private val storage = "update_test_operation_outcome"
    private lateinit var lifecycle: ActivityController<UpdateTestActivity>
    private lateinit var activity: UpdateTestActivity
    private lateinit var controller: UpdateController
    private lateinit var ui: UpdateUi
    private var installerCalls = 0
    private val installs = object : UpdateUi.InstallActions {
        override fun allowed(): Boolean = true
        override fun openSettings() { fail("No OEM settings expected") }
        override fun install(intent: Intent, requestCode: Int) { installerCalls++ }
    }
    @Before fun setup() {
        lifecycle = Robolectric.buildActivity(UpdateTestActivity::class.java).setup().visible()
        activity = lifecycle.get()
        seed("cancelled")
        controller = newController()
        ui = UpdateUi(activity, controller, { false }, installs)
        activity.updatesForTests = ui
        ui.mount(activity.content); ui.start()
        ShadowLooper.idleMainLooper()
    }
    @After fun cleanup() {
        ui.stop()
        controller.cancel()
        lifecycle.pause().stop().destroy()
    }
    @Suppress("DEPRECATION")
    private fun seed(outcome: String) {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        val installed = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val target = installed.toInt() + 1
        val manifest = JSONObject().put("schema", 1).put("applicationId", activity.packageName)
            .put("versionCode", target).put("versionName", "Fixture").put("apkAssetName", "ScrcpyReceive.apk")
            .put("sha256", "a".repeat(64)).put("signingCertificateSha256", "b".repeat(64)).put("protocolVersion", 3)
        activity.getSharedPreferences(storage, Activity.MODE_PRIVATE).edit().clear()
            .putString("manifest", manifest.toString())
            .putString("apk_url", "https://github.com/arisewanggithub/ScrcpyReceive/releases/download/v2/ScrcpyReceive.apk")
            .putLong("last_success", System.currentTimeMillis())
            .putLong("install_target_version", target.toLong()).putString("install_outcome", outcome)
            .putString("install_signer", "b".repeat(64)).commit()
        val directory = java.io.File(activity.cacheDir, storage).apply { mkdirs() }
        java.io.File(directory, "verified.apk").outputStream().use { it.write("invalid APK fixture".toByteArray(Charsets.UTF_8)) }
    }
    private fun newController(): UpdateController = object :
        UpdateController(requireNotNull(activity.applicationContext), "ScrcpyReceive", "ScrcpyReceive.apk", storage) {
        override fun newClient(): ReleaseClient = ReleaseClient { url ->
            object : HttpURLConnection(URL(url)) {
                override fun connect() {}
                override fun usingProxy(): Boolean = false
                override fun disconnect() {}
                override fun getResponseCode(): Int = 500
            }
        }
    }
    private fun findButton(view: View?, prefix: String): Button? {
        if (view is Button && view.text.toString().startsWith(prefix)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount)
            findButton(view.getChildAt(i), prefix)?.let { return it }
        return null
    }
    private fun hasText(view: View?, text: String): Boolean {
        if (view is TextView && view.text.toString().contains(text)) return true
        if (view is ViewGroup) for (i in 0 until view.childCount)
            if (hasText(view.getChildAt(i), text)) return true
        return false
    }
    private fun center(view: View?, prefix: String) {
        val button = requireNotNull(findButton(view, prefix))
        assertTrue(button.requestFocus())
        button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        button.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        ShadowLooper.idleMainLooper()
    }
    private fun awaitOperation() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            if (!controller.busy) return
            Thread.sleep(10)
        }
        fail("Updater operation did not finish")
    }
    @Test fun cancelledInstallThenFailedVerificationCannotHideErrorAfterRecreation() {
        center(activity.content, "Update available")
        assertTrue(hasText(ui.dialogViewForTests(), "Installation cancelled"))
        center(ui.dialogViewForTests(), "Retry installation")
        awaitOperation()
        assertTrue(hasText(ui.dialogViewForTests(), "APK checksum mismatch"))
        assertFalse(hasText(ui.dialogViewForTests(), "APK is retained"))
        assertFalse(controller.apk.isFile)
        assertNull(controller.installMessage())
        val saved = ui.saveState()
        ui.stop(); activity.content.removeAllViews()
        ui = UpdateUi(activity, controller, { false }, installs)
        activity.updatesForTests = ui
        ui.restoreState(saved); ui.mount(activity.content); ui.start(); ui.resume()
        assertTrue(hasText(ui.dialogViewForTests(), "APK checksum mismatch"))
        assertFalse(hasText(ui.dialogViewForTests(), "Installation cancelled"))
        assertNull(newController().installMessage())
        assertEquals(0, installerCalls)
    }
    @Test fun cancelledInstallThenFailedCheckKeepsActualFileAndShowsNewError() {
        center(activity.content, "Check")
        awaitOperation()
        assertTrue(hasText(ui.dialogViewForTests(), "HTTP 500"))
        assertFalse(hasText(ui.dialogViewForTests(), "Installation cancelled"))
        assertTrue(controller.apk.isFile)
        assertNull(controller.installMessage())
        assertNull(newController().installMessage())
        assertEquals(0, installerCalls)
    }
    @Test fun cancelledInstallThenFailedDownloadShowsNewError() {
        center(activity.content, "Update available")
        controller.download()
        awaitOperation()
        assertTrue(hasText(ui.dialogViewForTests(), "HTTP 500"))
        assertFalse(hasText(ui.dialogViewForTests(), "Installation cancelled"))
        assertNull(controller.installMessage())
        assertEquals(0, installerCalls)
    }
    @Test fun missingRetainedFileIsReportedUnavailable() {
        ui.stop()
        controller.apk.delete()
        assertTrue(controller.displayStatus().contains("APK is unavailable"))
        assertFalse(controller.displayStatus().contains("APK is retained"))
        controller = newController()
        assertFalse(controller.downloadReady())
        assertTrue(controller.installMessage()?.contains("APK is unavailable") == true)
        assertFalse(controller.installMessage()?.contains("APK is retained") == true)
    }
    @Test fun operationFailureDoesNotClearPendingInstallerState() {
        ui.stop(); seed("pending")
        controller = newController()
        activity.content.removeAllViews()
        ui = UpdateUi(activity, controller, { false }, installs)
        activity.updatesForTests = ui
        ui.mount(activity.content); ui.start()
        controller.check()
        awaitOperation()
        assertTrue(controller.installPending())
        assertTrue(hasText(activity.content, "HTTP 500"))
        assertFalse(hasText(activity.content, "Waiting for Android installer"))
        assertEquals(0, installerCalls)
    }
}
