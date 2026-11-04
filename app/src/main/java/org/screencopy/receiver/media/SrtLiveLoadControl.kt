package org.screencopy.receiver.media

import androidx.media3.exoplayer.LoadControl
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SrtLiveLoadControl(
    private val delegate: LoadControl,
    private val sourceOriginUs: () -> Long
) : LoadControl {
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
        delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline,
        mediaPeriodId: MediaPeriodId, bufferedDurationUs: Long): Boolean =
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldContinueLoading(withSourceDuration(parameters))

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
        delegate.shouldStartPlayback(withSourceDuration(parameters))

    private fun withSourceDuration(parameters: LoadControl.Parameters): LoadControl.Parameters {
        val origin = sourceOriginUs()
        if (origin <= 0) return parameters
        // The empty interval before the first capture timestamp contains no buffered media.
        return LoadControl.Parameters(parameters.playerId, parameters.timeline, parameters.mediaPeriodId,
            parameters.playbackPositionUs, (parameters.bufferedDurationUs - origin).coerceAtLeast(0),
            parameters.playbackSpeed, parameters.playWhenReady, parameters.rebuffering,
            parameters.targetLiveOffsetUs, parameters.lastRebufferRealtimeMs)
    }
}
