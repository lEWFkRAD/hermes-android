package com.hermesandroid.bridge.audio

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression tests for #98: the recorder state machine could stick at
 * STARTING when the service was destroyed before the record loop began (or
 * a STOP arrived while isRecording was already false), after which every
 * /mic_start returned 409 until the app restarted.
 *
 * requestStop()'s not-recording branch and onDestroy() must reset an active
 * phase to ERROR so tryReserveStart() succeeds again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MicrophoneRecorderServiceStuckStateTest {

    private fun stopIntent(): Intent =
        Intent(RuntimeEnvironment.getApplication(), MicrophoneRecorderService::class.java)
            .apply { action = "stop" }

    @Test
    fun `stop before the record loop resets a stuck STARTING state`() {
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        assertTrue(MicrophoneRecordingState.snapshot().isActive)

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create().startCommand(0, 1)

        val snapshot = MicrophoneRecordingState.snapshot()
        assertFalse("state must not stay active after STOP", snapshot.isActive)
        assertEquals(MicrophoneRecordingPhase.ERROR, snapshot.phase)

        controller.destroy()
    }

    @Test
    fun `a new start is possible after the stuck state is reset`() {
        assertTrue(MicrophoneRecordingState.tryReserveStart())

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create().startCommand(0, 1)
        controller.destroy()

        // The exact symptom of #98 was this returning false forever.
        assertTrue(MicrophoneRecordingState.tryReserveStart())
        MicrophoneRecordingState.markError("test cleanup")
    }

    @Test
    fun `stop while inactive does not overwrite a READY result`() {
        val completed = java.io.File("recording_ready.wav")
        MicrophoneRecordingState.markReady(completed, 42L)

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create().startCommand(0, 1)

        assertEquals(MicrophoneRecordingPhase.READY, MicrophoneRecordingState.snapshot().phase)
        assertEquals("recording_ready.wav", MicrophoneRecordingState.snapshot().activeFileName)

        controller.destroy()
    }

    @Test
    fun `service destroyed without STOP resets an active state`() {
        assertTrue(MicrophoneRecordingState.tryReserveStart())

        val controller = Robolectric.buildService(
            MicrophoneRecorderService::class.java,
            stopIntent(),
        )
        controller.create()
        // No startCommand: the system destroys the service before the loop.
        controller.destroy()

        val snapshot = MicrophoneRecordingState.snapshot()
        assertFalse(snapshot.isActive)
        assertEquals(MicrophoneRecordingPhase.ERROR, snapshot.phase)
    }
}
