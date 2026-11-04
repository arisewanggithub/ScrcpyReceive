package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LowLatencyGapPipelineTest {
    @Test fun stoppedQueueIsClearedEvenWhenNativeReleaseFailsExplicitly() {
        val worker = object : TrackWorker("release-fixture", 1024, { _, _ -> fail("Worker was never started") }) {
            override fun step() {}
            override fun release() { throw IllegalStateException("synthetic native release failure") }
        }
        repeat(3) { worker.offer(AccessUnit(it * 20_000L, byteArrayOf(1))) }
        assertEquals(3, worker.queuedPackets())
        assertThrows(IllegalStateException::class.java) { worker.close() }
        assertEquals(0, worker.queuedPackets())
        assertThrows(IllegalStateException::class.java) { worker.offer(AccessUnit(100_000, byteArrayOf(1))) }
    }

    @Test fun productionPcmWorkerRecoversRepeated219msGapsAndCatchupWithoutCongestion() {
        val now = AtomicLong()
        val output = Device()
        val errors = ConcurrentLinkedQueue<String>()
        val events = ConcurrentLinkedQueue<AudioRegulationDiagnostic>()
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
            .apply { audioExpected = true; anchorVideo(1_000_000) }
        val player = AudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code $detail") }, { output }, onRegulation = events::add)
        val delayed = ArrayDeque<AccessUnit>()
        try {
            player.start()
            repeat(1600) { tick ->
                now.addAndGet(5_000_000)
                output.advance(240)
                if (tick % 4 == 0) {
                    val bytes = ByteBuffer.allocate(3840).order(ByteOrder.LITTLE_ENDIAN)
                    repeat(1920) { bytes.putShort(7) }
                    delayed.addLast(AccessUnit(1_000_000 + tick * 5000L, bytes.array()))
                }
                val gap = tick % 200 in 80..123
                if (!gap) {
                    while (delayed.isNotEmpty()) player.offer(delayed.removeFirst())
                    await { player.queuedPackets() == 0 || errors.isNotEmpty() }
                }
                Thread.sleep(3)
                assertTrue(errors.toString(), errors.isEmpty())
                assertEquals(0L, clock.videoDelayUs(9_000_000))
            }
            assertTrue(events.toString(), events.any { it.silenceFrames > 24000 && it.recoveries > 3 && it.droppedFrames > 0 })
            assertTrue(events.all { it.bufferedFrames <= 5520 && it.speed in 0.98f..1.02f })
            assertTrue(output.source.get() > 48000)
            assertTrue(output.silence.get() > 24000)
            assertTrue(output.maximumQueued <= 480)
        } finally { player.close() }
        val last = output.submitted
        Thread.sleep(5)
        assertEquals(last, output.submitted)
        assertTrue(output.closed)
        assertTrue(errors.toString(), errors.isEmpty())
    }

    private fun await(ready: () -> Boolean) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!ready() && System.nanoTime() < limit) Thread.sleep(1)
        assertTrue("Audio decoder failed to drain bounded compressed admission", ready())
    }
    private class Device : AudioOutput {
        var submitted = 0L
            private set
        private var consumed = 0L
        var maximumQueued = 0L
            private set
        var closed = false
            private set
        private var playing = false
        val source = AtomicLong()
        val silence = AtomicLong()
        override val underrunCount = 0
        override val playbackActive get() = playing
        override fun play() { playing = true }
        override fun pause() { playing = false }
        @Synchronized fun advance(frames: Long) { consumed = minOf(submitted, consumed + frames) }
        @Synchronized override fun timing() = AudioOutputTiming(48000, 480, submitted, consumed, consumed, null, AudioClockSource.HEAD_UNAVAILABLE)
        @Synchronized override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            check(!closed)
            val frames = minOf(count / 2, (480 - submitted + consumed).toInt())
            if (frames > 0) {
                if (samples[offset] == 0.toShort()) silence.addAndGet(frames.toLong()) else source.addAndGet(frames.toLong())
                submitted += frames
                maximumQueued = maxOf(maximumQueued, submitted - consumed)
            }
            return frames * 2
        }
        override fun setSpeed(speed: Float) { require(speed in 0.98f..1.02f) }
        override fun close() { closed = true }
    }
}
