package org.screencopy.receiver.ui

import org.screencopy.receiver.media.QueuePressure

enum class ScreenPhase { STARTING, READY, CONNECTING, PLAYING, ERROR, NOT_READY, EXITING }

sealed interface ReceiverScreenEvent {
    data class Listener(val addresses: List<String>) : ReceiverScreenEvent
    data object Connecting : ReceiverScreenEvent
    data object FramePresented : ReceiverScreenEvent
    data class Problem(val code: String, val reason: FailureReason? = null, val pressure: QueuePressure? = null) : ReceiverScreenEvent
    data class Notice(val code: String) : ReceiverScreenEvent
    data object CleanupFinished : ReceiverScreenEvent
    data object ListenerStopped : ReceiverScreenEvent
    data object Exit : ReceiverScreenEvent
}

data class ReceiverScreen(
    val phase: ScreenPhase = ScreenPhase.STARTING,
    val addresses: List<String> = emptyList(),
    val listenerOpen: Boolean = false,
    val problem: String? = null,
    val failureReason: FailureReason? = null,
    val pressure: QueuePressure? = null
) {
    val showWaiting: Boolean get() = phase != ScreenPhase.PLAYING
    val ready: Boolean get() = listenerOpen && addresses.isNotEmpty()
    val addressText: String get() = addresses.joinToString("\n") { it.replace(":", " : ") }
    val canExit: Boolean get() = phase != ScreenPhase.EXITING

    fun reduce(event: ReceiverScreenEvent): ReceiverScreen {
        if (phase == ScreenPhase.EXITING) return this
        return when (event) {
            is ReceiverScreenEvent.Listener -> {
                val endpoints = event.addresses.distinct()
                copy(addresses = endpoints, listenerOpen = true,
                    phase = when {
                        endpoints.isEmpty() -> ScreenPhase.NOT_READY
                        phase == ScreenPhase.PLAYING || phase == ScreenPhase.CONNECTING -> phase
                        else -> ScreenPhase.READY
                    })
            }
            ReceiverScreenEvent.Connecting -> if (ready) copy(phase = ScreenPhase.CONNECTING, problem = null, failureReason = null, pressure = null) else this
            ReceiverScreenEvent.FramePresented -> if (phase == ScreenPhase.CONNECTING && ready)
                copy(phase = ScreenPhase.PLAYING, problem = null, failureReason = null, pressure = null) else this
            is ReceiverScreenEvent.Problem -> if (event.code == "CLEANUP" && problem != null) this else
                copy(phase = ScreenPhase.ERROR, problem = event.code, failureReason = event.reason, pressure = event.pressure)
            is ReceiverScreenEvent.Notice -> if (phase == ScreenPhase.PLAYING || problem != null) this else copy(problem = event.code)
            ReceiverScreenEvent.CleanupFinished -> copy(phase = if (ready) ScreenPhase.READY else ScreenPhase.NOT_READY)
            ReceiverScreenEvent.ListenerStopped -> copy(phase = ScreenPhase.NOT_READY, listenerOpen = false)
            ReceiverScreenEvent.Exit -> copy(phase = ScreenPhase.EXITING, listenerOpen = false)
        }
    }
}

data class ScreenMargins(val horizontal: Int, val vertical: Int)

object ReceiverLayout {
    fun margins(widthDp: Int, heightDp: Int): ScreenMargins = ScreenMargins(
        (widthDp * 0.05f).toInt().coerceIn(24, 96),
        (heightDp * 0.05f).toInt().coerceIn(20, 72)
    )
}
