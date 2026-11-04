package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AudioOutputPreparationTest {
    private val format = AudioFormat("pcm", 48000, 2, byteArrayOf())

    @Test fun nativeSmallRateDeadbandDoesNotBecomeAFatalMismatch() {
        val platform = FakePlatform(adjust = { it.minimumSpeedDelta = 0.0001f })
        PlatformAudioOutput(format, { _, _ -> }, platform).use { output ->
            output.setSpeed(1.001294f)
            output.setSpeed(1.0012041f)
            assertEquals(1.001294f, output.preparedSpeed, 0f)
            output.setSpeed(1f)
            assertEquals(1f, output.preparedSpeed, 0f)
            assertFalse(output.isPlaying)
        }
    }

    @Test fun nativeDeadbandNeverAllowsTheActualRateOutsideTheTwoPercentEnvelope() {
        val track = FakeTrack(format, 6240, 5793, null)
        track.actualSpeed = 1.02005f
        assertFalse(AudioOutputPreparation.rateMatches(track, 1.02f))
        track.actualSpeed = 0.97995f
        assertFalse(AudioOutputPreparation.rateMatches(track, 0.98f))
        track.actualSpeed = 1f
        assertFalse(AudioOutputPreparation.rateMatches(track, 1.0012041f))
    }

    @Test fun smallDeviceMinimumRequestsFiveMsRatherThanTheFullRouteCeiling() {
        val plan = AudioOutputPreparation.plan(format, 128 * 4)
        assertEquals(240, plan.frames)
        assertEquals(960, plan.bytes)
        assertEquals(6240, plan.maxFrames)
    }
    @Test fun nativeRateHeadroomCanExceedTheNominalFormulaButNeverTheExistingCeiling() {
        val tracks = mutableListOf<FakeTrack>()
        val retries = mutableListOf<OutputPreparationDiagnostic>()
        val platform = object : AudioOutputPlatform {
            override fun minimumBytes(format: AudioFormat) = 5760 * 4
            override fun create(format: AudioFormat, bytes: Int, failure: (String, String) -> Unit): RateAudioTrack =
                FakeTrack(format, bytes / 4, 6100, null).also(tracks::add)
        }
        val (track, plan) = AudioOutputPreparation.open(format, platform, { _, _ -> }, retries::add)
        try {
            assertEquals(listOf(5876, 6240), tracks.map { it.capacityFrames })
            assertEquals(1, tracks.first().releases)
            assertEquals(1, retries.size)
            assertEquals(5876, retries.single().requestedFrames)
            assertEquals(6240, plan.frames)
            assertEquals(listOf(1.02f, 0.98f, 1f), tracks.last().requests.toList())
            assertFalse(track.isPlaying)
        } finally { track.close() }
    }

    @Test fun permanentlyUnsupportedRateRemainsExplicitAfterOneBoundedRetry() {
        val tracks = mutableListOf<FakeTrack>()
        val platform = object : AudioOutputPlatform {
            override fun minimumBytes(format: AudioFormat) = 5760 * 4
            override fun create(format: AudioFormat, bytes: Int, failure: (String, String) -> Unit): RateAudioTrack =
                FakeTrack(format, bytes / 4, 7000, null).also(tracks::add)
        }
        val error = assertThrows(AudioOutputPreparationException::class.java) {
            AudioOutputPreparation.open(format, platform, { _, _ -> })
        }
        assertEquals(OutputPreparationReason.RATE_UNSUPPORTED, error.diagnostic.reason)
        assertEquals(listOf(5876, 6240), tracks.map { it.capacityFrames })
        assertTrue(tracks.all { it.releases == 1 && it.plays == 0 })
        assertEquals(1, error.suppressed.size)
    }

    @Test fun exactGalaxyMinimumIsPreparedForTwoPercentWithoutReservingTheEntire130msBudget() {
        val platform = FakePlatform()
        val old = FakeTrack(format, 5760, 5793, null)
        assertThrows(IllegalArgumentException::class.java) { old.setSpeed(1.005f) }
        val output = PlatformAudioOutput(format, { _, _ -> fail("No asynchronous error during prepare") }, platform)
        try {
            val track = checkNotNull(platform.created)
            assertEquals(5876, output.bufferFrames)
            assertEquals(5876, output.capacityFrames)
            assertEquals(listOf(1.02f, 0.98f, 1f), track.requests.toList())
            assertEquals(1f, output.preparedSpeed, 0f)
            assertFalse(output.isPlaying)
            assertEquals(0, track.plays)
            assertEquals(0, track.pauses)
            assertEquals(0, track.written.get())
        } finally { output.close() }
        assertEquals(1, platform.created?.releases)
    }

    @Test fun monoStereoRatesAndUnalignedMinimumBytesRemainWholeFramesWithin130ms() {
        for (rate in listOf(8000, 12000, 16000, 24000, 32000, 44100, 48000, 96000)) {
            for (channels in 1..2) {
                val selected = format.copy(sampleRate = rate, channels = channels)
                val minimum = rate / 10 * channels * 2 + 1
                val plan = AudioOutputPreparation.plan(selected, minimum)
                assertEquals((minimum + channels * 2 - 1) / (channels * 2), plan.minimumFrames)
                assertEquals(0, plan.bytes % (channels * 2))
                assertEquals(plan.frames * channels * 2, plan.bytes)
                assertTrue(plan.frames * 1000L <= rate * 130L)
                assertTrue(plan.frames > plan.minimumFrames * 1.02)
            }
        }
        assertThrows(IllegalArgumentException::class.java) { AudioOutputPreparation.plan(format.copy(channels = 0), 1024) }
        assertThrows(IllegalArgumentException::class.java) { AudioOutputPreparation.plan(format.copy(sampleRate = 0), 1024) }
    }

    @Test fun insufficientMinimumBufferBudgetFailsBeforeCreatingAnyTrack() {
        for (minimum in listOf(0, -2, 6240 * 4, Int.MAX_VALUE)) {
            val platform = FakePlatform(minimum = minimum)
            val failure = assertThrows(AudioOutputPreparationException::class.java) {
                PlatformAudioOutput(format, { _, _ -> }, platform)
            }
            assertEquals("UNSUPPORTED_CONFIG", failure.code)
            assertEquals(OutputPreparationReason.BUFFER_LIMIT, failure.diagnostic.reason)
            assertEquals(48000, failure.diagnostic.sampleRate)
            assertEquals(6240, failure.diagnostic.maxFrames)
            assertNull(platform.created)
        }
    }

    @Test fun platformRoundedCapacityAboveBudgetIsRejectedAndReleased() {
        val platform = FakePlatform(adjust = { it.capacityFrames = 6256; it.bufferFrames = 6256 })
        val failure = assertThrows(AudioOutputPreparationException::class.java) { PlatformAudioOutput(format, { _, _ -> }, platform) }
        assertEquals(OutputPreparationReason.BUFFER_LIMIT, failure.diagnostic.reason)
        assertEquals(6256, failure.diagnostic.capacityFrames)
        assertEquals(1, platform.created?.releases)
        assertTrue(checkNotNull(platform.created).requests.isEmpty())
    }

    @Test fun fastOrSlowUnsupportedFailsAtPreparationNotAfterPlayback() {
        for (rejected in listOf(1.02f, 0.98f)) {
            val platform = FakePlatform(adjust = { it.reject = rejected })
            val failure = assertThrows(AudioOutputPreparationException::class.java) { PlatformAudioOutput(format, { _, _ -> }, platform) }
            assertEquals(OutputPreparationReason.RATE_UNSUPPORTED, failure.diagnostic.reason)
            assertEquals(rejected, failure.diagnostic.speed, 0f)
            assertEquals(1, platform.created?.releases)
            assertEquals(0, platform.created?.plays)
            assertFalse(failure.message.orEmpty().contains("private vendor"))
        }
    }

    @Test fun silentlyAlteredParamsOrUnexpectedPlaybackStateAreNotAccepted() {
        val adjustments: List<(FakeTrack) -> Unit> = listOf(
            { it.ignoreSpeed = true }, { it.returnedPitch = Float.NaN },
            { it.autoPlay = true }, { it.failOnUnsupported = false }
        )
        for (adjust in adjustments) {
            val platform = FakePlatform(adjust = adjust)
            val failure = assertThrows(AudioOutputPreparationException::class.java) { PlatformAudioOutput(format, { _, _ -> }, platform) }
            assertTrue(failure.diagnostic.reason in setOf(OutputPreparationReason.PARAMS_MISMATCH, OutputPreparationReason.PLAY_STATE))
            assertEquals(1, platform.created?.releases)
        }
    }

    @Test fun formatMismatchDoesNotSubstituteRateChannelsOrPcmEncoding() {
        val adjustments: List<(FakeTrack) -> Unit> = listOf(
            { it.sampleRate = 44100 }, { it.channels = 1 }, { it.pcm16 = false }
        )
        for (adjust in adjustments) {
            val platform = FakePlatform(adjust = adjust)
            val failure = assertThrows(AudioOutputPreparationException::class.java) { PlatformAudioOutput(format, { _, _ -> }, platform) }
            assertEquals(OutputPreparationReason.FORMAT_MISMATCH, failure.diagnostic.reason)
            assertEquals(1, platform.created?.releases)
            assertEquals(0, platform.created?.plays)
        }
    }

    @Test fun failedNominalResetIsFatalAndStillReleasesTheTrack() {
        val platform = FakePlatform(adjust = { it.reject = 1f })
        val failure = assertThrows(AudioOutputPreparationException::class.java) { PlatformAudioOutput(format, { _, _ -> }, platform) }
        assertEquals(listOf(1.02f, 0.98f, 1f), platform.created?.requests?.toList())
        assertEquals(1f, failure.diagnostic.speed, 0f)
        assertEquals(1, platform.created?.releases)
        assertEquals(0, platform.created?.plays)
    }

    @Test fun productionAudioPlayerCorrectionsUseThePreparedEnvelope() {
        val now = AtomicLong()
        val waits = AtomicInteger()
        val tick = Semaphore(0)
        val errors = ConcurrentLinkedQueue<String>()
        val clock = PlaybackClock(now::get) { waits.incrementAndGet(); check(tick.tryAcquire(3, TimeUnit.SECONDS)) }
            .apply { audioExpected = true; anchorVideo(1_000_000) }
        val platform = FakePlatform(now = now)
        val player = AudioPlayer(format, clock, { code, detail -> errors.add("$code: $detail") },
            { PlatformAudioOutput(format, { code, detail -> errors.add("$code: $detail") }, platform) })
        val track = checkNotNull(platform.created)
        assertEquals(listOf(1.02f, 0.98f, 1f), track.requests.toList())
        assertEquals(0, track.plays)
        try {
            player.start()
            player.offer(packet(0))
            await { waits.get() > 0 || errors.isNotEmpty() }
            for (index in 1..65) {
                val priorWait = waits.get()
                now.addAndGet(20_000_000)
                player.offer(packet(index))
                tick.release()
                await { waits.get() > priorWait || player.sourceSubmittedFrames >= (index + 1) * 960 || errors.isNotEmpty() }
                assertTrue(errors.toString(), errors.isEmpty())
            }
            await { player.sourceSubmittedFrames == 66 * 960L || errors.isNotEmpty() }
            assertTrue(errors.toString(), errors.isEmpty())
            assertTrue(track.requests.size > 3)
            assertTrue(track.requests.all { it in 0.98f..1.02f })
            assertEquals(5876, track.bufferFrames)
            assertEquals(66 * 960L, player.sourceSubmittedFrames)
        } finally { tick.release(100); player.close() }
        assertEquals(1, track.releases)
    }

    @Test fun unsupportedPreparationStopsAudioPlayerConstructionBeforeAnyPlaybackOrPcm() {
        val platform = FakePlatform(adjust = { it.reject = 1.02f })
        val clock = PlaybackClock()
        val failure = assertThrows(AudioOutputPreparationException::class.java) {
            AudioPlayer(format, clock, { _, _ -> fail("Worker must not start") },
                { PlatformAudioOutput(format, { _, _ -> }, platform) })
        }
        assertEquals("UNSUPPORTED_CONFIG", failure.code)
        assertEquals(0, platform.created?.plays)
        assertEquals(0, platform.created?.pauses)
        assertEquals(0, platform.created?.written?.get())
        assertEquals(1, platform.created?.releases)
        assertNull(clock.currentUs())
    }

    @Test fun releaseFailureDoesNotReplaceTheOriginalRatePreparationFailure() {
        val platform = FakePlatform(adjust = { it.reject = 1.02f; it.throwOnClose = true })
        val failure = assertThrows(AudioOutputPreparationException::class.java) { PlatformAudioOutput(format, { _, _ -> }, platform) }
        assertEquals(OutputPreparationReason.RATE_UNSUPPORTED, failure.diagnostic.reason)
        assertEquals(1, failure.suppressed.size)
        assertEquals(1, platform.created?.releases)
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    private fun packet(index: Int) = AccessUnit(1_000_000 + index * 20_000L, ByteArray(3840))
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(1)
        assertTrue("Actual prepared output/player did not progress", condition())
    }

    private class FakePlatform(
        private val minimum: Int = 5760 * 4,
        private val now: AtomicLong? = null,
        private val adjust: (FakeTrack) -> Unit = {}
    ) : AudioOutputPlatform {
        var created: FakeTrack? = null
        override fun minimumBytes(format: AudioFormat) = minimum
        override fun create(format: AudioFormat, bytes: Int, failure: (String, String) -> Unit): RateAudioTrack =
            FakeTrack(format, bytes / (format.channels * 2), 5793, now).also { adjust(it); created = it }
    }

    private class FakeTrack(format: AudioFormat, frames: Int, private val fastMinimum: Int, private val now: AtomicLong?) : RateAudioTrack {
        override val initialized = true
        override var sampleRate = format.sampleRate
        override var channels = format.channels
        override var pcm16 = true
        override var bufferFrames = frames
        override var capacityFrames = frames
        override var failOnUnsupported = true
        override var isPlaying = false
        override val speed: Float get() = actualSpeed
        override val pitch: Float get() = returnedPitch
        override val underrunCount = 0
        var actualSpeed = 1f
        var returnedPitch = 1f
        var reject: Float? = null
        var ignoreSpeed = false
        var minimumSpeedDelta = 0f
        var autoPlay = false
        var throwOnClose = false
        var plays = 0
        var pauses = 0
        var releases = 0
        val requests = ConcurrentLinkedQueue<Float>()
        val written = AtomicInteger()
        private var startNs = 0L
        override fun setSpeed(speed: Float) {
            requests.add(speed)
            if (speed == reject || speed > 1f && capacityFrames < fastMinimum)
                throw IllegalArgumentException("private vendor buffer-size text")
            if (!ignoreSpeed && kotlin.math.abs(actualSpeed - speed) >= minimumSpeedDelta) actualSpeed = speed
            if (autoPlay) isPlaying = true
        }
        override fun play() { plays++; isPlaying = true; startNs = now?.get() ?: 0 }
        override fun pause() { pauses++; isPlaying = false }
        override fun positionUs(): Long = if (isPlaying && now != null)
            minOf(((now.get() - startNs) / 1000 - 20_000).coerceAtLeast(0), written.get() * 1_000_000L / (sampleRate * channels)) else 0
        override fun timing(): AudioOutputTiming {
            val frames = positionUs() * sampleRate / 1_000_000
            return AudioOutputTiming(sampleRate, bufferFrames, written.get().toLong() / channels, frames, frames, null, AudioClockSource.HEAD_UNAVAILABLE)
        }
        override fun write(samples: ShortArray, offset: Int, count: Int): Int {
            val queued = written.get() - positionUs() * sampleRate * channels / 1_000_000
            val accepted = minOf(count, (bufferFrames * channels - queued).coerceAtLeast(0).toInt()) / channels * channels
            written.addAndGet(accepted)
            return accepted
        }
        override fun close() { releases++; if (throwOnClose) throw IllegalStateException("private cleanup text") }
    }
}
