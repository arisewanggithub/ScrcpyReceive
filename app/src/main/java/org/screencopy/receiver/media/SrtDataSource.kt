package org.screencopy.receiver.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import org.screencopy.receiver.protocol.SrtTransport
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/** Unseekable live byte stream. SRT message boundaries/leftovers belong to the native transport. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class SrtDataSource private constructor(
    private val transport: SrtTransport,
    private val owner: Factory
) : BaseDataSource(true) {
    private val lock = Any()
    private var input: SrtHandle? = null
    private var cancelled = false
    private var uri: Uri? = null
    private var opened = false
    private var openingAttempted = false

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.position != 0L || dataSpec.uri.toString() != transport.url)
            throw IOException("SRT live input is unseekable and pinned to the accepted peer")
        transferInitializing(dataSpec)
        val handle = synchronized(lock) {
            if (cancelled) throw IOException("SRT input closed")
            if (openingAttempted) throw IOException("SRT live input cannot reopen or seek")
            openingAttempted = true
            SrtHandle(SrtNative.createCaller(transport.streamId, transport.latencyMs)).also { input = it }
        }
        try {
            // Handle is published before this potentially blocking operation, so close cancels connect.
            val address = URI(transport.url)
            SrtNative.connect(handle.get(), address.host.removeSurrounding("[", "]"), address.port, 5000)
            synchronized(lock) {
                if (cancelled) throw IOException("SRT input closed")
                uri = dataSpec.uri
                opened = true
                transferStarted(dataSpec)
            }
            return C.LENGTH_UNSET.toLong()
        } catch (e: Exception) {
            owner.failure("OPEN", e, handle)
            close()
            throw e
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val handle = synchronized(lock) { input ?: throw IOException("SRT input not open") }
        val begin = System.nanoTime()
        owner.observeReadStart(begin)
        owner.readCalls.incrementAndGet()
        val count = try { SrtNative.read(handle.get(), buffer, offset, length) }
        catch (e: IOException) { owner.failure("READ", e, handle); throw e }
        owner.completedReads.incrementAndGet()
        owner.observeReadEnd(begin, System.nanoTime())
        if (count == -1) {
            owner.failure("EOF", IOException("SRT input EOF"), handle)
            return C.RESULT_END_OF_INPUT
        }
        if (count <= 0) {
            val error = IOException("SRT read made no progress")
            owner.failure("READ", error, handle)
            throw error
        }
        bytesTransferred(count)
        owner.receivedBytes.addAndGet(count.toLong())
        return count
    }

    override fun getUri(): Uri? = synchronized(lock) { uri }
    internal fun diagnosticHandle(): SrtHandle? = synchronized(lock) { input }

    override fun close() {
        val handle = synchronized(lock) {
            cancelled = true
            input.also { input = null; uri = null }
        }
        owner.recordClose(handle)
        try { handle?.close() } finally {
            synchronized(lock) {
                if (opened) { opened = false; transferEnded() }
            }
            owner.retire(this)
        }
    }

    class Factory(private val transport: SrtTransport) : DataSource.Factory, AutoCloseable {
        internal val readCalls = AtomicLong()
        internal val completedReads = AtomicLong()
        internal val receivedBytes = AtomicLong()
        @Volatile internal var maxReadWaitUs = 0L
            private set
        @Volatile internal var maxReadCallGapUs = 0L
            private set
        private var lastReadEndNs: Long? = null
        internal fun observeReadStart(nowNs: Long) {
            lastReadEndNs?.let { maxReadCallGapUs = maxOf(maxReadCallGapUs, (nowNs - it) / 1000) }
        }
        internal fun observeReadEnd(startNs: Long, endNs: Long) {
            maxReadWaitUs = maxOf(maxReadWaitUs, (endNs - startNs) / 1000)
            lastReadEndNs = endNs
        }
        private val lock = Any()
        private var closed = false
        private var diagnostics: SrtSessionDiagnostics? = null
        @Volatile private var closeOwner: SrtCloseOwner? = null
        @Volatile private var failed = false
        internal fun attach(value: SrtSessionDiagnostics) { diagnostics = value }
        private fun snapshot(handle: SrtHandle?): String = try {
            handle?.snapshot() ?: "snapshot=unavailable snapshot_unavailable=no_socket state=UNKNOWN last_peer_response_ms=unavailable"
        } catch (_: IOException) {
            "snapshot=unavailable snapshot_unavailable=query_error state=UNKNOWN last_peer_response_ms=unavailable"
        }
        internal fun failure(stage: String, error: Throwable, handle: SrtHandle) {
            failed = true
            val record = diagnostics ?: return
            if (record.hasFailure()) return
            val evidence = if (SrtFailureDetails.chain(error).any { it.message.orEmpty().contains(" | snapshot ") }) error
                else IOException("SRT input evidence | snapshot ${snapshot(handle)}", error)
            record.failure(stage, evidence)
        }
        internal fun recordClose(handle: SrtHandle?) {
            diagnostics?.close(closeOwner ?: if (failed) SrtCloseOwner.ERROR else SrtCloseOwner.LOADER_CANCEL,
                snapshot(handle), readCalls.get(), completedReads.get(), receivedBytes.get())
        }
        internal fun markClose(owner: SrtCloseOwner) {
            val input = synchronized(lock) {
                if (closeOwner == null) closeOwner = owner
                inputs.firstOrNull()
            }
            recordClose(input?.diagnosticHandle())
        }
        private var created = false
        private val inputs = mutableSetOf<SrtDataSource>()
        override fun createDataSource(): SrtDataSource = synchronized(lock) {
            if (closed) throw IOException("SRT session closed")
            if (created) throw IOException("SRT live input cannot reconnect; start a new control session")
            created = true
            SrtDataSource(transport, this).also { inputs.add(it) }
        }
        internal fun retire(input: SrtDataSource) { synchronized(lock) { inputs.remove(input) } }
        override fun close() = close(SrtCloseOwner.SURFACE_RETIRED)
        internal fun close(owner: SrtCloseOwner) {
            val previous = synchronized(lock) {
                if (closeOwner == null) closeOwner = owner
                closed = true
                inputs.toList().also { inputs.clear() }
            }
            recordClose(previous.firstOrNull()?.diagnosticHandle())
            previous.forEach { it.close() }
        }
    }
}
