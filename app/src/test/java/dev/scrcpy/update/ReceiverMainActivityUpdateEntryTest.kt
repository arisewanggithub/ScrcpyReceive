package dev.scrcpy.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.widget.Button
import android.widget.TextView
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
import org.robolectric.shadows.ShadowUiAutomation
import org.screencopy.receiver.MainActivity
import org.screencopy.receiver.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w720dp-h240dp-land")
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class ReceiverMainActivityUpdateEntryTest {
    private lateinit var lifecycle: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var backend: FakeBackend
    private lateinit var ui: UpdateUi
    private val storage = "update_test_receiver_entry"
    private class FakeBackend(context: Context, storage: String) :
        UpdateController(context, "ScrcpyRecv", "ScrcpyReceive.apk", storage) {
        private var observer: Observer? = null
        var checks = 0
        var cancels = 0
        override fun attach(value: Observer) { observer = value; observer?.changed() }
        override fun detach() { observer = null; cancel() }
        override fun foregroundCheck() { check() }
        override fun check() {
            clearCompletedInstallOutcome()
            checks++
            status = "Controlled update check completed."
            checkSucceeded = false
            observer?.changed()
        }
        override fun cancel() { cancels++; busy = false; observer?.changed() }
        override fun download() { fail("Entry navigation must not download an APK") }
        override fun verifyForInstall(ready: () -> Unit) { fail("Entry navigation must not install an APK") }
        fun makeUpdateAvailable() {
            release = ReleasePolicy.Manifest("org.screencopy.receiver", "Fixture", "ScrcpyReceive.apk",
                "a".repeat(64), "a".repeat(64), installedCode.toInt() + 1)
            checkSucceeded = true
            observer?.changed()
        }
    }
    private val installs = object : UpdateUi.InstallActions {
        override fun allowed(): Boolean = false
        override fun openSettings() { fail("Entry navigation must not open OEM settings") }
        override fun install(intent: Intent, requestCode: Int) { fail("Entry navigation must not launch an installer") }
    }
    @Before fun setup() {
        lifecycle = Robolectric.buildActivity(MainActivity::class.java)
        activity = lifecycle.get()
        activity.getSharedPreferences(storage, Activity.MODE_PRIVATE).edit().clear().commit()
        activity.updateUiFactoryForTests = { host, castActive ->
            backend = FakeBackend(requireNotNull(host.applicationContext), storage)
            UpdateUi(host, backend, castActive, installs).also { ui = it }
        }
        lifecycle.create()
        // Keep the real activity lifecycle and layout, but leave its video surface unavailable.
        activity.findViewById<SurfaceView>(R.id.video_surface).holder.removeCallback(activity)
        lifecycle.start().resume().visible()
        ShadowLooper.idleMainLooper()
        assertFalse(activity.receiverReadyForTests)
        assertTrue(home().hasFocus())
        assertEquals(1, backend.checks)
        assertNull(ui.dialogViewForTests())
    }
    @After fun cleanup() {
        ui.stop()
        lifecycle.pause().stop().destroy()
        ShadowLooper.idleMainLooper()
    }
    private fun exit(): Button = activity.findViewById(R.id.exit_receiver)
    private fun home(): View = activity.findViewById(R.id.waiting_content)
    private fun key(code: Int) {
        assertTrue(ShadowUiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)))
        assertTrue(ShadowUiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, code)))
        ShadowLooper.idleMainLooper()
    }
    @Test fun landscapeHomeKeepsBothReceiverColumnsVisible() {
        val addresses = activity.findViewById<View>(R.id.address_group)
        val details = activity.findViewById<View>(R.id.details_group)
        assertTrue("Receiver address column was squeezed out", addresses.width > 200)
        assertTrue("Receiver status column was squeezed out", details.width > 200)
        assertNull(activity.findViewById<View>(R.id.check_app_updates))
        assertNull(activity.findViewById<View>(R.id.app_update_available))
        assertEquals(activity.getString(R.string.installed_version, backend.installedName),
            activity.findViewById<TextView>(R.id.home_installed_version).text.toString())
    }
    @Test fun homeDisplaysListeningEndpointsAndRenamedAppLabel() {
        val endpoints = listOf("192.168.1.3:46756", "10.0.0.5:46756")
        val state = org.screencopy.receiver.ui.ReceiverScreen().reduce(
            org.screencopy.receiver.ui.ReceiverScreenEvent.Listener(endpoints))
        org.screencopy.receiver.ui.ReceiverScreenView(activity.window.decorView) {}.render(state)
        val addresses = activity.findViewById<TextView>(R.id.receiver_addresses)
        assertEquals("192.168.1.3 : 46756\n10.0.0.5 : 46756", addresses.text.toString())
        assertEquals(2, addresses.maxLines)
        assertEquals("ScrcpyReceive", activity.applicationInfo.loadLabel(activity.packageManager).toString())
        assertEquals("org.screencopy.receiver", activity.packageName)
    }
    @Test fun initialRemoteConfirmDoesNotExitReceiver() {
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertFalse("Initial remote OK must not activate Exit", activity.isFinishing)
        assertNull(ui.dialogViewForTests())
        assertEquals(1, backend.checks)
    }
    @Test fun onlyNewVersionOpensPopupAndBackKeepsReceiverOpen() {
        backend.makeUpdateAvailable()
        ShadowLooper.idleMainLooper()
        assertEquals(1, backend.checks)
        assertNotNull(ui.dialogViewForTests())
        key(KeyEvent.KEYCODE_BACK)
        assertNull(ui.dialogViewForTests())
        assertFalse(activity.isFinishing)
        assertTrue(home().hasFocus())
        assertFalse(activity.receiverReadyForTests)
    }
    @Test fun deliberateDpadNavigationStillReachesExit() {
        assertEquals(exit().id, home().nextFocusDownId)
        assertEquals(home().id, exit().nextFocusUpId)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(exit().hasFocus())
        key(KeyEvent.KEYCODE_DPAD_UP)
        assertTrue(home().hasFocus())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertNull(ui.dialogViewForTests())
        assertEquals(1, backend.checks)
        assertFalse(activity.isFinishing)
        assertTrue(home().hasFocus())
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        assertTrue(exit().hasFocus())
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(activity.isFinishing)
        assertFalse(activity.receiverReadyForTests)
    }
    @Test fun configurationChangePreservesVersionAndDoesNotCheckAgain() {
        activity.onConfigurationChanged(android.content.res.Configuration(activity.resources.configuration))
        ShadowLooper.idleMainLooper()
        assertTrue(home().hasFocus())
        assertNull(activity.findViewById<View>(R.id.check_app_updates))
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(1, backend.checks)
        assertNull(ui.dialogViewForTests())
        assertEquals(activity.getString(R.string.installed_version, backend.installedName),
            activity.findViewById<TextView>(R.id.home_installed_version).text.toString())
        assertFalse(activity.isFinishing)
    }
}
