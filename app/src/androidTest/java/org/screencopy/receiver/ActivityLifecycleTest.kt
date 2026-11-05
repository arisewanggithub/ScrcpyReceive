package org.screencopy.receiver

import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.screencopy.receiver.lifecycle.ReceiverWork
import org.screencopy.receiver.net.ReceiverServer
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ActivityLifecycleTest {
    @Test fun rapidRecreationRebindsAfterDelayedPreviousActivityCleanup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = checkNotNull(context.getSystemService(android.os.PowerManager::class.java))
        val keyguard = checkNotNull(context.getSystemService(android.app.KeyguardManager::class.java))
        assertTrue("Wake and unlock the test device before running Surface/lifecycle instrumentation",
            power.isInteractive && !keyguard.isKeyguardLocked)
        val port = ReceiverServer.PORT
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitSurfaceAndListener(scenario, port)
            repeat(5) {
                val busy = CountDownLatch(1)
                val allowCleanup = CountDownLatch(1)
                val pending = ReceiverWork.executor.submit {
                    busy.countDown()
                    check(allowCleanup.await(5, TimeUnit.SECONDS))
                }
                try {
                    assertTrue(busy.await(5, TimeUnit.SECONDS))
                    scenario.recreate()
                    assertFalse(pending.isDone)
                } finally {
                    allowCleanup.countDown()
                    pending.get(5, TimeUnit.SECONDS)
                }
                awaitSurfaceAndListener(scenario, port)
            }
        }
        ReceiverWork.executor.submit {}.get(5, TimeUnit.SECONDS)
        Socket().use { probe ->
            try {
                probe.connect(InetSocketAddress("127.0.0.1", port), 1000)
                fail("Listener must be closed after leaving the receiver Activity")
            } catch (_: java.net.ConnectException) {
                // The Activity's queued socket teardown completed before this probe.
            }
        }
    }

    @Test fun remoteExitFinishesOwnedListenerAndIgnoresLateSurfaceStartup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = checkNotNull(context.getSystemService(android.os.PowerManager::class.java))
        val keyguard = checkNotNull(context.getSystemService(android.app.KeyguardManager::class.java))
        assertTrue("An awake unlocked display is required", power.isInteractive && !keyguard.isKeyguardLocked)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitSurfaceAndListener(scenario, ReceiverServer.PORT)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { activity ->
                val exit = activity.findViewById<android.widget.Button>(R.id.exit_receiver)
                exit.requestFocus()
                assertTrue(exit.isFocused)
                exit.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
                exit.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
                assertTrue(activity.isFinishing)
                assertEquals(0, activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val surface = activity.findViewById<TextureView>(R.id.video_surface)
                activity.onSurfaceTextureAvailable(checkNotNull(surface.surfaceTexture), surface.width, surface.height)
            }
        }
        ReceiverWork.executor.submit {}.get(5, TimeUnit.SECONDS)
        Socket().use { probe ->
            try {
                probe.connect(InetSocketAddress("127.0.0.1", ReceiverServer.PORT), 1000)
                fail("Exit must not leave or restart a listener")
            } catch (_: java.net.ConnectException) {
                // Owned cleanup has closed the listener without restarting on the late Surface event.
            }
        }
    }

    private fun awaitSurfaceAndListener(scenario: ActivityScenario<MainActivity>, port: Int) {
        val ready = AtomicBoolean(false)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready.get() && System.nanoTime() < deadline) {
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                ready.set(findSurface(content)?.isAvailable == true)
            }
            if (!ready.get()) Thread.sleep(10)
        }
        assertTrue("Receiver Surface must be created", ready.get())
        ReceiverWork.executor.submit {}.get(5, TimeUnit.SECONDS)
        Socket().use { probe -> probe.connect(InetSocketAddress("127.0.0.1", port), 1000) }
    }

    private fun findSurface(view: View): TextureView? {
        if (view is TextureView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findSurface(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }
}
