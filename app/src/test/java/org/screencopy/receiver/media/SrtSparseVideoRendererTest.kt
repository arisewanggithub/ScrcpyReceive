package org.screencopy.receiver.media

import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.Renderer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SrtSparseVideoRendererTest {
    @Test fun recoveredAudioNeedsRealSourceProofAndNeverHidesAStreamError() {
        val f = Fixture()
        var sourceAdvanced = false
        val audio = SrtRecoveredAudioRenderer(f.delegate) { sourceAdvanced }
        assertFalse(audio.isReady())
        sourceAdvanced = true
        assertTrue(audio.isReady())
        assertNull("Source audio clock must not become Media3's loading/presentation master", audio.getMediaClock())
        f.streamError = IOException("synthetic source failure")
        assertThrows(IOException::class.java) { audio.isReady() }
        sourceAdvanced = false
        assertFalse(audio.isReady())
    }

    private class Fixture {
        val main = ArrayDeque<() -> Unit>()
        val readiness = SrtVideoReadiness { main.addLast(it) }
        val calls = mutableListOf<String>()
        var delegateReady = false
        var streamError: IOException? = null
        val clock = Proxy.newProxyInstance(MediaClock::class.java.classLoader,
            arrayOf(MediaClock::class.java)) { _, _, _ -> null } as MediaClock
        val delegate = Proxy.newProxyInstance(Renderer::class.java.classLoader,
            arrayOf(Renderer::class.java)) { _, method, _ ->
            calls += method.name
            when (method.name) {
                "isReady" -> delegateReady
                "isEnded", "hasReadStreamToEnd", "isCurrentStreamFinal" -> false
                "getMediaClock" -> clock
                "maybeThrowStreamError" -> { streamError?.let { throw it }; null }
                else -> null
            }
        } as Renderer
        val renderer = SrtSparseVideoRenderer(delegate, readiness)

        fun drainMain() { while (main.isNotEmpty()) main.removeFirst().invoke() }
        fun renderFirstFrame() {
            readiness.reset(active = true)
            drainMain()
            readiness.onRenderedFirstFrame()
        }
    }

    @Test fun neverOverridesUnreadyVideoBeforeActualFirstFrame() {
        val f = Fixture()
        assertFalse(f.renderer.isReady())
        f.readiness.reset(active = true)
        f.drainMain()
        assertFalse(f.renderer.isReady())
        f.readiness.onRenderedFirstFrame()
        assertTrue(f.renderer.isReady())
        assertEquals(listOf("isReady", "isReady", "isReady", "maybeThrowStreamError"), f.calls)
    }

    @Test fun queuedOldFirstFrameCannotArmANewPositionOrRetiredStream() {
        val f = Fixture()
        f.renderFirstFrame()
        f.renderer.resetPosition(100, true)
        // The old first-frame callback was queued before the new epoch acknowledgement.
        f.readiness.onRenderedFirstFrame()
        assertFalse(f.renderer.isReady())
        f.drainMain()
        assertFalse(f.renderer.isReady())
        f.readiness.onRenderedFirstFrame()
        assertTrue(f.renderer.isReady())
        f.renderer.disable()
        f.readiness.onRenderedFirstFrame()
        f.drainMain()
        f.readiness.onRenderedFirstFrame()
        assertFalse(f.renderer.isReady())
    }

    @Test fun resetsAndReleaseRetireProofButBufferingStopPreservesTheHeldPicture() {
        val f = Fixture()
        f.renderFirstFrame()
        f.renderer.stop()
        assertTrue(f.renderer.isReady())
        f.renderer.reset()
        assertFalse(f.renderer.isReady())
        f.renderFirstFrame()
        f.renderer.release()
        assertFalse(f.renderer.isReady())
        assertTrue(f.calls.containsAll(listOf("stop", "reset", "release")))
    }

    @Test fun forwardsDefaultReadinessRenderingClockAndEndStateWithoutFabrication() {
        val f = Fixture()
        f.delegateReady = true
        assertTrue(f.renderer.isReady())
        assertFalse(f.calls.contains("maybeThrowStreamError"))
        f.renderer.render(123, 456)
        assertSame(f.clock, f.renderer.getMediaClock())
        assertFalse(f.renderer.isEnded())
        assertFalse(f.renderer.hasReadStreamToEnd())
        assertFalse(f.renderer.isCurrentStreamFinal())
        assertTrue(f.calls.contains("render"))
    }

    @Test fun readinessOverrideDoesNotHideStreamErrors() {
        val f = Fixture()
        f.renderFirstFrame()
        val error = IOException("synthetic stream failure")
        f.streamError = error
        assertSame(error, assertThrows(IOException::class.java) { f.renderer.isReady() })
    }
}
