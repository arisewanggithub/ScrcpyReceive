package org.screencopy.receiver.media

/** Presentation metadata follows decoded PTS, not compressed input arrival or skipped pictures. */
internal class VideoRotationTimeline {
    private val changes = ArrayDeque<Pair<Long, Int>>()
    private var lastRotation = -1
    private var current = 0

    fun offer(pts: Long, rotation: Int) {
        require(rotation in listOf(0, 90, 180, 270)) { "Invalid video presentation rotation" }
        if (rotation != lastRotation) {
            if (changes.size >= 1024) throw PlaybackException("Video rotation metadata exceeded its bounded queue", "CONGESTION")
            changes.addLast(pts to rotation)
        }
        lastRotation = rotation
    }

    fun at(pts: Long): Int {
        while (changes.firstOrNull()?.first?.let { it <= pts } == true) current = changes.removeFirst().second
        return current
    }
}
