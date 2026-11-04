package org.screencopy.receiver.media

import kotlin.math.abs

data class AudioRegulationDiagnostic(
    val bufferedFrames: Int, val targetFrames: Int, val limitFrames: Int,
    val averageFrames: Double, val speed: Float, val sourceFrames: Long,
    val silenceFrames: Long, val droppedFrames: Long, val recoveries: Long
) {
    fun logMessage() = "event=AUDIO_REGULATION buffered_frames=$bufferedFrames target_frames=$targetFrames" +
        " limit_frames=$limitFrames average_frames=${averageFrames.toLong()} speed=$speed" +
        " source_frames=$sourceFrames inserted_silence_frames=$silenceFrames dropped_output_frames=$droppedFrames" +
        " source_recoveries=$recoveries"
}

internal data class AudioChunk(val samples: ShortArray, val offset: Int, val frames: Int, val ptsUs: Long)

/** Decoded FIFO only: compressed packets always retain their codec dependencies. */
internal class AudioRegulator(private val rate: Int, private val channels: Int) {
    private data class Entry(val samples: ShortArray, val slice: TrimmedAudio, var used: Int = 0)
    private val queue = ArrayDeque<Entry>()
    val targetFrames = rate * 50 / 1000
    private val startupLimit = targetFrames + rate * 10 / 1000
    private val playingLimit = targetFrames * 11 / 10 + rate * 60 / 1000
    var bufferedFrames = 0
        private set
    var started = false
        private set
    private var average = targetFrames.toDouble()
    private var lastCorrectionNs: Long? = null
    private var compensating = false
    private var empty = false
    private var source = 0L
    private var silence = 0L
    private var dropped = 0L
    private var recoveries = 0L
    private var speed = 1f

    init { require(rate in 1..96000 && channels in 1..2) }
    fun offer(samples: ShortArray, slice: TrimmedAudio) {
        require(slice.skipFrames >= 0 && slice.frames > 0 &&
            (slice.skipFrames.toLong() + slice.frames) * channels <= samples.size)
        queue.addLast(Entry(samples, slice))
        bufferedFrames += slice.frames
        val excess = (bufferedFrames - limit()).coerceAtLeast(0)
        if (excess > 0) {
            discard(excess)
            dropped += excess
            average = (average - excess).coerceAtLeast(0.0)
        }
        if (!started && bufferedFrames >= targetFrames) started = true
        if (started) average += (bufferedFrames - average) / 128
    }
    fun peek(maxFrames: Int): AudioChunk? {
        require(maxFrames > 0)
        if (!started || queue.isEmpty()) return null
        val first = queue.first()
        return AudioChunk(first.samples, (first.slice.skipFrames + first.used) * channels,
            minOf(maxFrames, first.slice.frames - first.used),
            first.slice.audiblePtsUs + first.used * 1_000_000L / rate)
    }
    fun startAfterPreroll() { started = true }
    fun take(maxFrames: Int): AudioChunk? = peek(maxFrames)?.also { discard(it.frames) }
    fun consumed(frames: Int) {
        require(frames in 1..bufferedFrames)
        discard(frames)
        sourceWritten(frames)
    }
    fun sourceWritten(frames: Int) {
        require(frames > 0)
        source += frames
        if (empty) { recoveries++; empty = false }
    }
    fun insertedSilence(frames: Int) {
        require(frames > 0)
        silence += frames
        if (started) {
            empty = true
            average += frames
        }
    }
    fun correction(nowNs: Long): Float? {
        val last = lastCorrectionNs
        if (last == null) { lastCorrectionNs = nowNs; return null }
        if (nowNs - last < 1_000_000_000L || !started) return null
        lastCorrectionNs = nowNs
        var difference = average - targetFrames
        val deadband = rate * (if (compensating) 1 else 4) / 1000.0
        if (abs(difference) < deadband || difference > 0 && bufferedFrames < targetFrames) difference = 0.0
        speed = (1.0 + difference / (4.0 * rate)).coerceIn(0.98, 1.02).toFloat()
        compensating = speed != 1f
        return speed
    }
    fun diagnostic() = AudioRegulationDiagnostic(bufferedFrames, targetFrames, limit(), average, speed,
        source, silence, dropped, recoveries)
    fun clear() {
        queue.clear()
        bufferedFrames = 0
        started = false
        average = targetFrames.toDouble()
        lastCorrectionNs = null
        compensating = false
        empty = false
        source = 0
        silence = 0
        dropped = 0
        recoveries = 0
        speed = 1f
    }
    private fun limit() = if (started) playingLimit else startupLimit
    private fun discard(frames: Int) {
        var remaining = frames
        while (remaining > 0) {
            val first = queue.first()
            val count = minOf(remaining, first.slice.frames - first.used)
            first.used += count
            remaining -= count
            bufferedFrames -= count
            if (first.used == first.slice.frames) queue.removeFirst()
        }
    }
}

/** Device frames include underrun silence; source presentation must not. */
internal class AudioPresentationTimeline(private val rate: Int) {
    private data class Span(val deviceEnd: Long, val sourceEnd: Long, val source: Boolean)
    private val spans = ArrayDeque<Span>()
    private var deviceWritten = 0L
    private var sourceWritten = 0L
    private var sourcePresented = 0L
    @Synchronized fun write(source: Boolean, channels: Int, frames: Int, operation: () -> Int): Int {
        val samples = operation()
        check(samples >= 0) { "AudioTrack write failed ($samples)" }
        check(samples <= frames * channels && samples % channels == 0) { "AudioTrack returned an invalid sample count" }
        if (samples > 0) submitted(samples / channels, source)
        return samples
    }
    @Synchronized fun presentedUs(position: () -> Long): Long = presentedUs(position())
    @Synchronized fun submitted(frames: Int, source: Boolean) {
        require(frames > 0)
        deviceWritten += frames
        if (source) sourceWritten += frames
        if (spans.lastOrNull()?.source == source) spans.removeLast()
        spans.addLast(Span(deviceWritten, sourceWritten, source))
    }
    @Synchronized fun presentedUs(deviceFrames: Long): Long {
        require(deviceFrames in 0..deviceWritten)
        while (spans.isNotEmpty() && spans.first().deviceEnd <= deviceFrames) {
            sourcePresented = maxOf(sourcePresented, spans.removeFirst().sourceEnd)
        }
        spans.firstOrNull()?.let {
            val current = if (it.source) it.sourceEnd - (it.deviceEnd - deviceFrames) else it.sourceEnd
            sourcePresented = maxOf(sourcePresented, current)
        }
        return sourcePresented / rate * 1_000_000 + sourcePresented % rate * 1_000_000 / rate
    }
    @Synchronized fun clear() {
        spans.clear()
        deviceWritten = 0
        sourceWritten = 0
        sourcePresented = 0
    }
}
