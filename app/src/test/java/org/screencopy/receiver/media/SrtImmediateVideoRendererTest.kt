package org.screencopy.receiver.media

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.video.VideoSink
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SrtImmediateVideoRendererTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun control(evaluator: VideoFrameReleaseControl.FrameTimingEvaluator) =
        VideoFrameReleaseControl(context, evaluator, 0).apply {
            setRequiresOutputSurface(false)
            onStreamChanged(VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY)
        }
    private fun action(control: VideoFrameReleaseControl, pts: Long, position: Long, decodeOnly: Boolean = false,
        last: Boolean = false) =
        control.getFrameReleaseAction(pts, position, SystemClock.elapsedRealtime() * 1000, 0, decodeOnly, last,
            16_666_667, 1, VideoFrameReleaseControl.FrameReleaseInfo())

    private fun renderer(originUs: Long = 0) = SrtImmediateVideoRenderer(context,
        Handler(Looper.getMainLooper()), object : VideoRendererEventListener {}) { originUs }

    private fun productionControl(renderer: SrtImmediateVideoRenderer) =
        ReflectionHelpers.getField<VideoFrameReleaseControl>(renderer, "videoFrameReleaseControl").apply {
            setRequiresOutputSurface(false)
            onStreamChanged(VideoSink.RELEASE_FIRST_FRAME_IMMEDIATELY)
        }

    private fun dropsCompressedDependencies(renderer: SrtImmediateVideoRenderer): Boolean =
        ReflectionHelpers.callInstanceMethod(renderer, "shouldDropBuffersToKeyframe",
            ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE, -5_000_000L),
            ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE, 0L),
            ReflectionHelpers.ClassParameter.from(java.lang.Boolean.TYPE, false))

    private fun start(control: VideoFrameReleaseControl, origin: Long = 0) {
        assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY, action(control, origin, 0))
        control.onFrameReleasedIsFirstFrame()
        control.onStarted()
        assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY, action(control, origin + 16667, 0))
        control.onFrameReleasedIsFirstFrame()
    }

    @Test fun frameTimestampSubstitutedForPositionFreezesTheFirstPostStartFrame() {
        val evaluator = object : VideoFrameReleaseControl.FrameTimingEvaluator {
            override fun shouldForceReleaseFrame(earlyUs: Long, elapsedSinceLastReleaseUs: Long) = false
            override fun shouldDropFrame(earlyUs: Long, elapsedRealtimeUs: Long, isLastFrame: Boolean) = false
            override fun shouldIgnoreFrame(earlyUs: Long, positionUs: Long, elapsedRealtimeUs: Long,
                isLastFrame: Boolean, treatDroppedBuffersAsSkipped: Boolean) = false
        }
        val control = control(evaluator)
        assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY, action(control, 0, 0))
        control.onFrameReleasedIsFirstFrame()
        control.onStarted()
        repeat(5) {
            assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_TRY_AGAIN_LATER, action(control, 16667, 16667))
        }
    }

    @Test fun immediateHookReleasesFollowingFramesWithoutFakingClockProgress() {
        val renderer = renderer()
        try {
            val control = productionControl(renderer)
            assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY, action(control, 0, 0))
            control.onFrameReleasedIsFirstFrame()
            control.onStarted()
            repeat(30) {
                assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY,
                    action(control, (it + 1) * 16667L, 0))
                control.onFrameReleasedIsFirstFrame()
            }
            assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_SKIP, action(control, 600000, 0, decodeOnly = true))
            assertFalse(renderer.shouldIgnoreFrame(-5_000_000, 0, 0, false, true))
            assertFalse(dropsCompressedDependencies(renderer))
        } finally { renderer.release() }
    }

    @Test fun catchupDropsLateDecodedPicturesAndImmediatelyReleasesTheCurrentPicture() {
        for (origin in listOf(0L, 1_250_000L)) {
            val renderer = renderer(origin)
            try {
                val control = productionControl(renderer)
                start(control, origin)
                for (pts in listOf(100_000L, 200_000L, 300_000L)) {
                    assertEquals("Stale output was forced to the Surface instead of dropped (origin=$origin)",
                        VideoFrameReleaseControl.FRAME_RELEASE_DROP, action(control, origin + pts, 500_000))
                }
                assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY,
                    action(control, origin + 500_000, 500_000))
                assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_SKIP,
                    action(control, origin + 600_000, 500_000, decodeOnly = true))
                assertFalse(dropsCompressedDependencies(renderer))
            } finally { renderer.release() }
        }
    }

    @Test fun sparseLatePictureStillPresentsAndDoesNotDisableFollowingCatchupDrops() {
        val renderer = renderer()
        try {
            val control = productionControl(renderer)
            start(control)
            ShadowSystemClock.advanceBy(Duration.ofMillis(101))
            assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_IMMEDIATELY, action(control, 100_000, 500_000))
            control.onFrameReleasedIsFirstFrame()
            assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_DROP, action(control, 200_000, 500_000))
        } finally { renderer.release() }
    }

    @Test fun nativeLatenessAndSparseRefreshBoundariesArePreservedWithoutDroppingTheLastPicture() {
        val renderer = renderer()
        try {
            assertTrue(renderer.shouldForceReleaseFrame(-30_000, 0))
            assertFalse(renderer.shouldForceReleaseFrame(-30_001, 100_000))
            assertTrue(renderer.shouldForceReleaseFrame(-30_001, 100_001))
            assertFalse(renderer.shouldDropFrame(-30_000, 0, false))
            assertTrue(renderer.shouldDropFrame(-30_001, 0, false))
            assertFalse(renderer.shouldDropFrame(-30_001, 0, true))
            val control = productionControl(renderer)
            start(control)
            assertEquals(VideoFrameReleaseControl.FRAME_RELEASE_SCHEDULED,
                action(control, 100_000, 500_000, last = true))
        } finally { renderer.release() }
    }
}
