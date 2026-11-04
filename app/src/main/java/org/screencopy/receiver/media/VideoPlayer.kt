package org.screencopy.receiver.media

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

class VideoPlayer private constructor(
    private val format: VideoFormat,
    private val clock: PlaybackClock,
    report: (String) -> Unit,
    failure: (String, String) -> Unit,
    onPresented: () -> Unit,
    factory: (VideoFormat, () -> Unit) -> VideoDecoder,
    private val generation: Long,
    private val onPresentation: (VideoDiagnostic) -> Unit
) : TrackWorker("ScreenCopy-video", 8 * 1024 * 1024, failure, track = 1) {
    constructor(format: VideoFormat, surface: Surface, clock: PlaybackClock, report: (String) -> Unit,
        failure: (String, String) -> Unit, onPresented: () -> Unit = {}, generation: Long = 1) :
        this(format, clock, report, failure, onPresented, { selected, presented -> AndroidVideoDecoder(selected, surface, presented) },
            generation, { android.util.Log.i("ScreenCopyReceiver", "event=VIDEO_PRESENTATION ${it.logMessage()}") })

    internal constructor(format: VideoFormat, clock: PlaybackClock, failure: (String, String) -> Unit, decoder: VideoDecoder,
        generation: Long = 1, onPresentation: (VideoDiagnostic) -> Unit = {}) :
        this(format, clock, {}, failure, {}, { _, _ -> decoder }, generation, onPresentation)

    internal constructor(format: VideoFormat, clock: PlaybackClock, failure: (String, String) -> Unit,
        factory: (VideoFormat, () -> Unit) -> VideoDecoder, generation: Long = 1) :
        this(format, clock, {}, failure, {}, factory, generation, {})

    private val decoder: VideoDecoder
    private var waiting: AccessUnit? = null
    private var firstKeyFrame = false
    private var heldOutput = -1
    private var heldPtsUs = 0L
    private var heldAtNs = 0L
    private var waitingAtNs = 0L
    private var stage = VideoStage.SELECT
    private var delayUs: Long? = null
    private var inputBytes = 0
    private var inputCapacity = 0
    private var inputKeyFrame = false
    private var outputBytes = 0
    private var outputFlags = 0
    private var lastInputPtsUs: Long? = null
    private var lastOutputPtsUs: Long? = null
    private var queuedFrames = 0L
    private var decodedFrames = 0L
    private var renderSubmitted = 0L
    private var decodedSkipped = 0L
    private var lateRendered = 0L
    private var pendingOutputAtNs: Long? = null
    private var lastPresentationEventNs: Long? = null
    private var lastReportedSkipped = 0L
    private var lastReportedLate = 0L

    init {
        require(format.width in 1..8192 && format.height in 1..8192 && format.frameRate in 1..240 &&
            format.rotation in listOf(0, 90, 180, 270) && generation > 0) { "Invalid video output format" }
        decoder = try { factory(format) { if (running.get()) onPresented() } } catch (e: Exception) { throw failure(e) }
        stage = VideoStage.START
        try {
            report(decoder.description)
            onPresentation(snapshot())
        } catch (e: Exception) {
            try { decoder.close() } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw failure(e)
        }
    }

    override fun step() {
        if (heldOutput >= 0) {
            stage = VideoStage.CLOCK
            clock.checkStartup()
            // Only decoded pictures are replaceable; keep admitting every compressed dependency.
            for (index in 0 until 8) {
                val newer = dequeuePicture() ?: break
                stage = VideoStage.OUTPUT_RELEASE
                decoder.releaseOutput(heldOutput, null)
                decodedSkipped++
                hold(newer)
            }
            stage = VideoStage.CLOCK
            delayUs = clock.delayUs(heldPtsUs)
            if (delayUs?.let { it < -250_000 } == true) lateRendered++
            stage = VideoStage.OUTPUT_RELEASE
            decoder.releaseOutput(heldOutput, clock.monotonicNs())
            renderSubmitted++
            heldOutput = -1
            reportPresentation()
        }
        if (waiting == null) {
            waiting = next()
            waitingAtNs = clock.monotonicNs()
        }
        val packet = waiting
        if (packet != null) {
            inputBytes = packet.bytes.size
            inputKeyFrame = packet.keyFrame
            inputCapacity = 0
            stage = VideoStage.CLOCK
            delayUs = if (packet.ptsUs >= 0) clock.delayUs(packet.ptsUs) else null
            stage = VideoStage.INPUT_KEYFRAME
            if (!firstKeyFrame && !packet.keyFrame) fail(VideoFault.FIRST_KEYFRAME)
            if (packet.ptsUs < 0 || lastInputPtsUs?.let { packet.ptsUs < it } == true) fail(VideoFault.INPUT_PTS)
            stage = VideoStage.INPUT_DEQUEUE
            val index = decoder.dequeueInput()
            if (index >= 0) {
                stage = VideoStage.INPUT_BUFFER
                val input = decoder.input(index) ?: fail(VideoFault.INPUT_BUFFER)
                inputCapacity = input.capacity()
                if (inputCapacity < packet.bytes.size) fail(VideoFault.INPUT_CAPACITY)
                input.clear()
                input.put(packet.bytes)
                clock.anchorVideo(packet.ptsUs)
                stage = VideoStage.INPUT_QUEUE
                decoder.queueInput(index, packet.bytes.size, packet.ptsUs, packet.keyFrame)
                lastInputPtsUs = packet.ptsUs
                queuedFrames++
                if (pendingOutputAtNs == null && lastOutputPtsUs?.let { packet.ptsUs > it } != false)
                    pendingOutputAtNs = clock.monotonicNs()
                firstKeyFrame = true
                waiting = null
            } else if (clock.monotonicNs() - waitingAtNs > 3_000_000_000L) fail(VideoFault.INPUT_STALL)
        }
        if (heldOutput < 0) dequeuePicture()?.let(::hold)
        if (heldOutput < 0 && pendingOutputAtNs?.let { clock.monotonicNs() - it > 3_000_000_000L } == true)
            fail(VideoFault.OUTPUT_STALL)
    }

    private fun dequeuePicture(): VideoOutput? {
        stage = VideoStage.OUTPUT_DEQUEUE
        val output = decoder.dequeueOutput()
        if (output != null) {
            stage = VideoStage.OUTPUT_VALIDATE
            outputBytes = output.size
            outputFlags = output.flags
            if (output.size < 0 || output.index == heldOutput) fail(VideoFault.OUTPUT_METADATA)
            if (output.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) fail(VideoFault.END_OF_STREAM)
            if (output.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 || output.size == 0) {
                stage = VideoStage.OUTPUT_RELEASE
                decoder.releaseOutput(output.index, null)
            } else {
                stage = VideoStage.CLOCK
                delayUs = if (output.ptsUs >= 0) clock.delayUs(output.ptsUs) else null
                stage = VideoStage.OUTPUT_VALIDATE
                if (output.ptsUs < 0 || lastOutputPtsUs?.let { output.ptsUs < it } == true) fail(VideoFault.OUTPUT_PTS)
                lastOutputPtsUs = output.ptsUs
                decodedFrames++
                // A codec may omit an earlier picture; latest output PTS, not 1:1 counts, proves progress.
                pendingOutputAtNs = if (lastInputPtsUs?.let { output.ptsUs >= it } != false) null else clock.monotonicNs()
                return output
            }
        }
        return null
    }

    private fun hold(output: VideoOutput) {
        heldOutput = output.index
        heldPtsUs = output.ptsUs
        heldAtNs = clock.monotonicNs()
        outputBytes = output.size
        outputFlags = output.flags
    }

    private fun reportPresentation() {
        if (decodedSkipped == lastReportedSkipped && lateRendered == lastReportedLate) return
        val now = clock.monotonicNs()
        if (lastPresentationEventNs?.let { now - it < 1_000_000_000L } == true) return
        onPresentation(snapshot())
        lastPresentationEventNs = now
        lastReportedSkipped = decodedSkipped
        lastReportedLate = lateRendered
    }

    private fun snapshot(fault: VideoFault = VideoFault.NONE, exception: String = "None", codec: VideoCodecFailure? = null) =
        VideoDiagnostic(stage, fault, exception, generation, format.width, format.height, format.rotation, format.frameRate,
            delayUs, if (heldOutput >= 0) (clock.monotonicNs() - heldAtNs).coerceAtLeast(0) / 1000 else 0,
            inputBytes, inputCapacity, inputKeyFrame, outputBytes, outputFlags, queuedFrames, decodedFrames,
            renderSubmitted, decodedSkipped, lateRendered, codec?.errorCode, codec?.recoverable ?: false, codec?.transient ?: false,
            when (format.mime) { "video/avc" -> "H264"; "video/hevc" -> "H265"; "video/av01" -> "AV1"; else -> "UNKNOWN" },
            if (waiting != null) (clock.monotonicNs() - waitingAtNs).coerceAtLeast(0) / 1000 else 0,
            pendingOutputAtNs?.let { (clock.monotonicNs() - it).coerceAtLeast(0) / 1000 } ?: 0)

    private fun fail(fault: VideoFault): Nothing = throw VideoPlaybackException(snapshot(fault, "VideoPlaybackException"))

    private fun failure(error: Exception): VideoPlaybackException {
        if (error is VideoPlaybackException) return error
        val original = if (error is VideoInitializationFailure) error.failure else error
        if (error is VideoInitializationFailure) stage = error.stage
        val codec = original as? VideoCodecFailure
        val fault = when {
            original is PlaybackException && original.code == "UNSUPPORTED_CONFIG" -> VideoFault.UNSUPPORTED
            codec?.errorCode == 1100 -> VideoFault.CODEC_RESOURCE
            codec?.errorCode == 1101 -> VideoFault.CODEC_RECLAIMED
            stage == VideoStage.CLOCK -> VideoFault.CLOCK_FAILURE
            else -> VideoFault.CODEC_API
        }
        val exception = if (codec != null) "CodecException" else
            original.javaClass.simpleName.takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,64}")) } ?: "Exception"
        return VideoPlaybackException(snapshot(fault, exception, codec), error)
    }

    override fun describeFailure(error: Exception): String {
        if (error is AudioClockException) return super.describeFailure(error)
        val diagnostic = failure(error).diagnostic
        return "Video stage=${diagnostic.stage} exception=${diagnostic.exceptionType}: Video failure: ${diagnostic.logMessage()}"
    }

    override fun release() {
        stage = VideoStage.CLOSE
        try { decoder.close() } catch (e: Exception) { throw failure(e) }
    }

    companion object {
        fun mediaFormat(format: VideoFormat): MediaFormat {
            require(format.width in 1..8192 && format.height in 1..8192) { "Unsupported video dimensions" }
            require(format.frameRate in 1..240) { "Unsupported video frame rate" }
            require(format.rotation in listOf(0, 90, 180, 270)) { "Invalid video rotation" }
            return MediaFormat.createVideoFormat(format.mime, format.width, format.height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, format.frameRate)
                setInteger(MediaFormat.KEY_ROTATION, format.rotation)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8 * 1024 * 1024)
                format.configuration.forEachIndexed { index, bytes ->
                    setByteBuffer("csd-$index", ByteBuffer.wrap(bytes))
                }
            }
        }
    }
}

internal fun findPreferredDecoder(format: MediaFormat): String? {
    val mime = checkNotNull(format.getString(MediaFormat.KEY_MIME))
    return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.asSequence()
        .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, true) } }
        .sortedBy { isSoftwareDecoder(it) }
        .firstOrNull { it.getCapabilitiesForType(mime).isFormatSupported(format) }?.name
}

internal fun isSoftwareDecoder(info: MediaCodecInfo): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= 29) !info.isHardwareAccelerated
    else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")
