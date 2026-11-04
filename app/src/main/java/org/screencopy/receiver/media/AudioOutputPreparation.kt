package org.screencopy.receiver.media

import kotlin.math.abs
import kotlin.math.floor

internal interface RateAudioTrack : AudioOutput {
    val initialized: Boolean
    val sampleRate: Int
    val channels: Int
    val pcm16: Boolean
    val bufferFrames: Int
    val capacityFrames: Int
    val isPlaying: Boolean
    val speed: Float
    val pitch: Float
    val failOnUnsupported: Boolean
}

internal interface AudioOutputPlatform {
    fun minimumBytes(format: AudioFormat): Int
    fun create(format: AudioFormat, bytes: Int, failure: (String, String) -> Unit): RateAudioTrack
}

enum class OutputPreparationReason { RATE_UNSUPPORTED, BUFFER_LIMIT, TRACK_INITIALIZATION, FORMAT_MISMATCH, PLAY_STATE, PARAMS_MISMATCH }

data class OutputPreparationDiagnostic(
    val reason: OutputPreparationReason,
    val sampleRate: Int,
    val channels: Int,
    val minimumFrames: Int,
    val requestedFrames: Int,
    val bufferFrames: Int,
    val capacityFrames: Int,
    val maxFrames: Int,
    val speed: Float,
    val actualSampleRate: Int = 0,
    val actualChannels: Int = 0,
    val actualSpeed: Float? = null,
    val actualPitch: Float? = null,
    val failOnUnsupported: Boolean? = null
) {
    fun logMessage() = "output_reason=$reason sample_rate=$sampleRate channels=$channels minimum_frames=$minimumFrames" +
        " requested_frames=$requestedFrames buffer_frames=$bufferFrames capacity_frames=$capacityFrames" +
        " max_frames=$maxFrames intended_speed=$speed actual_sample_rate=$actualSampleRate actual_channels=$actualChannels" +
        (actualSpeed?.let { " actual_speed=$it" } ?: "") +
        (actualPitch?.let { " actual_pitch=$it" } ?: "") +
        (failOnUnsupported?.let { " fail_on_unsupported=$it" } ?: "")

    companion object {
        internal fun fromLocalDetail(detail: String): OutputPreparationDiagnostic? {
            val fields = detail.substringAfter("Audio output rate preparation unsupported: ", "")
                .split(' ').mapNotNull { word ->
                    val pair = word.split('=', limit = 2)
                    if (pair.size == 2) pair[0] to pair[1] else null
                }.toMap()
            val reason = OutputPreparationReason.entries.firstOrNull { it.name == fields["output_reason"] } ?: return null
            fun integer(key: String) = fields[key]?.toIntOrNull()
            val speed = fields["intended_speed"]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
            return OutputPreparationDiagnostic(reason, integer("sample_rate") ?: return null,
                integer("channels") ?: return null, integer("minimum_frames") ?: return null,
                integer("requested_frames") ?: return null, integer("buffer_frames") ?: return null,
                integer("capacity_frames") ?: return null, integer("max_frames") ?: return null, speed,
                integer("actual_sample_rate") ?: return null, integer("actual_channels") ?: return null,
                fields["actual_speed"]?.toFloatOrNull(), fields["actual_pitch"]?.toFloatOrNull(),
                fields["fail_on_unsupported"]?.toBooleanStrictOrNull())
        }
    }
}

class AudioOutputPreparationException(val diagnostic: OutputPreparationDiagnostic, cause: Exception? = null) :
    PlaybackException("Audio output rate preparation unsupported: ${diagnostic.logMessage()}", "UNSUPPORTED_CONFIG") {
    init { if (cause != null) initCause(cause) }
}

internal data class AudioBufferPlan(
    val sampleRate: Int, val channels: Int, val minimumFrames: Int, val frames: Int, val bytes: Int,
    val maxFrames: Int
)

internal object AudioOutputPreparation {
    const val MIN_SPEED = 0.98f
    const val MAX_SPEED = 1.02f
    const val NOMINAL_SPEED = 1f
    const val MAX_BUFFER_MS = 130
    const val REQUESTED_BUFFER_MS = 5
    // AudioTrack may intentionally retain the prior speed for smaller native deltas.
    const val NATIVE_SPEED_DEADBAND = 0.0001f

    fun rateMatches(device: RateAudioTrack, requested: Float): Boolean {
        val actual = device.speed
        val pitch = device.pitch
        return actual.isFinite() && actual in MIN_SPEED..MAX_SPEED &&
            abs(actual - requested) < NATIVE_SPEED_DEADBAND &&
            pitch.isFinite() && abs(pitch - 1f) <= 0.00001f && device.failOnUnsupported
    }

    fun plan(format: AudioFormat, minimumBytes: Int): AudioBufferPlan {
        require(format.sampleRate in 1..96000 && format.channels in 1..2) { "Invalid PCM output format" }
        val frameBytes = format.channels * 2
        val maxFrames = (format.sampleRate * MAX_BUFFER_MS.toLong() / 1000).toInt()
        val minimumFrames = if (minimumBytes > 0) ((minimumBytes.toLong() + frameBytes - 1) / frameBytes).toInt() else 0
        // Android documents a size greater than speed * minimum; the native route may require more.
        val rateMinimum = floor(minimumFrames * MAX_SPEED.toDouble()).toLong() + 1
        if (minimumBytes <= 0 || maxFrames <= 0 || rateMinimum > maxFrames) {
            throw AudioOutputPreparationException(OutputPreparationDiagnostic(OutputPreparationReason.BUFFER_LIMIT,
                format.sampleRate, format.channels, minimumFrames, maxFrames, 0, 0, maxFrames, MAX_SPEED))
        }
        val requested = maxOf(format.sampleRate * REQUESTED_BUFFER_MS / 1000, rateMinimum.toInt())
        return AudioBufferPlan(format.sampleRate, format.channels, minimumFrames, requested, requested * frameBytes, maxFrames)
    }

    fun open(format: AudioFormat, platform: AudioOutputPlatform, failure: (String, String) -> Unit,
        onHeadroomRetry: (OutputPreparationDiagnostic) -> Unit = {}): Pair<RateAudioTrack, AudioBufferPlan> {
        val plan = plan(format, platform.minimumBytes(format))
        try {
            return prepare(format, plan, platform, failure)
        } catch (error: AudioOutputPreparationException) {
            if (error.diagnostic.reason != OutputPreparationReason.RATE_UNSUPPORTED ||
                error.cause !is IllegalArgumentException || error.suppressed.isNotEmpty() || plan.frames == plan.maxFrames) throw error
            onHeadroomRetry(error.diagnostic)
            val bounded = plan.copy(frames = plan.maxFrames, bytes = plan.maxFrames * plan.channels * 2)
            try {
                return prepare(format, bounded, platform, failure)
            } catch (retry: AudioOutputPreparationException) {
                retry.addSuppressed(error)
                throw retry
            }
        }
    }

    private fun prepare(format: AudioFormat, plan: AudioBufferPlan, platform: AudioOutputPlatform,
        failure: (String, String) -> Unit): Pair<RateAudioTrack, AudioBufferPlan> {
        val device = try { platform.create(format, plan.bytes, failure) } catch (e: Exception) {
            throw AudioOutputPreparationException(OutputPreparationDiagnostic(OutputPreparationReason.TRACK_INITIALIZATION,
                plan.sampleRate, plan.channels, plan.minimumFrames, plan.frames, 0, 0, plan.maxFrames, MAX_SPEED), e)
        }
        var checkingSpeed = NOMINAL_SPEED
        fun diagnostic(reason: OutputPreparationReason): OutputPreparationDiagnostic {
            val initialized = device.initialized
            return OutputPreparationDiagnostic(reason, plan.sampleRate, plan.channels, plan.minimumFrames, plan.frames,
                if (initialized) device.bufferFrames else 0, if (initialized) device.capacityFrames else 0,
                plan.maxFrames, checkingSpeed, if (initialized) device.sampleRate else 0, if (initialized) device.channels else 0,
                if (initialized) device.speed else null, if (initialized) device.pitch else null,
                if (initialized) device.failOnUnsupported else null)
        }
        fun verify() {
            if (!device.initialized) throw AudioOutputPreparationException(diagnostic(OutputPreparationReason.TRACK_INITIALIZATION))
            if (device.sampleRate != plan.sampleRate || device.channels != plan.channels || !device.pcm16)
                throw AudioOutputPreparationException(diagnostic(OutputPreparationReason.FORMAT_MISMATCH))
            if (device.bufferFrames !in plan.minimumFrames..plan.maxFrames || device.capacityFrames !in device.bufferFrames..plan.maxFrames)
                throw AudioOutputPreparationException(diagnostic(OutputPreparationReason.BUFFER_LIMIT))
            if (device.isPlaying) throw AudioOutputPreparationException(diagnostic(OutputPreparationReason.PLAY_STATE))
        }
        try {
            verify()
            for (speed in floatArrayOf(MAX_SPEED, MIN_SPEED, NOMINAL_SPEED)) {
                checkingSpeed = speed
                device.setSpeed(speed)
                verify()
                if (!rateMatches(device, speed))
                    throw AudioOutputPreparationException(diagnostic(OutputPreparationReason.PARAMS_MISMATCH))
            }
            return device to plan
        } catch (e: Exception) {
            val error = if (e is AudioOutputPreparationException) e
                else AudioOutputPreparationException(diagnostic(OutputPreparationReason.RATE_UNSUPPORTED), e)
            try { device.close() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
    }
}
