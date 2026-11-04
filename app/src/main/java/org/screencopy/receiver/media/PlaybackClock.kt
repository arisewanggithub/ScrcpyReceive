package org.screencopy.receiver.media

class PlaybackClock(
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleepMs: (Long) -> Unit = Thread::sleep
) {
    private var basePtsUs = -1L
    private var baseNs = 0L
    private var audioPositionUs: (() -> Long)? = null
    private var audioSource: AudioSourceClock? = null
    @Volatile var audioExpected = false
    private var firstVideoPtsUs: Long? = null
    private var startupAtNs: Long? = null
    private var audioStarted = false
    private var prerollEpochUs: Long? = null
    private var videoPrerollEnded = false

    @Synchronized
    fun anchorVideo(ptsUs: Long) {
        if (firstVideoPtsUs == null) {
            firstVideoPtsUs = ptsUs
            if (audioExpected && !audioStarted && prerollEpochUs?.let { ptsUs > it } == true) {
                prerollEpochUs = ptsUs
                basePtsUs = -1
            }
        }
        noteMedia()
        if (!audioExpected) anchor(ptsUs)
    }

    @Synchronized
    fun noteAudioPacket() = noteMedia()

    @Synchronized
    fun checkStartup() {
        if (audioExpected && !audioStarted && startupAtNs?.let { nanoTime() - it >= 3_000_000_000L } == true)
            throw PlaybackException("Audio/video startup did not complete within three seconds")
    }

    @Synchronized
    fun prepareAudio(slice: TrimmedAudio, rate: Int): TrimmedAudio? {
        if (!audioExpected || audioStarted) return slice
        checkStartup()
        val videoPts = firstVideoPtsUs ?: return null
        val epoch = maxOf(videoPts, slice.audiblePtsUs)
        if (epoch - minOf(videoPts, slice.audiblePtsUs) > 3_000_000)
            throw PlaybackException("Initial track timestamps exceed the three-second synchronization budget")
        val skip = minOf(slice.frames.toLong(), ((epoch - slice.audiblePtsUs) * rate + 999_999) / 1_000_000).toInt()
        val result = TrimmedAudio(slice.skipFrames + skip, slice.frames - skip, slice.audiblePtsUs + skip * 1_000_000L / rate)
        prerollEpochUs = epoch
        if (result.frames > 0 && (basePtsUs < 0 || basePtsUs != result.audiblePtsUs)) {
            basePtsUs = result.audiblePtsUs
            baseNs = nanoTime() + 50_000_000L
        }
        return result
    }

    @Synchronized
    fun canPrepareAudio(): Boolean = !audioExpected || firstVideoPtsUs != null || audioStarted

    @Synchronized
    fun videoDelayUs(ptsUs: Long): Long? {
        checkStartup()
        require(ptsUs >= 0) { "Negative presentation timestamp" }
        return 0
    }

    @Synchronized
    fun endVideoPreroll() { videoPrerollEnded = true }

    @Synchronized
    fun audioStartDelayUs(ptsUs: Long): Long {
        anchor(ptsUs)
        val delay = Math.subtractExact(ptsUs, checkNotNull(currentUs()))
        if (delay > 3_000_000) throw PlaybackException("Audio startup exceeds the three-second synchronization budget")
        if (delay < -250_000) throw PlaybackException("Audio arrived over 250 ms behind the presentation clock")
        return delay
    }

    fun awaitAudioStart(ptsUs: Long, running: () -> Boolean): Boolean {
        val started = nanoTime()
        while (running()) {
            synchronized(this) {
                if (audioExpected && !audioStarted && (basePtsUs < 0 || prerollEpochUs?.let { ptsUs < it } == true))
                    return false
            }
            if (audioStartDelayUs(ptsUs) <= 0) return true
            if (nanoTime() - started >= 3_000_000_000L)
                throw PlaybackException("Audio startup did not complete within three seconds")
            sleepMs(2)
        }
        return false
    }

    @Synchronized
    internal fun anchorAudio(ptsUs: Long, sampleRate: Int = 48000, positionUs: () -> Long) {
        check(audioStartDelayUs(ptsUs) <= 0) { "Audio cannot start before its source presentation time" }
        audioPositionUs = positionUs
        audioSource = AudioSourceClock(ptsUs, sampleRate)
        audioStarted = true
    }

    @Synchronized
    internal fun audioPacket(sampleUs: Long, ptsUs: Long): AudioClockCorrection? {
        val source = checkNotNull(audioSource)
        source.current(checkNotNull(audioPositionUs).invoke())
        return source.packet(sampleUs, ptsUs)
    }

    @Synchronized
    internal fun audioWritten(sampleUs: Long) { checkNotNull(audioSource).written(sampleUs) }

    internal fun monotonicNs(): Long = nanoTime()

    @Synchronized
    fun detachAudio() {
        try {
            audioPositionUs?.let {
                basePtsUs = checkNotNull(audioSource).current(it())
                baseNs = nanoTime()
            }
        } finally {
            audioPositionUs = null
            audioSource = null
        }
    }

    @Synchronized
    fun currentUs(): Long? {
        audioPositionUs?.let { return checkNotNull(audioSource).current(it()) }
        if (basePtsUs < 0) return null
        return basePtsUs + (nanoTime() - baseNs) / 1000
    }

    fun delayUs(ptsUs: Long): Long? = currentUs()?.let { ptsUs - it }

    private fun anchor(ptsUs: Long) {
        require(ptsUs >= 0) { "Negative presentation timestamp" }
        val now = nanoTime()
        if (basePtsUs < 0) {
            basePtsUs = ptsUs
            baseNs = now
        } else if (audioPositionUs == null && now < baseNs && ptsUs < basePtsUs) {
            basePtsUs = ptsUs
        }
    }

    private fun noteMedia() {
        if (startupAtNs == null) startupAtNs = nanoTime()
    }

}
