package org.screencopy.receiver.protocol

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class Type(val number: Int) {
    HELLO(1), SESSION_REQUEST(3), SESSION_ACCEPT(4), REJECT(5),
    STREAM_CONFIG(6), STREAM_READY(7), VIDEO_FRAME(8), AUDIO_PACKET(9),
    STATUS(10), ERROR(11), PING(12), PONG(13), STOP(14), SESSION_END(15)
}

data class Message(
    val type: Type,
    val session: Long = 0,
    val track: Int = 0,
    val generation: Long = 0,
    val ptsUs: Long = -1,
    val flags: Int = 0,
    val payload: ByteArray
) {
    fun json(): JSONObject = StrictJson.decode(payload)
    companion object {
        fun json(type: Type, payload: JSONObject, session: Long = 0, track: Int = 0, generation: Long = 0) =
            Message(type, session, track, generation, payload = payload.toString().toByteArray(Charsets.UTF_8))
    }
}

object Wire {
    const val MAGIC = 0x53435059
    const val VERSION = 3
    const val MAX_VIDEO = 4 * 1024 * 1024
    const val MAX_AUDIO = 256 * 1024
    const val MAX_CONTROL = 65536

    fun read(input: InputStream): Message {
        val stream = DataInputStream(input)
        protocolRequire(stream.readInt() == MAGIC, "Invalid protocol magic")
        protocolRequire(stream.readUnsignedShort() == VERSION, "Unsupported protocol version", "VERSION")
        val number = stream.readUnsignedShort()
        val type = Type.entries.firstOrNull { it.number == number }
            ?: throw ProtocolException("PROTOCOL", "Unknown message type")
        val length = stream.readInt().toLong() and 0xffffffffL
        val flags = stream.readInt()
        val session = stream.readLong()
        val track = stream.readInt()
        val generation = stream.readInt().toLong() and 0xffffffffL
        val pts = stream.readLong()
        validate(type, length, flags, session, track, generation, pts)
        val payload = ByteArray(length.toInt())
        stream.readFully(payload)
        return Message(type, session, track, generation, pts, flags, payload).also {
            if (type != Type.VIDEO_FRAME && type != Type.AUDIO_PACKET) it.json()
        }
    }

    fun write(output: OutputStream, message: Message) {
        validate(message.type, message.payload.size.toLong(), message.flags, message.session, message.track, message.generation, message.ptsUs)
        val stream = DataOutputStream(output)
        stream.writeInt(MAGIC)
        stream.writeShort(VERSION)
        stream.writeShort(message.type.number)
        stream.writeInt(message.payload.size)
        stream.writeInt(message.flags)
        stream.writeLong(message.session)
        stream.writeInt(message.track)
        stream.writeInt(message.generation.toInt())
        stream.writeLong(message.ptsUs)
        stream.write(message.payload)
        stream.flush()
    }

    private fun validate(type: Type, length: Long, flags: Int, session: Long, track: Int, generation: Long, pts: Long) {
        val media = type == Type.VIDEO_FRAME || type == Type.AUDIO_PACKET
        val trackMessage = media || type == Type.STREAM_CONFIG || type == Type.STREAM_READY
        val limit = when (type) { Type.VIDEO_FRAME -> MAX_VIDEO; Type.AUDIO_PACKET -> MAX_AUDIO; else -> MAX_CONTROL }
        protocolRequire(length in 0..limit.toLong(), "Message length exceeds limit")
        protocolRequire(!media || length > 0, "Empty media packet")
        protocolRequire(session >= 0, "Invalid session ID")
        protocolRequire(!trackMessage || session > 0, "Track messages require a negotiated session")
        protocolRequire(flags == 0 || (type == Type.VIDEO_FRAME && flags in 1..7), "Reserved message flags")
        protocolRequire(if (trackMessage) track in 1..2 && generation in 1..Int.MAX_VALUE.toLong() else track == 0 && generation == 0L, "Invalid track/generation")
        protocolRequire(type != Type.VIDEO_FRAME || track == 1, "Video on non-video track")
        protocolRequire(type != Type.AUDIO_PACKET || track == 2, "Audio on non-audio track")
        protocolRequire(if (media) pts >= 0 else pts == -1L, "Invalid presentation timestamp")
    }
}
