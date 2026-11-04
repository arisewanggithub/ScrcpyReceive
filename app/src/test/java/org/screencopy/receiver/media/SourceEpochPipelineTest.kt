package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.Configuration
import org.screencopy.receiver.protocol.Request
import org.screencopy.receiver.protocol.StrictJson
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class SourceEpochPipelineTest {
    @Test fun exact64112RefreshSurvivesEightSecondsAndConvergesWithoutLosingPcm() =
        scenario(400, { if (it >= 10) 64_112 else 0 }, expectedOffset = 64_112)

    @Test fun negativeMonotonicRefreshesAreSlewedWithoutRewindingTheSharedClock() =
        scenario(400, { index -> when {
            index < 10 -> 0
            index < 13 -> -(index - 9) * 16_000L
            else -> -64_112
        } }, expectedOffset = -64_112)

    @Test fun multipleTimeVaryingRefreshesDoNotBecomeFatalCumulativePhaseError() =
        scenario(800, { index -> when {
            index < 10 -> 0
            index < 100 -> 64_112
            index < 104 -> 64_112 - (index - 99) * 16_000L
            index < 120 -> 112
            index < 124 -> 112 - (index - 119) * 16_000L
            index < 200 -> -63_888
            else -> 64_112
        } }, expectedOffset = 64_112)

    @Test fun sourceRateAndOutputRateDifferencesRegulateSamplesNotTimestampPhase() {
        for (rate in listOf(0.998, 1.002)) {
            for (sourcePpm in listOf(-2000, 2000)) {
                scenario(1500, { it * 20_000L * sourcePpm / 1_000_000 },
                    deviceRate = rate, expectedRatePpm = sourcePpm)
            }
        }
    }

    @Test fun decodedOpusPreskipAndAnOverlappingFirstRefreshPreserveAllAudibleSamples() {
        val metadata = Configuration.audio(
            StrictJson.decode("""{"codec":"opus","sampleRate":48000,"channels":2,"csd":["T3B1c0hlYWQBAjgBgLsAAAAAAA=="],"codecDelayNs":6500000,"seekPrerollNs":80000000}""".toByteArray()),
            Request("h264", "opus", 60, "")
        )
        // Exercise the common decoded-PCM stage with real Opus metadata, without host JNI/AudioTrack.
        scenario(400, { when { it == 0 -> 0; it < 10 -> -16_000; else -> 64_112 } },
            format = metadata.copy(codec = "pcm"), expectedOffset = 64_112)
    }

    @Test fun decodedAacFrameCadenceUsesTheSameRecoverableSourceMapping() {
        val metadata = Configuration.audio(
            StrictJson.decode("""{"codec":"aac","sampleRate":48000,"channels":2,"csd":["EZA="]}""".toByteArray()),
            Request("h264", "aac", 60, "")
        )
        scenario(400, { if (it >= 10) 64_112 else 0 }, frames = 1024,
            format = metadata.copy(codec = "pcm"), expectedOffset = 64_112)
    }

    @Test fun interleavedVideoAndRealAudioWorkerFollowTheSlewedSourceMaster() =
        scenario(400, { if (it >= 10) 64_112 else 0 }, expectedOffset = 64_112, withVideo = true)

    @Test fun genuinelyDecreasingDecodedPtsStillFailsInsteadOfBeingResynchronized() =
        scenario(50, { if (it >= 10) -40_000 else 0 }, expectedFailure = "Decoded audio PTS decreased")

    private fun scenario(
        packets: Int,
        correction: (Int) -> Long,
        frames: Int = 960,
        format: AudioFormat = AudioFormat("pcm", 48000, 2, byteArrayOf()),
        deviceRate: Double = 1.0,
        expectedOffset: Long? = null,
        withVideo: Boolean = false,
        expectedFailure: String? = null,
        expectedRatePpm: Int? = null
    ) {
        val base = 8_000_000_000L
        val now = AtomicLong()
        val clock = PlaybackClock(now::get, { now.addAndGet(it * 1_000_000) })
            .apply { audioExpected = true; anchorVideo(base) }
        val errors = ConcurrentLinkedQueue<String>()
        val corrections = ConcurrentLinkedQueue<AudioClockCorrection>()
        val output = ClockedOutput(now, deviceRate)
        val player = AudioPlayer(format, clock, { code, detail -> errors.add("$code: $detail") }, { output }, corrections::add)
        val displayed = AtomicInteger()
        val video = if (withVideo) object : TrackWorker("epoch-video", 8 * 1024 * 1024,
            { code, detail -> errors.add("$code: $detail") }, 1) {
            private var pending: AccessUnit? = null
            override fun step() {
                if (pending == null) pending = next()
                pending?.let {
                    val delay = clock.videoDelayUs(it.ptsUs)
                    if (delay != null && delay < -250_000) throw PlaybackException("Interleaved video exceeded lateness bound")
                    if (delay != null && delay <= 16_666) { displayed.incrementAndGet(); pending = null }
                    else Thread.sleep(1)
                }
            }
            override fun release() {}
        } else null
        var previousClock = Long.MIN_VALUE
        try {
            player.start()
            video?.start()
            var liveStartNs = 0L
            for (index in 0 until packets) {
                if (index > 0) now.updateAndGet { maxOf(it, liveStartNs + index * frames * 1_000_000_000L / format.sampleRate) }
                video?.offer(AccessUnit(base + index * frames * 1_000_000L / format.sampleRate, byteArrayOf(1)))
                player.offer(AccessUnit(base + index * frames * 1_000_000L / format.sampleRate + correction(index),
                    ByteArray(frames * format.channels * 2)))
                val expected = ((index + 1) * frames - format.preSkip).coerceAtLeast(0) * format.channels
                await {
                    if (player.sourceSubmittedFrames * format.channels < expected) now.addAndGet(1_000_000)
                    player.sourceSubmittedFrames * format.channels >= expected || errors.isNotEmpty()
                }
                if (withVideo) {
                    // Do not advance simulated capture time ahead of the three-frame renderer window.
                    await { displayed.get() >= (index - 2).coerceAtLeast(0) || errors.isNotEmpty() }
                }
                if (errors.isNotEmpty()) {
                    assertNotNull(errors.toString(), expectedFailure)
                    assertTrue(errors.toString(), errors.any { it.startsWith("AUDIO_FAILED:") && it.contains(checkNotNull(expectedFailure)) })
                    return
                }
                if (index == 0) liveStartNs = now.get()
                val current = checkNotNull(clock.currentUs())
                assertTrue("Shared clock must not rewind", current >= previousClock)
                previousClock = current
                if (withVideo && index == 200) {
                    clock.endVideoPreroll()
                    clock.anchorVideo(base + index * frames * 1_000_000L / format.sampleRate)
                    assertEquals("Video generation must not reset audio source mapping", current, clock.currentUs())
                }
                assertTrue(player.queuedPackets() <= 48)
            }
            assertNull("Expected malformed decoded PTS to fail", expectedFailure)
            assertEquals((packets * frames - format.preSkip).toLong(), player.sourceSubmittedFrames)
            expectedOffset?.let {
                val expected = base + format.preSkip * 1_000_000L / format.sampleRate + player.sourcePositionUs() + it
                assertTrue("Source mapping did not converge: ${clock.currentUs()} vs $expected", abs(checkNotNull(clock.currentUs()) - expected) < 1000)
            }
            expectedRatePpm?.let {
                val played = player.sourcePositionUs()
                val expected = base + played + played * it / 1_000_000
                assertTrue("Source-rate mapping did not track played samples", abs(checkNotNull(clock.currentUs()) - expected) < 1000)
            }
            if (withVideo) {
                repeat(8) { now.addAndGet(20_000_000); Thread.sleep(2) }
                await { displayed.get() == packets || errors.isNotEmpty() }
                assertEquals(packets, displayed.get())
            }
            assertTrue(errors.toString(), errors.isEmpty())
            assertTrue(output.maxBufferedUs <= 130_000)
            assertTrue(output.speeds.all { it in 0.98f..1.02f })
            if (expectedOffset != null) assertTrue(corrections.isNotEmpty())
            assertTrue(output.speeds.isNotEmpty())
        } finally {
            try { video?.close() } finally { player.close() }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Actual audio worker stalled", condition())
    }

    private class ClockedOutput(private val now: AtomicLong, private val deviceRate: Double) : AudioOutput {
        val written = AtomicInteger()
        val speeds = ConcurrentLinkedQueue<Float>()
        var maxBufferedUs = 0L
            private set
        private var playing = false
        private var previousNs = 0L
        private var position = 0.0
        private var speed = 1.0
        override val underrunCount = 0
        @Synchronized override fun play() { playing = true; previousNs = now.get() }
        @Synchronized override fun pause() { positionUs(); playing = false }
        @Synchronized override fun positionUs(): Long {
            val current = now.get()
            if (playing) position = minOf(written.get() * 1_000_000.0 / 96000,
                position + (current - previousNs).coerceAtLeast(0) / 1000.0 * deviceRate * speed)
            previousNs = current
            return position.toLong()
        }
        @Synchronized override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            val buffered = written.get() / 2 - positionUs() * 48000 / 1_000_000
            val accepted = minOf(count / 2, (5760 - buffered).coerceAtLeast(0).toInt()) * 2
            written.addAndGet(accepted)
            maxBufferedUs = maxOf(maxBufferedUs, (buffered + accepted / 2) * 1_000_000L / 48000)
            return accepted
        }
        @Synchronized override fun timing(): AudioOutputTiming {
            val frames = positionUs() * 48000 / 1_000_000
            return AudioOutputTiming(48000, 5760, written.get().toLong() / 2, frames, frames, null, AudioClockSource.HEAD_UNAVAILABLE)
        }
        @Synchronized override fun setSpeed(speed: Float) { positionUs(); this.speed = speed.toDouble(); speeds.add(speed) }
        override fun close() {}
    }
}
