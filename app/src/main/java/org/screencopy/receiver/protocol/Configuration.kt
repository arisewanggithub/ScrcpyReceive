package org.screencopy.receiver.protocol

import org.json.JSONObject
import org.screencopy.receiver.media.AudioFormat
import org.screencopy.receiver.media.VideoFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

data class Request(val videoCodec: String, val audioCodec: String?, val fps: Int, val summary: String,
    val receiverOrientation: ReceiverOrientation = ReceiverOrientation.AUTO) {
    val tracks: List<Int> get() = if (audioCodec == null) listOf(1) else listOf(1, 2)

    companion object {
        fun parse(json: JSONObject): Request {
            protocolRequire(!json.has("mediaTransport"),
                "Only custom TCP media is supported; omit mediaTransport", "UNSUPPORTED_CONFIG")
            val receiverOrientation = if (json.has("receiverOrientation")) ReceiverOrientation.parse(json.opt("receiverOrientation"))
                else ReceiverOrientation.AUTO
            val video = json.obj("video")
            val codec = video.string("codec")
            protocolRequire(codec in listOf("h264", "h265", "av1"), "Unsupported video codec", "UNSUPPORTED_CONFIG")
            video.string("encoder", 256)
            protocolRequire(video.integer("bitrate") > 0, "Invalid video bitrate")
            protocolRequire(video.integer("maxSize") in 0..16384, "Invalid maxSize")
            val fps = video.integer("maxFps")
            protocolRequire(fps in 1..240, "Invalid maxFps")
            protocolRequire(video.integer("displayId") >= 0, "Invalid displayId")
            protocolRequire(video.string("orientation") in listOf("0", "90", "180", "270", "@0", "@90", "@180", "@270"), "Invalid orientation")
            video.string("codecOptions", 8192)
            protocolRequire(video.has("crop"), "Missing crop")
            if (!video.isNull("crop")) {
                val crop = video.obj("crop")
                protocolRequire(crop.integer("width") > 0 && crop.integer("height") > 0 &&
                    crop.integer("x") >= 0 && crop.integer("y") >= 0, "Invalid crop")
            }
            val audio = json.obj("audio")
            val enabled = audio.boolean("enabled")
            val audioCodec = audio.string("codec")
            protocolRequire(audioCodec in listOf("opus", "aac", "raw"), "Unsupported audio codec", "UNSUPPORTED_CONFIG")
            audio.string("encoder", 256)
            protocolRequire(audio.integer("bitrate") > 0, "Invalid audio bitrate")
            audio.string("source", 80)
            audio.string("codecOptions", 8192)
            return Request(codec, if (enabled) audioCodec else null, fps,
                "Requested $codec ${video.integer("bitrate")} bps, max ${video.integer("maxSize")} px, $fps fps; " +
                    (if (enabled) "audio $audioCodec ${audio.integer("bitrate")} bps" else "audio disabled"),
                receiverOrientation)
        }
    }
}

object Configuration {
    fun video(json: JSONObject, requested: Request): VideoFormat {
        val codec = json.string("codec")
        protocolRequire(codec == requested.videoCodec, "Actual video codec differs from accepted settings", "UNSUPPORTED_CONFIG")
        val width = json.integer("width")
        val height = json.integer("height")
        protocolRequire(width in 1..16384 && height in 1..16384, "Invalid actual video dimensions")
        val csd = csd(json)
        protocolRequire(csd.isNotEmpty() && csd.all { it.isNotEmpty() }, "Missing video codec configuration")
        if (codec != "av1") protocolRequire(csd.all { annexB(it) }, "Video codec configuration must be Annex B")
        return VideoFormat(mime(codec), width, height, csd, requested.fps)
    }

    fun audio(json: JSONObject, requested: Request): AudioFormat {
        val codec = json.string("codec")
        protocolRequire(codec == requested.audioCodec, "Actual audio codec differs from accepted settings", "UNSUPPORTED_CONFIG")
        val rate = json.integer("sampleRate")
        val channels = json.integer("channels")
        protocolRequire(rate == 48000 && channels in 1..2, "Unsupported audio rate or channels", "UNSUPPORTED_CONFIG")
        val csd = csd(json)
        return when (codec) {
            "opus" -> {
                protocolRequire(csd.size == 1, "Opus requires exactly one OpusHead")
                val header = opusHead(csd[0], channels)
                protocolRequire(json.integer("codecDelayNs").toLong() == header.first * 1_000_000_000L / 48000, "Opus delay does not match pre-skip")
                protocolRequire(json.integer("seekPrerollNs") == 80_000_000, "Invalid Opus seek preroll")
                AudioFormat(codec, rate, channels, csd[0], header.first, header.second)
            }
            "aac" -> {
                protocolRequire(channels == 2 && csd.size == 1 && csd[0].isNotEmpty(), "AAC requires stereo and AudioSpecificConfig", "UNSUPPORTED_CONFIG")
                val actual = AacSpecificConfig.parse(csd[0])
                protocolRequire(actual.sampleRate == rate && actual.channels == channels, "AAC ASC differs from actual metadata", "UNSUPPORTED_CONFIG")
                AudioFormat(codec, rate, channels, csd[0])
            }
            "raw" -> {
                protocolRequire(channels == 2 && csd.isEmpty() && json.string("sampleFormat") == "s16le", "Raw audio requires stereo s16le", "UNSUPPORTED_CONFIG")
                AudioFormat("pcm", rate, channels, ByteArray(0))
            }
            else -> throw ProtocolException("UNSUPPORTED_CONFIG", "Unsupported audio codec")
        }
    }

    fun opusHead(bytes: ByteArray, channels: Int): Pair<Int, Int> {
        protocolRequire(bytes.size == 19 && bytes.copyOfRange(0, 8).contentEquals("OpusHead".toByteArray(Charsets.US_ASCII)), "Expected canonical 19-byte OpusHead")
        protocolRequire(bytes[8].toInt() == 1 && bytes[9].toInt() == channels && bytes[18].toInt() == 0, "Unsupported Opus version/channels/mapping")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return (buffer.getShort(10).toInt() and 0xffff) to buffer.getShort(16).toInt()
    }

    fun mime(codec: String) = when (codec) {
        "h264" -> "video/avc"
        "h265" -> "video/hevc"
        "av1" -> "video/av01"
        else -> throw ProtocolException("UNSUPPORTED_CONFIG", "Unsupported video codec")
    }

    fun annexB(bytes: ByteArray) = bytes.size >= 4 &&
        bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
        (bytes[2] == 1.toByte() || bytes[2] == 0.toByte() && bytes[3] == 1.toByte())

    private fun csd(json: JSONObject): List<ByteArray> {
        val array = json.array("csd")
        protocolRequire(array.length() <= 8, "Too many codec configuration buffers")
        return (0 until array.length()).map {
            val value = array.opt(it)
            protocolRequire(value is String, "Codec configuration must be Base64 strings")
            base64(value as String)
        }
    }

    private fun base64(value: String): ByteArray {
        protocolRequire(value.length % 4 == 0 && value.matches(Regex("[A-Za-z0-9+/]*={0,2}")), "Invalid canonical Base64")
        val result = try { Base64.getDecoder().decode(value) } catch (_: IllegalArgumentException) {
            throw ProtocolException("PROTOCOL", "Invalid Base64")
        }
        protocolRequire(Base64.getEncoder().encodeToString(result) == value, "Noncanonical Base64")
        return result
    }
}

class TrackState(private val tracks: List<Int>) {
    private val generations = mutableMapOf<Int, Long>()
    private val timestamps = mutableMapOf<Int, Long>()
    private val ready = mutableSetOf<Int>()
    private val codecs = mutableMapOf<Int, String>()
    private var initialReady = false

    fun allReady(): Boolean = ready.containsAll(tracks)

    fun configure(track: Int, generation: Long, codec: String? = null) {
        protocolRequire(track in tracks, "Track is not enabled")
        protocolRequire(generation == (generations[track] ?: 0) + 1, "Stale or skipped track generation")
        generations[track] = generation
        if (codec == null) codecs.remove(track) else codecs[track] = codec
        ready.remove(track)
        timestamps.remove(track)
    }

    fun ready(track: Int) {
        protocolRequire(generations.containsKey(track), "Track not configured")
        ready.add(track)
        if (ready.containsAll(tracks)) initialReady = true
    }

    fun media(message: Message) {
        protocolRequire(initialReady && message.track in ready, "Media arrived before all initial tracks were ready")
        protocolRequire(generations[message.track] == message.generation, "Stale media generation")
        protocolRequire(message.ptsUs >= 0, "Negative media PTS")
        val opus = message.type == Type.AUDIO_PACKET && message.track == 2 && codecs[message.track] == "opus"
        if (!opus)
            protocolRequire(message.ptsUs >= (timestamps[message.track] ?: 0), "Decreasing media PTS")
        timestamps[message.track] = message.ptsUs
    }
}
