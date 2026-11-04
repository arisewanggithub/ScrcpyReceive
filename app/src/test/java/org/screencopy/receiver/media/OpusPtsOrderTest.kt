package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.Configuration
import org.screencopy.receiver.protocol.Message
import org.screencopy.receiver.protocol.ProtocolException
import org.screencopy.receiver.protocol.Request
import org.screencopy.receiver.protocol.StrictJson
import org.screencopy.receiver.protocol.TrackState
import org.screencopy.receiver.protocol.Type
import org.screencopy.receiver.protocol.Wire
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class OpusPtsOrderTest {
    private fun fixture() = checkNotNull(javaClass.getResourceAsStream("/opus-pts-order.json"))
        .use { StrictJson.decode(it.readBytes()) }
    private fun packet() = fixture().getString("opusPacketHex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun roundTrip(message: Message): Message = ByteArrayOutputStream().let {
        Wire.write(it, message)
        Wire.read(ByteArrayInputStream(it.toByteArray()))
    }

    @Test fun allSevenOwnerCasesUseConfiguredCodecWithoutAReversalMagnitudeOrStartupLimit() {
        val cases = fixture().getJSONArray("cases")
        assertEquals(7, cases.length())
        repeat(cases.length()) { index ->
            val case = cases.getJSONObject(index)
            val track = case.getInt("track")
            val state = TrackState(listOf(track))
            state.configure(track, 1, case.getString("codec"))
            state.ready(track)
            val input = case.getJSONArray("ptsUs")
            val actual = mutableListOf<Long>()
            val accept = {
                repeat(input.length()) { position ->
                    val pts = input.getLong(position)
                    val message = roundTrip(Message(if (track == 1) Type.VIDEO_FRAME else Type.AUDIO_PACKET,
                        1, track, 1, pts, payload = packet()))
                    state.media(message)
                    actual.add(message.ptsUs)
                }
            }
            if (case.getBoolean("accepted")) {
                accept()
                assertEquals((0 until input.length()).map(input::getLong), actual)
            } else assertThrows(ProtocolException::class.java) { accept() }
        }
    }

    @Test fun wireTrackStateAndActualOpusPlayerKeepTheMinus449StartupPacketAndPreskipExactlyOnce() =
        play(listOf(1_000_000, 999_551), throughWire = true)

    @Test fun actualOpusDecodeWorkerAlsoAcceptsTheBackwardObservationAfterTrackValidation() =
        play(listOf(1_000_000, 999_551), throughWire = false)

    @Test fun laterLargeOpusReversalsRemainArrivalOrderedWithoutALifetimeOrMagnitudeGuard() =
        play((0 until 90).map { index ->
            when {
                index == 1 -> 999_551L
                index >= 70 -> 1_000_000L + index * 20_000 - 600_000
                else -> 1_000_000L + index * 20_000
            }
        }, throughWire = true)

    @Test fun missingOrNonExactCodecVideoAndStaleGenerationCannotAcquireTheOpusException() {
        for (codec in listOf(null, "OPUS", "aac", "raw")) {
            val state = TrackState(listOf(2))
            state.configure(2, 1, codec)
            state.ready(2)
            state.media(Message(Type.AUDIO_PACKET, 1, 2, 1, 1000000, payload = packet()))
            assertThrows(ProtocolException::class.java) {
                state.media(Message(Type.AUDIO_PACKET, 1, 2, 1, 999551, payload = packet()))
            }
        }
        val state = TrackState(listOf(1, 2))
        state.configure(1, 1, "h264"); state.ready(1)
        state.configure(2, 1, "opus"); state.ready(2)
        state.media(Message(Type.AUDIO_PACKET, 1, 2, 1, 1000000, payload = packet()))
        state.configure(1, 2, "h264"); state.ready(1)
        state.media(Message(Type.AUDIO_PACKET, 1, 2, 1, 999551, payload = packet()))
        assertThrows(ProtocolException::class.java) {
            state.media(Message(Type.AUDIO_PACKET, 1, 2, 2, 999000, payload = packet()))
        }
        state.configure(2, 2, "aac"); state.ready(2)
        state.media(Message(Type.AUDIO_PACKET, 1, 2, 2, 1000000, payload = packet()))
        assertThrows(ProtocolException::class.java) {
            state.media(Message(Type.AUDIO_PACKET, 1, 2, 2, 999551, payload = packet()))
        }
    }

    @Test fun actualWorkerQueueStillRejectsActualMinMaxSpanOver250msWithoutReordering() {
        val now = AtomicLong()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val format = Configuration.audio(fixture().getJSONObject("opusConfig"), Request("h264", "opus", 60, ""))
        val clock = PlaybackClock(now::get).apply { audioExpected = true; anchorVideo(1_000_000) }
        val decoder = object : OpusPacketDecoder {
            override fun decode(packet: ByteArray): ShortArray {
                entered.countDown()
                check(resume.await(3, TimeUnit.SECONDS))
                return ShortArray(1920)
            }
            override fun close() {}
        }
        val player = AudioPlayer(format, clock, { _, _ -> }, { RecordingOutput(now) }, opusFactory = { decoder })
        try {
            player.start()
            player.offer(AccessUnit(1_000_000, packet()))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            listOf(1_000_000L, 800_000L, 1_050_000L).forEach { player.offer(AccessUnit(it, packet())) }
            val error = assertThrows(QueueCongestionException::class.java) { player.offer(AccessUnit(799_999, packet())) }
            assertEquals(QueueLimit.PTS, error.pressure.limit)
            assertEquals(250_001L, error.pressure.spanUs)
            assertEquals(250_000L, error.pressure.maxSpanUs)
            assertEquals(3, player.queuedPackets())
        } finally { resume.countDown(); player.close() }
    }

    private fun play(points: List<Long>, throughWire: Boolean) {
        val data = fixture()
        val config = roundTrip(Message.json(Type.STREAM_CONFIG, data.getJSONObject("opusConfig"), 1, 2, 1))
        val format = Configuration.audio(config.json(), Request("h264", "opus", 60, ""))
        val state = TrackState(listOf(1, 2))
        state.configure(1, 1, "h264")
        state.ready(1)
        state.configure(2, 1, format.codec)
        state.ready(2)
        val now = AtomicLong()
        val clock = PlaybackClock(now::get) { now.addAndGet(it * 1_000_000) }
            .apply { audioExpected = true; anchorVideo(1_000_000) }
        val errors = ConcurrentLinkedQueue<String>()
        val adjustments = ConcurrentLinkedQueue<AudioClockCorrection>()
        val output = RecordingOutput(now)
        val decoded = AtomicInteger()
        val received = ConcurrentLinkedQueue<ByteArray>()
        val decoder = object : OpusPacketDecoder {
            override fun decode(packet: ByteArray): ShortArray {
                received.add(packet.copyOf())
                val value = decoded.incrementAndGet().toShort()
                return ShortArray(1920) { value }
            }
            override fun close() {}
        }
        val player = AudioPlayer(format, clock, { code, detail -> errors.add("$code: $detail") },
            { output }, onClockCorrection = adjustments::add, opusFactory = { decoder })
        var liveStart = 0L
        var previous = Long.MIN_VALUE
        try {
            player.start()
            points.forEachIndexed { index, pts ->
                if (index > 0) now.set(liveStart + index * 20_000_000L)
                val message = roundTrip(Message(Type.AUDIO_PACKET, 1, 2, 1, pts, payload = packet()))
                if (throughWire) state.media(message)
                assertEquals(pts, message.ptsUs)
                player.offer(AccessUnit(message.ptsUs, message.payload))
                val frames = (index + 1) * 960 - 312
                await { player.sourceSubmittedFrames >= frames || errors.isNotEmpty() }
                assertTrue(errors.toString(), errors.isEmpty())
                if (index == 0) liveStart = now.get()
                val current = checkNotNull(clock.currentUs())
                assertTrue("Source observation reversals must not rewind presentation", current >= previous)
                previous = current
            }
            assertEquals(points.size * 960L - 312, player.sourceSubmittedFrames)
            assertEquals(1296L + (2..points.size).sumOf { it * 1920L }, output.sum.get())
            assertEquals(points.size, decoded.get())
            assertTrue(received.all { it.contentEquals(packet()) })
            assertTrue(adjustments.toString(), adjustments.any { it.afterOffsetUs == -20_449L })
            assertTrue(output.maxQueued.get() <= 6240)
        } finally { player.close() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Real Opus decode worker made no progress", condition())
    }

    private class RecordingOutput(private val now: AtomicLong) : AudioOutput {
        val frames = AtomicLong()
        val sum = AtomicLong()
        val maxQueued = AtomicLong()
        override val underrunCount = 0
        private var start = 0L
        private var playing = false
        override fun play() { playing = true; start = now.get() }
        override fun pause() { playing = false }
        override fun timing(): AudioOutputTiming {
            val consumed = if (playing) minOf(frames.get(), (now.get() - start).coerceAtLeast(0) * 48000 / 1_000_000_000) else 0
            return AudioOutputTiming(48000, 6240, frames.get(), consumed, consumed, null, AudioClockSource.HEAD_UNAVAILABLE)
        }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            val queued = frames.get() - timing().consumedFrames
            val accepted = minOf(count / 2, (6240 - queued).coerceAtLeast(0).toInt())
            repeat(accepted * 2) { sum.addAndGet(samples[offset + it].toLong()) }
            frames.addAndGet(accepted.toLong())
            maxQueued.updateAndGet { maxOf(it, frames.get() - timing().consumedFrames) }
            return accepted * 2
        }
        override fun setSpeed(speed: Float) {}
        override fun close() {}
    }
}
