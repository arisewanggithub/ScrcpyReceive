package org.screencopy.receiver.media

import android.media.AudioAttributes
import android.media.AudioTimestamp
import android.media.AudioFormat as PlatformAudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Handler
import android.os.Looper

interface AudioOutput : AutoCloseable {
    val underrunCount: Int
    val playbackActive: Boolean? get() = null
    fun play()
    fun pause()
    fun write(samples: ShortArray, offset: Int, count: Int): Int
    fun positionUs(): Long = timing().presentedUs
    fun timing(): AudioOutputTiming
    fun setSpeed(speed: Float)
}

internal class PlatformAudioOutput private constructor(
    format: AudioFormat, failure: (String, String) -> Unit, platform: AudioOutputPlatform,
    private val prepared: (String) -> Unit
) : AudioOutput {
    constructor(format: AudioFormat, failure: (String, String) -> Unit) :
        this(format, failure, AndroidAudioOutputPlatform, { android.util.Log.i("ScreenCopyReceiver", it) })
    internal constructor(format: AudioFormat, failure: (String, String) -> Unit, platform: AudioOutputPlatform) :
        this(format, failure, platform, {})

    private val opened = AudioOutputPreparation.open(format, platform, failure) {
        prepared("event=AUDIO_OUTPUT_HEADROOM_RETRY stage=OUTPUT_PREPARE ${it.logMessage()}")
    }
    private val device = opened.first
    internal val bufferFrames: Int get() = device.bufferFrames
    internal val capacityFrames: Int get() = device.capacityFrames
    internal val preparedSpeed: Float get() = device.speed
    internal val isPlaying: Boolean get() = device.isPlaying

    init {
        prepared("event=AUDIO_OUTPUT_PREPARED stage=OUTPUT_PREPARE sample_rate=${format.sampleRate}" +
            " channels=${format.channels} buffer_frames=${device.bufferFrames} capacity_frames=${device.capacityFrames}" +
            " minimum_frames=${opened.second.minimumFrames} requested_frames=${opened.second.frames} max_frames=${opened.second.maxFrames}" +
            " min_speed=${AudioOutputPreparation.MIN_SPEED} max_speed=${AudioOutputPreparation.MAX_SPEED} nominal_speed=${device.speed}")
    }
    override val underrunCount: Int get() = device.underrunCount
    override val playbackActive: Boolean get() = device.isPlaying
    override fun play() = device.play()
    override fun pause() = device.pause()
    override fun write(samples: ShortArray, offset: Int, count: Int) = device.write(samples, offset, count)
    override fun positionUs(): Long = device.positionUs()
    override fun timing(): AudioOutputTiming = device.timing()
    override fun setSpeed(speed: Float) {
        require(speed in AudioOutputPreparation.MIN_SPEED..AudioOutputPreparation.MAX_SPEED) { "Audio output rate exceeds prepared envelope" }
        var reason = OutputPreparationReason.RATE_UNSUPPORTED
        try {
            device.setSpeed(speed)
            reason = OutputPreparationReason.BUFFER_LIMIT
            check(device.bufferFrames in opened.second.minimumFrames..opened.second.maxFrames &&
                device.capacityFrames in device.bufferFrames..opened.second.maxFrames) { "Audio output buffer changed beyond prepared budget" }
            reason = OutputPreparationReason.FORMAT_MISMATCH
            check(device.sampleRate == opened.second.sampleRate && device.channels == opened.second.channels && device.pcm16) {
                "Audio output format changed after preparation"
            }
            reason = OutputPreparationReason.PARAMS_MISMATCH
            check(AudioOutputPreparation.rateMatches(device, speed)) {
                "Audio output rate unsupported within prepared envelope"
            }
            if (device.speed != speed)
                prepared("event=AUDIO_OUTPUT_RATE_READBACK requested_speed=$speed actual_speed=${device.speed}" +
                    " native_deadband=${AudioOutputPreparation.NATIVE_SPEED_DEADBAND}")
        } catch (e: Exception) {
            val plan = opened.second
            throw AudioOutputPreparationException(OutputPreparationDiagnostic(reason, plan.sampleRate, plan.channels,
                plan.minimumFrames, plan.frames, device.bufferFrames, device.capacityFrames, plan.maxFrames, speed,
                device.sampleRate, device.channels, device.speed, device.pitch, device.failOnUnsupported), e)
        }
    }
    override fun close() = device.close()
}

private object AndroidAudioOutputPlatform : AudioOutputPlatform {
    override fun minimumBytes(format: AudioFormat): Int = AudioTrack.getMinBufferSize(
        format.sampleRate,
        if (format.channels == 1) PlatformAudioFormat.CHANNEL_OUT_MONO else PlatformAudioFormat.CHANNEL_OUT_STEREO,
        PlatformAudioFormat.ENCODING_PCM_16BIT
    )
    override fun create(format: AudioFormat, bytes: Int, failure: (String, String) -> Unit): RateAudioTrack =
        AndroidRateAudioTrack(format, bytes, failure)
}

private class AndroidRateAudioTrack(format: AudioFormat, bytes: Int, failure: (String, String) -> Unit) : RateAudioTrack {
    private val track: AudioTrack
    private val position: AudioPosition
    @Volatile private var playing = false
    @Volatile private var routeId: Int? = null
    private val routeListener = AudioRouting.OnRoutingChangedListener {
        val current = track.routedDevice?.id
        val previous = routeId
        routeId = current
        if (playing && previous != null && previous != current)
            failure("AUDIO_FAILED", "Audio output device changed; reconnect to reset playback timing")
    }

    init {
        val mask = if (format.channels == 1) PlatformAudioFormat.CHANNEL_OUT_MONO else PlatformAudioFormat.CHANNEL_OUT_STEREO
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(PlatformAudioFormat.Builder().setEncoding(PlatformAudioFormat.ENCODING_PCM_16BIT).setSampleRate(format.sampleRate).setChannelMask(mask).build())
            .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(bytes).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED) { "Audio output initialization failed" }
            track.addOnRoutingChangedListener(routeListener, Handler(Looper.getMainLooper()))
            position = AudioPosition(format.sampleRate, track.bufferSizeInFrames, System::nanoTime, {
                val value = AudioTimestamp()
                if (track.getTimestamp(value)) FrameTimestamp(value.framePosition, value.nanoTime) else null
            }, { track.playbackHeadPosition })
        } catch (e: Exception) {
            track.release()
            throw e
        }
    }

    override val underrunCount: Int get() = track.underrunCount
    override val initialized: Boolean get() = track.state == AudioTrack.STATE_INITIALIZED
    override val sampleRate: Int get() = track.sampleRate
    override val channels: Int get() = track.channelCount
    override val pcm16: Boolean get() = track.audioFormat == PlatformAudioFormat.ENCODING_PCM_16BIT
    override val bufferFrames: Int get() = track.bufferSizeInFrames
    override val capacityFrames: Int get() = track.bufferCapacityInFrames
    override val isPlaying: Boolean get() = track.playState == AudioTrack.PLAYSTATE_PLAYING
    override val speed: Float get() = track.playbackParams.speed
    override val pitch: Float get() = track.playbackParams.pitch
    override val failOnUnsupported: Boolean get() = track.playbackParams.audioFallbackMode == PlaybackParams.AUDIO_FALLBACK_MODE_FAIL
    override fun play() { playing = true; track.play() }
    override fun pause() { playing = false; track.pause() }
    override fun write(samples: ShortArray, offset: Int, count: Int): Int = synchronized(position) {
        val written = track.write(samples, offset, count, AudioTrack.WRITE_NON_BLOCKING)
        check(written <= count && (written <= 0 || written % channels == 0)) { "AudioTrack returned an invalid sample count" }
        if (written > 0) position.written(written / channels)
        written
    }
    override fun positionUs(): Long = timing().presentedUs
    override fun timing(): AudioOutputTiming = position.current()
    override fun setSpeed(speed: Float): Unit = synchronized(position) {
        track.playbackParams = PlaybackParams().allowDefaults().setSpeed(speed).setPitch(1f)
            .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
        position.speed(track.playbackParams.speed)
    }
    override fun close() {
        playing = false
        try { track.removeOnRoutingChangedListener(routeListener) } finally { track.release() }
    }
}
