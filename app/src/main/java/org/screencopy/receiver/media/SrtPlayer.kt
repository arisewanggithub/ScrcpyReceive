package org.screencopy.receiver.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.ExtractorsFactory
import org.json.JSONObject
import org.screencopy.receiver.protocol.Request
import org.screencopy.receiver.protocol.SrtTransport
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Media3 owns demux/codecs/output; decoded FIFO regulation and immediate video keep latency bounded. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SrtPlayer(
    context: Context,
    private val surface: Surface,
    request: Request,
    // Explicit debug instrumentation seam; production always uses accepted SRT transport.
    private val fixtureUri: String? = null
) : SrtPlayback {
    private val context = context.applicationContext
    private val transport = request.mediaTransport as SrtTransport
    private val audioExpected = request.audioCodec != null
    private val videoMime = org.screencopy.receiver.protocol.Configuration.mime(request.videoCodec)
    private val audioMime = when (request.audioCodec) { "opus" -> MimeTypes.AUDIO_OPUS; "aac" -> MimeTypes.AUDIO_AAC; else -> null }
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val released = AtomicBoolean()
    private val videoReadiness = SrtVideoReadiness { action ->
        check(main.post { action() }) { "Media3 main looper unavailable" }
    }
    internal val isReleasedForTests: Boolean get() = released.get()
    private val result = AtomicReference("")
    private val inputs = SrtDataSource.Factory(transport)
    private val diagnostics = SrtSessionDiagnostics.create(this.context, transport.streamId, request.videoCodec, request.audioCodec)
        .also { inputs.attach(it) }
    private val extractionProgress = SrtExtractionProgress()
    @Volatile private var audioSink: SrtLowLatencyAudioSink? = null
    // All fields below are owned exclusively by mainLooper.
    private var player: ExoPlayer? = null
    private var videoRendered = false
    private var audioAdvancing = false
    private var width = 0
    private var height = 0
    private var decoder = ""
    private var rendered = 0
    private var dropped = 0
    private var failure: String? = null
    private var releaseAttempted = false

    override fun start() {
        check(started.compareAndSet(false, true)) { "SRT player already started" }
        check(!closed.get()) { "SRT player closed" }
        diagnostics.start()
        handoff {
            if (closed.get()) return@handoff
            check(surface.isValid) { "SRT Surface unavailable" }
            val defaults = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean,
                    enableAudioOutputPlaybackParams: Boolean): AudioSink {
                    val output = AudioTrackAudioOutputProvider.Builder(context)
                        .setAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()
                            .setTargetPcmBufferDurationUs(5000).build()).build()
                    return SrtLowLatencyAudioSink(DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
                        .setEnableAudioOutputPlaybackParameters(false).setAudioOutputProvider(output).build(),
                        opus = audioMime == MimeTypes.AUDIO_OPUS)
                        .also { audioSink = it }
                }
            }
            val renderers = RenderersFactory { handler, video, audio, text, metadata ->
                defaults.createRenderers(handler, video, audio, text, metadata).map { renderer ->
                    when (renderer.trackType) {
                        C.TRACK_TYPE_VIDEO -> {
                            renderer.release()
                            val immediate = SrtImmediateVideoRenderer(context, handler, video) { extractionProgress.sourceOriginUs }
                            SrtSparseVideoRenderer(immediate, videoReadiness)
                        }
                        C.TRACK_TYPE_AUDIO -> SrtRecoveredAudioRenderer(renderer,
                            { audioSink?.setPlaybackPositionUs(it) }) { audioSink?.sourceAdvanced == true }
                        else -> renderer
                    }
                }.toTypedArray()
            }
            val next = ExoPlayer.Builder(context, renderers)
                .setLooper(main.looper)
                .setLoadControl(SrtLiveLoadControl(DefaultLoadControl.Builder()
                    .setBufferDurationsMs(50, 250, 0, 0)
                    .setPrioritizeTimeOverSizeThresholds(true).build()) { extractionProgress.sourceOriginUs })
                .build()
            player = next
            next.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (closed.get()) return
                    width = videoSize.width
                    height = videoSize.height
                    diagnostics.videoSize(width, height)
                    publish()
                }
                override fun onPlayerError(error: PlaybackException) {
                    // Only controlled cause fields escape; no URLs or remote container text.
                    diagnostics.failure("PLAYER_ERROR", error)
                    fail("Media3 ${error.errorCodeName} (${error.errorCode}) ${SrtFailureDetails.summary(error)}")
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    diagnostics.state(playbackState)
                    if (playbackState == Player.STATE_ENDED) {
                        diagnostics.failure("STATE_ENDED", java.io.IOException("Media3 live input ended"))
                        fail("Media3 live input ended")
                    }
                }
            })
            next.addAnalyticsListener(object : AnalyticsListener {
                override fun onLoadError(eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                    mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
                    error: java.io.IOException, wasCanceled: Boolean) {
                    if (!wasCanceled) diagnostics.failure("LOAD_ERROR", error)
                }
                override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime,
                    decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                    if (closed.get()) return
                    decoder = decoderName
                    publish()
                }
                override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) {
                    if (closed.get()) return
                    videoReadiness.onRenderedFirstFrame()
                    videoRendered = true
                    publish()
                }
                override fun onAudioPositionAdvancing(eventTime: AnalyticsListener.EventTime, playoutStartSystemTimeMs: Long) {
                    if (closed.get()) return
                    audioAdvancing = true
                    publish()
                }
                override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                    if (closed.get()) return
                    dropped += droppedFrames
                    publish()
                }
                override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int,
                    bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                    Log.w("ScreenCopySrt", "event=AUDIO_UNDERRUN_RECOVERY buffer_bytes=$bufferSize" +
                        " buffer_ms=$bufferSizeMs feed_gap_ms=$elapsedSinceLastFeedMs")
                }
            })
            next.setVideoSurface(surface)
            val sourceFactory = if (fixtureUri == null) inputs else {
                check(org.screencopy.receiver.BuildConfig.DEBUG && fixtureUri.startsWithAnyFixtureScheme()) {
                    "Only explicit debug fixtures allowed"
                }
                DefaultDataSource.Factory(context)
            }
            // Notify Media3 after each completed extractor read, even when the first tiny
            // video-only fragment is followed by an indefinitely blocked live read.
            next.setMediaSource(ProgressiveMediaSource.Factory(sourceFactory, ExtractorsFactory {
                arrayOf(SrtAvcExtractor(progress = extractionProgress, expectedVideoMime = videoMime,
                    expectedAudioMime = audioMime, enforceAcceptedTracks = true))
            }).setContinueLoadingCheckIntervalBytes(1)
                .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy(0) {
                override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET
            }).createMediaSource(MediaItem.Builder().setUri(fixtureUri ?: transport.url)
                .setMimeType(MimeTypes.VIDEO_MP4).build()))
            next.prepare()
            next.play()
            if (closed.get()) releaseOnMain()
        }
    }

    private fun publish() {
        if (closed.get() || failure != null) return
        if (!videoRendered || !videoReadiness.hasRenderedFrame() ||
            audioExpected && (!audioAdvancing || audioSink?.sourceAdvanced != true) ||
            width <= 0 || height <= 0 || decoder.isEmpty()) {
            result.set("")
            return
        }
        val current = player ?: return
        val videoCounters = current.videoDecoderCounters
        val audioCounters = current.audioDecoderCounters
        videoCounters?.ensureUpdated()
        audioCounters?.ensureUpdated()
        rendered = videoCounters?.renderedOutputBufferCount ?: rendered
        result.set(JSONObject().put("width", width).put("height", height).put("decoder", decoder)
            .put("firstFrame", videoRendered).put("audioProof", audioAdvancing)
            .put("renderedFrames", rendered).put("droppedFrames", dropped)
            .put("audioRenderedBuffers", audioCounters?.renderedOutputBufferCount ?: 0)
            .put("positionMs", current.currentPosition).toString())
    }

    private fun fail(detail: String) {
        if (!closed.get() && failure == null) {
            videoReadiness.reset(active = false)
            failure = detail
            result.set("ERROR:$detail")
            Log.e("ScreenCopySrt", detail)
        }
    }

    override fun poll(): String {
        if (closed.get()) return ""
        handoff { publish() }
        val current = result.get()
        if (!current.startsWith("ERROR:") &&
            (!videoReadiness.hasRenderedFrame() || audioExpected && audioSink?.sourceAdvanced != true)) return ""
        return current
    }

    internal fun diagnosticsForTests(): String {
        check(Looper.myLooper() == main.looper)
        val current = player
        val video = current?.videoDecoderCounters
        val audio = current?.audioDecoderCounters
        video?.ensureUpdated()
        audio?.ensureUpdated()
        return JSONObject().put("firstFrame", videoRendered)
            .put("audioProof", audioAdvancing && audioSink?.sourceAdvanced == true)
            .put("audioSourcePositionUs", audioSink?.sourcePositionUs ?: 0)
            .put("sourceOriginUs", extractionProgress.sourceOriginUs)
            .put("audioRegulation", audioSink?.regulation?.let {
                JSONObject().put("sourceFrames", it.sourceFrames).put("silenceFrames", it.silenceFrames)
                    .put("droppedFrames", it.droppedFrames).put("speed", it.speed)
            })
            .put("inputReadCalls", inputs.readCalls.get())
            .put("inputMaxReadWaitUs", inputs.maxReadWaitUs)
            .put("inputMaxReadCallGapUs", inputs.maxReadCallGapUs)
            .put("inputCompletedReads", inputs.completedReads.get())
            .put("inputBytes", inputs.receivedBytes.get())
            .put("extractorReadCalls", extractionProgress.readCalls.get())
            .put("extractorCompletedReads", extractionProgress.completedReads.get())
            .put("avcBytes", extractionProgress.videoBytes.get())
            .put("avcBufferedBytes", extractionProgress.bufferedVideoBytes.get())
            .put("avcSamples", extractionProgress.videoSamples.get())
            .put("avcLastSampleBytes", extractionProgress.lastSampleBytes.get())
            .put("avcLastSamplePtsUs", extractionProgress.lastSamplePtsUs.get())
            .put("width", width).put("height", height).put("decoder", decoder)
            .put("state", current?.playbackState).put("playing", current?.isPlaying)
            .put("loading", current?.isLoading).put("positionMs", current?.currentPosition)
            .put("bufferedMs", current?.totalBufferedDuration)
            .put("videoDecoderCount", video?.decoderInitCount)
            .put("videoInputBuffers", video?.queuedInputBufferCount)
            .put("videoOutputBuffers", video?.renderedOutputBufferCount)
            .put("audioDecoderCount", audio?.decoderInitCount)
            .put("audioInputBuffers", audio?.queuedInputBufferCount)
            .put("audioOutputBuffers", audio?.renderedOutputBufferCount).toString()
    }

    override fun controlProgress(received: Boolean) = diagnostics.control(received)
    override fun sessionPhase(value: String) = diagnostics.phase(value)
    override fun controlFailure(error: Throwable) = diagnostics.failure("CONTROL_ERROR", error)
    override fun closeInitiated(owner: SrtCloseOwner) = inputs.markClose(owner)
    override fun close() = close(SrtCloseOwner.SURFACE_RETIRED)
    override fun close(owner: SrtCloseOwner) {
        if (!closed.compareAndSet(false, true)) return
        videoReadiness.reset(active = false)
        result.set("")
        // Close before release: ExoPlayer.release waits for the loader, whose blocking read must unblock.
        try { inputs.close(owner) } finally { handoff { releaseOnMain() } }
    }

    private fun releaseOnMain() {
        if (releaseAttempted) return
        releaseAttempted = true
        val previous = player
        player = null
        try { previous?.release() } finally { audioSink = null }
        released.set(true)
    }

    private fun handoff(action: () -> Unit) {
        val guarded = Runnable {
            try { action() } catch (e: Exception) {
                mainFailure(e)
            } catch (e: LinkageError) {
                mainFailure(e)
            }
        }
        if (Looper.myLooper() == main.looper) { guarded.run(); return }
        // ResourceSlot may own a lifecycle monitor here. Never wait for main: Activity surface
        // retirement also needs that monitor. FIFO posts plus closed gate cancel queued startup.
        check(main.post(guarded)) { "Media3 main looper unavailable" }
    }

    private fun mainFailure(error: Throwable) {
        diagnostics.failure("MAIN_ERROR", error)
        val detail = "Media3 operation failed ${SrtFailureDetails.summary(error)}"
        // Cleanup failures after close must stay visible even though poll is intentionally retired.
        Log.e("ScreenCopySrt", detail)
        fail(detail)
        try { inputs.close(SrtCloseOwner.ERROR) } finally {
            try { releaseOnMain() } catch (e: RuntimeException) {
                Log.e("ScreenCopySrt", "Media3 cleanup failed (${e.javaClass.simpleName})")
                throw e
            }
        }
    }
}

internal fun String.startsWithAnyFixtureScheme(): Boolean =
    startsWith("file:") || startsWith("content:") || startsWith("http://") || startsWith("https://")

/** Playback-thread epochs, acknowledged in FIFO order with main-thread first-frame callbacks. */
internal class SrtVideoReadiness(private val post: (() -> Unit) -> Unit) {
    private class Epoch(val active: Boolean) {
        val rendered = AtomicBoolean()
    }
    private val current = AtomicReference(Epoch(false))
    // Main-thread only: an old queued frame callback must not arm a new playback epoch.
    private var callbackEpoch = current.get()

    fun reset(active: Boolean) {
        val epoch = Epoch(active)
        current.set(epoch)
        post { callbackEpoch = epoch }
    }

    fun onRenderedFirstFrame() {
        val epoch = callbackEpoch
        if (epoch.active && current.get() === epoch) epoch.rendered.set(true)
    }

    fun hasRenderedFrame(): Boolean = current.get().let { it.active && it.rendered.get() }
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SrtRecoveredAudioRenderer(renderer: Renderer,
    private val onPosition: (Long) -> Unit = {}, private val sourceAdvanced: () -> Boolean) :
    ForwardingRenderer(renderer) {
    // Source-only audio time must not stop loading/video while the device plays recovery silence.
    override fun getMediaClock(): MediaClock? = null

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        onPosition(positionUs)
        super.render(positionUs, elapsedRealtimeUs)
    }

    override fun getDurationToProgressUs(positionUs: Long, elapsedRealtimeUs: Long): Long =
        minOf(5000, super.getDurationToProgressUs(positionUs, elapsedRealtimeUs))

    override fun isReady(): Boolean {
        if (super.isReady()) return true
        if (!sourceAdvanced()) return false
        super.maybeThrowStreamError()
        return true
    }
}

/** A held live picture keeps scheduling/loading active, including video-only streams. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SrtSparseVideoRenderer(
    renderer: Renderer,
    private val readiness: SrtVideoReadiness
) : ForwardingRenderer(renderer) {
    override fun isReady(): Boolean {
        if (super.isReady()) return true
        if (!readiness.hasRenderedFrame()) return false
        // ExoPlayer normally checks stream errors only for an unready renderer.
        super.maybeThrowStreamError()
        return true
    }

    override fun enable(
        configuration: RendererConfiguration, formats: Array<Format>, stream: SampleStream,
        positionUs: Long, joining: Boolean, mayRenderStartOfStream: Boolean,
        startPositionUs: Long, offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId
    ) {
        readiness.reset(active = true)
        super.enable(configuration, formats, stream, positionUs, joining, mayRenderStartOfStream,
            startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun replaceStream(
        formats: Array<Format>, stream: SampleStream, startPositionUs: Long,
        offsetUs: Long, mediaPeriodId: MediaSource.MediaPeriodId
    ) {
        readiness.reset(active = true)
        super.replaceStream(formats, stream, startPositionUs, offsetUs, mediaPeriodId)
    }

    override fun resetPosition(positionUs: Long, sampleStreamIsResetToKeyFrame: Boolean) {
        readiness.reset(active = true)
        super.resetPosition(positionUs, sampleStreamIsResetToKeyFrame)
    }

    override fun disable() {
        readiness.reset(active = false)
        super.disable()
    }

    override fun reset() {
        readiness.reset(active = false)
        super.reset()
    }

    override fun release() {
        readiness.reset(active = false)
        super.release()
    }
}
