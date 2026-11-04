package org.screencopy.receiver.media

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import org.json.JSONObject
import org.screencopy.receiver.protocol.SrtTransport
import java.lang.reflect.Proxy
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SrtLoadingClockTest {
    @Test fun heldVideoOnlyPictureKeepsTheClockAdvancingUntilLoadingCanResume() {
        val readiness = SrtVideoReadiness { it() }
        val delegate = Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, method, _ ->
            when (method.name) {
                "getMediaClock" -> null
                "isReady", "isEnded", "hasReadStreamToEnd" -> false
                "getState" -> Renderer.STATE_STARTED
                else -> null
            }
        } as Renderer
        val renderer = SrtSparseVideoRenderer(delegate, readiness)
        assertFalse("No picture may establish readiness before actual presentation", renderer.isReady())
        readiness.reset(active = true)
        readiness.onRenderedFirstFrame()
        assertFalse("The drained codec itself is unready after immediate presentation", delegate.isReady())
        assertTrue("A real held picture is still valid without an audio renderer", renderer.isReady())
        val (_, position) = playerClock(renderer)
        val firstPosition = position()
        val futurePictureUs = firstPosition + 1_000_000
        val control = SrtLiveLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(50, 250, 0, 0)
            .setPrioritizeTimeOverSizeThresholds(true).build()) { 0 }
        val id = PlayerId.UNSET
        val timeline = SinglePeriodTimeline(C.TIME_UNSET, false, true, false, null,
            MediaItem.fromUri("srt://127.0.0.1:43517"))
        fun parameters(current: Long) = LoadControl.Parameters(id, timeline, MediaPeriodId(timeline.getUidOfPeriod(0)),
            current, (futurePictureUs - current).coerceAtLeast(0), 1f, true, false, C.TIME_UNSET, C.TIME_UNSET)
        control.onPrepared(id)
        try {
            assertFalse(control.shouldContinueLoading(parameters(firstPosition)))
            ShadowSystemClock.advanceBy(Duration.ofMillis(1100))
            val advanced = position()
            assertEquals(firstPosition + 1_100_000, advanced)
            assertTrue(renderer.isReady())
            assertTrue("The running video-only clock must unblock loading after a future picture",
                control.shouldContinueLoading(parameters(advanced)))
            renderer.resetPosition(advanced, true)
            assertFalse("Reconfiguration must retire held-picture proof", renderer.isReady())
        } finally { control.onReleased(id) }
    }

    @Test fun nonzeroCaptureOriginIsNotCountedAsAlreadyBufferedMedia() {
        val progress = SrtExtractionProgress()
        progress.firstSample(C.TRACK_TYPE_AUDIO, 1_250_000)
        progress.firstSample(C.TRACK_TYPE_VIDEO, 1_250_000)
        val stock = DefaultLoadControl.Builder().setBufferDurationsMs(50, 250, 0, 0)
            .setPrioritizeTimeOverSizeThresholds(true).build()
        val control = SrtLiveLoadControl(stock) { progress.sourceOriginUs }
        val id = PlayerId.UNSET
        val timeline = SinglePeriodTimeline(C.TIME_UNSET, false, true, false, null,
            MediaItem.fromUri("srt://127.0.0.1:43517"))
        fun parameters(buffered: Long, position: Long = 0) = LoadControl.Parameters(id, timeline,
            MediaPeriodId(timeline.getUidOfPeriod(0)), position, buffered, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET)
        control.onPrepared(id)
        try {
            assertFalse("Stock loading stops before the native codec can produce startup output",
                stock.shouldContinueLoading(parameters(1_250_000)))
            assertTrue(control.shouldContinueLoading(parameters(1_250_000)))
            val allocator = control.getAllocator(id)
            val allocation = allocator.allocate()
            try {
                assertEquals(allocator.individualAllocationLength, stock.getAllocator(id).totalBytesAllocated)
            } finally { allocator.release(allocation) }
            assertFalse("The existing Media3 buffer ceiling still applies to actual sample duration",
                control.shouldContinueLoading(parameters(1_750_000)))
            assertTrue("The source origin remains a constant offset after scheduling advances",
                control.shouldContinueLoading(parameters(1_250_000, 2_000_000)))
            progress.firstSample(C.TRACK_TYPE_AUDIO, 1_249_551)
            assertEquals("Later legal Opus reversals must not redefine the source epoch",
                1_250_000L, progress.sourceOriginUs)
        } finally { control.onReleased(id) }
    }

    @Test fun sourceOriginUsesFirstMetadataOfBothTracksWithoutRewritingTheirOrder() {
        val progress = SrtExtractionProgress()
        assertEquals(0L, progress.sourceOriginUs)
        progress.firstSample(C.TRACK_TYPE_AUDIO, 1_300_000)
        assertEquals(1_300_000L, progress.sourceOriginUs)
        progress.firstSample(C.TRACK_TYPE_VIDEO, 1_250_000)
        assertEquals(1_250_000L, progress.sourceOriginUs)
        progress.firstSample(C.TRACK_TYPE_VIDEO, 0)
        assertEquals(1_250_000L, progress.sourceOriginUs)
    }

    private fun playerClock(renderer: Renderer): Pair<MediaClock, () -> Long> {
        val type = Class.forName("androidx.media3.exoplayer.DefaultMediaClock")
        val listenerType = Class.forName("androidx.media3.exoplayer.DefaultMediaClock\$PlaybackParametersListener")
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, _, _ -> null }
        val instance = type.getDeclaredConstructor(listenerType, Clock::class.java).apply { isAccessible = true }
            .newInstance(listener, Clock.DEFAULT)
        type.getDeclaredMethod("onRendererEnabled", Renderer::class.java).apply { isAccessible = true }.invoke(instance, renderer)
        type.getDeclaredMethod("start").apply { isAccessible = true }.invoke(instance)
        val sync = type.getDeclaredMethod("syncAndGetPositionUs", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        return (instance as MediaClock) to { sync.invoke(instance, false) as Long }
    }

    @Test fun stalledSourceClockNoLongerFreezesMedia3LoadingWhileReadinessStillNeedsRealSource() {
        val source = object : MediaClock {
            override fun getPositionUs() = 0L
            override fun getPlaybackParameters() = PlaybackParameters.DEFAULT
            override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {}
        }
        val delegate = Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, method, _ ->
            when (method.name) {
                "getMediaClock" -> source
                "isReady" -> true
                "isEnded", "hasReadStreamToEnd" -> false
                "getState" -> Renderer.STATE_STARTED
                else -> null
            }
        } as Renderer
        val (_, oldPosition) = playerClock(delegate)
        assertEquals(0L, oldPosition())
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        assertEquals("Native silence cannot advance the source-only master", 0L, oldPosition())

        var sourceProof = false
        val independent = SrtRecoveredAudioRenderer(delegate) { sourceProof }
        val (_, newPosition) = playerClock(independent)
        val before = newPosition()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        assertEquals(before + 2_000_000, newPosition())
        assertFalse("A moving scheduling clock is not audio source readiness", sourceProof)
    }

    @Test fun rendererPublishesTheSchedulingPositionBeforeDecodingAhead() {
        var position = -1L
        val delegate = Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, method, args ->
            if (method.name == "render") assertEquals(args[0] as Long, position)
            null
        } as Renderer
        val renderer = SrtRecoveredAudioRenderer(delegate, { position = it }) { false }
        renderer.render(1_000_000, 55)
        assertEquals(1_000_000L, position)
        assertNull(renderer.mediaClock)
    }

    @Test fun decodedFifoGetsServicedBeforeTheNativeBufferBasedSleepDeadline() {
        var duration = 60_000L
        val delegate = Proxy.newProxyInstance(Renderer::class.java.classLoader, arrayOf(Renderer::class.java)) { _, method, _ ->
            if (method.name == "getDurationToProgressUs") duration else null
        } as Renderer
        val renderer = SrtRecoveredAudioRenderer(delegate) { true }
        assertEquals(5000L, renderer.getDurationToProgressUs(1_000_000, 55))
        duration = 2000
        assertEquals("Earlier codec deadlines must still win", 2000L, renderer.getDurationToProgressUs(1_000_000, 55))
    }

    @Test fun inputTimingSeparatesTransportWaitFromLoaderSuspension() {
        val input = SrtDataSource.Factory(SrtTransport.parse(JSONObject()
            .put("kind", "srt").put("container", "fmp4").put("url", "srt://127.0.0.1:43517")
            .put("latencyMs", 100).put("streamId", "sc-0000000000000001")))
        input.observeReadStart(1_000_000_000)
        input.observeReadEnd(1_000_000_000, 1_300_000_000)
        assertEquals(300_000L, input.maxReadWaitUs)
        assertEquals(0L, input.maxReadCallGapUs)
        input.observeReadStart(1_380_000_000)
        input.observeReadEnd(1_380_000_000, 1_400_000_000)
        assertEquals(300_000L, input.maxReadWaitUs)
        assertEquals(80_000L, input.maxReadCallGapUs)
        input.close()
    }
}
