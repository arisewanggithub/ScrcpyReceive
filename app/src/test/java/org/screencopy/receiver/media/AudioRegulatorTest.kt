package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class AudioRegulatorTest {
    private val rate = 48000
    private fun packet(fifo: AudioRegulator, frames: Int, pts: Long = 0) =
        fifo.offer(ShortArray(frames * 2) { 7 }, TrimmedAudio(0, frames, pts))

    @Test fun fiftyMsTargetAndStartupOverflowKeepNewestDecodedSamplesAndPts() {
        val fifo = AudioRegulator(rate, 2)
        packet(fifo, 960, 1_000_000)
        assertNull(fifo.peek(240))
        packet(fifo, 4800, 1_020_000)
        assertTrue(fifo.started)
        assertEquals(2880, fifo.bufferedFrames)
        val first = checkNotNull(fifo.peek(240))
        assertEquals(1_060_000L, first.ptsUs)
        assertEquals(2880L, fifo.diagnostic().droppedFrames)
    }

    @Test fun recurring200To250msGapsRecoverWithin115msWithoutDiscardOnUnderrun() {
        for (gapMs in listOf(200, 219, 225, 250)) {
            val fifo = AudioRegulator(rate, 2)
            packet(fifo, 2400)
            var now = 0L
            fifo.correction(now)
            repeat(20) { cycle ->
                while (fifo.bufferedFrames > 0) fifo.consumed(minOf(240, fifo.bufferedFrames))
                val previousDrops = fifo.diagnostic().droppedFrames
                repeat(gapMs / 5) {
                    fifo.insertedSilence(240)
                    now += 5_000_000
                    fifo.correction(now)
                }
                assertEquals("Underrun is not an instruction to discard delayed audio", previousDrops, fifo.diagnostic().droppedFrames)
                repeat((gapMs + 19) / 20) { index ->
                    packet(fifo, 960, (cycle * 300_000L) + index * 20_000)
                    assertTrue(fifo.bufferedFrames <= 5520)
                }
                repeat(20) {
                    fifo.consumed(minOf(240, fifo.bufferedFrames))
                    packet(fifo, 240)
                    now += 5_000_000
                    fifo.correction(now)?.let { assertTrue(it in 0.98f..1.02f) }
                }
                assertTrue(fifo.bufferedFrames <= 5520)
            }
            val result = fifo.diagnostic()
            assertTrue(result.recoveries >= 20)
            assertTrue(result.silenceFrames >= rate * gapMs / 1000L * 19)
            assertTrue(result.droppedFrames > 0)
            fifo.clear()
            assertEquals(0, fifo.bufferedFrames)
        }
    }

    @Test fun smoothedFourSecondCompensationIsBoundedAndNeverAcceleratesBelowTarget() {
        val fifo = AudioRegulator(rate, 2)
        packet(fifo, 2400)
        fifo.correction(0)
        fifo.insertedSilence(48000)
        assertEquals(1.02f, checkNotNull(fifo.correction(1_000_000_000)), 0f)
        fifo.consumed(2160)
        assertEquals(1f, checkNotNull(fifo.correction(2_000_000_000)), 0f)
        assertEquals(240, fifo.bufferedFrames)
    }

    @Test fun compensationUsesDecodedOccupancyNotCaptureTimestampPhase() {
        val first = AudioRegulator(rate, 2)
        val second = AudioRegulator(rate, 2)
        packet(first, 2400, 1_000_000)
        packet(second, 2400, 2_000_000)
        first.correction(0); second.correction(0)
        repeat(100) {
            first.consumed(240); second.consumed(240)
            packet(first, 240, 1_000_000 + it * 5000L)
            packet(second, 240, 2_000_000 - it * 1000L)
            assertEquals(first.correction((it + 1) * 20_000_000L), second.correction((it + 1) * 20_000_000L))
        }
    }

    @Test fun occupancyAverageFollowsDecodedProductionNotEmptyOutputPolling() {
        val fifo = AudioRegulator(rate, 2)
        packet(fifo, 2400)
        fifo.consumed(2400)
        val before = fifo.diagnostic().averageFrames
        repeat(200) { fifo.correction(it * 20_000_000L) }
        assertEquals("Consumer polling must not repeatedly bias the average toward empty",
            before, fifo.diagnostic().averageFrames, 0.0)
        packet(fifo, 960)
        assertEquals(before + (960 - before) / 128, fifo.diagnostic().averageFrames, 0.0)
    }

    @Test fun insertedSilenceNeverAdvancesPresentedSourceSamples() {
        val timeline = AudioPresentationTimeline(rate)
        timeline.submitted(480, true)
        timeline.submitted(10560, false)
        timeline.submitted(480, true)
        assertEquals(10_000L, timeline.presentedUs(480))
        assertEquals(10_000L, timeline.presentedUs(480 + 5000))
        assertEquals(10_000L, timeline.presentedUs(11040))
        assertEquals(20_000L, timeline.presentedUs(11520))
        timeline.clear()
    }
}
