package org.screencopy.receiver.media

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

abstract class TrackWorker(
    name: String,
    maxBytes: Int,
    private val failure: (String, String) -> Unit,
    private val track: Int = 0
) : AutoCloseable {
    private val queue = ArrayDeque<AccessUnit>()
    private val monitor = Object()
    private val budget = PacketBudget(maxBytes, 250_000)
    protected val running = AtomicBoolean(true)
    private val thread = Thread({ runTrack() }, name)

    fun start() = thread.start()

    internal fun queuedPackets(): Int = synchronized(monitor) { queue.size }

    fun offer(packet: AccessUnit) {
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(250)
        synchronized(monitor) {
            while (true) {
                check(running.get()) { "Track decoder is stopped" }
                val pressure = budget.pressure(packet, track, 48, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                if (pressure == null) {
                    budget.add(packet)
                    queue.addLast(packet)
                    monitor.notifyAll()
                    return
                }
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0 || packet.bytes.size > pressure.maxBytes)
                    throw QueueCongestionException(pressure)
                TimeUnit.NANOSECONDS.timedWait(monitor, remaining)
            }
        }
    }

    protected fun next(): AccessUnit? = synchronized(monitor) {
        if (queue.isEmpty() && running.get()) monitor.wait(2)
        if (queue.isEmpty()) null else queue.removeFirst().also {
            budget.remove()
            monitor.notifyAll()
        }
    }

    private fun runTrack() {
        try {
            while (running.get()) step()
        } catch (e: InterruptedException) {
            if (running.get()) failure("DECODER_FAILED", "Playback interrupted unexpectedly")
        } catch (e: Exception) {
            if (running.get()) failure(if (e is PlaybackException) e.code else "DECODER_FAILED", describeFailure(e))
        }
    }

    protected abstract fun step()
    protected open fun describeFailure(error: Exception): String =
        if (error is AudioClockException) "Audio stage=OUTPUT_CLOCK exception=AudioClockException: ${error.message}"
        else "Playback failed: ${error.message ?: error.javaClass.simpleName}"
    protected abstract fun release()
    protected open fun unblock() {}

    fun halt() {
        if (!running.getAndSet(false)) return
        synchronized(monitor) { monitor.notifyAll() }
        try {
            unblock()
        } finally {
            thread.interrupt()
        }
    }

    final override fun close() {
        try {
            halt()
        } finally {
            try {
                if (Thread.currentThread() !== thread && thread.isAlive) {
                    thread.join(2000)
                    check(!thread.isAlive) { "Decoder did not stop within two seconds" }
                }
                release()
            } finally {
                synchronized(monitor) { queue.clear(); budget.clear(); monitor.notifyAll() }
            }
        }
    }
}
