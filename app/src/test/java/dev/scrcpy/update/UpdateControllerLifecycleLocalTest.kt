package dev.scrcpy.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@LooperMode(LooperMode.Mode.PAUSED)
class UpdateControllerLifecycleLocalTest {
    @Test fun browserLinkUsesValidatedCachedAssetAndRequiresANewerVersion() {
        val context = RuntimeEnvironment.getApplication()
        val storage = "update_test_browser_cache"
        val manifest = JSONObject().put("schema", 1).put("protocolVersion", 3)
            .put("applicationId", context.packageName).put("versionCode", 20000)
            .put("versionName", "1.0.6").put("apkAssetName", "ScrcpyReceive.apk")
            .put("sha256", "a".repeat(64)).put("signingCertificateSha256", "b".repeat(64))
        val prefs = context.getSharedPreferences(storage, 0)
        prefs.edit().clear().putString("manifest", manifest.toString())
            .putString("apk_url", "https://github.com/arisewanggithub/ScrcpyReceive/releases/download/v1.0.6/ScrcpyReceive.apk")
            .commit()
        val controller = object : UpdateController(context, "ScrcpyReceive", "ScrcpyReceive.apk", storage) {}
        assertEquals("https://github.com/arisewanggithub/ScrcpyReceive/releases/tag/v1.0.6",
            controller.browserReleaseUrl())
        controller.installedCode = 20000
        assertThrows(IllegalStateException::class.java) { controller.browserReleaseUrl() }
        prefs.edit().putString("apk_url", "https://github.com/arisewanggithub/ScrcpySend/releases/download/v1.0.6/ScrcpyReceive.apk").commit()
        val wrongRepository = object : UpdateController(context, "ScrcpyReceive", "ScrcpyReceive.apk", storage) {}
        assertThrows(IllegalArgumentException::class.java) { wrongRepository.browserReleaseUrl() }
        prefs.edit().clear().commit()
    }
    @Test fun recreationRetainsWorkerThenNormalStopCancelsAndDropsStaleUiCallbacks() {
        val reading = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL("https://api.github.com/fixture")) {
            override fun connect() {}
            override fun usingProxy(): Boolean = false
            override fun getResponseCode(): Int = 200
            override fun getContentLengthLong(): Long = -1
            override fun disconnect() { unblock.countDown() }
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    reading.countDown()
                    try {
                        if (!unblock.await(5, TimeUnit.SECONDS)) throw IOException("Fixture timeout")
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("Fixture cancelled")
                    }
                    throw IOException("Fixture disconnected")
                }
                override fun close() { closed.countDown() }
            }
        }
        val context = RuntimeEnvironment.getApplication()
        val controller = object : UpdateController(context, "ScrcpyReceive", "ScrcpyReceive.apk") {
            override fun newClient(): ReleaseClient = ReleaseClient { connection }
        }
        val callbacks = AtomicInteger()
        controller.attach { callbacks.incrementAndGet() }
        controller.check()
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        controller.detachForRecreation()
        assertTrue(controller.busy)
        assertFalse("Recreation must not disconnect the ongoing launch check", unblock.await(100, TimeUnit.MILLISECONDS))
        controller.attach { callbacks.incrementAndGet() }
        controller.detach()
        val detachedCount = callbacks.get()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        ShadowLooper.idleMainLooper()
        assertEquals(detachedCount, callbacks.get())
        assertFalse(controller.busy)
        assertTrue(controller.status.startsWith("Cancelled"))
        assertNull(controller.release)
    }
    @Test fun foregroundCheckDoesNotSkipFreshLaunchBecauseOfDailyCache() {
        val context = RuntimeEnvironment.getApplication()
        val storage = "update_test_launch_controller"
        context.getSharedPreferences(storage, 0).edit()
            .putLong("last_success", System.currentTimeMillis())
            .putLong("last_attempt", System.currentTimeMillis()).commit()
        var checks = 0
        val controller = object : UpdateController(context, "ScrcpyReceive", "ScrcpyReceive.apk", storage) {
            override fun check() { checks++ }
        }
        controller.foregroundCheck()
        assertEquals(1, checks)
    }
    @Test fun oldReceiverRepositoryIsNotAnAcceptedUpdaterTarget() {
        assertThrows(IllegalArgumentException::class.java) {
            object : UpdateController(RuntimeEnvironment.getApplication(), "ScrcpyRecv", "ScrcpyReceive.apk") {}
        }
    }
}
