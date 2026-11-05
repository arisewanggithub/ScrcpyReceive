package org.screencopy.receiver.media

data class VideoFormat(
    val mime: String,
    val width: Int,
    val height: Int,
    val configuration: List<ByteArray>,
    val frameRate: Int,
    val rotation: Int = 0
)

data class AudioFormat(
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val configuration: ByteArray,
    val preSkip: Int = 0,
    val outputGainQ8: Int = 0
)

data class AccessUnit(val ptsUs: Long, val bytes: ByteArray, val keyFrame: Boolean = false, val rotation: Int = 0)

open class PlaybackException(message: String, val code: String = "DECODER_FAILED") : IllegalStateException(message)

enum class QueueLimit { PACKETS, BYTES, PTS }

data class QueuePressure(
    val track: Int,
    val limit: QueueLimit,
    val queuedPackets: Int,
    val queuedBytes: Int,
    val attemptedPackets: Int,
    val attemptedBytes: Long,
    val spanUs: Long,
    val maxPackets: Int,
    val maxBytes: Int,
    val maxSpanUs: Long,
    val waitedMs: Long = 0
) {
    fun logMessage(): String = "track=$track queue_limit=$limit queued_packets=$queuedPackets queued_bytes=$queuedBytes" +
        " attempted_packets=$attemptedPackets attempted_bytes=$attemptedBytes relative_pts_span_us=$spanUs" +
        " max_packets=$maxPackets max_bytes=$maxBytes max_span_us=$maxSpanUs wait_ms=$waitedMs"
}

class QueueCongestionException(val pressure: QueuePressure) :
    PlaybackException("Playback admission stalled: ${pressure.logMessage()}", "CONGESTION")

/** Duration and byte limits apply as well as a packet count limit. */
class PacketBudget(
    private val maxBytes: Int,
    private val maxDurationUs: Long
) {
    private val entries = ArrayDeque<Pair<Long, Int>>()
    private var bytes = 0

    @Synchronized
    fun add(packet: AccessUnit) {
        pressure(packet)?.let { throw QueueCongestionException(it) }
        entries.addLast(packet.ptsUs to packet.bytes.size)
        bytes += packet.bytes.size
    }

    @Synchronized
    fun pressure(packet: AccessUnit, track: Int = 0, maxPackets: Int = Int.MAX_VALUE, waitedMs: Long = 0): QueuePressure? {
        require(packet.ptsUs >= 0) { "Negative presentation timestamp" }
        val minimum = minOf(entries.minOfOrNull { it.first } ?: packet.ptsUs, packet.ptsUs)
        val maximum = maxOf(entries.maxOfOrNull { it.first } ?: packet.ptsUs, packet.ptsUs)
        val attemptedBytes = bytes.toLong() + packet.bytes.size
        val limit = when {
            entries.size >= maxPackets -> QueueLimit.PACKETS
            attemptedBytes > maxBytes -> QueueLimit.BYTES
            maximum - minimum > maxDurationUs -> QueueLimit.PTS
            else -> return null
        }
        return QueuePressure(track, limit, entries.size, bytes, entries.size + 1, attemptedBytes,
            maximum - minimum, maxPackets, maxBytes, maxDurationUs, waitedMs)
    }

    @Synchronized
    fun remove() {
        bytes -= entries.removeFirst().second
    }

    @Synchronized
    fun clear() { entries.clear(); bytes = 0 }
}
