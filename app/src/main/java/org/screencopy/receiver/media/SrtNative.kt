package org.screencopy.receiver.media

import java.util.concurrent.atomic.AtomicLong

object SrtNative {
    init {
        check(org.screencopy.receiver.BuildConfig.SRT_TRIAL) { "SRT is disabled in this APK" }
        System.loadLibrary("screencopy_srt")
    }

    external fun listen(address: String, expectedPeer: String, streamId: String, latencyMs: Int): Long
    external fun localPort(handle: Long): Int
    external fun awaitCaller(handle: Long, timeoutMs: Int): Boolean
    external fun createCaller(streamId: String, latencyMs: Int): Long
    external fun connect(handle: Long, address: String, port: Int, timeoutMs: Int)
    external fun read(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int
    external fun write(handle: Long, bytes: ByteArray, offset: Int, length: Int)
    external fun close(handle: Long)
    external fun snapshot(handle: Long): String
    external fun version(): String
}

internal class SrtHandle(handle: Long) : AutoCloseable {
    private val owned = AtomicLong(handle.also {
        if (it <= 0L) throw java.io.IOException("SRT native transport returned an invalid handle")
    })
    fun get(): Long = owned.get().also { if (it == 0L) throw java.io.IOException("SRT input closed") }
    fun snapshot(): String = SrtNative.snapshot(owned.get())
    override fun close() {
        val previous = owned.getAndSet(0)
        if (previous != 0L) SrtNative.close(previous)
    }
}
