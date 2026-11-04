package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AudioUnderrunRecoveryTest {
    @Test fun moreThanEightLifetimeUnderrunsDoNotAbortSuccessfulPcmWrites() = stream(240, 20)
    @Test fun slowlyAccumulatingLifetimeEventsRemainRecoverableOverFiftySeconds() = stream(2500, 200)
    @Test fun clusteredEventsAndShortEmptyOutputRecoverWithoutChangingAudioOrVideoEpoch() =
        stream(300, 0, withGapAndVideo = true)

    private fun stream(packets: Int, counterEvery: Int, withGapAndVideo: Boolean = false) {
        val now = AtomicLong(1_000_000_000)
        val format = AudioFormat("pcm", 48000, 2, byteArrayOf())
        val native = RecoveringTrack(now)
        val platform = object : AudioOutputPlatform {
            override fun minimumBytes(format: AudioFormat) = 4800 * 4
            override fun create(format: AudioFormat, bytes: Int, failure: (String, String) -> Unit): RateAudioTrack {
                assertEquals(AudioOutputPreparation.plan(format, 4800 * 4).bytes, bytes)
                return native
            }
        }
        val errors = ConcurrentLinkedQueue<String>()
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
            .apply { audioExpected = true; anchorVideo(8_000_000_000) }
        val diagnostics = ConcurrentLinkedQueue<AudioUnderrunDiagnostic>()
        val regulation = ConcurrentLinkedQueue<AudioRegulationDiagnostic>()
        val player = AudioPlayer(format, clock, { code, detail -> errors.add("$code: $detail") },
            { PlatformAudioOutput(format, { code, detail -> errors.add("$code: $detail") }, platform) },
            onUnderrunRecovery = diagnostics::add, onRegulation = regulation::add)
        var liveStart = 0L
        var gapUs = 0L
        val decoders = mutableListOf<PassthroughDecoder>()
        fun video(generation: Long): VideoPlayer {
            val decoder = PassthroughDecoder().also(decoders::add)
            val videoFormat = if (generation == 1L) VideoFormat("video/avc", 640, 360, listOf(byteArrayOf(1)), 50)
                else VideoFormat("video/avc", 360, 640, listOf(byteArrayOf(2)), 50, 90)
            return VideoPlayer(videoFormat, clock, { code, detail -> errors.add("$code: $detail") }, decoder, generation)
                .also { it.start() }
        }
        var renderer = if (withGapAndVideo) video(1) else null
        var captured = 0
        var previousClock = Long.MIN_VALUE
        val totalPackets = packets + if (withGapAndVideo) 16 else 0
        try {
            player.start()
            repeat(totalPackets) { index ->
                if (index > 0) now.updateAndGet { maxOf(it, liveStart + index * 20_000_000L + gapUs * 1000) }
                if (counterEvery > 0 && index > 0 && index % counterEvery == 0) native.underruns.incrementAndGet()
                if (withGapAndVideo && index == 10) {
                    synchronized(native) {
                        now.addAndGet(80_000_000)
                        native.underruns.addAndGet(12)
                    }
                    await { diagnostics.any { it.total == 12L } || errors.isNotEmpty() }
                    now.addAndGet(40_000_000)
                    // This is a bounded observed gap, not an invented long-silence clock policy.
                    Thread.sleep(5)
                    gapUs += 120_000
                }
                if (withGapAndVideo && index == 150) {
                    await { decoders.last().completed.get() == decoders.last().inputs.get() || errors.isNotEmpty() }
                    val before = clock.currentUs()
                    renderer?.close()
                    clock.endVideoPreroll()
                    renderer = video(2)
                    assertEquals(before, clock.currentUs())
                }
                if (renderer != null && index < packets && index !in 140..149) {
                    renderer?.offer(AccessUnit(8_000_000_000 + index * 20_000L + gapUs, byteArrayOf(1), index == 0 || index == 150))
                    captured++
                }
                player.offer(packet(index, 8_000_000_000 + index * 20_000L + gapUs))
                await {
                    if (player.sourceSubmittedFrames < (index + 1) * 960L) now.addAndGet(1_000_000)
                    player.sourceSubmittedFrames >= (index + 1) * 960L || errors.isNotEmpty()
                }
                assertTrue(errors.toString(), errors.isEmpty())
                if (index == 0) liveStart = now.get()
                val current = checkNotNull(clock.currentUs())
                assertTrue(current >= previousClock)
                previousClock = current
            }
            assertTrue(diagnostics.toString(), diagnostics.any { it.total >= 9 && it.playbackActive == true })
            assertTrue(diagnostics.all { it.timing.bufferedUs <= 130000 })
            assertEquals(if (withGapAndVideo) 12 else (packets - 1) / counterEvery, native.underruns.get())
            assertEquals(totalPackets * 960L, player.sourceSubmittedFrames)
            assertEquals(1920L * totalPackets * (totalPackets + 1) / 2, native.sampleSum.get())
            assertTrue(native.maximumQueued.get() <= 6240)
            assertTrue(native.timing().consumedFrames > 200 * 960)
            if (withGapAndVideo) {
                // Later empty intervals may replace the gap's duration before a rate-limited event.
                assertTrue(regulation.toString(), regulation.any { it.silenceFrames > 0 })
                await { decoders.sumOf { it.completed.get() } == captured || errors.isNotEmpty() }
                assertEquals(captured, decoders.sumOf { it.inputs.get() })
                assertTrue(decoders.first().closed)
                assertTrue(errors.toString(), errors.isEmpty())
            }
        } finally { try { renderer?.close() } finally { player.close() } }
        assertTrue(native.closed)
    }

    @Test fun outputErrorsStallsAndMalformedPcmStayFatalAfterRecoveredEvents() {
        for (failure in listOf(-6, 0, 1)) {
            val now = AtomicLong(1_000_000_000)
            val native = RecoveringTrack(now)
            val errors = ConcurrentLinkedQueue<String>()
            val format = AudioFormat("pcm", 48000, 2, byteArrayOf())
            val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
                .apply { audioExpected = true; anchorVideo(8_000_000_000) }
            val player = AudioPlayer(format, clock, { code, detail -> errors.add("$code: $detail") }, { native })
            try {
                player.start()
                player.offer(packet(0, 8_000_000_000))
                await { player.sourceSubmittedFrames == 960L || errors.isNotEmpty() }
                native.underruns.set(12)
                if (failure != 1) native.writeResult = failure
                now.addAndGet(20_000_000)
                player.offer(if (failure == 1) AccessUnit(8_000_020_000, byteArrayOf(1, 2, 3)) else packet(1, 8_000_020_000))
                await { errors.isNotEmpty() }
                assertEquals(1, errors.size)
                val error = errors.single()
                assertTrue(error, error.startsWith("AUDIO_FAILED:"))
                when (failure) {
                    -6 -> assertTrue(error, error.contains("stage=OUTPUT_WRITE") && error.contains("AudioTrack write failed"))
                    0 -> assertTrue(error, error.contains("stage=OUTPUT_WRITE") && error.contains("Audio output stalled for 250 ms"))
                    else -> assertTrue(error, error.contains("stage=PCM_DECODE") && error.contains("not frame aligned"))
                }
            } finally { player.close() }
        }
    }

    private fun packet(index: Int, ptsUs: Long): AccessUnit {
        val buffer = ByteBuffer.allocate(3840).order(ByteOrder.LITTLE_ENDIAN)
        repeat(1920) { buffer.putShort((index + 1).toShort()) }
        return AccessUnit(ptsUs, buffer.array())
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Actual PCM worker stopped progressing", condition())
    }

    private class RecoveringTrack(private val now: AtomicLong) : RateAudioTrack {
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
        val underruns = AtomicInteger()
        override val underrunCount get() = synchronized(this) { underruns.get() }
        val written = AtomicLong()
        val sampleSum = AtomicLong()
        val maximumQueued = AtomicLong()
        @Volatile var writeResult: Int? = null
        @Volatile var closed = false
        private var consumed = 0.0
        private var previousNs = 0L
        private var startNs = 0L
        private fun advance() {
            // One packet of startup buffering prevents fake empty crossings at every normal tick.
            val current = (now.get() - startNs - 20_000_000L).coerceAtLeast(0)
            if (isPlaying) consumed = minOf(written.get().toDouble(),
                consumed + (current - previousNs).coerceAtLeast(0) * sampleRate.toDouble() * speed / 1_000_000_000)
            previousNs = current
        }
        @Synchronized override fun play() { isPlaying = true; startNs = now.get(); previousNs = 0 }
        @Synchronized override fun pause() { advance(); isPlaying = false }
        @Synchronized override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            writeResult?.let {
                if (it == 0) now.addAndGet(10_000_000L)
                return it
            }
            advance()
            val frames = minOf(count / channels, (bufferFrames - (written.get() - consumed.toLong())).coerceAtLeast(0).toInt())
            var sum = 0L
            repeat(frames * channels) { sum += samples[offset + it] }
            sampleSum.addAndGet(sum)
            written.addAndGet(frames.toLong())
            maximumQueued.updateAndGet { maxOf(it, written.get() - consumed.toLong()) }
            return frames * channels
        }
        @Synchronized override fun timing(): AudioOutputTiming {
            advance()
            val head = consumed.toLong()
            return AudioOutputTiming(sampleRate, bufferFrames, written.get(), head, head, 0, AudioClockSource.TIMESTAMP)
        }
        @Synchronized override fun setSpeed(speed: Float) { advance(); actualSpeed = speed }
        override fun close() { closed = true }
    }

    private class PassthroughDecoder : VideoDecoder {
        override val description = "Recovery test decoder"
        private val outputs = ConcurrentLinkedQueue<VideoOutput>()
        val inputs = AtomicInteger()
        val completed = AtomicInteger()
        @Volatile var closed = false
        override fun dequeueInput() = 0
        override fun input(index: Int) = ByteBuffer.allocate(16)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            outputs.add(VideoOutput(inputs.incrementAndGet(), ptsUs, 1, 0))
        }
        override fun dequeueOutput() = outputs.poll()
        override fun releaseOutput(index: Int, renderAtNs: Long?) { completed.incrementAndGet() }
        override fun close() { closed = true }
    }
}
