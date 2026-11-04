package org.screencopy.receiver.media

import android.media.AudioAttributes
import android.media.AudioTrack
import android.media.PlaybackParams
import android.media.AudioFormat as PlatformAudioFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue

@RunWith(AndroidJUnit4::class)
class AudioRateEnvelopeTest {
    @Test(timeout = 60_000)
    fun smallNativeRateTransitionsRemainValidOnTheSamePreparedTrack() {
        PlatformAudioOutput(AudioFormat("pcm", 48000, 2, byteArrayOf()), { code, detail ->
            fail("$code $detail")
        }).use { output ->
            output.setSpeed(1.001294f)
            output.setSpeed(1.0012041f)
            android.util.Log.i("ScreenCopyReceiver", "event=NATIVE_RATE_DEADBAND requested=1.0012041 actual=${output.preparedSpeed}")
            assertTrue(kotlin.math.abs(output.preparedSpeed - 1.0012041f) < AudioOutputPreparation.NATIVE_SPEED_DEADBAND)
            output.play()
            repeat(1000) { index ->
                val speed = 1f + ((index % 81) - 40) * 0.00005f
                output.setSpeed(speed)
                assertTrue(kotlin.math.abs(output.preparedSpeed - speed) < AudioOutputPreparation.NATIVE_SPEED_DEADBAND)
                assertTrue(output.preparedSpeed in 0.98f..1.02f)
            }
            output.pause()
            assertFalse(output.isPlaying)
            assertTrue(output.bufferFrames <= 6240)
            assertTrue(output.capacityFrames <= 6240)
        }
    }

    @Test fun nativeBufferAndPerformanceModeProbe() {
        var supported = 0
        for (channels in 1..2) {
            val minimum = AudioTrack.getMinBufferSize(48000,
                if (channels == 1) PlatformAudioFormat.CHANNEL_OUT_MONO else PlatformAudioFormat.CHANNEL_OUT_STEREO,
                PlatformAudioFormat.ENCODING_PCM_16BIT)
            val plan = AudioOutputPreparation.plan(AudioFormat("pcm", 48000, channels, byteArrayOf()), minimum)
            for (frames in listOf(plan.frames, plan.maxFrames)) {
                for (mode in listOf(AudioTrack.PERFORMANCE_MODE_NONE, AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)) {
                    val track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setAudioFormat(PlatformAudioFormat.Builder().setSampleRate(48000)
                            .setChannelMask(if (channels == 1) PlatformAudioFormat.CHANNEL_OUT_MONO else PlatformAudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(PlatformAudioFormat.ENCODING_PCM_16BIT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(mode)
                        .setBufferSizeInBytes(frames * channels * 2).build()
                    try {
                        var accepted = true
                        try {
                            for (speed in listOf(1.02f, 0.98f, 1f)) {
                                track.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
                                    .setAudioFallbackMode(PlaybackParams.AUDIO_FALLBACK_MODE_FAIL)
                                track.pause()
                            }
                        } catch (e: IllegalArgumentException) {
                            accepted = false
                            android.util.Log.i("ScreenCopyReceiver", "event=AUDIO_RATE_PROBE rejected=${e.javaClass.simpleName}")
                        }
                        android.util.Log.i("ScreenCopyReceiver", "event=AUDIO_RATE_PROBE channels=$channels" +
                            " frames=$frames performance_mode=$mode accepted=$accepted capacity_frames=${track.bufferCapacityInFrames}")
                        if (accepted) supported++
                    } finally { track.release() }
                }
            }
        }
        assertTrue("No native track accepted the required rate envelope within the unchanged route ceiling", supported > 0)
    }

    @Test fun actualAndroidTrackPreparesFastSlowAndNominalWithoutStartingPlayback() {
        for (channels in 1..2) {
            val failures = ConcurrentLinkedQueue<String>()
            PlatformAudioOutput(AudioFormat("pcm", 48000, channels, byteArrayOf()),
                { code, detail -> failures.add("$code: $detail") }).use { output ->
                assertFalse(output.isPlaying)
                assertEquals(1f, output.preparedSpeed, 0.00001f)
                assertTrue(output.bufferFrames * 1000L <= 48000L * 130)
                assertTrue(output.capacityFrames * 1000L <= 48000L * 130)
                for (speed in listOf(1.02f, 0.98f, 1f)) {
                    output.setSpeed(speed)
                    assertEquals(speed, output.preparedSpeed, 0.00001f)
                    assertFalse(output.isPlaying)
                }
                assertTrue(failures.toString(), failures.isEmpty())
            }
        }
    }
}
