package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class VideoEpochPipelineTest {
    @Test fun sixtyFiveSecondsOfActualAudioAndVideoWorkersSurvivePhaseRefreshStaticDecodeDelayAndRotation() {
        val base = 8_000_000_000L
        val now = AtomicLong(1_000_000_000)
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
            .apply { audioExpected = true; anchorVideo(base) }
        val errors = ConcurrentLinkedQueue<String>()
        val events = ConcurrentLinkedQueue<VideoDiagnostic>()
        val output = ClockedOutput(now)
        val audio = AudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output })
        val decoders = mutableListOf<ClockedDecoder>()
        fun video(generation: Long): VideoPlayer {
            val decoder = ClockedDecoder(now, base).also(decoders::add)
            val format = if (generation == 1L) VideoFormat("video/avc", 640, 360, listOf(byteArrayOf(1)), 50)
                else VideoFormat("video/avc", 360, 640, listOf(byteArrayOf(2)), 50, 90)
            return VideoPlayer(format, clock, { code, detail -> errors.add("$code: $detail") },
                decoder, generation, events::add).also { it.start() }
        }
        var renderer = video(1)
        var captured = 0
        var liveStart = 0L
        var previousClock = Long.MIN_VALUE
        try {
            audio.start()
            for (index in 0 until 3266) {
                if (index > 0) now.updateAndGet { maxOf(it, liveStart + index * 20_000_000L) }
                if (index == 2625) {
                    await { renderer.queuedPackets() == 0 && decoders.last().completed.get() == decoders.last().inputs.get() || errors.isNotEmpty() }
                    assertTrue(errors.toString(), errors.isEmpty())
                    val before = clock.currentUs()
                    renderer.close()
                    assertTrue(decoders.last().closed)
                    clock.endVideoPreroll()
                    renderer = video(2)
                    assertEquals("Video generation must not reset the running audio/source clock", before, clock.currentUs())
                }
                // Static-screen gaps contain no missing encoded packets. Audio continues throughout.
                val capture = index < 3250 && index !in 1501..1539 && index !in 2601..2624
                if (capture) {
                    renderer.offer(AccessUnit(base + index * 20_000L, byteArrayOf(1), index == 0 || index == 2625))
                    captured++
                }
                val phase = when {
                    index < 2550 -> 0
                    index < 2600 -> 85_272
                    else -> 149_651
                }
                audio.offer(AccessUnit(base + index * 20_000L + phase, ByteArray(3840)))
                await {
                    if (audio.sourceSubmittedFrames < (index + 1) * 960L) now.addAndGet(1_000_000)
                    audio.sourceSubmittedFrames >= (index + 1) * 960L || errors.isNotEmpty()
                }
                assertTrue(errors.toString(), errors.isEmpty())
                if (index == 0) liveStart = now.get()
                val current = checkNotNull(clock.currentUs())
                assertTrue("Shared presentation cannot rewind", current >= previousClock)
                previousClock = current
                assertTrue(renderer.queuedPackets() <= 48)
                assertTrue(audio.queuedPackets() <= 48)
            }
            await { decoders.sumOf { it.completed.get() } == captured || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(3266 * 960L, audio.sourceSubmittedFrames)
            assertEquals(captured, decoders.sumOf { it.inputs.get() })
            assertEquals(captured, decoders.sumOf { it.completed.get() })
            assertTrue("The static-screen decode delay must exercise late-frame recovery",
                events.any { it.lateRendered > 0 || it.decodedSkipped > 0 })
            assertTrue(events.any { it.generation == 2L && it.width == 360 && it.height == 640 && it.rotation == 90 })
            assertTrue(output.maxQueued.get() <= 6240)
            assertTrue(decoders.all { it.maxBuffered.get() <= 8 })
            assertTrue(output.speeds.all { it in 0.98f..1.02f })
            assertTrue(abs(checkNotNull(clock.currentUs()) - (base + audio.sourcePositionUs() + 149_651)) < 1000)
        } finally {
            try { renderer.close() } finally { audio.close() }
        }
        assertTrue(decoders.all { it.closed })
        assertTrue(output.closed)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Production interleaved workers stopped progressing", condition())
    }

    private class ClockedDecoder(private val now: AtomicLong, private val base: Long) : VideoDecoder {
        override val description = "Deterministic codec adapter"
        private val outputs = ConcurrentLinkedQueue<VideoOutput>()
        val inputs = AtomicInteger()
        val completed = AtomicInteger()
        val maxBuffered = AtomicInteger()
        @Volatile var closed = false
        private var delayedUntil: Long? = null
        override fun dequeueInput() = if (inputs.get() - completed.get() < 8) 0 else -1
        override fun input(index: Int) = ByteBuffer.allocate(16)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            val sequence = inputs.incrementAndGet()
            if (ptsUs == base + 30_000_000) delayedUntil = now.get() + 800_000_000
            outputs.add(VideoOutput(sequence, ptsUs, 1, 0))
            maxBuffered.updateAndGet { maxOf(it, inputs.get() - completed.get()) }
        }
        override fun dequeueOutput(): VideoOutput? {
            if (delayedUntil?.let { now.get() < it } == true) return null
            return outputs.poll()
        }
        override fun releaseOutput(index: Int, renderAtNs: Long?) { completed.incrementAndGet() }
        override fun close() { closed = true }
    }

    private class ClockedOutput(private val now: AtomicLong) : AudioOutput {
        val written = AtomicLong()
        val maxQueued = AtomicLong()
        val speeds = ConcurrentLinkedQueue<Float>()
        @Volatile var closed = false
        private var playing = false
        private var previousNs = 0L
        private var consumed = 0.0
        private var speed = 1f
        override val underrunCount = 0
        @Synchronized override fun play() { playing = true; previousNs = now.get() }
        @Synchronized override fun pause() { advance(); playing = false }
        private fun advance() {
            val current = now.get()
            if (playing) consumed = minOf(written.get().toDouble(),
                consumed + (current - previousNs).coerceAtLeast(0) * 48000.0 * speed / 1_000_000_000)
            previousNs = current
        }
        @Synchronized override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            advance()
            val frames = minOf(count / 2, (6240 - (written.get() - consumed.toLong())).coerceAtLeast(0).toInt())
            written.addAndGet(frames.toLong())
            maxQueued.updateAndGet { maxOf(it, written.get() - consumed.toLong()) }
            return frames * 2
        }
        @Synchronized override fun timing(): AudioOutputTiming {
            advance()
            val head = consumed.toLong()
            return AudioOutputTiming(48000, 6240, written.get(), head, (head - 8000).coerceAtLeast(0), 0, AudioClockSource.TIMESTAMP)
        }
        @Synchronized override fun setSpeed(speed: Float) { advance(); this.speed = speed; speeds.add(speed) }
        override fun close() { closed = true }
    }
}
