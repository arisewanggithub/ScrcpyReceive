package org.screencopy.receiver.media

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.screencopy.receiver.BuildConfig
import org.screencopy.receiver.MainActivity
import org.screencopy.receiver.R
import org.screencopy.receiver.protocol.*
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Fixtures need at least 16 seconds of audio for startup plus six seconds idle; headless is not app acceptance. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SrtReceivePipelineTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private fun transport(url: String, streamId: String) = SrtTransport.parse(JSONObject()
        .put("kind", "srt").put("container", "fmp4").put("url", url)
        .put("latencyMs", 100).put("streamId", streamId))

    @Test fun nonzeroInitialPtsStartsRealAudioWithoutWaitingForTheStoppedLoadingClock() {
        val fixture = arguments.getString("srtOffsetFixtureUri")
        val url = arguments.getString("srtOffsetTestUrl") ?: if (fixture != null) "srt://127.0.0.1:43517"
            else error("Explicit H264/Opus fixture or SRT source with nonzero initial timestamps required")
        HeadlessSurface().use { output ->
            val player = SrtPlayer(instrumentation.targetContext, output.surface,
                Request("h264", "opus", 30, "Nonzero startup timestamps",
                    mediaTransport = transport(url, "sc-0000000000000001")), fixture)
            try {
                val start = SystemClock.elapsedRealtime()
                player.start()
                var previous = awaitProof(player::poll)
                assertTrue("Source preroll waited for a scheduling clock that cannot start yet",
                    SystemClock.elapsedRealtime() - start < 3000)
                var details = ""
                instrumentation.runOnMainSync { details = player.diagnosticsForTests() }
                assertEquals("Fixture did not preserve the nonzero source timestamps",
                    1_250_000L, JSONObject(details).getLong("sourceOriginUs"))
                if (fixture == null) assertTrue(JSONObject(details).getLong("inputBytes") > 0)
                repeat(3) {
                    val baseline = previous
                    Thread.sleep(1000)
                    previous = awaitProof(player::poll) {
                        it.getLong("audioRenderedBuffers") > baseline.getLong("audioRenderedBuffers") &&
                            it.getLong("renderedFrames") > baseline.getLong("renderedFrames") + 10
                    }
                    output.checkHealthy()
                }
            } finally { logPlayerState(player); player.close(); awaitRelease(player) }
        }
    }

    @Test fun liveSrtSteadyAudioDoesNotLoseSamplesToDecoderReadAhead() {
        val url = arguments.getString("srtTestUrl") ?: error("Explicit continuous-tone SRT source required")
        val id = arguments.getString("srtTestStreamId") ?: error("Explicit continuity stream id required")
        HeadlessSurface().use { output ->
            val player = SrtPlayer(instrumentation.targetContext, output.surface,
                Request("h264", "opus", 30, "Continuous tone fixture", mediaTransport = transport(url, id)),
                arguments.getString("srtToneFixtureUri"))
            fun diagnostic(): JSONObject {
                var result = ""
                instrumentation.runOnMainSync { result = player.diagnosticsForTests() }
                return JSONObject(result)
            }
            try {
                player.start()
                var previous = awaitProof(player::poll)
                Thread.sleep(5000)
                val initial = diagnostic()
                val initialAudio = initial.getJSONObject("audioRegulation")
                val start = SystemClock.elapsedRealtime()
                repeat(15) {
                    val baseline = previous
                    Thread.sleep(2000)
                    previous = awaitProof(player::poll) {
                        it.getLong("renderedFrames") > baseline.getLong("renderedFrames") + 15 &&
                            it.getLong("positionMs") > baseline.getLong("positionMs") + 1000
                    }
                    val current = diagnostic()
                    val audio = current.getJSONObject("audioRegulation")
                    assertEquals("Steady audio was shed as decoded backlog: $current",
                        initialAudio.getLong("droppedFrames"), audio.getLong("droppedFrames"))
                    assertEquals("Continuous source needed inserted silence: $current",
                        initialAudio.getLong("silenceFrames"), audio.getLong("silenceFrames"))
                    assertTrue(audio.getDouble("speed") in 0.98..1.02)
                    output.checkHealthy()
                    android.util.Log.i("ScreenCopySrtTest", "audio-continuity=$current")
                }
                val elapsedUs = (SystemClock.elapsedRealtime() - start) * 1000
                val advancedUs = diagnostic().getLong("audioSourcePositionUs") -
                    initial.getLong("audioSourcePositionUs")
                assertTrue("Source audio fell behind while scheduling continued: source=$advancedUs elapsed=$elapsedUs",
                    advancedUs > elapsedUs * 97 / 100)
            } finally { logPlayerState(player); player.close(); awaitRelease(player) }
        }
    }

    @Test fun liveSrtRemainsProgressingBeyondStuckTimeoutWithRecurrentSourceGaps() {
        val url = arguments.getString("srtTestUrl") ?: error("Explicit bounded recovery SRT source required")
        val id = arguments.getString("srtTestStreamId") ?: error("Explicit recovery stream id required")
        val video = arguments.getString("srtFixtureVideoCodec") ?: "h264"
        HeadlessSurface().use { output ->
            val player = SrtPlayer(instrumentation.targetContext, output.surface,
                Request(video, "opus", 30, "Sustained source-gap fixture", mediaTransport = transport(url, id)))
            try {
                player.start()
                var previous = awaitProof(player::poll)
                val initial = previous
                var previousSourceUs = 0L
                instrumentation.runOnMainSync {
                    previousSourceUs = JSONObject(player.diagnosticsForTests()).getLong("audioSourcePositionUs")
                }
                repeat(24) { index ->
                    val baseline = previous
                    val drained = output.drainedFrames.get()
                    Thread.sleep(3000)
                    previous = awaitProof(player::poll) {
                        it.getLong("positionMs") > baseline.getLong("positionMs") + 1500 &&
                            it.getLong("renderedFrames") > baseline.getLong("renderedFrames") + 15 &&
                            output.drainedFrames.get() > drained + 15
                    }
                    if (index % 2 == 1) {
                        var currentSourceUs = 0L
                        instrumentation.runOnMainSync {
                            currentSourceUs = JSONObject(player.diagnosticsForTests()).getLong("audioSourcePositionUs")
                        }
                        assertTrue("Real source presentation failed to resume across a six-second recovery window",
                            currentSourceUs > previousSourceUs + 500000)
                        previousSourceUs = currentSourceUs
                    }
                    android.util.Log.i("ScreenCopySrtTest", "sustained-recovery proof=$previous")
                }
                assertTrue(previous.getLong("audioRenderedBuffers") > initial.getLong("audioRenderedBuffers") + 100)
                assertTrue(previous.getLong("positionMs") > initial.getLong("positionMs") + 70000)
            } finally { logPlayerState(player); player.close(); awaitRelease(player) }
        }
    }

    @Test fun sourceAudioGapDoesNotStopVideoLoadingAndAudioResumesWithoutFakeProof() {
        val fixture = arguments.getString("srtGapFixtureUri")
            ?: error("Explicit 24-second H264/Opus fixture with source audio absent from 8 to 12 seconds required")
        HeadlessSurface().use { output ->
            val player = SrtPlayer(instrumentation.targetContext, output.surface,
                Request("h264", "opus", 30, "Audio-source gap fixture",
                    mediaTransport = transport("srt://127.0.0.1:43517", "sc-0000000000000001")), fixture)
            try {
                player.start()
                var previous = awaitProof(player::poll)
                var duringGapAudioBuffers = 0L
                repeat(9) { index ->
                    val baseline = previous
                    val drained = output.drainedFrames.get()
                    Thread.sleep(1500)
                    previous = awaitProof(player::poll) {
                        it.getLong("positionMs") > baseline.getLong("positionMs") + 500 &&
                            it.getLong("renderedFrames") > baseline.getLong("renderedFrames") + 10 &&
                            output.drainedFrames.get() > drained + 10
                    }
                    if (index == 5) duringGapAudioBuffers = previous.getLong("audioRenderedBuffers")
                    android.util.Log.i("ScreenCopySrtTest", "source-gap progression=$previous")
                }
                assertTrue("Real source audio did not resume after the gap",
                    previous.getLong("audioRenderedBuffers") > duringGapAudioBuffers + 10)
            } finally { logPlayerState(player); player.close(); awaitRelease(player) }
        }
    }

    @Test fun localMovingFmp4KeepsVideoAndSourceAudioAdvancingAfterStartup() {
        val fixture = arguments.getString("srtLocalFixtureUri")
            ?: error("Explicit moving H264/AAC fMP4 fixture required")
        require(fixture.startsWith("file:"))
        HeadlessSurface().use { output ->
            val player = SrtPlayer(instrumentation.targetContext, output.surface,
                Request("h264", "aac", 30, "Moving frame-release fixture",
                    mediaTransport = transport("srt://127.0.0.1:43517", "sc-0000000000000001")), fixture)
            try {
                player.start()
                var previous = awaitProof(player::poll)
                repeat(3) {
                    val baseline = previous
                    val drained = output.drainedFrames.get()
                    Thread.sleep(1500)
                    previous = awaitProof(player::poll) {
                        it.getLong("renderedFrames") > baseline.getLong("renderedFrames") + 10 &&
                            it.getLong("audioRenderedBuffers") > baseline.getLong("audioRenderedBuffers") &&
                            it.getLong("positionMs") > baseline.getLong("positionMs") + 500 &&
                            output.drainedFrames.get() > drained + 10
                    }
                    output.checkHealthy()
                    android.util.Log.i("ScreenCopySrtTest", "moving-fixture before=$baseline after=$previous")
                }
            } finally { logPlayerState(player); player.close(); awaitRelease(player) }
        }
    }

    @Test fun headlessLiveSrtRotationRetainsVideoAudioAndSession() = liveRotation("h264", "opus")

    @Test fun headlessLiveSrtHevcRotationRetainsVideoAudioAndSession() {
        require(arguments.getString("srtFixtureVideoCodec") == "h265") { "Explicit synthetic HEVC fixture required" }
        require(arguments.getString("srtFixtureExpectedSeconds")?.toLongOrNull()?.let { it in 16L..90L } == true) {
            "srtFixtureExpectedSeconds=16..90 must match the bounded source lifetime for HEVC rotation"
        }
        val audio = arguments.getString("srtFixtureAudioCodec") ?: "aac"
        require(audio in setOf("opus", "aac"))
        liveRotation("h265", audio)
    }

    private fun liveRotation(videoCodec: String, audioCodec: String) {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val url = arguments.getString("srtRotationTestUrl")
        val id = arguments.getString("srtRotationTestStreamId")
        assumeTrue("Supply bounded synthetic portrait-landscape-portrait fixture", url != null && id != null)
        val screen = arguments.getString("srtFixtureProfile") == "screen"
        val portraitWidth = if (screen) 1080 else 240
        val portraitHeight = if (screen) 1920 else 320
        HeadlessSurface().use { output ->
            val request = Request(videoCodec, audioCodec, if (screen) 60 else 30, "Synthetic SRT rotation fixture",
                mediaTransport = transport(checkNotNull(url), checkNotNull(id)))
            val player = SrtPlayer(instrumentation.targetContext, output.surface, request)
            try {
                val playbackStartMs = SystemClock.elapsedRealtime()
                player.start()
                var previous = awaitProof(player::poll) {
                    it.getInt("width") == portraitWidth && it.getInt("height") == portraitHeight
                }
                for ((width, height) in listOf(portraitHeight to portraitWidth, portraitWidth to portraitHeight)) {
                    val baseline = previous
                    val drained = output.drainedFrames.get()
                    previous = awaitProof(player::poll) {
                        it.getInt("width") == width && it.getInt("height") == height &&
                            it.getLong("positionMs") > baseline.getLong("positionMs") + 500 &&
                            it.getInt("audioRenderedBuffers") > baseline.getInt("audioRenderedBuffers") &&
                            it.getInt("renderedFrames") > baseline.getInt("renderedFrames") &&
                            output.drainedFrames.get() > drained
                    }
                    output.checkHealthy()
                    assertTrue(previous.getBoolean("firstFrame"))
                    assertTrue(previous.getBoolean("audioProof"))
                    android.util.Log.i("ScreenCopySrtTest", "headless-SRT rotation before=$baseline after=$previous")
                }
                val rotated = previous
                Thread.sleep(1500)
                awaitProof(player::poll) {
                    it.getInt("width") == portraitWidth && it.getInt("height") == portraitHeight &&
                        it.getLong("positionMs") > rotated.getLong("positionMs") + 1000 &&
                        it.getInt("audioRenderedBuffers") > rotated.getInt("audioRenderedBuffers") &&
                        it.getInt("renderedFrames") > rotated.getInt("renderedFrames")
                }
                awaitExpectedBoundedFixtureEnd(player, playbackStartMs)
            } finally { logPlayerState(player); player.close(); awaitRelease(player) }
        }
    }

    @Test fun liveFmp4HasRealVideoAndAudioOutputProgression() = liveCodecProgression(false)
    @Test fun liveAdditionalCodecFmp4HasRealVideoAndAudioOutputProgression() = liveCodecProgression(true)

    private fun liveCodecProgression(selectable: Boolean) {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val url = arguments.getString("srtTestUrl")
        val id = arguments.getString("srtTestStreamId")
        assumeTrue("Supply explicitly started bounded fMP4 fixture URL and streamId", url != null && id != null)
        val videoCodec = if (selectable) arguments.getString("srtFixtureVideoCodec") ?: "h264" else "h264"
        val audioCodec = if (selectable) arguments.getString("srtFixtureAudioCodec") ?: "aac" else "opus"
        require(videoCodec in setOf("h264", "h265", "av1") && audioCodec in setOf("opus", "aac"))
        if (selectable) {
            val mimes = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }
            val available = mimes.any { it.equals(Configuration.mime(videoCodec), true) } &&
                mimes.any { it.equals(if (audioCodec == "aac") "audio/mp4a-latm" else "audio/opus", true) }
            if (!available) {
                android.util.Log.w("ScreenCopySrtTest", "phase=unsupported video=$videoCodec audio=$audioCodec validation=NOT_VALIDATED reason=no-advertised-platform-decoder")
                error("UNSUPPORTED_CONFIG: selected platform decoder unavailable; codec NOT_VALIDATED, not a skipped PASS")
            }
        }
        val screen = selectable && arguments.getString("srtFixtureProfile") == "screen"
        HeadlessSurface().use { output ->
            val request = Request(videoCodec, audioCodec, if (screen) 60 else 30, "Bounded $videoCodec/$audioCodec fixture",
                mediaTransport = transport(checkNotNull(url), checkNotNull(id)))
            val player = SrtPlayer(instrumentation.targetContext, output.surface, request)
            try {
                val playbackStartMs = SystemClock.elapsedRealtime()
                player.start()
                val first = awaitProof(player::poll)
                val drained = output.drainedFrames.get()
                Thread.sleep(1500)
                val second = awaitProof(player::poll) { details ->
                    details.getLong("renderedFrames") > first.getLong("renderedFrames") &&
                        details.getLong("positionMs") > first.getLong("positionMs") + 500 &&
                        details.getLong("audioRenderedBuffers") > first.getLong("audioRenderedBuffers") &&
                        output.drainedFrames.get() > drained
                }
                output.checkHealthy()
                assertTrue("No video output progression", second.getLong("renderedFrames") > first.getLong("renderedFrames"))
                assertTrue("No audio clock progression", second.getLong("positionMs") > first.getLong("positionMs") + 500)
                assertTrue("No audio buffer progression", second.getLong("audioRenderedBuffers") > first.getLong("audioRenderedBuffers"))
                assertTrue("No actual Surface output", output.drainedFrames.get() > drained)
                if (selectable) {
                    assertEquals(if (screen) 1080 else 320, second.getInt("width"))
                    assertEquals(if (screen) 1920 else 240, second.getInt("height"))
                    assertTrue(second.getBoolean("firstFrame")); assertTrue(second.getBoolean("audioProof"))
                    android.util.Log.i("ScreenCopySrtTest", "phase=codec-renderer-proof video=$videoCodec audio=$audioCodec profile=${if (screen) "screen" else "small"} details=$second")
                }
                awaitExpectedBoundedFixtureEnd(player, playbackStartMs)
            } finally { logPlayerState(player); player.close(); awaitRelease(player) }
        }
    }

    @Test fun headlessLiveSrtSingleStaticFrameContinuesAudioForSixSeconds() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val url = arguments.getString("srtStaticTestUrl")
        val id = arguments.getString("srtStaticTestStreamId")
        assumeTrue("Supply an approved live SRT exactly-one-video-frame + at least 16 seconds Opus fixture",
            url != null && id != null)
        HeadlessSurface().use { output ->
            val request = Request("h264", "opus", 30, "Headless static H264/Opus fixture",
                mediaTransport = transport(checkNotNull(url), checkNotNull(id)))
            val player = SrtPlayer(instrumentation.targetContext, output.surface, request)
            try {
                val playbackStartMs = SystemClock.elapsedRealtime()
                player.start()
                val first = awaitProof(player::poll)
                assertTrue(first.getBoolean("firstFrame"))
                assertTrue(first.getBoolean("audioProof"))
                assertEquals(1, first.getInt("renderedFrames"))
                Thread.sleep(6000)
                val next = awaitProof(player::poll) { details ->
                    details.getLong("positionMs") > first.getLong("positionMs") + 5000 &&
                        details.getLong("audioRenderedBuffers") > first.getLong("audioRenderedBuffers")
                }
                assertEquals("Static video must not be duplicated to sustain audio", 1, next.getInt("renderedFrames"))
                assertTrue("Audio media position stalled while static video was idle",
                    next.getLong("positionMs") > first.getLong("positionMs") + 5000)
                assertTrue("Audio output buffers stalled while static video was idle",
                    next.getLong("audioRenderedBuffers") > first.getLong("audioRenderedBuffers"))
                output.checkHealthy()
                assertTrue("No actual headless Surface output", output.drainedFrames.get() >= 1)
                android.util.Log.i("ScreenCopySrtTest", "headless-SRT static/audio initial=$first afterSixSeconds=$next")
                awaitExpectedBoundedFixtureEnd(player, playbackStartMs)
            } finally { logPlayerState(player); player.close(); awaitRelease(player) }
        }
    }

    @Test fun videoOnlySparseFuturePicturesDoNotStopTheClockOrLoading() {
        val fixture = arguments.getString("srtAudioOffGapFixtureUri")
            ?: error("Explicit video-only H264 fMP4 with at least eight pictures spaced one second apart required")
        HeadlessSurface().use { output ->
            val player = SrtPlayer(instrumentation.targetContext, output.surface,
                Request("h264", null, 1, "Sparse video-only fixture",
                    mediaTransport = transport("srt://127.0.0.1:43517", "sc-0000000000000001")), fixture)
            try {
                player.start()
                val first = awaitProof(player::poll)
                val advanced = awaitProof(player::poll) {
                    it.getLong("positionMs") > first.getLong("positionMs") + 1500 &&
                        it.getLong("renderedFrames") >= 3 && output.drainedFrames.get() >= 3
                }
                assertFalse(advanced.getBoolean("audioProof"))
                assertEquals(0, advanced.getInt("audioRenderedBuffers"))
                output.checkHealthy()
            } finally { logPlayerState(player); player.close(); awaitRelease(player) }
        }
    }

    @Test fun headlessLiveSrtAudioOffRendersFirstFrameWithoutAudioProof() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val url = arguments.getString("srtAudioOffTestUrl")
        val id = arguments.getString("srtAudioOffTestStreamId")
        assumeTrue("Supply an approved live SRT video-only fixture with audio disabled", url != null && id != null)
        val videoCodec = arguments.getString("srtFixtureVideoCodec") ?: "h264"
        require(videoCodec in setOf("h264", "h265", "av1"))
        check(android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any {
            !it.isEncoder && it.supportedTypes.any { mime -> mime.equals(Configuration.mime(videoCodec), true) }
        }) { "UNSUPPORTED_CONFIG: no selected video decoder; NOT_VALIDATED (not a skipped PASS)" }
        HeadlessSurface().use { output ->
            val request = Request(videoCodec, null, 30, "Headless $videoCodec audio-off fixture",
                mediaTransport = transport(checkNotNull(url), checkNotNull(id)))
            val player = SrtPlayer(instrumentation.targetContext, output.surface, request)
            try {
                val playbackStartMs = SystemClock.elapsedRealtime()
                player.start()
                val proof = awaitProof(player::poll)
                assertTrue(proof.getBoolean("firstFrame"))
                assertFalse("Audio-off fixture unexpectedly advanced audio", proof.getBoolean("audioProof"))
                assertEquals(0, proof.getInt("audioRenderedBuffers"))
                assertEquals("Exactly one genuine video-only sample must render", 1, proof.getInt("renderedFrames"))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (output.drainedFrames.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
                output.checkHealthy()
                assertTrue("No actual headless Surface output", output.drainedFrames.get() >= 1)
                Thread.sleep(1500)
                val idle = awaitProof(player::poll)
                assertTrue("Video-only idle presentation must keep the scheduling clock moving",
                    idle.getLong("positionMs") > proof.getLong("positionMs") + 1000)
                assertEquals("An idle video-only source must not invent another frame", 1, idle.getInt("renderedFrames"))
                assertFalse(idle.getBoolean("audioProof"))
                assertEquals(0, idle.getInt("audioRenderedBuffers"))
                android.util.Log.i("ScreenCopySrtTest", "headless-SRT audio-off proof=$proof")
                awaitExpectedBoundedFixtureEnd(player, playbackStartMs)
            } finally { logPlayerState(player); player.close(); awaitRelease(player) }
        }
    }

    @Test fun nativePendingConnectAndReadAreCancellableAndStaleHandlesFail() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val stream = "sc-0000000000000001"
        val listener = SrtHandle(SrtNative.listen("127.0.0.1", "127.0.0.1", stream, 100))
        val caller = SrtHandle(SrtNative.createCaller(stream, 100))
        val failure = AtomicReference<Throwable?>()
        val connected = Thread {
            try { SrtNative.connect(caller.get(), "127.0.0.1", SrtNative.localPort(listener.get()), 3000) }
            catch (e: Throwable) { failure.set(e) }
        }
        try {
            connected.start()
            assertTrue(SrtNative.awaitCaller(listener.get(), 3000))
            connected.join(4000)
            assertFalse(connected.isAlive)
            assertNull(failure.get())
            val stale = caller.get()
            val readFailure = AtomicReference<Throwable?>()
            val reader = Thread {
                try {
                    SrtNative.read(stale, ByteArray(128), 0, 128)
                    readFailure.set(AssertionError("Closed native read returned without an IO error"))
                } catch (e: Throwable) { readFailure.set(e) }
            }
            reader.start()
            Thread.sleep(100)
            val before = System.nanoTime()
            caller.close()
            reader.join(2000)
            assertFalse("SRT read cancellation blocked", reader.isAlive)
            assertTrue("Native read must surface cancellation as IOException", readFailure.get() is java.io.IOException)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2000)
            assertThrows(java.io.IOException::class.java) { SrtNative.read(stale, ByteArray(1), 0, 1) }
            caller.close()
        } finally { caller.close(); listener.close(); connected.join(4000) }

        // The parent supplies an explicitly approved nonresponding endpoint; no arbitrary LAN probing.
        val pendingUrl = arguments.getString("srtPendingConnectUrl") ?: return
        val uri = java.net.URI(transport(pendingUrl, stream).url)
        val pending = SrtHandle(SrtNative.createCaller(stream, 100))
        val connectFailure = AtomicReference<Throwable?>()
        val connecting = Thread {
            try {
                SrtNative.connect(pending.get(), uri.host.removeSurrounding("[", "]"), uri.port, 5000)
                connectFailure.set(AssertionError("Nonresponding fixture unexpectedly connected"))
            } catch (e: Throwable) { connectFailure.set(e) }
        }
        try {
            connecting.start()
            Thread.sleep(100)
            val before = System.nanoTime()
            pending.close()
            connecting.join(2000)
            assertFalse("SRT connect cancellation blocked", connecting.isAlive)
            assertTrue("Native connect must surface cancellation as IOException", connectFailure.get() is java.io.IOException)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 2000)
        } finally { pending.close(); connecting.join(6000) }
    }

    @Test fun exoLoaderBlockedOnLiveInputReleasesBeforeFiveSeconds() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val stream = "sc-0000000000000001"
        SrtHandle(SrtNative.listen("127.0.0.1", "127.0.0.1", stream, 100)).use { listener ->
            HeadlessSurface().use { output ->
                val request = Request("h264", null, 30, "Loader cancellation fixture",
                    mediaTransport = transport("srt://127.0.0.1:${SrtNative.localPort(listener.get())}", stream))
                val player = SrtPlayer(instrumentation.targetContext, output.surface, request)
                try {
                    player.start()
                    assertTrue("Media3 loader did not connect", SrtNative.awaitCaller(listener.get(), 3000))
                    // Keep the accepted source open with no bytes: the real loader is blocked in read.
                    Thread.sleep(100)
                    val before = System.nanoTime()
                    player.close()
                    awaitRelease(player)
                    assertTrue("Native read / ExoPlayer loader release exceeded five seconds",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 5000)
                    assertEquals("", player.poll())
                    output.checkHealthy()
                } finally { player.close(); awaitRelease(player) }
            }
        }
    }

    @Test fun liveDataSourceRefusesSeekReplacementAndReopen() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val accepted = transport("srt://127.0.0.1:43517", "sc-0000000000000001")
        SrtDataSource.Factory(accepted).use { factory ->
            val input = factory.createDataSource()
            assertThrows(java.io.IOException::class.java) { factory.createDataSource() }
            val spec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(accepted.url).setPosition(1).build()
            assertThrows(java.io.IOException::class.java) { input.open(spec) }
            input.close()
            assertThrows(java.io.IOException::class.java) { input.open(spec.buildUpon().setPosition(0).build()) }
        }
    }

    @Test fun queuedStartCancelledOnMainDoesNotCreatePlayerOrTouchRetiredSurface() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        HeadlessSurface().use { output ->
            val request = Request("h264", null, 30, "Cancelled before main startup",
                mediaTransport = transport("srt://127.0.0.1:43517", "sc-0000000000000001"))
            val player = SrtPlayer(instrumentation.targetContext, output.surface, request)
            instrumentation.runOnMainSync {
                val worker = Thread { player.start() }
                worker.start()
                worker.join(1000)
                assertFalse("start waited for the main looper", worker.isAlive)
                player.close()
            }
            instrumentation.waitForIdleSync()
            assertTrue("Queued player release did not complete", player.isReleasedForTests)
            assertEquals("", player.poll())
            player.close()
        }
    }

    @Test fun coldLocalFixtureUsesActualActivityControlSessionAndWaitingUi() {
        assumeUnlocked()
        val fixture = arguments.getString("srtLocalFixtureUri")
        assumeTrue("Supply an explicit existing file/content fragmented MP4 fixture", fixture != null)
        assertTrue(checkNotNull(fixture).startsWith("file:") || fixture.startsWith("content:"))
        activityFixture(fixture)
    }

    @Test fun additionalCodecLocalFixtureUsesActualActivityControlSessionAndWaitingUi() {
        assumeUnlocked()
        val fixture = arguments.getString("srtLocalFixtureUri")
        assumeTrue("Supply an explicit local AAC/AV1 codec fixture and codec selection arguments", fixture != null)
        assertTrue(checkNotNull(fixture).startsWith("file:") || fixture.startsWith("content:"))
        activityFixture(fixture, selectableCodecs = true)
    }

    @Test fun progressiveNetworkFmp4UsesActualActivityAndContinuesAudioAfterVideoGoesIdle() {
        assumeUnlocked()
        val fixture = arguments.getString("srtProgressiveFixtureUrl")
        assumeTrue("Supply an explicitly approved progressive HTTP(S) single-frame+Opus fMP4 fixture", fixture != null)
        assertTrue(checkNotNull(fixture).startsWith("http://") || fixture.startsWith("https://"))
        activityFixture(fixture, singleFrame = true)
    }

    @Test fun localFmp4SingleStaticFrameContinuesAudioWithoutMoreVideo() {
        assumeUnlocked()
        val fixture = arguments.getString("srtLocalStaticFixtureUri")
        assumeTrue("Supply an explicit file/content single-frame+Opus fMP4 fixture", fixture != null)
        assertTrue(checkNotNull(fixture).startsWith("file:") || fixture.startsWith("content:"))
        activityFixture(fixture, singleFrame = true)
    }

    @Test fun actualActivityLocalFixtureStopsAndReconnectsWithNewSession() {
        assumeUnlocked()
        val staticFixture = arguments.getString("srtLocalStaticFixtureUri")
        val fixture = staticFixture ?: arguments.getString("srtLocalFixtureUri")
        assumeTrue("Supply an existing local fMP4 fixture for two sessions in the same Activity", fixture != null)
        assertTrue(checkNotNull(fixture).startsWith("file:") || fixture.startsWith("content:"))
        withActivity { activity ->
            instrumentation.runOnMainSync { activity.srtFixtureUriForTests = fixture }
            for (session in 1L..2L) {
                assertEquals("Retired session diagnostics survived before admission", "", activity.srtDiagnosticsForTests())
                connectActivity().use { peer ->
                    hello(peer)
                    send(peer, Type.SESSION_REQUEST, settings(session), session)
                    val accepted = Wire.read(peer.inputStream)
                    assertEquals(Type.SESSION_ACCEPT, accepted.type)
                    assertEquals(session, accepted.session)
                    assertEquals("srt", accepted.json().obj("mediaTransport").string("kind"))
                    val proof = awaitProof(activity::srtDiagnosticsForTests)
                    assertTrue(proof.getBoolean("firstFrame"))
                    assertTrue(proof.getBoolean("audioProof"))
                    waitForPlaying(peer, session)
                    assertPlayingUi(activity)
                    send(peer, Type.PING, JSONObject().put("nonce", 6000), session)
                    readUntil(peer, Type.PONG, session)
                    assertAudioContinues(activity, proof, singleFrame = staticFixture != null)
                    send(peer, Type.STOP, JSONObject().put("reason", "reconnect fixture session $session complete"), session)
                    readUntil(peer, Type.SESSION_END, session)
                }
                assertWaitingUi(activity)
                assertEquals("STOP did not retire player diagnostics", "", activity.srtDiagnosticsForTests())
                Thread.sleep(1000)
                assertWaitingUi(activity)
                assertEquals("Late callbacks revived retired diagnostics", "", activity.srtDiagnosticsForTests())
            }
        }
    }

    private fun activityFixture(fixture: String, singleFrame: Boolean = false, selectableCodecs: Boolean = false) {
        withActivity { activity ->
            instrumentation.runOnMainSync { activity.srtFixtureUriForTests = fixture }
            connectActivity().use { peer ->
                hello(peer)
                val selected = settings()
                if (selectableCodecs) {
                    val video = arguments.getString("srtFixtureVideoCodec") ?: "h264"
                    val audio = arguments.getString("srtFixtureAudioCodec") ?: "aac"
                    require(video in setOf("h264", "h265", "av1") && audio in setOf("opus", "aac"))
                    selected.obj("video").put("codec", video)
                    selected.obj("audio").put("codec", audio)
                    if (arguments.getString("srtFixtureProfile") == "screen") {
                        selected.obj("video").put("maxFps", 60).put("bitrate", 20_000_000)
                    }
                }
                send(peer, Type.SESSION_REQUEST, selected)
                assertEquals(Type.SESSION_ACCEPT, Wire.read(peer.inputStream).type)
                val proof = awaitProof(activity::srtDiagnosticsForTests)
                assertTrue(proof.getBoolean("firstFrame"))
                assertTrue(proof.getBoolean("audioProof"))
                if (selectableCodecs) {
                    val screen = arguments.getString("srtFixtureProfile") == "screen"
                    assertEquals(if (screen) 1080 else 320, proof.getInt("width"))
                    assertEquals(if (screen) 1920 else 240, proof.getInt("height"))
                }
                waitForPlaying(peer)
                assertPlayingUi(activity)
                send(peer, Type.PING, JSONObject().put("nonce", 6000))
                readUntil(peer, Type.PONG)
                assertAudioContinues(activity, proof, singleFrame)
                send(peer, Type.STOP, JSONObject().put("reason", "fixture complete"))
                readUntil(peer, Type.SESSION_END)
            }
        }
    }

    @Test fun actualActivitySingleStaticFrameNeedsNoFollowingVideoAndUsesRealControlSender() {
        assumeUnlocked()
        assumeTrue("Parent must start an approved single-frame+Opus control-session fixture",
            arguments.getString("srtControlFixture") == "true")
        withActivity { activity ->
            // External sender must negotiate with this Activity listener, preserving numeric-peer pinning.
            val proof = awaitProof(activity::srtDiagnosticsForTests)
            assertTrue(proof.getBoolean("firstFrame"))
            assertTrue(proof.getBoolean("audioProof"))
            assertTrue(proof.getLong("renderedFrames") >= 1)
            assertPlayingUi(activity)
            assertAudioContinues(activity, proof, singleFrame = true)
            android.util.Log.i("ScreenCopySrtTest", "actual-Activity static-frame proof=$proof")
        }
    }

    private fun assertAudioContinues(activity: MainActivity, first: JSONObject, singleFrame: Boolean) {
        Thread.sleep(6000)
        val next = awaitProof(activity::srtDiagnosticsForTests) { details ->
            details.getLong("positionMs") > first.getLong("positionMs") + 5000 &&
                details.getLong("audioRenderedBuffers") > first.getLong("audioRenderedBuffers")
        }
        assertTrue("Audio media clock stalled while video was idle",
            next.getLong("positionMs") > first.getLong("positionMs") + 5000)
        assertTrue("Audio output buffers stopped while video was idle",
            next.getLong("audioRenderedBuffers") > first.getLong("audioRenderedBuffers"))
        if (singleFrame) {
            assertEquals("Fixture must contain exactly one real video frame", 1, first.getInt("renderedFrames"))
            assertEquals("No duplicate video may hide static-source starvation", 1, next.getInt("renderedFrames"))
        } else {
            assertTrue("Audio continued but moving video froze after startup",
                next.getLong("renderedFrames") > first.getLong("renderedFrames"))
        }
        assertPlayingUi(activity)
        android.util.Log.i("ScreenCopySrtTest", "actual-Activity continuing-audio proof=$next")
    }

    private fun assumeUnlocked() {
        assumeTrue(BuildConfig.SRT_TRIAL)
        val manager = instrumentation.targetContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assumeTrue("Activity tests require an already unlocked device; never change keyguard/policy", !manager.isKeyguardLocked)
    }
    private fun withActivity(block: (MainActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!activity.receiverReadyForTests && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue("Actual Activity Surface/listener was not ready", activity.receiverReadyForTests)
            block(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
    private fun awaitExpectedBoundedFixtureEnd(player: SrtPlayer, playbackStartMs: Long) {
        val value = arguments.getString("srtFixtureExpectedSeconds") ?: return
        val seconds = value.toLongOrNull()
        require(seconds != null && seconds in 16L..90L) { "srtFixtureExpectedSeconds must be an integer in 16..90" }
        val minimumMs = seconds * 1000
        val deadlineMs = playbackStartMs + minimumMs + 8000
        while (SystemClock.elapsedRealtime() <= deadlineMs) {
            val result = player.poll()
            val elapsedMs = SystemClock.elapsedRealtime() - playbackStartMs
            if (result.startsWith("ERROR:")) {
                assertTrue("Bounded source ended early at ${elapsedMs}ms: $result", elapsedMs >= minimumMs)
                android.util.Log.i("ScreenCopySrtTest",
                    "expected bounded fixture ended after playback proof elapsedMs=$elapsedMs terminal=$result")
                return
            }
            // Terminal renderer callbacks may retire proof before the main-thread error arrives.
            if (result.isEmpty())
                assertTrue("Playback proof disappeared before bounded fixture ended", elapsedMs >= minimumMs)
            Thread.sleep(20)
        }
        error("Bounded fixture did not end within ${seconds + 8} seconds after player start")
    }

    private fun logPlayerState(player: SrtPlayer) {
        instrumentation.runOnMainSync {
            android.util.Log.i("ScreenCopySrtTest", "Media3 final state=${player.diagnosticsForTests()}")
        }
    }

    private fun awaitRelease(player: SrtPlayer) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!player.isReleasedForTests && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Media3 main-looper release did not finish promptly", player.isReleasedForTests)
    }
    private fun assertPlayingUi(activity: MainActivity) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var waiting = true
        while (waiting && System.nanoTime() < deadline) {
            instrumentation.runOnMainSync { waiting = activity.findViewById<View>(R.id.waiting_screen).visibility == View.VISIBLE }
            Thread.sleep(20)
        }
        assertFalse("Actual receiver waiting UI never entered playing", waiting)
    }
    private fun assertWaitingUi(activity: MainActivity) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var waiting = false
        while (!waiting && System.nanoTime() < deadline) {
            instrumentation.runOnMainSync {
                waiting = activity.findViewById<View>(R.id.waiting_screen).visibility == View.VISIBLE
            }
            Thread.sleep(20)
        }
        assertTrue("STOP did not restore the actual Activity waiting UI", waiting)
    }
    private fun awaitProof(poll: () -> String, predicate: (JSONObject) -> Boolean = { true }): JSONObject {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) {
            val result = poll()
            assertFalse(result, result.startsWith("ERROR:"))
            if (result.isNotEmpty()) {
                val details = JSONObject(result)
                if (predicate(details)) return details
            }
            Thread.sleep(20)
        }
        error("No actual Media3 playback proof satisfying required output progression within 12 seconds")
    }
    private fun connectActivity(): Socket {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            try { return Socket("127.0.0.1", org.screencopy.receiver.net.ReceiverServer.PORT).apply { soTimeout = 15000 } }
            catch (e: java.io.IOException) { if (System.nanoTime() >= deadline) throw e; Thread.sleep(20) }
        }
    }
    private fun settings(session: Long = 1) = StrictJson.decode(
        """{"video":{"codec":"h264","encoder":"","bitrate":8000000,"maxSize":1920,"maxFps":30,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":true,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""}}""".toByteArray()
    ).put("mediaTransport", JSONObject().put("kind", "srt").put("container", "fmp4")
        .put("url", "srt://127.0.0.1:43517").put("latencyMs", 100)
        .put("streamId", "sc-" + session.toString(16).padStart(16, '0')))
    private fun hello(peer: Socket) {
        Wire.write(peer.outputStream, Message.json(Type.HELLO, JSONObject().put("role", "sender")
            .put("versions", JSONArray().put(3)).put("name", "Bounded local fixture")))
        assertEquals(Type.HELLO, Wire.read(peer.inputStream).type)
    }
    private fun send(peer: Socket, type: Type, json: JSONObject, session: Long = 1) =
        Wire.write(peer.outputStream, Message.json(type, json, session))
    private fun waitForPlaying(peer: Socket, session: Long = 1) {
        assertEquals("srt-playing", readUntil(peer, Type.STATUS, session).json().string("state"))
    }
    private fun readUntil(peer: Socket, type: Type, session: Long = 1): Message {
        repeat(12) {
            val message = Wire.read(peer.inputStream)
            assertEquals("Control response crossed session ownership", session, message.session)
            if (message.type == Type.PING) send(peer, Type.PONG, message.json(), session)
            else { assertEquals(type, message.type); return message }
        }
        error("No $type")
    }
}
