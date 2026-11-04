package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ExecutionException

class StartupBackpressureTest {
    @Test fun boundedReadyBurstWaitsForRealAudioWorkerInsteadOfRejectingHealthyStartup() {
        val now = AtomicLong()
        val starting = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<String>()
        val clock = PlaybackClock(now::get) {
            starting.countDown()
            check(resume.await(3, TimeUnit.SECONDS))
            now.set(80_000_000L)
        }.apply { audioExpected = true; anchorVideo(1_000_000) }
        val output = BoundedOutput(now)
        val player = AudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> errors.add("$code: $detail") }, { output })
        val producer = Executors.newSingleThreadExecutor()
        try {
            player.start()
            player.offer(packet(0))
            assertTrue(starting.await(3, TimeUnit.SECONDS))
            for (index in 1..13) player.offer(packet(index))
            assertEquals(13, player.queuedPackets())
            val attempted = CountDownLatch(1)
            val burst = producer.submit {
                attempted.countDown()
                player.offer(packet(14))
            }
            assertTrue(attempted.await(3, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { burst.get(40, TimeUnit.MILLISECONDS) }
            assertEquals(13, player.queuedPackets())
            resume.countDown()
            burst.get(1, TimeUnit.SECONDS)
            repeat(20) {
                now.addAndGet(20_000_000)
                Thread.sleep(3)
            }
            await {
                now.addAndGet(5_000_000)
                output.sourceSamples(15) == 1920 || errors.isNotEmpty()
            }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(1920, output.sourceSamples(15))
            assertTrue(player.sourceSubmittedFrames in 1..15 * 960L)
        } finally {
            resume.countDown()
            player.close()
            producer.shutdownNow()
            assertTrue(producer.awaitTermination(3, TimeUnit.SECONDS))
        }
        assertTrue(output.closed.get())
    }

    @Test fun interleavedVideoAndRealPcmWorkerRecoverFromBoundedDecoderStartupStalls() {
        val now = AtomicLong()
        val audioWaiting = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<String>()
        val clock = PlaybackClock(now::get) {
            audioWaiting.countDown()
            check(resume.await(3, TimeUnit.SECONDS))
            now.updateAndGet { maxOf(it, 80_000_000L) }
        }.apply { audioExpected = true; anchorVideo(1_000_000) }
        val output = BoundedOutput(now)
        val audio = AudioPlayer(AudioFormat("pcm", 48000, 2, byteArrayOf()), clock,
            { code, detail -> failures.add("$code: $detail") }, { output })
        val displayed = AtomicInteger()
        val video = object : TrackWorker("synthetic-decoded-video", 8 * 1024 * 1024,
            { code, detail -> failures.add("$code: $detail") }, track = 1) {
            private var pending: AccessUnit? = null
            override fun step() {
                if (pending == null) pending = next()
                pending?.let {
                    val delay = clock.videoDelayUs(it.ptsUs)
                    if (delay != null && delay < -250_000) throw PlaybackException("Synthetic video exceeded the production lateness bound")
                    if ((delay ?: Long.MAX_VALUE) <= 16_666) {
                        displayed.incrementAndGet()
                        pending = null
                    } else Thread.sleep(2)
                }
            }
            override fun release() {}
        }
        val producer = Executors.newSingleThreadExecutor()
        val live = CountDownLatch(1)
        val cadence = Semaphore(0)
        try {
            audio.start()
            video.start()
            audio.offer(packet(0))
            assertTrue(audioWaiting.await(3, TimeUnit.SECONDS))
            val burst = producer.submit {
                for (index in 1..24) {
                    video.offer(AccessUnit(1_000_000 + index * 20_000L, ByteArray(100)))
                    audio.offer(packet(index))
                }
                live.countDown()
                for (index in 25..44) {
                    check(cadence.tryAcquire(2, TimeUnit.SECONDS))
                    video.offer(AccessUnit(1_000_000 + index * 20_000L, ByteArray(100)))
                    audio.offer(packet(index))
                }
            }
            Thread.sleep(40)
            resume.countDown()
            val untilLive = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (live.count != 0L && System.nanoTime() < untilLive) { now.addAndGet(20_000_000); Thread.sleep(3) }
            assertEquals(0L, live.count)
            repeat(50) { now.addAndGet(20_000_000); cadence.release(); Thread.sleep(3) }
            burst.get(1, TimeUnit.SECONDS)
            await {
                now.addAndGet(5_000_000)
                output.sourceSamples(45) == 1920 && displayed.get() == 44 || failures.isNotEmpty()
            }
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(1920, output.sourceSamples(45))
            assertTrue(audio.sourceSubmittedFrames in 1..45 * 960L)
            assertEquals(44, displayed.get())
        } finally {
            resume.countDown()
            cadence.release(100)
            try { video.close() } finally { audio.close() }
            producer.shutdownNow()
            assertTrue(producer.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun persistentVideoPtsPressureFailsWithinBoundWithExactTrackAndSpan() {
        StalledTrack(1, 8 * 1024 * 1024).use { track ->
            track.start()
            track.offer(AccessUnit(0, byteArrayOf(1)))
            assertTrue(track.entered.await(3, TimeUnit.SECONDS))
            for (index in 1..8) track.offer(AccessUnit(index * 33_333L, ByteArray(100)))
            val started = System.nanoTime()
            val failure = assertThrows(QueueCongestionException::class.java) {
                track.offer(AccessUnit(9 * 33_333L, ByteArray(100)))
            }
            val pressure = failure.pressure
            assertEquals(1, pressure.track)
            assertEquals(QueueLimit.PTS, pressure.limit)
            assertEquals(8, pressure.queuedPackets)
            assertEquals(800, pressure.queuedBytes)
            assertEquals(9, pressure.attemptedPackets)
            assertEquals(900L, pressure.attemptedBytes)
            assertEquals(266_664L, pressure.spanUs)
            assertEquals(250_000L, pressure.maxSpanUs)
            assertTrue(pressure.waitedMs >= 240)
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(2))
            assertEquals(8, track.queuedPackets())
        }
    }

    @Test fun byteAndPacketPressureRemainFatalWithUnchangedLimits() {
        StalledTrack(2, 512 * 1024).use { track ->
            track.start()
            track.offer(AccessUnit(0, byteArrayOf(1)))
            assertTrue(track.entered.await(3, TimeUnit.SECONDS))
            repeat(2) { track.offer(AccessUnit(0, ByteArray(256 * 1024))) }
            val failure = assertThrows(QueueCongestionException::class.java) { track.offer(AccessUnit(0, byteArrayOf(1))) }
            assertEquals(QueueLimit.BYTES, failure.pressure.limit)
            assertEquals(2, failure.pressure.track)
            assertEquals(512 * 1024, failure.pressure.queuedBytes)
            assertEquals(512 * 1024 + 1L, failure.pressure.attemptedBytes)
        }
        StalledTrack(1, 8 * 1024 * 1024).use { track ->
            track.start()
            track.offer(AccessUnit(0, byteArrayOf(1)))
            assertTrue(track.entered.await(3, TimeUnit.SECONDS))
            repeat(48) { track.offer(AccessUnit(0, byteArrayOf(1))) }
            val failure = assertThrows(QueueCongestionException::class.java) { track.offer(AccessUnit(0, byteArrayOf(1))) }
            assertEquals(QueueLimit.PACKETS, failure.pressure.limit)
            assertEquals(48, failure.pressure.queuedPackets)
            assertEquals(49, failure.pressure.attemptedPackets)
        }
    }

    @Test fun haltWakesAReadPathBlockedByQueuePressureWithoutWaitingForDeadline() {
        val producer = Executors.newSingleThreadExecutor()
        StalledTrack(1, 8 * 1024 * 1024).use { track ->
            try {
                track.start()
                track.offer(AccessUnit(0, byteArrayOf(1)))
                assertTrue(track.entered.await(3, TimeUnit.SECONDS))
                repeat(48) { track.offer(AccessUnit(0, byteArrayOf(1))) }
                val offer = producer.submit { track.offer(AccessUnit(0, byteArrayOf(1))) }
                assertThrows(TimeoutException::class.java) { offer.get(40, TimeUnit.MILLISECONDS) }
                track.halt()
                val ended = assertThrows(ExecutionException::class.java) { offer.get(150, TimeUnit.MILLISECONDS) }
                assertTrue(ended.cause is IllegalStateException)
                assertFalse(ended.cause is QueueCongestionException)
            } finally {
                producer.shutdownNow()
                assertTrue(producer.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    private class StalledTrack(track: Int, bytes: Int) :
        TrackWorker("stalled-track-$track", bytes, { code, detail -> throw AssertionError("$code: $detail") }, track) {
        val entered = CountDownLatch(1)
        private val resume = CountDownLatch(1)
        override fun step() {
            if (next() != null) {
                entered.countDown()
                resume.await()
            }
        }
        override fun unblock() { resume.countDown() }
        override fun release() {}
    }

    private fun packet(index: Int) = AccessUnit(1_000_000L + index * 20_000L,
        ByteArray(3840) { if (it % 2 == 0) (index + 1).toByte() else 0 })

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Real worker did not drain", condition())
    }

    private class BoundedOutput(private val now: AtomicLong) : AudioOutput {
        val written = AtomicInteger()
        val closed = AtomicBoolean()
        private val sourceSamples = ConcurrentHashMap<Int, AtomicInteger>()
        fun sourceSamples(marker: Int): Int = sourceSamples[marker]?.get() ?: 0
        private var startNs = 0L
        private var playing = false
        override val underrunCount = 0
        override fun play() { startNs = now.get(); playing = true }
        override fun pause() { playing = false }
        override fun positionUs(): Long = if (playing) minOf((now.get() - startNs) / 1000, written.get() * 1_000_000L / 96000) else 0
        override fun timing(): AudioOutputTiming {
            val frames = positionUs() * 48000 / 1_000_000
            return AudioOutputTiming(48000, 5760, written.get().toLong() / 2, frames, frames, null, AudioClockSource.HEAD_UNAVAILABLE)
        }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            val buffered = written.get() - positionUs() * 96000 / 1_000_000
            val accepted = minOf(count, (11520 - buffered).coerceAtLeast(0).toInt())
            if (accepted > 0 && samples[offset] > 0)
                sourceSamples.computeIfAbsent(samples[offset].toInt()) { AtomicInteger() }.addAndGet(accepted)
            written.addAndGet(accepted)
            return accepted
        }
        override fun setSpeed(speed: Float) {}
        override fun close() { closed.set(true) }
    }
}
