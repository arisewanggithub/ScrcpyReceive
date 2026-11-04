package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AudioOutputClockPipelineTest {
    @Test fun preparedOutputWith129msHardwareTransitDoesNotTreatDacLatencyAsClientOccupancy() = stream(false)

    @Test fun timestampReturningTrueButStaleDoesNotHideAdvancingClientConsumption() = stream(true)

    @Test fun aTrueClientOutputStallStillCancelsSharingExplicitly() = stream(false, stall = true)

    @Test fun sharedClockFailureObservedByVideoWorkerRetainsAudioCodeAndNumericCause() {
        val now = AtomicLong(1_000_000_000)
        var head = 480
        val position = AudioPosition(48000, 6240, now::get, { null }, { head })
        position.written(6240)
        position.current()
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
        clock.awaitAudioStart(1_000_000) { true }
        clock.anchorAudio(1_000_000) { position.current().presentedUs }
        clock.audioWritten(130_000)
        head = 0
        val failed = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Pair<String, String>>()
        val worker = object : TrackWorker("test-video-clock", 1024, { code, detail ->
            failures.add(code to detail); failed.countDown()
        }, track = 1) {
            override fun step() { clock.currentUs() }
            override fun release() {}
        }
        try {
            worker.start()
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            val error = failures.single()
            assertEquals("AUDIO_FAILED", error.first)
            assertTrue(error.second, error.second.startsWith("Audio stage=OUTPUT_CLOCK exception=AudioClockException:"))
            assertTrue(error.second.contains("clock_reason=HEAD_REGRESSION"))
            assertTrue(error.second.contains("head_delta_frames=-480"))
        } finally { worker.close() }
    }

    private fun stream(stale: Boolean, stall: Boolean = false) {
        val now = AtomicLong(1_000_000_000)
        val format = AudioFormat("pcm", 48000, 2, byteArrayOf())
        val native = TraceTrack(now, stale, stall)
        val platform = object : AudioOutputPlatform {
            override fun minimumBytes(format: AudioFormat) = 5760 * 4
            override fun create(format: AudioFormat, bytes: Int, failure: (String, String) -> Unit): RateAudioTrack {
                assertEquals(AudioOutputPreparation.plan(format, 5760 * 4).bytes, bytes)
                return native
            }
        }
        val errors = ConcurrentLinkedQueue<String>()
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
            .apply { audioExpected = true; anchorVideo(8_000_000_000) }
        val output = PlatformAudioOutput(format, { code, detail -> errors.add("$code: $detail") }, platform)
        val readings = ConcurrentLinkedQueue<AudioOutputTiming>()
        val player = AudioPlayer(format, clock, { code, detail -> errors.add("$code: $detail") }, { output },
            onOutputTiming = { readings.add(it) })
        try {
            player.start()
            repeat(60) { index ->
                now.addAndGet(21_333_333)
                // AAC-sized decoded PCM, including the reported first correction at 48,128 frames.
                player.offer(AccessUnit(8_000_000_000 + index * 1024_000_000L / 48000, ByteArray(4096)))
                await {
                    if (stall) now.addAndGet(1_000_000)
                    player.sourceSubmittedFrames >= (index + 1) * 1024L || errors.isNotEmpty()
                }
                if (errors.isNotEmpty() && stall) {
                    assertEquals(1, errors.size)
                    assertTrue(errors.single(), errors.single().contains("stage=OUTPUT_WRITE") &&
                        errors.single().contains("Audio output stalled for 250 ms"))
                    return
                }
                assertTrue(errors.toString(), errors.isEmpty())
            }
            assertFalse("A genuine stall must not become clock recovery or silent audio disable", stall)
            await { native.speeds.size > 3 || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(61_440L, player.sourceSubmittedFrames)
            assertTrue(native.maximumQueued <= 6240)
            val first = readings.first()
            assertTrue(first.submittedFrames >= 48_000)
            assertTrue(first.bufferedUs <= 130_000)
            if (stale) assertEquals(AudioClockSource.HEAD_STALE, first.clockSource)
            else {
                assertEquals(AudioClockSource.TIMESTAMP, first.clockSource)
                assertEquals(6192L, first.consumedFrames - first.presentedFrames)
                assertTrue(first.consumedFrames > first.presentedFrames)
            }
            assertTrue(native.speeds.drop(3).all { it in 0.98f..1.02f })
            val presented = player.sourcePositionUs()
            assertEquals(8_000_000_000 + presented, clock.currentUs())
            clock.endVideoPreroll()
            assertEquals(0L, clock.videoDelayUs(8_001_280_000))
        } finally { player.close() }
        assertTrue(native.closed)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Actual audio worker made no progress", condition())
    }

    private class TraceTrack(private val now: AtomicLong, private val stale: Boolean, private val stall: Boolean) : RateAudioTrack {
        override val initialized = true
        override val sampleRate = 48000
        override val channels = 2
        override val pcm16 = true
        override val bufferFrames = 6240
        override val capacityFrames = 6240
        override val pitch = 1f
        override val failOnUnsupported = true
        override var isPlaying = false
        private var actualSpeed = 1f
        override val speed get() = actualSpeed
        override val underrunCount = 0
        val written = AtomicLong()
        val speeds = ConcurrentLinkedQueue<Float>()
        @Volatile var maximumQueued = 0L
        @Volatile var closed = false
        private var startNs = 0L
        private val position = AudioPosition(sampleRate, bufferFrames, now::get, {
            if (stale) FrameTimestamp(0, startNs)
            else FrameTimestamp((consumed() - 6192).coerceAtLeast(0), now.get())
        }, { consumed().toInt() })
        private fun consumed() = minOf(written.get(), if (stall) 24000L else Long.MAX_VALUE,
            ((now.get() - startNs) * sampleRate / 1_000_000_000).coerceAtLeast(0))
        override fun play() { startNs = now.get(); isPlaying = true }
        override fun pause() { isPlaying = false }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int = synchronized(position) {
            val available = (bufferFrames - (written.get() - consumed())).toInt()
            if (available == 0) { now.addAndGet(1_000_000); return 0 }
            val frames = minOf(count / channels, available)
            written.addAndGet(frames.toLong())
            position.written(frames)
            maximumQueued = maxOf(maximumQueued, written.get() - consumed())
            frames * channels
        }
        override fun positionUs() = position.current().presentedUs
        override fun timing() = position.current()
        override fun setSpeed(speed: Float) { require(speed in 0.98f..1.02f); actualSpeed = speed; speeds.add(speed) }
        override fun close() { closed = true }
    }
}
