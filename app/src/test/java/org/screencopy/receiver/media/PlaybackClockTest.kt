package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class PlaybackClockTest {
    @Test fun videoFirstPlaysBeforeAudioWithThreeHundredMillisecondSourceOffset() {
        val time = FakeTime()
        val clock = time.clock()
        val first = 8_000_000_000L
        clock.anchorVideo(first)
        assertEquals(0L, clock.delayUs(first))
        assertEquals(300_000L, clock.audioStartDelayUs(first + 300_000))
        time.advanceUs(150_000)
        assertEquals(0L, clock.delayUs(first + 150_000))
        assertEquals(150_000L, clock.audioStartDelayUs(first + 300_000))
        time.advanceUs(150_000)
        val beforeHandover = clock.currentUs()
        var playedUs = 0L
        clock.anchorAudio(first + 300_000) { playedUs }
        clock.audioWritten(40_000)
        assertEquals(beforeHandover, clock.currentUs())
        playedUs = 20_000
        assertEquals(first + 320_000, clock.currentUs())
    }

    @Test fun delayedOpusAudioIsScheduledAtItsFirstAudibleTimestampIncludingPreskip() {
        val time = FakeTime()
        val clock = time.clock()
        clock.anchorVideo(1_000_000)
        val audio = StartupTrim(312, 48000).apply(960, 1_300_000)
        assertEquals(1_306_500L, audio.audiblePtsUs)
        assertEquals(306_500L, clock.audioStartDelayUs(audio.audiblePtsUs))
        time.advanceUs(306_500)
        val beforeHandover = clock.currentUs()
        clock.anchorAudio(audio.audiblePtsUs) { 0 }
        assertEquals(beforeHandover, clock.currentUs())
    }

    @Test fun earlierVideoDoesNotRewindAnAlreadyRunningMonotonicAnchor() {
        val time = FakeTime()
        val clock = time.clock()
        assertEquals(0L, clock.audioStartDelayUs(1_300_000))
        time.advanceUs(20_000)
        clock.anchorVideo(1_000_000)
        assertEquals(-20_000L, clock.audioStartDelayUs(1_300_000))
        assertEquals(1_320_000L, clock.currentUs())
        assertEquals(0L, clock.videoDelayUs(1_000_000))
    }

    @Test fun startupWaitIsCancellableAndBoundedWithoutDroppingVideo() {
        val time = FakeTime()
        val clock = time.clock()
        clock.anchorVideo(1_000_000)
        assertTrue(clock.awaitAudioStart(1_300_000) { true })
        assertEquals(1_300_000L, clock.currentUs())
        assertEquals(300_000_000L, time.ns)
        assertFalse(clock.awaitAudioStart(1_400_000) { false })
        assertEquals(300_000_000L, time.ns)
        assertThrows(PlaybackException::class.java) { clock.audioStartDelayUs(5_000_000) }
    }

    @Test fun audioCannotBecomeMasterBeforeItsPresentationTime() {
        val time = FakeTime()
        val clock = time.clock()
        clock.anchorVideo(1_000_000)
        assertThrows(IllegalStateException::class.java) { clock.anchorAudio(1_300_000) { 0 } }
        assertEquals(1_000_000L, clock.currentUs())
    }

    @Test fun unchangedAudioClockSurvivesVideoGenerationAndAudioDetachStaysContinuous() {
        val time = FakeTime()
        val clock = time.clock()
        assertTrue(clock.awaitAudioStart(1_000_000) { true })
        var audioUs = 20_000L
        clock.anchorAudio(1_000_000) { audioUs }
        clock.audioWritten(100_000)
        clock.anchorVideo(1_200_000)
        assertEquals(1_020_000L, clock.currentUs())
        audioUs = 40_000
        clock.detachAudio()
        assertEquals(1_040_000L, clock.currentUs())
        time.advanceUs(20_000)
        assertEquals(1_060_000L, clock.currentUs())
    }

    @Test fun videoOnlyStillUsesItsMonotonicAnchorAndLateAudioFailsExplicitly() {
        val time = FakeTime()
        val clock = time.clock()
        assertNull(clock.currentUs())
        clock.anchorVideo(1_000_000)
        time.advanceUs(580_000)
        assertEquals(1_580_000L, clock.currentUs())
        assertThrows(PlaybackException::class.java) { clock.audioStartDelayUs(1_000_000) }
    }

    @Test fun defaultVideoNeverWaitsForTheAudioSourceClockEvenAfterPreroll() {
        val time = FakeTime()
        val clock = time.clock().apply { audioExpected = true }
        clock.anchorVideo(1_000_000)
        val slice = checkNotNull(clock.prepareAudio(TrimmedAudio(0, 960, 1_300_000), 48000))
        time.advanceUs(80_000)
        var playedUs = 0L
        clock.anchorAudio(slice.audiblePtsUs) { playedUs }
        clock.audioWritten(3_000_000)
        assertEquals(0L, clock.videoDelayUs(1_000_000))
        time.advanceUs(3_000_000)
        playedUs = 3_000_000
        assertEquals(0L, clock.videoDelayUs(1_000_000))
        assertEquals(0L, clock.videoDelayUs(9_000_000))
    }

    private class FakeTime {
        var ns = 0L
        fun advanceUs(us: Long) { ns += us * 1000 }
        fun clock() = PlaybackClock({ ns }, { ms -> ns += ms * 1_000_000 })
    }
}
