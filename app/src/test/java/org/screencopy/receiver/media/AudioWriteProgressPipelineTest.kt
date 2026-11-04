package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AudioWriteProgressPipelineTest {
    @Test fun partialProgressAcrossLongPacketDoesNotBecomeAWriteStall() {
        val fixture = Fixture { track, count, call ->
            if (call % 2 == 0) {
                track.now.addAndGet(100_000_000)
                minOf(192, count)
            } else {
                track.now.addAndGet(20_000_000)
                0
            }
        }
        fixture.use {
            it.start()
            await { it.player.sourceSubmittedFrames == 960L || it.errors.isNotEmpty() }
            assertTrue(it.errors.toString(), it.errors.isEmpty())
            assertEquals((0 until 1920).map(Int::toShort), it.track.accepted.take(1920))
            assertTrue("Packet elapsed exceeds the unchanged no-progress budget", it.track.elapsedNs > 250_000_000)
            assertTrue(it.track.maximumQueued <= it.track.bufferFrames)
            assertEquals(960L, it.player.sourceSubmittedFrames)
            await { it.clock.currentUs() == 1_020_000L }
            assertEquals(it.player.sourcePositionUs() + 1_000_000, it.clock.currentUs())
        }
    }

    @Test fun progressAfterLongWriteCallStartsANewNoProgressWindow() {
        val fixture = Fixture { track, count, call ->
            when (call) {
                0 -> { track.now.addAndGet(400_000_000); 192 }
                1 -> { track.now.addAndGet(1_000_000); 0 }
                else -> count
            }
        }
        fixture.use {
            it.start()
            await { it.player.sourceSubmittedFrames == 960L || it.errors.isNotEmpty() }
            assertTrue(it.errors.toString(), it.errors.isEmpty())
            assertEquals((0 until 1920).map(Int::toShort), it.track.accepted.take(1920))
        }
    }

    @Test fun continuousZeroWritesStillFailAtTheOriginalNoProgressLimit() {
        val fixture = Fixture { track, _, _ -> track.now.addAndGet(50_000_000); 0 }
        fixture.use {
            it.start()
            await { it.errors.isNotEmpty() }
            assertEquals(1, it.errors.size)
            val error = it.errors.single()
            assertTrue(error, error.startsWith("AUDIO_FAILED: Audio stage=OUTPUT_WRITE"))
            assertTrue(error, error.contains("Audio output stalled for 250 ms"))
            assertTrue(error, error.contains("event=WRITE_STALL"))
            assertTrue(error, error.contains("recent_progress_ms=300"))
            assertTrue(error, error.contains("queued_decoded_frames=960"))
            assertTrue(error, error.contains("source_frames=0"))
            assertTrue(error, error.contains("playing_state=true"))
            assertTrue(error, error.contains("submitted_frames=0"))
            assertTrue(error, error.contains("consumed_frames=0"))
            assertEquals(6, it.track.calls)
            assertTrue(it.track.accepted.isEmpty())
        }
    }

    @Test fun aTrueStallAfterProgressReportsOnlyTimeSinceLastAcceptedSamples() {
        val fixture = Fixture { track, _, call ->
            track.now.addAndGet(100_000_000)
            if (call == 0) 192 else 0
        }
        fixture.use {
            it.start()
            await { it.errors.isNotEmpty() }
            val error = it.errors.single()
            assertTrue(error, error.contains("recent_progress_ms=300"))
            assertTrue(error, error.contains("queued_decoded_frames=864"))
            assertTrue(error, error.contains("source_frames=96"))
            assertEquals(192, it.track.accepted.size)
        }
    }

    @Test fun negativeNativeWriteRemainsFatal() = invalidWrite(-6, "AudioTrack write failed (-6)")
    @Test fun excessiveNativeWriteRemainsFatal() = invalidWrite(1922, "AudioTrack returned an invalid sample count")
    @Test fun unalignedNativeWriteRemainsFatal() = invalidWrite(1, "AudioTrack returned an invalid sample count")

    private fun invalidWrite(result: Int, expected: String) {
        val fixture = Fixture { _, _, _ -> result }
        fixture.use {
            it.start()
            await { it.errors.isNotEmpty() }
            assertEquals(1, it.errors.size)
            assertTrue(it.errors.single(), it.errors.single().contains(expected))
        }
    }

    @Test fun cancellationInterruptsOutstandingZeroWriteLoopAndReleasesOutput() {
        val entered = CountDownLatch(1)
        val fixture = Fixture { _, _, _ -> entered.countDown(); 0 }
        var started = 0L
        try {
            fixture.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
        } finally {
            started = System.nanoTime()
            fixture.close()
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)
        assertTrue(fixture.errors.toString(), fixture.errors.isEmpty())
        assertFalse(fixture.track.isPlaying)
        assertTrue(fixture.track.closed)
        assertEquals(0, fixture.player.queuedPackets())
    }

    @Test fun writeProgressFixDoesNotEnlargeProducerQueueOrAdmissionTimeout() {
        val entered = CountDownLatch(1)
        val wait = CountDownLatch(1)
        val fixture = Fixture { _, _, _ -> entered.countDown(); wait.await(); 0 }
        fixture.use {
            it.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            for (index in 1..13) it.player.offer(AccessUnit(1_000_000 + index * 20_000L, ByteArray(3840)))
            val started = System.nanoTime()
            try {
                it.player.offer(AccessUnit(1_280_000, ByteArray(3840)))
                fail("An output retry must not enlarge the existing producer budget")
            } catch (error: QueueCongestionException) {
                assertEquals(QueueLimit.PTS, error.pressure.limit)
                assertEquals(250_000L, error.pressure.maxSpanUs)
                assertEquals(512 * 1024, error.pressure.maxBytes)
                assertEquals(48, error.pressure.maxPackets)
            }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) in 240..1000)
            assertEquals(13, it.player.queuedPackets())
            assertTrue(it.errors.toString(), it.errors.isEmpty())
        }
    }

    private class Fixture(write: (TraceTrack, Int, Int) -> Int) : AutoCloseable {
        val now = AtomicLong(1_000_000_000)
        val errors = ConcurrentLinkedQueue<String>()
        val format = AudioFormat("pcm", 48000, 2, byteArrayOf())
        val track = TraceTrack(now, write)
        val output = PlatformAudioOutput(format, { code, detail -> errors.add("$code: $detail") },
            object : AudioOutputPlatform {
                override fun minimumBytes(format: AudioFormat) = 5760 * 4
                override fun create(format: AudioFormat, bytes: Int, failure: (String, String) -> Unit): RateAudioTrack {
                    assertEquals(AudioOutputPreparation.plan(format, 5760 * 4).bytes, bytes)
                    return track
                }
            })
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
            .apply { audioExpected = true; anchorVideo(1_000_000) }
        val player = AudioPlayer(format, clock,
            { code, detail -> errors.add("$code: $detail") }, { output })
        fun start() {
            player.start()
            val bytes = ByteBuffer.allocate(1920 * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(1920) { bytes.putShort(it.toShort()) }
            player.offer(AccessUnit(1_000_000, bytes.array()))
        }
        override fun close() = player.close()
    }

    private class TraceTrack(val now: AtomicLong, private val write: (TraceTrack, Int, Int) -> Int) : RateAudioTrack {
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
        val accepted = ConcurrentLinkedQueue<Short>()
        private val submitted = AtomicLong()
        @Volatile var calls = 0
        @Volatile var closed = false
        var maximumQueued = 0L
        private var startNs = 0L
        val elapsedNs get() = now.get() - startNs
        private fun consumed() = minOf(submitted.get(), elapsedNs * sampleRate / 1_000_000_000)
        private val position = AudioPosition(sampleRate, bufferFrames, now::get, { null }, { consumed().toInt() })
        override fun play() { startNs = now.get(); isPlaying = true }
        override fun pause() { isPlaying = false }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            check(!closed)
            val result = write(this, count, calls++)
            if (result > 0 && result <= count && result % channels == 0) {
                position.written(result / channels)
                submitted.addAndGet(result.toLong() / channels)
                maximumQueued = maxOf(maximumQueued, submitted.get() - consumed())
                for (index in offset until offset + result) accepted.add(samples[index])
            }
            return result
        }
        override fun positionUs() = position.current().presentedUs
        override fun timing() = position.current()
        override fun setSpeed(speed: Float) { actualSpeed = speed }
        override fun close() { closed = true }
    }

    companion object {
        private fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
            assertTrue("Production audio worker made no progress", condition())
        }
    }
}
