package org.screencopy.receiver.media

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SrtLowLatencyAudioSinkTest {
    @Test fun nonzeroInitialSourcePtsCanFillStartupWithoutAdvancingTheSchedulingClock() {
        val device = Device()
        val sink = SrtLowLatencyAudioSink(device.sink, { 0 }, {}, opus = true)
        val position = 1_000_000_000_000L
        val source = position + 1_250_000
        try {
            sink.setPlaybackPositionUs(position)
            configure(sink)
            repeat(3) { assertTrue("A stopped startup clock must not block source preroll",
                sink.handleBuffer(packet(), source + it * 20_000, 1)) }
            assertFalse("Queued source is not proof of actual presentation", sink.sourceAdvanced)
            val future = packet()
            assertFalse(sink.handleBuffer(future, source + 250_000, 1))
            assertEquals(0, future.position())
            device.advance()
            sink.hasPendingData()
            assertFalse("Startup silence is not source-audio presentation", sink.sourceAdvanced)
            device.advance()
            sink.hasPendingData()
            assertTrue(sink.sourceAdvanced)
            sink.setPlaybackPositionUs(position + 250_000)
            assertTrue(sink.handleBuffer(future, source + 250_000, 1))
        } finally { sink.release() }
    }

    @Test fun onlyOpusGrantsTheArrivalOrderedPtsReversalException() {
        for (opus in listOf(false, true)) {
            val device = Device()
            val sink = SrtLowLatencyAudioSink(device.sink, { 0 }, {}, opus)
            configure(sink)
            try {
                assertTrue(sink.handleBuffer(packet(), 1_000_000, 1))
                if (opus) assertTrue(sink.handleBuffer(packet(), 999_551, 1))
                else assertThrows(IllegalArgumentException::class.java) { sink.handleBuffer(packet(), 999_551, 1) }
                assertFalse(sink.sourceAdvanced)
            } finally { sink.release() }
        }
    }

    private class Device {
        var accepted = 0L
        var consumed = 0L
        var firstPts: Long? = null
        var listener: AudioSink.Listener? = null
        var parameters = PlaybackParameters.DEFAULT
        var configuredDelay = -1
        var notified = false
        var released = false
        var failure = false
        var bufferUs = 5000L
        var parameterChanges = 0
        val sink = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, arguments ->
            when (method.name) {
                "setListener" -> { listener = arguments[0] as AudioSink.Listener; null }
                "configure" -> { configuredDelay = (arguments[0] as AudioSink.AudioSinkConfig).format.encoderDelay; null }
                "handleBuffer" -> {
                    if (failure) throw IllegalStateException("synthetic device failure")
                    val buffer = arguments[0] as ByteBuffer
                    if (firstPts == null) firstPts = arguments[1] as Long
                    val frames = minOf(buffer.remaining() / 4, (240 - accepted + consumed).toInt())
                    accepted += frames
                    buffer.position(buffer.position() + frames * 4)
                    !buffer.hasRemaining()
                }
                "getCurrentPositionUs" -> {
                    if (consumed > 0 && !notified) {
                        notified = true
                        listener?.onPositionAdvancing(123)
                    }
                    firstPts?.let { it + consumed * 1_000_000 / 48000 } ?: AudioSink.CURRENT_POSITION_NOT_SET
                }
                "hasPendingData" -> accepted > consumed
                "isEnded" -> accepted == consumed
                "getPlaybackParameters" -> parameters
                "getAudioTrackBufferSizeUs" -> bufferUs
                "setPlaybackParameters" -> { parameterChanges++; parameters = arguments[0] as PlaybackParameters; null }
                "flush", "reset" -> { accepted = 0; consumed = 0; firstPts = null; notified = false; null }
                "release" -> { released = true; null }
                else -> null
            }
        } as AudioSink
        fun advance() { consumed = minOf(accepted, consumed + 240) }
    }
    private fun configure(sink: SrtLowLatencyAudioSink, delay: Int = 0) {
        sink.configure(AudioSink.AudioSinkConfig.Builder(Format.Builder().setSampleRate(48000).setChannelCount(2)
            .setPcmEncoding(C.ENCODING_PCM_16BIT).setEncoderDelay(delay).build()).build())
        sink.play()
    }
    private fun packet(): ByteBuffer = ByteBuffer.allocateDirect(3840).order(ByteOrder.LITTLE_ENDIAN).apply {
        repeat(1920) { putShort(7) }
        flip()
    }
    @Test fun decodedReadAheadIsBackpressuredWithoutDroppingSteadyAudioOrChangingTheHeldBuffer() {
        val device = Device()
        var now = 0L
        val events = mutableListOf<AudioRegulationDiagnostic>()
        val sink = SrtLowLatencyAudioSink(device.sink, { now }, events::add, opus = true)
        configure(sink)
        var next = packet()
        var pts = 1_000_000L
        try {
            repeat(4000) {
                now += 5_000_000
                device.advance()
                sink.setPlaybackPositionUs(1_000_000 + now / 1000)
                sink.hasPendingData()
                while (pts <= 1_000_000 + now / 1000 + 250_000 && sink.handleBuffer(next, pts, 1)) {
                    next = packet()
                    pts += 20_000
                }
                assertEquals("A future codec output must remain untouched for Media3 to retry", 0, next.position())
            }
            assertTrue(events.isNotEmpty())
            assertTrue("Decoder read-ahead caused audible output loss: $events", events.all { it.droppedFrames == 0L })
            assertTrue(events.all { it.bufferedFrames <= 5520 && it.speed in 0.98f..1.02f })
            var previousSpeed = 1f
            var changes = 0
            events.forEach {
                if (it.speed != previousSpeed) changes++
                previousSpeed = it.speed
            }
            assertEquals("Unchanged rates must not drain Sonic's processors", changes, device.parameterChanges)
            assertTrue(sink.sourceAdvanced)
            sink.flush()
            assertNull(sink.regulation)
            assertFalse(sink.sourceAdvanced)
        } finally { sink.release() }
    }

    @Test fun sourceEpochReanchorsAfterFlushAndResetWithoutErasingTheRendererPosition() {
        val device = Device()
        val sink = SrtLowLatencyAudioSink(device.sink, { 0 }, {}, opus = true)
        try {
            sink.setPlaybackPositionUs(1_000_000)
            configure(sink)
            repeat(3) { assertTrue(sink.handleBuffer(packet(), 11_000_000 + it * 20_000L, 1)) }
            val future = packet()
            assertFalse(sink.handleBuffer(future, 11_250_000, 1))
            assertEquals(0, future.position())
            sink.flush()
            sink.play()
            repeat(3) { assertTrue(sink.handleBuffer(packet(), 21_000_000 + it * 20_000L, 1)) }
            assertFalse(sink.handleBuffer(future, 21_250_000, 1))
            assertFalse(sink.sourceAdvanced)
            sink.setPlaybackPositionUs(1_250_000)
            assertTrue(sink.handleBuffer(future, 21_250_000, 1))
            sink.reset()
            sink.play()
            sink.setPlaybackPositionUs(3_000_000)
            repeat(3) { assertTrue(sink.handleBuffer(packet(), 31_000_000 + it * 20_000L, 1)) }
            assertFalse(sink.handleBuffer(packet(), 31_250_000, 1))
        } finally { sink.release() }
    }

    @Test fun actualDeviceBufferAndImplicitNativeReplacementAreNotSilentFallbacks() {
        val device = Device()
        val sink = SrtLowLatencyAudioSink(device.sink, { 0 }, {})
        sink.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() {}
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {}
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {}
        })
        configure(sink)
        val native = checkNotNull(device.listener)
        val config = AudioSink.AudioTrackConfig(C.ENCODING_PCM_16BIT, 48000, 12, false, false, 960)
        native.onAudioTrackInitialized(config)
        assertThrows(IllegalStateException::class.java) { native.onAudioTrackInitialized(config) }
        sink.flush()
        native.onAudioTrackInitialized(config)
        sink.play()
        device.bufferUs = 150_000
        assertThrows(IllegalStateException::class.java) { sink.handleBuffer(packet(), 1_000_000, 1) }
        device.bufferUs = 5000
        assertThrows(IllegalStateException::class.java) { native.onAudioSinkError(IllegalStateException("synthetic device failure")) }
        assertFalse(sink.sourceAdvanced)
        sink.release()
        assertTrue(device.released)
    }
    @Test fun startupAndUnderrunSilenceCannotArmSourceAudioReadiness() {
        val device = Device()
        var now = 0L
        val sink = SrtLowLatencyAudioSink(device.sink, { now }, {})
        var advances = 0
        sink.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() {}
            override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) { advances++ }
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {}
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {}
        })
        configure(sink, 312)
        assertEquals(0, device.configuredDelay)
        assertTrue(sink.handleBuffer(packet(), 1_000_000, 1))
        repeat(40) {
            now += 5_000_000
            device.advance()
            assertEquals(1_006_500L, sink.getCurrentPositionUs(false))
            assertFalse(sink.sourceAdvanced)
            assertEquals(0, advances)
        }
        val second = packet()
        val third = packet()
        while (!sink.handleBuffer(second, 1_020_000, 1)) { device.advance(); sink.getCurrentPositionUs(false) }
        while (!sink.handleBuffer(third, 1_040_000, 1)) { device.advance(); sink.getCurrentPositionUs(false) }
        repeat(40) {
            now += 5_000_000
            device.advance()
            sink.hasPendingData()
        }
        assertTrue(sink.sourceAdvanced)
        assertEquals(1, advances)
        sink.flush()
        assertFalse(sink.sourceAdvanced)
        sink.play()
        assertTrue(sink.handleBuffer(packet(), 3_000_000, 1))
        device.advance()
        assertEquals(3_006_500L, sink.getCurrentPositionUs(false))
        assertFalse(sink.sourceAdvanced)
        assertEquals(1, advances)
        sink.reset()
        assertFalse(sink.sourceAdvanced)
        sink.release()
        assertTrue(device.released)
    }
    @Test fun repeated225msSourceGapsHaveBoundedDecodedRecoveryAndDoNotFail() {
        val device = Device()
        var now = 0L
        val events = mutableListOf<AudioRegulationDiagnostic>()
        val sink = SrtLowLatencyAudioSink(device.sink, { now }, events::add)
        configure(sink)
        val inputs = ArrayDeque<Pair<ByteBuffer, Long>>()
        try {
            repeat(1200) { tick ->
                now += 5_000_000
                device.advance()
                sink.getCurrentPositionUs(false)
                if (tick % 4 == 0) inputs.addLast(packet() to 1_000_000 + tick * 5000L)
                if (tick % 200 !in 80..124) {
                    while (inputs.isNotEmpty()) {
                        val (buffer, pts) = inputs.first()
                        if (!sink.handleBuffer(buffer, pts, 1)) break
                        inputs.removeFirst()
                    }
                }
            }
            assertTrue(events.toString(), events.any { it.silenceFrames > 24000 && it.recoveries > 2 && it.droppedFrames > 0 })
            assertTrue(events.all { it.bufferedFrames <= 5520 && it.speed in 0.98f..1.02f })
            assertTrue(sink.sourceAdvanced)
        } finally { sink.release() }
    }
    @Test fun deviceFailureIsNotConvertedToSilenceOrReadiness() {
        val device = Device()
        val sink = SrtLowLatencyAudioSink(device.sink, { 0 }, {})
        configure(sink)
        device.failure = true
        assertThrows(IllegalStateException::class.java) { sink.handleBuffer(packet(), 1_000_000, 1) }
        assertFalse(sink.sourceAdvanced)
        sink.release()
        assertTrue(device.released)
    }

    @Test fun endOfStreamDrainsShortRealStartupAudioInsteadOfWaitingForeverForTheTarget() {
        val device = Device()
        var now = 0L
        val sink = SrtLowLatencyAudioSink(device.sink, { now }, {})
        configure(sink)
        try {
            assertTrue(sink.handleBuffer(packet(), 1_000_000, 1))
            sink.playToEndOfStream()
            repeat(20) {
                now += 5_000_000
                device.advance()
                sink.getCurrentPositionUs(true)
                sink.playToEndOfStream()
            }
            assertTrue(sink.sourceAdvanced)
            assertEquals(20_000L, sink.sourcePositionUs)
            assertTrue(sink.isEnded())
        } finally { sink.release() }
    }
}
