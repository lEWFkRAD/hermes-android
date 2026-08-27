package com.hermesandroid.bridge.power

import android.content.Context
import android.os.PowerManager
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression tests for WakeLockManager.wake() (PR #101).
 *
 * wake() must succeed both when the screen is already interactive (no-op)
 * and when it is off (acquires an auto-releasing wake lock), and repeated
 * wakes must not accumulate held locks (@Synchronized acquireWakeLock
 * releases any previously-held lock first).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WakeLockManagerWakeTest {

    private lateinit var powerManager: PowerManager

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        WakeLockManager.init(context)
    }

    @After
    fun tearDown() {
        WakeLockManager.forceRelease()
    }

    @Test
    fun `wake succeeds when screen already interactive`() {
        shadowOf(powerManager).setIsInteractive(true)

        assertTrue(WakeLockManager.wake())
    }

    @Test
    fun `wake succeeds when screen off`() {
        shadowOf(powerManager).setIsInteractive(false)

        assertTrue(WakeLockManager.wake())
    }

    @Test
    fun `repeated wakes do not throw or accumulate state`() {
        shadowOf(powerManager).setIsInteractive(false)

        repeat(3) { assertTrue(WakeLockManager.wake()) }
        // forceRelease clears whatever was held and must not throw.
        WakeLockManager.forceRelease()
        assertTrue(WakeLockManager.wake())
    }
}
