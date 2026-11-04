package org.screencopy.receiver.media

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.concurrent.atomic.AtomicLong

/** Media3 demuxes MP4; the bridge announces avc3/hev1 in-band CSD before its real sync sample. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SrtAvcExtractor(
    private val upstream: Extractor = FragmentedMp4Extractor(),
    val progress: SrtExtractionProgress = SrtExtractionProgress(),
    private val expectedVideoMime: String? = null,
    private val expectedAudioMime: String? = null,
    private val enforceAcceptedTracks: Boolean = false
) : Extractor by upstream {
    private val videoOutputs = mutableListOf<SrtAvcTrackOutput>()

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        progress.readCalls.incrementAndGet()
        return upstream.read(input, seekPosition).also {
            progress.completedReads.incrementAndGet()
        }
    }

    override fun init(output: ExtractorOutput) {
        upstream.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                if (enforceAcceptedTracks && type == C.TRACK_TYPE_AUDIO && expectedAudioMime == null)
                    throw ParserException.createForMalformedContainer("UNSUPPORTED_CONFIG: unexpected audio track", null)
                val actual = output.track(id, type)
                val track = object : TrackOutput by actual {
                    override fun format(format: Format) {
                        val expected = if (type == C.TRACK_TYPE_VIDEO) expectedVideoMime else expectedAudioMime
                        if (expected != null && format.sampleMimeType != expected)
                            throw ParserException.createForMalformedContainer("UNSUPPORTED_CONFIG: codec differs from accepted request", null)
                        if (format.sampleMimeType == MimeTypes.AUDIO_AAC) {
                            val asc = format.initializationData.singleOrNull()
                                ?: throw ParserException.createForMalformedContainer("UNSUPPORTED_CONFIG: missing AAC ASC", null)
                            val parsed = try { org.screencopy.receiver.protocol.AacSpecificConfig.parse(asc) }
                            catch (e: org.screencopy.receiver.protocol.ProtocolException) {
                                throw ParserException.createForMalformedContainer("UNSUPPORTED_CONFIG: invalid AAC ASC", e)
                            }
                            if (parsed.sampleRate != format.sampleRate || parsed.channels != format.channelCount)
                                throw ParserException.createForMalformedContainer("UNSUPPORTED_CONFIG: AAC metadata differs from ASC", null)
                        }
                        actual.format(format)
                    }
                    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int,
                        cryptoData: TrackOutput.CryptoData?) {
                        progress.firstSample(type, timeUs)
                        actual.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                    }
                }
                return if (type == C.TRACK_TYPE_VIDEO) SrtAvcTrackOutput(track, progress).also(videoOutputs::add) else track
            }
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
    }

    override fun seek(position: Long, timeUs: Long) {
        videoOutputs.forEach { it.reset() }
        upstream.seek(position, timeUs)
    }

    override fun release() {
        videoOutputs.forEach { it.reset() }
        upstream.release()
    }
}

internal class SrtExtractionProgress {
    private val firstVideoPtsUs = AtomicLong(C.TIME_UNSET)
    private val firstAudioPtsUs = AtomicLong(C.TIME_UNSET)
    val sourceOriginUs: Long
        get() {
            val video = firstVideoPtsUs.get()
            val audio = firstAudioPtsUs.get()
            val first = when {
                video == C.TIME_UNSET -> audio
                audio == C.TIME_UNSET -> video
                else -> minOf(video, audio)
            }
            return if (first == C.TIME_UNSET) 0 else first.coerceAtLeast(0)
        }
    fun firstSample(type: Int, timeUs: Long) {
        when (type) {
            C.TRACK_TYPE_VIDEO -> firstVideoPtsUs.compareAndSet(C.TIME_UNSET, timeUs)
            C.TRACK_TYPE_AUDIO -> firstAudioPtsUs.compareAndSet(C.TIME_UNSET, timeUs)
        }
    }
    val readCalls = AtomicLong()
    val completedReads = AtomicLong()
    val videoBytes = AtomicLong()
    val bufferedVideoBytes = AtomicLong()
    val videoSamples = AtomicLong()
    val lastSampleBytes = AtomicLong()
    val lastSamplePtsUs = AtomicLong()
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class SrtAvcTrackOutput(
    private val output: TrackOutput,
    private val progress: SrtExtractionProgress = SrtExtractionProgress()
) : TrackOutput {
    private var current: Format? = null
    private var sample = ByteArrayOutputStream()
    private val scratch = ByteArray(16 * 1024)

    override fun durationUs(durationUs: Long) = output.durationUs(durationUs)

    override fun format(format: Format) {
        require(format.sampleMimeType in setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_AV1)) { "SRT requires AVC, HEVC or AV1 video" }
        if (current?.sampleMimeType == MimeTypes.VIDEO_AV1 && current != format)
            throw malformed("UNSUPPORTED_CONFIG: fixed video configuration changes unsupported")
        current = format
        output.format(format)
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        checkPartAndSize(sampleDataPart, length.coerceAtMost(scratch.size))
        val count = input.read(scratch, 0, length.coerceAtMost(scratch.size))
        if (count == C.RESULT_END_OF_INPUT) {
            if (!allowEndOfInput) throw EOFException()
            return count
        }
        sample.write(scratch, 0, count)
        progress.videoBytes.addAndGet(count.toLong())
        progress.bufferedVideoBytes.set(sample.size().toLong())
        return count
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        checkPartAndSize(sampleDataPart, length)
        sample.write(data.data, data.position, length)
        data.skipBytes(length)
        progress.videoBytes.addAndGet(length.toLong())
        progress.bufferedVideoBytes.set(sample.size().toLong())
    }

    private fun checkPartAndSize(part: Int, length: Int) {
        if (part != TrackOutput.SAMPLE_DATA_PART_MAIN || length < 0 ||
            sample.size().toLong() + length > 8L * 1024 * 1024 + 64 * 1024)
            throw malformed("Invalid or oversized SRT AVC sample")
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        if (offset != 0 || size != sample.size() || cryptoData != null)
            throw malformed("Invalid SRT AVC sample metadata")
        val bytes = sample.toByteArray()
        reset()
        if (current?.sampleMimeType in setOf(MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_H265)) {
            if (current?.sampleMimeType == MimeTypes.VIDEO_H265) {
                updateHevcFormat(bytes, flags)
            }
        } else {
            val nals = CodecSpecificDataUtil.splitNalUnits(bytes) ?: throw malformed("SRT AVC sample lacks NAL units")
            val sps = nals.lastOrNull { it.size > 4 && it[4].toInt() and 31 == 7 }
            val pps = nals.lastOrNull { it.size > 4 && it[4].toInt() and 31 == 8 }
            if (sps != null || pps != null) {
                if (sps == null || pps == null || flags and C.BUFFER_FLAG_KEY_FRAME == 0 ||
                    nals.none { it.size > 4 && it[4].toInt() and 31 == 5 })
                    throw malformed("SRT AVC parameter sets require a real IDR")
                val parsed = try { NalUnitUtil.parseSpsNalUnit(sps, 4, sps.size) }
                catch (e: RuntimeException) { throw ParserException.createForMalformedContainer("Invalid SRT SPS", e) }
                if (parsed.width !in 1..16384 || parsed.height !in 1..16384)
                    throw malformed("Invalid SRT AVC dimensions")
                val next = checkNotNull(current).buildUpon()
                    .setWidth(parsed.width).setHeight(parsed.height)
                    .setPixelWidthHeightRatio(parsed.pixelWidthHeightRatio)
                    .setCodecs(CodecSpecificDataUtil.buildAvcCodecString(
                        parsed.profileIdc, parsed.constraintsFlagsAndReservedZero2Bits, parsed.levelIdc))
                    .setInitializationData(listOf(sps, pps)).build()
                if (next != current) {
                    current = next
                    output.format(next)
                }
            }
        }
        output.sampleData(ParsableByteArray(bytes), bytes.size)
        output.sampleMetadata(timeUs, flags, size, 0, null)
        progress.lastSampleBytes.set(size.toLong())
        progress.lastSamplePtsUs.set(timeUs)
        progress.videoSamples.incrementAndGet()
    }

    fun reset() {
        sample = ByteArrayOutputStream()
        progress.bufferedVideoBytes.set(0)
    }

    private fun updateHevcFormat(bytes: ByteArray, flags: Int) {
        val nals = CodecSpecificDataUtil.splitNalUnits(bytes) ?: throw malformed("SRT HEVC sample lacks NAL units")
        if (nals.any { it.size < 6 || it[4].toInt() and 0x81 != 0 ||
                it[5].toInt() and 0xf8 != 0 || it[5].toInt() and 7 == 0 })
            throw malformed("Invalid or multilayer SRT HEVC NAL header")
        fun type(nal: ByteArray) = (nal[4].toInt() ushr 1) and 63
        val picture = nals.indexOfFirst { type(it) < 32 }
        val irap = nals.any { type(it) in 16..23 }
        if (picture < 0 || (flags and C.BUFFER_FLAG_KEY_FRAME != 0) != irap)
            throw malformed("SRT HEVC key flag must match an authentic IRAP picture")
        if (nals.none { type(it) in 32..34 }) return
        if (!irap || nals.withIndex().any { type(it.value) in 32..34 && it.index > picture })
            throw malformed("SRT HEVC parameter sets must precede an authentic IRAP picture")
        val parameters = (32..34).map { kind ->
            val matching = nals.filter { type(it) == kind }
            if (matching.isEmpty() || matching.any { !it.contentEquals(matching.first()) })
                throw malformed("SRT HEVC rotation requires complete consistent VPS/SPS/PPS")
            matching.first()
        }
        val sps = parameters[1]
        val parsed = try { NalUnitUtil.parseH265SpsNalUnit(sps, 4, sps.size, null) }
        catch (e: RuntimeException) {
            throw ParserException.createForMalformedContainer("Invalid SRT HEVC SPS", e)
        }
        if (parsed.width !in 1..16384 || parsed.height !in 1..16384)
            throw malformed("Invalid SRT HEVC dimensions")
        val profile = parsed.profileTierLevel ?: throw malformed("Missing SRT HEVC profile")
        if (parameters.sumOf { it.size } > 64 * 1024)
            throw malformed("SRT HEVC initialization exceeds 64 KiB")
        val csd = parameters.fold(byteArrayOf()) { data, nal -> data + nal }
        val next = checkNotNull(current).buildUpon()
            .setWidth(parsed.width).setHeight(parsed.height)
            .setPixelWidthHeightRatio(parsed.pixelWidthHeightRatio)
            .setMaxNumReorderSamples(parsed.maxNumReorderPics)
            .setCodecs(CodecSpecificDataUtil.buildHevcCodecString(
                profile.generalProfileSpace, profile.generalTierFlag, profile.generalProfileIdc,
                profile.generalProfileCompatibilityFlags, profile.constraintBytes, profile.generalLevelIdc))
            .setInitializationData(listOf(csd)).build()
        if (next != current) {
            current = next
            output.format(next)
        }
    }

    private fun malformed(reason: String) = ParserException.createForMalformedContainer(reason, null)
}
