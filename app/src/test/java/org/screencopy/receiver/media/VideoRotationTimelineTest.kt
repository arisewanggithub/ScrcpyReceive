package org.screencopy.receiver.media

import org.junit.Assert.*
import org.junit.Test

class VideoRotationTimelineTest {
    @Test fun skippedDecodedPicturesAndRepeatedPtsKeepTheLatestApplicableRotation() {
        val timeline = VideoRotationTimeline()
        timeline.offer(100, 0); timeline.offer(200, 90); timeline.offer(300, 180); timeline.offer(400, 270)
        assertEquals(0, timeline.at(100))
        assertEquals(180, timeline.at(350))
        assertEquals(180, timeline.at(350))
        assertEquals(270, timeline.at(400))
        assertEquals(270, timeline.at(500))
    }
    @Test fun unchangedRotationDoesNotConsumeUnboundedMetadataStorage() {
        val timeline = VideoRotationTimeline()
        repeat(10_000) { timeline.offer(it.toLong(), 90) }
        assertEquals(90, timeline.at(10_000))
        val bounded = VideoRotationTimeline()
        repeat(1024) { bounded.offer(it.toLong(), it % 4 * 90) }
        assertEquals("CONGESTION", assertThrows(PlaybackException::class.java) { bounded.offer(1024, 0) }.code)
    }
}
