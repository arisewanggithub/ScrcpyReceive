package org.screencopy.receiver.media

import android.media.AudioFormat as PlatformAudioFormat
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Playback-thread-owned decoded PCM policy; Media3 still owns codecs, output and native errors. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SrtLowLatencyAudioSink(
    sink: AudioSink,
    private val now: () -> Long = System::nanoTime,
    private val report: (AudioRegulationDiagnostic) -> Unit = {
        android.util.Log.i("ScreenCopySrt", it.logMessage())
    },
    private val opus: Boolean = false
) : ForwardingAudioSink(sink) {
    private var rate = 0
    private var channels = 0
    private var regulator: AudioRegulator? = null
    private var presentation: AudioPresentationTimeline? = null
    private var sourceClock: AudioSourceClock? = null
    private var trim: StartupTrim? = null
    private var preSkip = 0
    private var firstPts: Long? = null
    private var sourceFrames = 0L
    private var deviceFrames = 0L
    private var pending: ByteBuffer? = null
    private var outputBlock: ByteBuffer? = null
    private var pendingPts = 0L
    private var pendingSourcePts: Long? = null
    private var playing = false
    private var ended = false
    private var listener: AudioSink.Listener? = null
    private var pendingAdvancementMs: Long? = null
    private var lastOutputProgressNs = 0L
    private var lastDevicePositionUs = AudioSink.CURRENT_POSITION_NOT_SET
    private var lastSourcePtsUs: Long? = null
    private var nativeInitializations = 0
    private var playbackPositionUs: Long? = null
    private var playbackOriginUs: Long? = null
    @Volatile var regulation: AudioRegulationDiagnostic? = null
        private set
    @Volatile var sourceAdvanced = false
        private set
    @Volatile var sourcePositionUs = 0L
        private set

    override fun supportsFormat(format: Format): Boolean = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW && format.pcmEncoding == C.ENCODING_PCM_16BIT)
            super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        require(playbackParameters.speed in 0.98f..1.02f && playbackParameters.pitch == 1f) {
            "SRT playback rate is outside the low-latency envelope"
        }
        super.setPlaybackParameters(playbackParameters)
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(object : AudioSink.Listener {
            override fun onPositionDiscontinuity() = listener.onPositionDiscontinuity()
            override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
                pendingAdvancementMs = playoutStartSystemTimeMs
            }
            override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                reportState()
                listener.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
            }
            override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
                listener.onSkipSilenceEnabledChanged(skipSilenceEnabled)
            override fun onAudioSinkError(audioSinkError: Exception) {
                sourceAdvanced = false
                listener.onAudioSinkError(audioSinkError)
                throw IllegalStateException("SRT device audio failed; implicit output replacement is disabled", audioSinkError)
            }
            override fun onAudioCapabilitiesChanged() = listener.onAudioCapabilitiesChanged()
            override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) {
                if (++nativeInitializations > 1) {
                    sourceAdvanced = false
                    throw IllegalStateException("SRT device audio output was reset unexpectedly")
                }
                require(audioTrackConfig.encoding == C.ENCODING_PCM_16BIT && audioTrackConfig.sampleRate == rate &&
                    audioTrackConfig.channelConfig == if (channels == 1) PlatformAudioFormat.CHANNEL_OUT_MONO
                    else PlatformAudioFormat.CHANNEL_OUT_STEREO) { "SRT output changed requested PCM format" }
                require(audioTrackConfig.bufferSize > 0 && audioTrackConfig.bufferSize % (channels * 2) == 0 &&
                    audioTrackConfig.bufferSize <= rate * channels * 2 * AudioOutputPreparation.MAX_BUFFER_MS / 1000) {
                    "SRT device output exceeds the bounded 130 ms route budget"
                }
                val actualUs = validateDeviceBuffer()
                check(actualUs != C.TIME_UNSET) { "SRT effective device output size is unavailable after initialization" }
                android.util.Log.i("ScreenCopySrt", "event=AUDIO_OUTPUT_PREPARED requested_ms=5" +
                    " planned_buffer_bytes=${audioTrackConfig.bufferSize} actual_buffer_us=$actualUs sample_rate=$rate channels=$channels")
                listener.onAudioTrackInitialized(audioTrackConfig)
            }
            override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) =
                listener.onAudioTrackReleased(audioTrackConfig)
            override fun onAudioSessionIdChanged(audioSessionId: Int) = listener.onAudioSessionIdChanged(audioSessionId)
        })
    }

    override fun configure(config: AudioSink.AudioSinkConfig) {
        val format = config.format
        require(format.pcmEncoding == C.ENCODING_PCM_16BIT && format.sampleRate in 1..96000 &&
            format.channelCount in 1..2) { "SRT decoder changed requested PCM output format" }
        clear()
        rate = format.sampleRate
        channels = format.channelCount
        preSkip = format.encoderDelay
        regulator = AudioRegulator(rate, channels)
        presentation = AudioPresentationTimeline(rate)
        outputBlock = ByteBuffer.allocateDirect(maxOf(1, rate * 5 / 1000) * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        trim = StartupTrim(format.encoderDelay, rate)
        // Pre-skip is consumed before the FIFO, never twice at the physical sink.
        super.configure(AudioSink.AudioSinkConfig.Builder(format.buildUpon().setEncoderDelay(0).build())
            .setOutputChannelMapping(config.outputChannelMapping).setTimeline(config.timeline)
            .setMediaPeriodId(config.mediaPeriodId).build())
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        pump()
        check(rate > 0 && channels > 0) { "SRT audio output is not configured" }
        require(presentationTimeUs >= 0 && buffer.remaining() % (channels * 2) == 0 &&
            buffer.remaining() <= rate * channels * 2 * 120 / 1000) { "Invalid SRT decoded PCM packet" }
        if (!buffer.hasRemaining()) return true
        playbackPositionUs?.let { position ->
            val sourceOrigin = firstPts
            val clockOrigin = playbackOriginUs
            // Preroll must start even when source PTS begins ahead of Media3's stopped startup clock.
            if (sourceOrigin != null && clockOrigin != null &&
                presentationTimeUs - sourceOrigin > (position - clockOrigin).coerceAtLeast(0) +
                    checkNotNull(regulator).targetFrames * 1_000_000L / rate) return false
        }
        require(opus || lastSourcePtsUs?.let { presentationTimeUs >= it } != false) {
            "SRT decoded AAC PTS decreased within a generation"
        }
        lastSourcePtsUs = presentationTimeUs
        val samples = ShortArray(buffer.remaining() / 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        buffer.position(buffer.limit())
        val slice = checkNotNull(trim).apply(samples.size / channels, presentationTimeUs)
        if (slice.frames > 0) {
            if (firstPts == null) {
                firstPts = slice.audiblePtsUs
                playbackOriginUs = playbackPositionUs
                sourceClock = AudioSourceClock(slice.audiblePtsUs, rate)
            }
            checkNotNull(regulator).offer(samples, slice)
        }
        pump()
        return true
    }

    fun setPlaybackPositionUs(positionUs: Long) {
        require(positionUs >= 0) { "Invalid SRT renderer playback position" }
        playbackPositionUs = positionUs
        if (firstPts != null && playbackOriginUs == null) playbackOriginUs = positionUs
    }

    private fun pump() {
        val fifo = regulator ?: return
        val base = firstPts ?: return
        if (!playing) return
        val position = super.getCurrentPositionUs(false)
        if (position != AudioSink.CURRENT_POSITION_NOT_SET && position > lastDevicePositionUs)
            lastOutputProgressNs = now()
        lastDevicePositionUs = position
        val block = maxOf(1, rate * 5 / 1000)
        for (iteration in 0 until 8) {
            if (pending == null) {
                val chunk = fifo.take(block)
                if (chunk == null && (ended || super.hasPendingData())) break
                val frames = chunk?.frames ?: block
                val bytes = checkNotNull(outputBlock)
                bytes.clear()
                bytes.limit(frames * channels * 2)
                val samples = bytes.asShortBuffer()
                if (chunk != null) samples.put(chunk.samples, chunk.offset, frames * channels)
                else repeat(frames * channels) { samples.put(0) }
                pending = bytes
                pendingPts = base + deviceFrames * 1_000_000 / rate
                pendingSourcePts = chunk?.ptsUs
            }
            val output = checkNotNull(pending)
            val before = output.position()
            val complete = super.handleBuffer(output, pendingPts, 1)
            validateDeviceBuffer()
            val accepted = (output.position() - before) / (channels * 2)
            if (accepted > 0) {
                checkNotNull(presentation).submitted(accepted, pendingSourcePts != null)
                deviceFrames += accepted
                pendingSourcePts?.let {
                    checkNotNull(sourceClock).packet(sourceFrames * 1_000_000 / rate, it)
                    fifo.sourceWritten(accepted)
                    sourceFrames += accepted
                    checkNotNull(sourceClock).written(sourceFrames * 1_000_000 / rate)
                    pendingSourcePts = it + accepted * 1_000_000L / rate
                } ?: fifo.insertedSilence(accepted)
                lastOutputProgressNs = now()
            }
            if (pending != null && now() - lastOutputProgressNs > 250_000_000L)
                throw IllegalStateException("SRT audio output stalled for 250 ms without device or write progress")
            if (!complete) break
            check(!output.hasRemaining()) { "Media3 accepted an incomplete PCM buffer" }
            val wasSilence = pendingSourcePts == null
            pending = null
            pendingSourcePts = null
            if (wasSilence) break
        }
        fifo.correction(now())?.let {
            if (super.getPlaybackParameters().speed != it)
                super.setPlaybackParameters(PlaybackParameters(it, 1f))
            check(kotlin.math.abs(super.getPlaybackParameters().speed - it) < 0.00001f) {
                "SRT audio compensation rate was not applied"
            }
            reportState()
        }
    }

    private fun checkedPump() {
        try { pump() }
        catch (e: AudioSink.InitializationException) { throw IllegalStateException("SRT audio output initialization failed", e) }
        catch (e: AudioSink.WriteException) { throw IllegalStateException("SRT audio output write failed", e) }
    }
    private fun validateDeviceBuffer(): Long {
        val actual = super.getAudioTrackBufferSizeUs()
        check(actual == C.TIME_UNSET || actual in 1..AudioOutputPreparation.MAX_BUFFER_MS * 1000L) {
            "SRT effective device output exceeds the bounded 130 ms route budget"
        }
        return actual
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        checkedPump()
        return observeSourcePosition(sourceEnded)
    }

    private fun observeSourcePosition(sourceEnded: Boolean): Long {
        val device = super.getCurrentPositionUs(sourceEnded)
        val base = firstPts ?: return AudioSink.CURRENT_POSITION_NOT_SET
        if (device == AudioSink.CURRENT_POSITION_NOT_SET) return device
        val frames = ((device - base).coerceAtLeast(0) * rate / 1_000_000).coerceAtMost(deviceFrames)
        val sourceUs = checkNotNull(presentation).presentedUs(frames)
        sourcePositionUs = sourceUs
        if (sourceUs > 0) {
            sourceAdvanced = true
            pendingAdvancementMs?.let {
                pendingAdvancementMs = null
                listener?.onPositionAdvancing(it)
            }
        }
        return checkNotNull(sourceClock).current(sourceUs)
    }
    override fun play() { playing = true; lastOutputProgressNs = now(); super.play() }
    override fun pause() { playing = false; super.pause() }
    override fun hasPendingData(): Boolean {
        checkedPump()
        observeSourcePosition(false)
        return pending != null || regulator?.bufferedFrames?.let { it > 0 } == true || super.hasPendingData()
    }
    override fun playToEndOfStream() {
        ended = true
        regulator?.startAfterPreroll()
        pump()
        if (pending == null && regulator?.bufferedFrames == 0) super.playToEndOfStream()
    }
    override fun isEnded(): Boolean = ended && pending == null && regulator?.bufferedFrames == 0 && super.isEnded()
    override fun flush() { playing = false; clear(); super.flush() }
    override fun reset() { playing = false; clear(); playbackPositionUs = null; super.reset() }
    override fun release() {
        playing = false
        try { super.release() } finally { clear(); playbackPositionUs = null; outputBlock = null }
    }
    private fun reportState() {
        regulator?.diagnostic()?.let {
            regulation = it
            report(it)
            android.util.Log.i("ScreenCopySrt", "event=AUDIO_SCHEDULING renderer_position_us=$playbackPositionUs" +
                " renderer_origin_us=$playbackOriginUs source_pts_us=$lastSourcePtsUs" +
                " output_base_us=$firstPts output_written_frames=$deviceFrames")
        }
    }
    private fun clear() {
        sourceAdvanced = false
        sourcePositionUs = 0
        regulation = null
        nativeInitializations = 0
        regulator?.clear()
        presentation?.clear()
        if (rate > 0) trim = StartupTrim(preSkip, rate)
        firstPts = null
        playbackOriginUs = null
        sourceClock = null
        sourceFrames = 0
        deviceFrames = 0
        pending = null
        pendingSourcePts = null
        pendingAdvancementMs = null
        lastDevicePositionUs = AudioSink.CURRENT_POSITION_NOT_SET
        lastSourcePtsUs = null
        ended = false
    }
}
