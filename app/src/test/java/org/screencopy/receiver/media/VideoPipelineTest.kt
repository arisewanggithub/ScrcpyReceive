package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

class VideoPipelineTest {
    private val format = VideoFormat("video/avc", 640, 360, listOf(byteArrayOf(1)), 50)

    @Test fun anIsolatedDecodedFrameDelayedBySchedulingStillPresentsWithoutCancellingCast() {
        val now = AtomicLong(1_000_000_000)
        val clock = PlaybackClock(now::get).apply { anchorVideo(8_000_000_000) }
        val decoder = FakeDecoder()
        val errors = ConcurrentLinkedQueue<String>()
        val player = VideoPlayer(format, clock, { code, detail -> errors.add("$code: $detail") }, decoder)
        try {
            player.start()
            player.offer(AccessUnit(8_000_000_000, byteArrayOf(1), true))
            assertTrue(decoder.outputReady.await(2, TimeUnit.SECONDS))
            now.addAndGet(500_000_000)
            decoder.resume.countDown()
            await { decoder.rendered.isNotEmpty() || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(1, decoder.rendered.size)
            assertEquals(1, decoder.inputs.size)
        } finally { decoder.resume.countDown(); player.close() }
        assertTrue(decoder.closed)
    }

    @Test fun catchupSkipsOnlyAnOlderDecodedPictureAndRendersTheLatestAvailable() {
        val now = AtomicLong(1_000_000_000)
        val clock = PlaybackClock(now::get).apply { anchorVideo(8_000_000_000) }
        val decoder = FakeDecoder(pictures = { pts, index ->
            listOf(VideoOutput(index, pts, 1, 0), VideoOutput(index + 100, pts + 400_000, 1, 1))
        })
        val errors = ConcurrentLinkedQueue<String>()
        val events = ConcurrentLinkedQueue<VideoDiagnostic>()
        val player = VideoPlayer(format, clock, { code, detail -> errors.add("$code: $detail") }, decoder, onPresentation = events::add)
        try {
            player.start()
            player.offer(AccessUnit(8_000_000_000, byteArrayOf(1), true))
            assertTrue(decoder.outputReady.await(2, TimeUnit.SECONDS))
            now.addAndGet(500_000_000)
            decoder.resume.countDown()
            await { decoder.rendered.isNotEmpty() || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(listOf(101), decoder.rendered.toList())
            assertEquals(listOf(1), decoder.skipped.toList())
            assertEquals(listOf(8_000_000_000L), decoder.inputs.toList())
            assertTrue(events.toString(), events.any { it.decodedSkipped == 1L })
            assertTrue(decoder.maximumOutstanding.get() <= 2)
        } finally { decoder.resume.countDown(); player.close() }
    }

    @Test fun missingKeyframeAndCapacityFailuresKeepExactProductionStageAndNumbers() {
        val keyframe = failed(FakeDecoder(block = false), keyFrame = false)
        assertEquals(VideoFault.FIRST_KEYFRAME, keyframe.fault)
        assertEquals(VideoStage.INPUT_KEYFRAME, keyframe.stage)
        assertFalse(keyframe.inputKeyFrame)
        val capacity = failed(FakeDecoder(block = false, capacity = 0))
        assertEquals(VideoFault.INPUT_CAPACITY, capacity.fault)
        assertEquals(VideoStage.INPUT_BUFFER, capacity.stage)
        assertEquals(1, capacity.inputBytes)
        assertEquals(0, capacity.inputCapacity)
        val missing = failed(FakeDecoder(block = false, missingInput = true))
        assertEquals(VideoFault.INPUT_BUFFER, missing.fault)
    }

    @Test fun codecResourceAndUnknownApiErrorsDoNotBecomeUnsupportedFormatOrLeakVendorText() {
        for (error in listOf(
            VideoCodecFailure(1100, false, true, IllegalStateException("private vendor details")),
            VideoCodecFailure(1101, true, false, IllegalStateException("private vendor details")),
            VideoCodecFailure(-1234, false, false, IllegalStateException("private vendor details")),
            IllegalStateException("private vendor details")
        )) {
            val diagnostic = failed(FakeDecoder(block = false, inputFailure = error))
            assertEquals(VideoStage.INPUT_DEQUEUE, diagnostic.stage)
            assertTrue(diagnostic.fault in setOf(VideoFault.CODEC_RESOURCE, VideoFault.CODEC_RECLAIMED, VideoFault.CODEC_API))
            if (error is VideoCodecFailure) {
                assertEquals(error.errorCode, diagnostic.codecError)
                assertEquals(error.recoverable, diagnostic.codecRecoverable)
                assertEquals(error.transient, diagnostic.codecTransient)
                assertEquals("CodecException", diagnostic.exceptionType)
            }
            assertFalse(diagnostic.logMessage().contains("private"))
        }
    }

    @Test fun outputMetadataPtsAndUnexpectedEosStayFatalInsteadOfBeingDroppedAsLate() {
        for ((picture, fault) in listOf(
            VideoOutput(1, 8_000_000_000, -1, 0) to VideoFault.OUTPUT_METADATA,
            VideoOutput(1, -1, 1, 0) to VideoFault.OUTPUT_PTS,
            VideoOutput(1, 8_000_000_000, 1, 4) to VideoFault.END_OF_STREAM
        )) {
            val diagnostic = failed(FakeDecoder(block = false, pictures = { _, _ -> listOf(picture) }))
            assertEquals(fault, diagnostic.fault)
            assertEquals(VideoStage.OUTPUT_VALIDATE, diagnostic.stage)
            assertEquals(picture.size, diagnostic.outputBytes)
            assertEquals(picture.flags, diagnostic.outputFlags)
        }
    }

    @Test fun decreasingDecodedPtsRemainFatalEvenWhenBothFramesAreLate() {
        val diagnostic = failed(FakeDecoder(block = false, pictures = { pts, index ->
            listOf(VideoOutput(index, pts, 1, 0), VideoOutput(index + 100, pts - 1, 1, 0))
        }), advance = true)
        assertEquals(VideoFault.OUTPUT_PTS, diagnostic.fault)
        assertNotNull(diagnostic.delayUs)
    }

    @Test fun realInputOutputStallsRemainBoundedAtThreeSeconds() {
        val output = failed(FakeDecoder(block = false, pictures = { _, _ -> emptyList() }), advance = true)
        assertEquals(VideoFault.OUTPUT_STALL, output.fault)
        assertEquals(1L, output.queuedFrames)
        assertTrue(output.outputWaitUs > 3_000_000)
        val input = failed(FakeDecoder(block = false, inputUnavailable = true), advance = true)
        assertEquals(VideoFault.INPUT_STALL, input.fault)
        assertEquals(VideoStage.INPUT_DEQUEUE, input.stage)
        assertTrue(input.inputWaitUs > 3_000_000)
    }

    @Test fun futureDecodedPicturePresentsImmediatelyWithoutAnAudioMasterWait() {
        val now = AtomicLong(1_000_000_000)
        val clock = PlaybackClock(now::get).apply {
            anchorVideo(8_000_000_000)
            anchorAudio(8_000_000_000) { 0 }
            audioWritten(20_000)
        }
        val decoder = FakeDecoder(block = false)
        val errors = ConcurrentLinkedQueue<String>()
        val player = VideoPlayer(format, clock, { code, detail -> errors.add("$code $detail") }, decoder)
        try {
            player.start()
            player.offer(AccessUnit(8_005_000_000, byteArrayOf(1), true))
            await { decoder.rendered.isNotEmpty() || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(1, decoder.rendered.size)
            assertEquals(1_000_000_000L, now.get())
        } finally { player.close() }
    }

    @Test fun unsupportedSelectionAndConfigurationFailuresRetainDifferentInitializationCauses() {
        val clock = PlaybackClock()
        val unsupported = assertThrows(VideoPlaybackException::class.java) {
            VideoPlayer(format, clock, { _, _ -> }, factory = { _, _ ->
                throw VideoInitializationFailure(VideoStage.SELECT, PlaybackException("private selector", "UNSUPPORTED_CONFIG"))
            }, generation = 2)
        }
        assertEquals("UNSUPPORTED_CONFIG", unsupported.code)
        assertEquals(VideoFault.UNSUPPORTED, unsupported.diagnostic.fault)
        assertEquals(2L, unsupported.diagnostic.generation)
        val api = assertThrows(VideoPlaybackException::class.java) {
            VideoPlayer(format, clock, { _, _ -> }, factory = { _, _ ->
                throw VideoInitializationFailure(VideoStage.CONFIGURE, IllegalArgumentException("private vendor format"))
            })
        }
        assertEquals("DECODER_FAILED", api.code)
        assertEquals(VideoStage.CONFIGURE, api.diagnostic.stage)
        assertEquals(VideoFault.CODEC_API, api.diagnostic.fault)
        assertFalse(api.message.orEmpty().contains("private"))
    }

    @Test fun latestOutputProvesProgressEvenIfCodecOmittedAnEarlierPictureBeforeStaticScreen() {
        val now = AtomicLong(1_000_000_000)
        val clock = PlaybackClock(now::get).apply { anchorVideo(8_000_000_000) }
        now.addAndGet(80_000_000)
        val decoder = FakeDecoder(block = false, pictures = { pts, index ->
            if (index == 1) emptyList() else listOf(VideoOutput(index, pts, 1, 0))
        })
        val errors = ConcurrentLinkedQueue<String>()
        val player = VideoPlayer(format, clock, { code, detail -> errors.add("$code: $detail") }, decoder)
        try {
            player.start()
            player.offer(AccessUnit(8_000_000_000, byteArrayOf(1), true))
            player.offer(AccessUnit(8_000_020_000, byteArrayOf(1)))
            await { decoder.rendered.size == 1 || errors.isNotEmpty() }
            now.addAndGet(4_000_000_000)
            Thread.sleep(25)
            assertTrue(errors.toString(), errors.isEmpty())
            assertEquals(2, decoder.inputs.size)
        } finally { player.close() }
    }

    @Test fun startupObservationFailureStillReleasesTheOwnedDecoder() {
        val decoder = FakeDecoder(block = false)
        val failure = assertThrows(VideoPlaybackException::class.java) {
            VideoPlayer(format, PlaybackClock(), { _, _ -> }, decoder,
                onPresentation = { throw IllegalStateException("private observer detail") })
        }
        assertTrue(decoder.closed)
        assertEquals(VideoStage.START, failure.diagnostic.stage)
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    @Test fun outputDequeueAndReleaseFailuresKeepTheirActualApiStage() {
        val dequeue = failed(FakeDecoder(block = false, outputFailure = IllegalStateException("private vendor output")))
        assertEquals(VideoStage.OUTPUT_DEQUEUE, dequeue.stage)
        assertEquals(VideoFault.CODEC_API, dequeue.fault)
        val release = failed(FakeDecoder(block = false,
            releaseFailure = VideoCodecFailure(-1010, false, false, IllegalStateException("private vendor release"))),
            advance = true)
        assertEquals(VideoStage.OUTPUT_RELEASE, release.stage)
        assertEquals(-1010, release.codecError)
        assertNotNull(release.delayUs)
    }

    private fun failed(decoder: FakeDecoder, keyFrame: Boolean = true, ptsOffset: Long = 0, advance: Boolean = false): VideoDiagnostic {
        val now = AtomicLong(1_000_000_000)
        val clock = PlaybackClock(now::get).apply { anchorVideo(8_000_000_000) }
        val errors = ConcurrentLinkedQueue<String>()
        val player = VideoPlayer(format, clock, { code, detail -> errors.add("$code: $detail") }, decoder, generation = 3)
        try {
            player.start()
            player.offer(AccessUnit(8_000_000_000 + ptsOffset, byteArrayOf(1), keyFrame))
            if (advance) {
                repeat(12) {
                    if (errors.isEmpty()) { Thread.sleep(5); now.addAndGet(1_000_000_000) }
                }
            }
            await { errors.isNotEmpty() }
            assertEquals(1, errors.size)
            val detail = errors.single()
            assertFalse(detail, detail.contains("private"))
            val diagnostic = checkNotNull(VideoDiagnostic.fromLocalDetail(detail)) { detail }
            assertEquals(3L, diagnostic.generation)
            return diagnostic
        } finally { decoder.resume.countDown(); player.close() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Actual video worker made no progress", condition())
    }

    private class FakeDecoder(
        private val block: Boolean = true,
        private val capacity: Int = 1024,
        private val missingInput: Boolean = false,
        private val inputUnavailable: Boolean = false,
        private val inputFailure: Exception? = null,
        private val outputFailure: Exception? = null,
        private val releaseFailure: Exception? = null,
        private val pictures: (Long, Int) -> List<VideoOutput> = { pts, index -> listOf(VideoOutput(index, pts, 1, 0)) }
    ) : VideoDecoder {
        override val description = "Test decoder"
        val outputReady = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val inputs = ConcurrentLinkedQueue<Long>()
        val rendered = ConcurrentLinkedQueue<Int>()
        val skipped = ConcurrentLinkedQueue<Int>()
        val maximumOutstanding = AtomicInteger()
        private val outstanding = AtomicInteger()
        private val outputs = ConcurrentLinkedQueue<VideoOutput>()
        @Volatile var closed = false
        override fun dequeueInput(): Int {
            inputFailure?.let { throw it }
            return if (inputUnavailable) -1 else 0
        }
        override fun input(index: Int): ByteBuffer? = if (missingInput) null else ByteBuffer.allocate(capacity)
        override fun queueInput(index: Int, size: Int, ptsUs: Long, keyFrame: Boolean) {
            inputs.add(ptsUs)
            outputs.addAll(pictures(ptsUs, inputs.size))
        }
        override fun dequeueOutput(): VideoOutput? {
            outputFailure?.let { throw it }
            val result = outputs.poll() ?: return null
            outputReady.countDown()
            if (block) check(resume.await(2, TimeUnit.SECONDS))
            val count = outstanding.incrementAndGet()
            maximumOutstanding.updateAndGet { maxOf(it, count) }
            return result
        }
        override fun releaseOutput(index: Int, renderAtNs: Long?) {
            releaseFailure?.let { throw it }
            if (renderAtNs != null) rendered.add(index) else skipped.add(index)
            outstanding.decrementAndGet()
        }
        override fun close() { closed = true }
    }
}
