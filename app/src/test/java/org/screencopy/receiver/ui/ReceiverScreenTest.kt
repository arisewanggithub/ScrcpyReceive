package org.screencopy.receiver.ui

import org.junit.Assert.*
import org.junit.Test

class ReceiverScreenTest {
    private fun ready() = ReceiverScreen().reduce(ReceiverScreenEvent.Listener(listOf("192.168.1.3:46756", "10.0.0.5:46756")))

    @Test fun idleProjectionShowsActualAddressesAndReadinessWithoutPublicIdentifiers() {
        val state = ready()
        assertEquals(ScreenPhase.READY, state.phase)
        assertEquals("192.168.1.3 : 46756\n10.0.0.5 : 46756", state.addressText)
        assertEquals(listOf("192.168.1.3:46756", "10.0.0.5:46756"), state.addresses)
        assertTrue(state.ready)
        assertTrue(state.showWaiting)
        assertTrue(state.canExit)
        assertNull(state.problem)
    }

    @Test fun connectionAndTrackInitializationCannotHideWaitingScreen() {
        val state = ready().reduce(ReceiverScreenEvent.Connecting)
        assertEquals(ScreenPhase.CONNECTING, state.phase)
        assertTrue(state.showWaiting)
        assertTrue(state.reduce(ReceiverScreenEvent.CleanupFinished).showWaiting)
    }

    @Test fun onlyActualPresentedFrameAfterConnectionEntersOverlayFreePlayback() {
        assertTrue(ready().reduce(ReceiverScreenEvent.FramePresented).showWaiting)
        val playing = ready().reduce(ReceiverScreenEvent.Connecting).reduce(ReceiverScreenEvent.FramePresented)
        assertEquals(ScreenPhase.PLAYING, playing.phase)
        assertFalse(playing.showWaiting)
        assertFalse(playing.reduce(ReceiverScreenEvent.Notice("DISCOVERY")).showWaiting)
    }

    @Test fun senderStopReturnsReadyWithTheSameAddressesAfterCleanup() {
        val waiting = ready()
        val state = waiting.reduce(ReceiverScreenEvent.Connecting).reduce(ReceiverScreenEvent.FramePresented)
            .reduce(ReceiverScreenEvent.CleanupFinished)
        assertEquals(waiting, state)
    }

    @Test fun playbackErrorRemainsActionableAfterCleanupAndClearsOnTheNextAttempt() {
        val error = ready().reduce(ReceiverScreenEvent.Connecting).reduce(ReceiverScreenEvent.FramePresented)
            .reduce(ReceiverScreenEvent.Problem("AUDIO_FAILED"))
        assertTrue(error.showWaiting)
        assertEquals(ScreenPhase.ERROR, error.phase)
        val cleaned = error.reduce(ReceiverScreenEvent.CleanupFinished)
        assertEquals(ScreenPhase.READY, cleaned.phase)
        assertEquals("AUDIO_FAILED", cleaned.problem)
        assertNull(cleaned.reduce(ReceiverScreenEvent.Connecting).problem)
    }

    @Test fun unavailableNetworkAndStoppedListenerDoNotClaimReady() {
        val noNetwork = ready().reduce(ReceiverScreenEvent.Listener(emptyList()))
        assertFalse(noNetwork.ready)
        assertEquals(ScreenPhase.NOT_READY, noNetwork.phase)
        val stopped = noNetwork.reduce(ReceiverScreenEvent.ListenerStopped)
        assertFalse(stopped.reduce(ReceiverScreenEvent.FramePresented).ready)
    }

    @Test fun exitIsTerminalForLateListenerFrameAndCleanupCallbacks() {
        val exiting = ready().reduce(ReceiverScreenEvent.Exit)
        assertEquals(ScreenPhase.EXITING, exiting.phase)
        assertFalse(exiting.canExit)
        for (event in listOf(ReceiverScreenEvent.Listener(listOf("192.168.1.50:46756")),
            ReceiverScreenEvent.Connecting, ReceiverScreenEvent.FramePresented,
            ReceiverScreenEvent.CleanupFinished, ReceiverScreenEvent.Problem("TIMEOUT"), ReceiverScreenEvent.ListenerStopped)) {
            assertEquals(exiting, exiting.reduce(event))
        }
    }

    @Test fun listenerRestartRefreshesTheActualAddressesAndDeduplicatesInterfaces() {
        val state = ready().reduce(ReceiverScreenEvent.ListenerStopped)
            .reduce(ReceiverScreenEvent.Listener(listOf("192.168.1.21:54321", "192.168.1.21:54321")))
        assertEquals("192.168.1.21 : 54321", state.addressText)
    }

    @Test fun safeMarginsScaleForPortraitLandscapeTabletAndProjector() {
        assertEquals(ScreenMargins(24, 40), ReceiverLayout.margins(360, 800))
        assertEquals(ScreenMargins(40, 20), ReceiverLayout.margins(800, 360))
        assertEquals(ScreenMargins(64, 40), ReceiverLayout.margins(1280, 800))
        assertEquals(ScreenMargins(96, 54), ReceiverLayout.margins(1920, 1080))
        assertEquals(ScreenMargins(96, 72), ReceiverLayout.margins(3840, 2160))
    }
}
