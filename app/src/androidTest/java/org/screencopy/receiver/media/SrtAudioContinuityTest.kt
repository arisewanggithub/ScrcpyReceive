package org.screencopy.receiver.media

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sin

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SrtAudioContinuityTest {
    @Test fun nativeSonicOutputWithSteadyPcmDoesNotUnderrunOrDiscardSamples() {
        val thread = HandlerThread("SrtAudioContinuity").apply { start() }
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        Handler(thread.looper).post {
            var sink: SrtLowLatencyAudioSink? = null
            try {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val output = AudioTrackAudioOutputProvider.Builder(context)
                    .setAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()
                        .setTargetPcmBufferDurationUs(5000).build()).build()
                val events = mutableListOf<AudioRegulationDiagnostic>()
                val next = SrtLowLatencyAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(false).setAudioOutputProvider(output).build(),
                    report = events::add, opus = true)
                sink = next
                var underruns = 0
                next.setListener(object : AudioSink.Listener {
                    override fun onPositionDiscontinuity() {}
                    override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) {}
                    override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                        underruns++
                    }
                })
                next.configure(AudioSink.AudioSinkConfig.Builder(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setSampleRate(48000)
                    .setChannelCount(2).setPcmEncoding(C.ENCODING_PCM_16BIT).build()).build())
                next.play()
                val base = 1_000_000_000_000L
                var index = 0L
                fun packet() = ByteBuffer.allocateDirect(3840).order(ByteOrder.LITTLE_ENDIAN).apply {
                    repeat(960) { frame ->
                        val sample = (sin((index * 960 + frame) * 2 * Math.PI * 997 / 48000) * 4096).toInt().toShort()
                        putShort(sample); putShort(sample)
                    }
                    flip()
                }
                var input = packet()
                val start = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - start < 15_000) {
                    val position = base + (SystemClock.elapsedRealtime() - start) * 1000
                    next.setPlaybackPositionUs(position)
                    next.hasPendingData()
                    while (next.handleBuffer(input, base + index * 20_000, 1)) {
                        index++
                        input = packet()
                    }
                    Thread.sleep(5)
                }
                assertTrue(next.sourceAdvanced)
                assertTrue(events.toString(), events.isNotEmpty())
                assertEquals("Steady PCM was discarded: $events", 0L, events.last().droppedFrames)
                assertTrue("Unexpected startup silence: $events", events.first().silenceFrames <= 240)
                assertEquals("Steady PCM needed recovery silence: $events",
                    events.first().silenceFrames, events.last().silenceFrames)
                assertEquals("Native output underruns with a continuously available source", 0, underruns)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                try { sink?.release() } catch (error: Throwable) {
                    failure.get()?.addSuppressed(error) ?: failure.set(error)
                }
                done.countDown()
            }
        }
        try {
            assertTrue("Native sink test did not finish", done.await(25, TimeUnit.SECONDS))
            failure.get()?.let { throw it }
        } finally {
            thread.quitSafely()
            thread.join(5000)
            assertFalse("Native sink owner survived cleanup", thread.isAlive)
        }
    }
}
