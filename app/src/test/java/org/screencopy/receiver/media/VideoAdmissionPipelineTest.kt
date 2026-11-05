package org.screencopy.receiver.media

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.net.ReceiverServer
import org.screencopy.receiver.protocol.*
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class VideoAdmissionPipelineTest {
    @Test fun negotiatedRotationCrossesRealTcpAndChangesPresentationWithoutANewDecoderGeneration() {
        val errors = ConcurrentLinkedQueue<String>()
        val rotations = ConcurrentLinkedQueue<Int>()
        val decoders = mutableListOf<BoundedDecoder>()
        val sizes = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
            onVideoSize = { width, height -> assertEquals(1080, width); assertEquals(1920, height); sizes.incrementAndGet() },
            videoFactory = { format, clock, failed, generation ->
                assertEquals(1L, generation)
                val decoder = BoundedDecoder(System::nanoTime).also(decoders::add)
                VideoPlayer(format, clock, failed, decoder, generation, onRotation = rotations::add)
            }).use { server ->
            server.start()
            Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                peer.soTimeout = 4000
                fun control(type: Type, json: JSONObject, track: Int = 0, generation: Long = 0) =
                    Wire.write(peer.outputStream, Message.json(type, json, if (type == Type.HELLO) 0 else 41, track, generation))
                fun expect(type: Type): Message {
                    val response = Wire.read(peer.inputStream)
                    assertEquals("Unexpected peer reply: ${response.type}; $errors", type, response.type)
                    return response
                }
                control(Type.HELLO, JSONObject().put("role", "sender").put("versions", JSONArray().put(3))
                    .put("name", "Phone").put("videoRotation", true))
                assertTrue(expect(Type.HELLO).json().getBoolean("videoRotation"))
                control(Type.SESSION_REQUEST, StrictJson.decode(
                    """{"video":{"codec":"h265","encoder":"","bitrate":20000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":false,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""}}""".toByteArray()))
                expect(Type.SESSION_ACCEPT)
                control(Type.STREAM_CONFIG, JSONObject().put("codec", "h265").put("width", 1080).put("height", 1920)
                    .put("csd", JSONArray().put("AAAAAQE=")), 1, 1)
                expect(Type.STREAM_READY)
                val expectedPts = mutableListOf<Long>()
                for ((index, turns) in listOf(1, 2, 3, 0, 1).withIndex()) {
                    val pts = 8_000_000_000 + index * 20_000L
                    expectedPts += pts
                    Wire.write(peer.outputStream, Message(Type.VIDEO_FRAME, 41, 1, 1, pts,
                        flags = (turns shl 1) or if (index == 0) 1 else 0, payload = byteArrayOf(0, 0, 0, 1, 1)))
                    await { rotations.size > index || errors.isNotEmpty() }
                    assertTrue(errors.toString(), errors.isEmpty())
                }
                assertEquals(listOf(90, 180, 270, 0, 90), rotations.toList())
                assertEquals(1, sizes.get())
                assertEquals(1, decoders.size)
                assertEquals(expectedPts, decoders.single().inputPts.toList())
                assertFalse(decoders.single().closed)
                control(Type.STOP, JSONObject().put("reason", "rotation test"))
                expect(Type.SESSION_END)
            }
        }
        assertTrue(decoders.single().closed)
    }

    @Test fun reportedProjectorCounterIsSixtyFpsPtsPressureNotByteOrPacketCapacity() {
        val budget = PacketBudget(8 * 1024 * 1024, 250_000)
        repeat(15) { index ->
            budget.add(AccessUnit(index * 16_680L, ByteArray(if (index == 14) 38_495 else 38_489)))
        }
        val pressure = checkNotNull(budget.pressure(AccessUnit(266_887, ByteArray(14_252)), 1, 48, 250))
        assertEquals("track=1 queue_limit=PTS queued_packets=15 queued_bytes=577341" +
            " attempted_packets=16 attempted_bytes=591593 relative_pts_span_us=266887" +
            " max_packets=48 max_bytes=8388608 max_span_us=250000 wait_ms=250", pressure.logMessage())
    }

    @Test fun playing60fpsVideoKeepsFeedingThroughHardwareTransitAndRefreshedOpusSourcePhase() = stream(60, 200_000)

    @Test fun playing30fpsVideoKeepsFeedingThrough300msHardwareTransit() = stream(30, 300_000)

    private fun stream(fps: Int, transitUs: Long) {
        val now = System::nanoTime
        val base = 8_000_000_000L
        val clock = PlaybackClock(now)
        val output = TransitOutput(now, transitUs)
        val decoders = mutableListOf<BoundedDecoder>()
        val errors = ConcurrentLinkedQueue<String>()
        val corrections = ConcurrentLinkedQueue<AudioClockCorrection>()
        val expectedPts = mutableListOf<Long>()
        val pcmFrames = 48000 / fps
        var sourceFrames = 0L
        var generation = 1L
        ReceiverServer.forProtocolTests("Receiver", onFailure = { errors.add(it.logMessage()) },
            clockFactory = { clock },
            videoFactory = { format, shared, failed, epoch ->
                assertEquals(1080, format.width)
                assertEquals(1920, format.height)
                assertEquals("video/hevc", format.mime)
                val decoder = BoundedDecoder(now).also(decoders::add)
                VideoPlayer(format, shared, failed, decoder, epoch)
            },
            audioFactory = { format, shared, failed ->
                AudioPlayer(format, shared, failed, { output }, onClockCorrection = corrections::add,
                    opusFactory = { object : OpusPacketDecoder {
                        override fun decode(packet: ByteArray) = ShortArray(pcmFrames * 2)
                        override fun close() {}
                    } })
            }).use { server ->
            server.start()
            Socket(InetAddress.getLoopbackAddress(), server.listeningPort).use { peer ->
                peer.soTimeout = 4000
                peer.tcpNoDelay = true
                fun control(type: Type, json: JSONObject, track: Int = 0, epoch: Long = 0) =
                    Wire.write(peer.outputStream, Message.json(type, json, if (type == Type.HELLO) 0 else 41, track, epoch))
                fun expect(type: Type): Message {
                    val result = Wire.read(peer.inputStream)
                    assertEquals("Unexpected peer response: ${if (result.type == Type.ERROR) result.json() else result.type}; $errors", type, result.type)
                    return result
                }
                control(Type.HELLO, JSONObject().put("role", "sender").put("versions", JSONArray().put(3)).put("name", "Phone"))
                expect(Type.HELLO)
                val settings = StrictJson.decode(
                    """{"video":{"codec":"h265","encoder":"","bitrate":20000000,"maxSize":0,"maxFps":$fps,"displayId":0,"orientation":"0","crop":{"width":1080,"height":1920,"x":0,"y":228},"codecOptions":""},"audio":{"enabled":true,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""},"receiverOrientation":"landscape"}""".toByteArray())
                control(Type.SESSION_REQUEST, settings)
                expect(Type.SESSION_ACCEPT)
                fun configureVideo() {
                    control(Type.STREAM_CONFIG, JSONObject().put("codec", "h265").put("width", 1080).put("height", 1920)
                        .put("csd", JSONArray().put("AAAAAQE=")), 1, generation)
                    expect(Type.STREAM_READY)
                }
                configureVideo()
                val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0, 0, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0)
                control(Type.STREAM_CONFIG, JSONObject().put("codec", "opus").put("sampleRate", 48000).put("channels", 2)
                    .put("csd", JSONArray().put(Base64.getEncoder().encodeToString(head)))
                    .put("codecDelayNs", 0).put("seekPrerollNs", 80_000_000), 2, 1)
                expect(Type.STREAM_READY)
                var start = 0L
                var previousClock = Long.MIN_VALUE
                repeat(fps * 14) { index ->
                    if (index > 0) {
                        val deadline = start + index * 1_000_000_000L / fps
                        while (now() < deadline) TimeUnit.NANOSECONDS.sleep((deadline - now()).coerceAtLeast(0))
                    }
                    if (index == fps * 11) {
                        assertTrue("Must already be presenting before generation change", decoders.sumOf { it.rendered.get() } > fps)
                        val before = clock.currentUs()
                        generation++
                        configureVideo()
                        assertTrue(checkNotNull(clock.currentUs()) >= checkNotNull(before))
                    }
                    val pts = base + index * 1_000_000L / fps
                    // A finite codec-output pause after playback has started, not a slow decoder.
                    if (index == fps * 2) {
                        assertTrue(output.written.get() > 48000)
                        assertTrue("Failure must not be a startup-only scenario", decoders.single().rendered.get() > fps / 2)
                        decoders.single().outputReadyAt.set(now() + 100_000_000)
                    }
                    val payload = ByteArray(20_000_000 / 8 / fps).apply { this[3] = 1 }
                    // A static screen before rotation lets the prior generation finish naturally.
                    if (index < fps * 10 || index in fps * 11 until fps * 13) {
                        Wire.write(peer.outputStream, Message(Type.VIDEO_FRAME, 41, 1, generation, pts,
                            flags = if (index == 0 || index == fps * 11) 1 else 0, payload = payload))
                        expectedPts.add(pts)
                    }
                    // Opus refreshes source phase independently of the continuous decoded sample timeline.
                    val phase = if (index < fps * 3) 0 else -149_651
                    Wire.write(peer.outputStream, Message(Type.AUDIO_PACKET, 41, 2, 1, pts + phase, payload = byteArrayOf(1)))
                    control(Type.PING, JSONObject().put("nonce", index))
                    expect(Type.PONG)
                    sourceFrames += pcmFrames
                    await { output.written.get() >= sourceFrames || errors.isNotEmpty() }
                    assertTrue(errors.toString(), errors.isEmpty())
                    if (index == 0) start = now()
                    val current = checkNotNull(clock.currentUs())
                    assertTrue("Presentation clock must not rewind", current >= previousClock)
                    previousClock = current
                }
                assertTrue(corrections.any { it.afterOffsetUs == -149_651L })
                assertTrue(errors.toString(), errors.isEmpty())
                await { decoders.sumOf { it.inputs.get() } == fps * 12 }
                assertEquals(fps * 12, decoders.sumOf { it.inputs.get() })
                assertEquals("Every compressed access unit must reach the decoder in order", expectedPts,
                    decoders.flatMap { it.inputPts.toList() })
                assertTrue(decoders.all { it.maxOutstanding.get() <= 2 })
                assertTrue(decoders.all { it.maximumBuffered.get() <= 8 })
                assertTrue(output.maxQueued.get() <= 6240)
                control(Type.STOP, JSONObject().put("reason", "test complete"))
                expect(Type.SESSION_END)
            }
        }
        assertTrue(decoders.all { it.closed })
        assertTrue(output.closed)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Production playback stopped progressing", condition())
    }

    private class BoundedDecoder(private val now: () -> Long) : VideoDecoder {
        override val description = "Bounded deterministic decoder"
        private val pictures = ConcurrentLinkedQueue<VideoOutput>()
        val inputs = AtomicInteger()
        val inputPts = ConcurrentLinkedQueue<Long>()
        private val released = AtomicInteger()
        private val outstanding = AtomicInteger()
        val maxOutstanding = AtomicInteger()
        val maximumBuffered = AtomicInteger()
        val rendered = AtomicInteger()
        val outputReadyAt = AtomicLong()
        @Volatile var closed = false
        override fun dequeueInput() = if (inputs.get() - released.get() < 8) 0 else -1
        override fun input(index: Int) = ByteBuffer.allocate(100_000)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            val sequence = inputs.incrementAndGet()
            inputPts.add(ptsUs)
            maximumBuffered.updateAndGet { maxOf(it, inputs.get() - released.get()) }
            pictures.add(VideoOutput(sequence, ptsUs, size, 0))
        }
        override fun dequeueOutput(): VideoOutput? {
            if (now() < outputReadyAt.get()) return null
            return pictures.poll()?.also {
                val held = outstanding.incrementAndGet()
                maxOutstanding.updateAndGet { maxOf(it, held) }
            }
        }
        override fun releaseOutput(index: Int, renderAtNs: Long?) {
            if (renderAtNs != null) {
                assertTrue("A future frame must retain its presentation deadline", renderAtNs >= now() - 10_000_000)
                rendered.incrementAndGet()
            }
            outstanding.decrementAndGet()
            released.incrementAndGet()
        }
        override fun close() { closed = true }
    }

    private class TransitOutput(private val now: () -> Long, private val transitUs: Long) : AudioOutput {
        val written = AtomicLong()
        val maxQueued = AtomicLong()
        private var previousNs = 0L
        private var consumed = 0.0
        private var speed = 1f
        private var playing = false
        @Volatile var closed = false
        override val underrunCount = 0
        override val playbackActive get() = playing
        @Synchronized override fun play() { playing = true; previousNs = now() }
        @Synchronized override fun pause() { advance(); playing = false }
        private fun advance() {
            val current = now()
            if (playing) consumed = minOf(written.get().toDouble(),
                consumed + (current - previousNs).coerceAtLeast(0) * 48000.0 * speed / 1_000_000_000)
            previousNs = current
        }
        @Synchronized override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            advance()
            val frames = minOf(count / 2, (6240 - written.get() + consumed.toLong()).coerceAtLeast(0).toInt())
            written.addAndGet(frames.toLong())
            maxQueued.updateAndGet { maxOf(it, written.get() - consumed.toLong()) }
            return frames * 2
        }
        @Synchronized override fun timing(): AudioOutputTiming {
            advance()
            return AudioOutputTiming(48000, 6240, written.get(), consumed.toLong(),
                (consumed.toLong() - transitUs * 48000 / 1_000_000).coerceAtLeast(0), 0, AudioClockSource.TIMESTAMP)
        }
        @Synchronized override fun setSpeed(speed: Float) { advance(); this.speed = speed }
        override fun close() { closed = true }
    }
}
