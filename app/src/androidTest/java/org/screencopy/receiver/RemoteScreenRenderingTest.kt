package org.screencopy.receiver

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.screencopy.receiver.ui.ReceiverScreen
import org.screencopy.receiver.ui.ReceiverScreenEvent
import org.screencopy.receiver.ui.ReceiverScreenView
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class RemoteScreenRenderingTest {
    @Test fun spacedListeningEndpointsFitCurrentDeviceLayout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val context = ContextThemeWrapper(base, R.style.ReceiverTheme)
            val root = LayoutInflater.from(context).inflate(R.layout.activity_receiver, null)
            val state = ReceiverScreen().reduce(ReceiverScreenEvent.Listener(
                listOf("100.64.0.1:27184", "192.168.100.200:65535")))
            ReceiverScreenView(root) {}.render(state)
            val metrics = context.resources.displayMetrics
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
            val addresses = root.findViewById<TextView>(R.id.receiver_addresses)
            val expected = listOf("100.64.0.1 : 27184", "192.168.100.200 : 65535")
            assertEquals(expected.joinToString("\n"), addresses.text.toString())
            assertEquals(2, addresses.maxLines)
            assertTrue(expected.all { addresses.paint.measureText(it) <= addresses.width + 1 })
        }
    }

    @Test fun portraitLandscapeTabletAndProjectorLayoutsKeepActualEndpointsAndExitReadable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for ((width, height) in listOf(360 to 800, 800 to 360, 1280 to 800, 1920 to 1080)) {
                val base = InstrumentationRegistry.getInstrumentation().targetContext
                val configuration = Configuration(base.resources.configuration).apply {
                    orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                    screenWidthDp = width
                    screenHeightDp = height
                    densityDpi = android.util.DisplayMetrics.DENSITY_MEDIUM
                    fontScale = 1f
                }
                val context = ContextThemeWrapper(base.createConfigurationContext(configuration), R.style.ReceiverTheme)
                val root = LayoutInflater.from(context).inflate(R.layout.activity_receiver, null) as ViewGroup
                val exits = AtomicInteger()
                val renderer = ReceiverScreenView(root) { exits.incrementAndGet() }
                val ips = listOf("192.168.100.200:65535", "10.0.0.5:46756")
                val displayedEndpoints = listOf("192.168.100.200 : 65535", "10.0.0.5 : 46756")
                val ready = ReceiverScreen().reduce(ReceiverScreenEvent.Listener(ips))
                renderer.render(ready)
                val density = context.resources.displayMetrics.density
                val pixelsWide = (width * density).toInt()
                val pixelsHigh = (height * density).toInt()
                root.measure(View.MeasureSpec.makeMeasureSpec(pixelsWide, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(pixelsHigh, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, pixelsWide, pixelsHigh)
                val addresses = root.findViewById<TextView>(R.id.receiver_addresses)
                assertEquals(displayedEndpoints.joinToString("\n"), addresses.text.toString())
                assertEquals(2, addresses.maxLines)
                assertTrue(displayedEndpoints.all { addresses.paint.measureText(it) <= addresses.width + 1 })
                assertEquals("Ready", root.findViewById<TextView>(R.id.status_label).text.toString())
                assertEquals(displayedEndpoints.joinToString("\n"), root.findViewById<TextView>(R.id.receiver_addresses).text.toString())
                val exit = root.findViewById<Button>(R.id.exit_receiver)
                assertTrue(exit.isFocusable)
                assertTrue(exit.height >= (48 * density).toInt())
                exit.requestFocus()
                assertTrue(exit.isFocused)
                exit.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
                exit.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
                assertEquals(1, exits.get())
                renderer.render(ready.reduce(ReceiverScreenEvent.Connecting))
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.waiting_screen).visibility)
                renderer.render(ready.reduce(ReceiverScreenEvent.Connecting).reduce(ReceiverScreenEvent.FramePresented))
                assertEquals(View.GONE, root.findViewById<View>(R.id.waiting_screen).visibility)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.video_surface).visibility)
                renderer.render(ready.reduce(ReceiverScreenEvent.Problem("AUDIO_FAILED")).reduce(ReceiverScreenEvent.CleanupFinished))
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.error_message).visibility)
                assertTrue(exit.isFocused)
                val bitmap = Bitmap.createBitmap(pixelsWide, pixelsHigh, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    assertTrue(bitmap.getPixel(pixelsWide / 2, pixelsHigh / 2) != 0)
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun congestionPageRetainsSpecificTrackAndLimitWithoutDumpingQueueCounters() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val root = LayoutInflater.from(ContextThemeWrapper(base, R.style.ReceiverTheme)).inflate(R.layout.activity_receiver, null)
            val renderer = ReceiverScreenView(root) {}
            val pressure = org.screencopy.receiver.media.QueuePressure(2, org.screencopy.receiver.media.QueueLimit.PTS,
                13, 49920, 14, 53760, 260000, 48, 512 * 1024, 250000, 250)
            val state = ReceiverScreen().reduce(ReceiverScreenEvent.Listener(listOf("192.168.1.20:46756")))
                .reduce(ReceiverScreenEvent.Connecting)
                .reduce(ReceiverScreenEvent.Problem("CONGESTION", org.screencopy.receiver.ui.FailureReason.QUEUE_PTS, pressure))
                .reduce(ReceiverScreenEvent.CleanupFinished)
            renderer.render(state)
            val error = root.findViewById<TextView>(R.id.error_message)
            assertEquals(View.VISIBLE, error.visibility)
            assertTrue(error.text.contains("Audio"))
            assertTrue(error.text.contains("timestamp-span"))
            assertFalse(error.text.contains("49920"))
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.waiting_screen).visibility)
        }
    }

    @Test fun exitSupportsEnterAndTerminalStateRejectsFurtherFocusActivation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val base = InstrumentationRegistry.getInstrumentation().targetContext
            val root = LayoutInflater.from(ContextThemeWrapper(base, R.style.ReceiverTheme)).inflate(R.layout.activity_receiver, null)
            val exits = AtomicInteger()
            val renderer = ReceiverScreenView(root) { exits.incrementAndGet() }
            val ready = ReceiverScreen().reduce(ReceiverScreenEvent.Listener(listOf("10.0.0.8:46756")))
            renderer.render(ready)
            val exit = root.findViewById<Button>(R.id.exit_receiver)
            exit.requestFocus()
            exit.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            exit.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            assertEquals(1, exits.get())
            renderer.render(ready.reduce(ReceiverScreenEvent.Exit))
            assertFalse(exit.isEnabled)
        }
    }
}
