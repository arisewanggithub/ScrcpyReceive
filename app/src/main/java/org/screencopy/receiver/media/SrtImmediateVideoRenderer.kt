package org.screencopy.receiver.media

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class,
    androidx.media3.common.util.ExperimentalApi::class])
internal class SrtImmediateVideoRenderer(context: Context, handler: Handler, listener: VideoRendererEventListener,
    private val sourceOriginUs: () -> Long = { 0L }) :
    MediaCodecVideoRenderer(Builder(context).setAllowedJoiningTimeMs(0).setEventHandler(handler).setEventListener(listener)) {
    init { experimentalDisableAdvancingTimestampChecksInVideoFrameReleaseControl() }

    override fun shouldForceRenderOutputBuffer(earlyUs: Long, elapsedSinceLastRenderUs: Long): Boolean {
        val relativeEarlyUs = earlyUs - sourceOriginUs()
        return !super.shouldDropOutputBuffer(relativeEarlyUs, SystemClock.elapsedRealtime() * 1000, false) ||
            super.shouldForceRenderOutputBuffer(relativeEarlyUs, elapsedSinceLastRenderUs)
    }

    override fun shouldDropOutputBuffer(earlyUs: Long, elapsedRealtimeUs: Long, isLastBuffer: Boolean): Boolean =
        super.shouldDropOutputBuffer(earlyUs - sourceOriginUs(), elapsedRealtimeUs, isLastBuffer)

    // Catch-up may supersede decoded surface pictures, never bypass compressed dependencies.
    override fun shouldDropBuffersToKeyframe(earlyUs: Long, elapsedRealtimeUs: Long, isLastBuffer: Boolean) = false
    override fun shouldSkipBuffersWithIdenticalReleaseTime() = false
}
