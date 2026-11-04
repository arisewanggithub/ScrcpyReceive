package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AudioPipelineTest {
    @Test fun exactReported64112usRefreshDoesNotCancelTheProductionWorker() =
        stream(0, { index -> if (index >= 10) 64_112 else 0 })
    @Test fun realAudioWorkerDrainsContinuousPacketsWithThreeHundredMillisecondInitialOffset() = stream(300_000)
    @Test fun realAudioWorkerDrainsContinuousPacketsWithTwoSecondInitialOffset() = stream(2_000_000)
    @Test fun refreshedMonotonicCaptureTimestampDoesNotCancelContinuousPcm() =
        stream(0, { index -> if (index >= 10) 6_000 else 0 })
    @Test fun backwardTimestampPhaseCorrectionRemainsMonotonicAndDoesNotCancelPcm() =
        stream(0, { index -> if (index >= 10) -12_666 else 0 })
    @Test fun sourcePhaseChangeIsRecoverableRatherThanMalformedMedia() =
        stream(0, { index -> if (index >= 10) 100_000 else 0 })
    @Test fun sourcePhaseDoesNotDrivePhysicalAudioBufferRegulation() =
        stream(0, { index -> if (index >= 10) 30_000 else 0 }, expectedSpeed = 1.0f)

    private fun stream(offsetUs: Long, timestampCorrection: (Int) -> Long = { 0 },
        expectedFailure: String? = null, expectedSpeed: Float? = null) {
        val time = AtomicLong()
        val waits = AtomicInteger()
        val tick = Semaphore(0)
        val errors = ConcurrentLinkedQueue<String>()
        val clock = PlaybackClock(time::get) {
            waits.incrementAndGet()
            check(tick.tryAcquire(3, TimeUnit.SECONDS))
        }.apply { audioExpected = true; anchorVideo(1_000_000) }
        val output = BoundedOutput(time)
        val player = AudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output })
        try {
            assertEquals(0L, clock.videoDelayUs(1_000_000))
            player.start()
            player.offer(packet(1_000_000 + offsetUs))
            await { waits.get() > 0 || errors.isNotEmpty() }
            for (index in 1..60) {
                val previousWaits = waits.get()
                time.addAndGet(20_000_000)
                player.offer(packet(1_000_000 + offsetUs + index * 20_000 + timestampCorrection(index)))
                tick.release()
                await {
                    waits.get() > previousWaits || player.sourceSubmittedFrames >= (index + 1) * 960 || errors.isNotEmpty()
                }
                if (expectedFailure != null && errors.isNotEmpty()) {
                    assertTrue(errors.toString(), errors.any { it.startsWith("AUDIO_FAILED:") && it.contains(expectedFailure) })
                    return
                }
                assertTrue(errors.toString(), errors.isEmpty())
            }
            assertNull("Expected a genuine discontinuity to fail", expectedFailure)
            await { player.sourceSubmittedFrames == 61 * 960L || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(61 * 960L, player.sourceSubmittedFrames)
            assertTrue(output.playing.get())
            expectedSpeed?.let { assertTrue("Rate correction stays in its prepared envelope: ${output.speeds}",
                output.speeds.isNotEmpty() && output.speeds.all { it in 0.98f..1.02f }) }
            clock.endVideoPreroll()
            assertEquals(0L, clock.videoDelayUs(1_000_000))
        } finally {
            tick.release(100)
            player.close()
        }
        assertTrue(output.closed.get())
    }

    @Test fun waitingForFirstVideoConsumesAudioAsBoundedDecoderPreroll() {
        val time = AtomicLong()
        val clock = PlaybackClock(time::get, { time.addAndGet(it * 1_000_000) }).apply { audioExpected = true }
        val errors = ConcurrentLinkedQueue<String>()
        val output = BoundedOutput(time)
        val player = AudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output })
        try {
            player.start()
            repeat(20) {
                player.offer(packet(1_000_000L + it * 20_000))
                // Use the real queue/decoder consumer, not a separately tested clock helper.
                await { player.queuedPackets() == 0 || errors.isNotEmpty() }
                time.addAndGet(20_000_000)
            }

            assertFalse(output.playing.get())
            clock.anchorVideo(1_300_000)
            player.offer(packet(1_400_000))
            await { output.playing.get() || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
        } finally { player.close() }
    }

    @Test fun actualOutputWriteFailureRemainsFatalAndReportsStageWithoutDisablingAudio() {
        val time = AtomicLong()
        val clock = PlaybackClock(time::get, { time.addAndGet(it * 1_000_000) })
            .apply { audioExpected = true; anchorVideo(1_000_000) }
        val failed = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<String>()
        val output = object : AudioOutput {
            override val underrunCount = 0
            override fun play() {}
            override fun pause() {}
            override fun write(samples: ShortArray, offset: Int, count: Int) = -6
            override fun positionUs() = 0L
            override fun timing() = AudioOutputTiming(48000, 5760, 0, 0, 0, null, AudioClockSource.HEAD_UNAVAILABLE)
            override fun setSpeed(speed: Float) {}
            override fun close() {}
        }
        val player = AudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail"); failed.countDown() }, { output })
        try {
            player.start()
            player.offer(packet(1_000_000))
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertEquals(1, errors.size)
            assertTrue(errors.single().startsWith("AUDIO_FAILED: Audio stage=OUTPUT_WRITE exception=IllegalStateException:"))
            assertTrue(errors.single().contains("AudioTrack write failed (-6)"))
            assertTrue(clock.audioExpected)
        } finally { player.close() }
    }

    private fun packet(pts: Long) = AccessUnit(pts, ByteArray(1920 * 2))
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < end) Thread.sleep(1)
        assertTrue("Production audio worker made no progress", condition())
    }

    private class BoundedOutput(private val time: AtomicLong) : AudioOutput {
        val samples = AtomicInteger()
        val playing = AtomicBoolean()
        val closed = AtomicBoolean()
        val speeds = ConcurrentLinkedQueue<Float>()
        private var startNs = 0L
        override val underrunCount = 0
        override fun play() { startNs = time.get(); playing.set(true) }
        override fun pause() { playing.set(false) }
        override fun positionUs(): Long = if (playing.get()) minOf((time.get() - startNs) / 1000, samples.get() * 1_000_000L / 96000) else 0
        override fun timing(): AudioOutputTiming {
            val frames = positionUs() * 48000 / 1_000_000
            return AudioOutputTiming(48000, 5760, samples.get().toLong() / 2, frames, frames, null, AudioClockSource.HEAD_UNAVAILABLE)
        }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            val buffered = this.samples.get() - positionUs() * 96000 / 1_000_000
            val available = (11520 - buffered).coerceAtLeast(0).toInt()
            val written = minOf(count, available)
            this.samples.addAndGet(written)
            return written
        }
        override fun setSpeed(speed: Float) { speeds.add(speed) }
        override fun close() { closed.set(true) }
    }
}
