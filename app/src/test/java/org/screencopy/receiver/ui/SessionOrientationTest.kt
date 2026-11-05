package org.screencopy.receiver.ui

import android.content.pm.ActivityInfo
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.ReceiverOrientation

class SessionOrientationTest {
    @Test fun negotiatedAutomaticVideoFollowsDisplayedAspectAndRestoresOriginalPolicy() {
        val device = Device(ActivityInfo.SCREEN_ORIENTATION_USER)
        val policy = device.policy()
        val owner = Any()
        policy.begin(owner, ReceiverOrientation.AUTO)
        policy.followVideo(1080.0 / 1920, 90)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, device.current)
        repeat(5) { policy.followVideo(1080.0 / 1920, 270) }
        assertEquals(1, device.writes.size)
        policy.followVideo(1080.0 / 1920, 0)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, device.current)
        policy.restore(owner)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, device.current)
        policy.followVideo(1080.0 / 1920, 90)
        assertEquals(3, device.writes.size)
    }

    @Test fun forcedReceiverAxisWinsOverVideoAndStaleCleanupCannotResetAutomaticVideoAxis() {
        val device = Device(ActivityInfo.SCREEN_ORIENTATION_USER)
        val policy = device.policy()
        val old = Any()
        policy.begin(old, ReceiverOrientation.PORTRAIT)
        policy.followVideo(0.5625, 90)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, device.current)
        val owner = Any()
        policy.begin(owner, ReceiverOrientation.AUTO)
        policy.followVideo(0.5625, 90)
        policy.restore(old)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, device.current)
        policy.restore(owner)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, device.current)
    }

    @Test fun ignoredAutomaticVideoAxisRequestsAreNotRepeatedAndSquareVideoKeepsPolicy() {
        var calls = 0
        val policy = SessionOrientation({ ActivityInfo.SCREEN_ORIENTATION_USER }, { calls++ })
        policy.begin(Any(), ReceiverOrientation.AUTO)
        policy.followVideo(1.0, 90)
        assertEquals(0, calls)
        repeat(5) { policy.followVideo(0.5625, 90) }
        assertEquals(1, calls)
        policy.followVideo(0.5625, 0)
        assertEquals(2, calls)
        assertThrows(IllegalArgumentException::class.java) { policy.followVideo(Double.NaN, 0) }
        assertThrows(IllegalArgumentException::class.java) { policy.followVideo(1.0, 45) }
    }

    @Test fun autoLeavesExistingSensorPolicyUntouched() {
        val device = Device(ActivityInfo.SCREEN_ORIENTATION_USER)
        val policy = device.policy()
        val owner = Any()
        policy.begin(owner, ReceiverOrientation.AUTO)
        policy.begin(owner, ReceiverOrientation.AUTO)
        policy.restore(owner)
        assertTrue(device.writes.isEmpty())
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, device.current)
    }

    @Test fun landscapeAndPortraitSetOnlyThePresentationPolicyOnceAndRestoreOriginal() {
        for ((mode, expected) in listOf(ReceiverOrientation.LANDSCAPE to ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ReceiverOrientation.PORTRAIT to ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
            val device = Device(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            val policy = device.policy()
            val owner = Any()
            policy.begin(owner, mode)
            repeat(5) { policy.begin(owner, mode) }
            assertEquals(listOf(expected), device.writes)
            policy.restore(owner)
            policy.restore(owner)
            assertEquals(listOf(expected, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED), device.writes)
        }
    }

    @Test fun stopFailureOrExitCleanupDoesNotLeakForcedPolicyIntoNextAutomaticSession() {
        repeat(3) {
            val device = Device(ActivityInfo.SCREEN_ORIENTATION_FULL_USER)
            val policy = device.policy()
            val first = Any()
            policy.begin(first, ReceiverOrientation.LANDSCAPE)
            policy.restore()
            val automatic = Any()
            policy.begin(automatic, ReceiverOrientation.AUTO)
            policy.restore(first)
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, device.current)
            policy.restore(automatic)
            assertEquals(2, device.writes.size)
        }
    }

    @Test fun staleCleanupCannotChangeANewerSessionAndAlreadyCorrectAxisNeedsNoSet() {
        val device = Device(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        val policy = device.policy()
        val first = Any()
        policy.begin(first, ReceiverOrientation.PORTRAIT)
        assertTrue(device.writes.isEmpty())
        val second = Any()
        policy.begin(second, ReceiverOrientation.LANDSCAPE)
        policy.restore(first)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, device.current)
        policy.restore(second)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, device.current)
    }

    @Test fun ignoredPlatformRequestIsNotRepeatedByConfigurationCallbacks() {
        var calls = 0
        val policy = SessionOrientation({ ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }, { calls++ })
        val owner = Any()
        repeat(5) { policy.begin(owner, ReceiverOrientation.LANDSCAPE) }
        assertEquals(1, calls)
        policy.restore(owner)
        assertEquals(1, calls)
    }

    @Test fun aThrowingPlatformRequestRetainsItsOriginalPolicyForCleanup() {
        val original = ActivityInfo.SCREEN_ORIENTATION_USER
        val policy = SessionOrientation({ original }, { throw SecurityException("platform policy") })
        val owner = Any()
        assertThrows(SecurityException::class.java) { policy.begin(owner, ReceiverOrientation.PORTRAIT) }
        assertEquals(original, policy.previousPolicy)
        policy.restore(owner)
        assertNull(policy.previousPolicy)
    }

    private class Device(var current: Int) {
        val writes = mutableListOf<Int>()
        fun policy() = SessionOrientation({ current }, { writes.add(it); current = it })
    }
}
