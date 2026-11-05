package org.screencopy.receiver

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextureRotationRenderingTest {
    @Test fun composedSyntheticCornersRotateOnTheGpuWithoutReplacingTheTexture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Unlock the receiver before rendering verification",
            context.getSystemService(android.os.PowerManager::class.java).isInteractive &&
                !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        ActivityScenario.launch(TextureRotationProbeActivity::class.java).use { scenario ->
            await {
                val ready = AtomicBoolean()
                scenario.onActivity { ready.set(it.texture.isAvailable && it.box.width > 0 && it.box.height > 0) }
                ready.get()
            }
            lateinit var texture: android.graphics.SurfaceTexture
            scenario.onActivity {
                texture = checkNotNull(it.texture.surfaceTexture)
                assertTrue(it.window.decorView.isHardwareAccelerated)
                assertEquals(0, it.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            val patterns = listOf(
                intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
                intArrayOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
                intArrayOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
                intArrayOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE)
            )
            for (turn in 0..3) {
                scenario.onActivity { it.rotate(turn * 90) }
                await {
                    val ready = AtomicBoolean()
                    scenario.onActivity {
                        ready.set(it.texture.width == it.texture.layoutParams.width &&
                            it.texture.height == it.texture.layoutParams.height)
                    }
                    ready.get()
                }
                var previous = 0
                scenario.onActivity { previous = it.updates.get(); it.drawSquares() }
                await {
                    val ready = AtomicBoolean()
                    scenario.onActivity { ready.set(it.updates.get() > previous) }
                    ready.get()
                }
                lateinit var bitmap: Bitmap
                val copied = CountDownLatch(1)
                val result = AtomicInteger(-1)
                val points = IntArray(8)
                scenario.onActivity {
                    assertSame(texture, it.texture.surfaceTexture)
                    val view = it.texture
                    val width = if (turn % 2 == 0) view.width else view.height
                    val height = if (turn % 2 == 0) view.height else view.width
                    val position = IntArray(2)
                    it.box.getLocationInWindow(position)
                    val left = position[0] + (it.box.width - width) / 2
                    val top = position[1] + (it.box.height - height) / 2
                    for (index in 0..3) {
                        points[index * 2] = left + width * (if (index % 2 == 0) 1 else 3) / 4
                        points[index * 2 + 1] = top + height * (if (index / 2 == 0) 1 else 3) / 4
                    }
                    bitmap = Bitmap.createBitmap(it.window.decorView.width, it.window.decorView.height, Bitmap.Config.ARGB_8888)
                    PixelCopy.request(it.window, bitmap, { status -> result.set(status); copied.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue(copied.await(5, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, result.get())
                try {
                    for (index in 0..3) {
                        val actual = bitmap.getPixel(points[index * 2], points[index * 2 + 1])
                        val expected = patterns[turn][index]
                        assertTrue("Composed rotation ${turn * 90}, corner $index: ${Integer.toHexString(actual)}",
                            abs(Color.red(actual) - Color.red(expected)) <= 16 &&
                                abs(Color.green(actual) - Color.green(expected)) <= 16 &&
                                abs(Color.blue(actual) - Color.blue(expected)) <= 16)
                    }
                } finally { bitmap.recycle() }
            }
        }
    }

    private fun await(ready: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!ready() && System.nanoTime() < until) Thread.sleep(10)
        assertTrue("Synthetic texture did not reach its expected render state", ready())
    }
}
