package com.hermesandroid.bridge.audio

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Security regression tests for MicrophoneRecordingFiles.resolve() (#99):
 * the server-side traversal/symlink defense backing /mic_file. Only
 * generated WAV basenames inside the recordings directory may resolve;
 * every escape vector must return null.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MicrophoneRecordingFilesResolveTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val directory: File
        get() = MicrophoneRecordingFiles.directory(context)

    @Before
    fun cleanDirectory() {
        directory.listFiles()?.forEach { it.delete() }
    }

    @After
    fun tearDown() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun createRecording(name: String, lastModified: Long): File =
        File(directory, name).apply {
            writeBytes("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray())
            setLastModified(lastModified)
        }

    @Test
    fun `blank name resolves to the latest completed recording`() {
        val older = createRecording("recording_20260101_000000_000.wav", 1_000L)
        val newer = createRecording("recording_20260102_000000_000.wav", 2_000L)

        assertEquals(newer.canonicalFile, MicrophoneRecordingFiles.resolve(context, null))
        assertEquals(newer.canonicalFile, MicrophoneRecordingFiles.resolve(context, ""))
        assertEquals(newer.canonicalFile, MicrophoneRecordingFiles.resolve(context, "   "))
        assertNotNull(older) // silence unused warning; kept for ordering setup
    }

    @Test
    fun `existing generated name resolves`() {
        val recording = createRecording("recording_20260102_120000_000.wav", 1_000L)

        assertEquals(
            recording.canonicalFile,
            MicrophoneRecordingFiles.resolve(context, "recording_20260102_120000_000.wav"),
        )
    }

    @Test
    fun `unknown but valid-looking name returns null`() {
        createRecording("recording_20260102_120000_000.wav", 1_000L)

        assertNull(MicrophoneRecordingFiles.resolve(context, "recording_19990101_000000_000.wav"))
    }

    @Test
    fun `traversal sequences are rejected`() {
        createRecording("recording_20260102_120000_000.wav", 1_000L)

        assertNull(MicrophoneRecordingFiles.resolve(context, "../x.wav"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "../../etc/passwd.wav"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "a/../b.wav"))
    }

    @Test
    fun `url-encoded traversal variants are rejected`() {
        // % is outside the safe-name alphabet, so encoded traversal never
        // reaches the filesystem layer.
        assertNull(MicrophoneRecordingFiles.resolve(context, "%2e%2e.wav"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "%2e%2e%2fx.wav"))
    }

    @Test
    fun `absolute paths are rejected`() {
        assertNull(MicrophoneRecordingFiles.resolve(context, "/etc/passwd.wav"))
        assertNull(MicrophoneRecordingFiles.resolve(context, directory.absolutePath + "/x.wav"))
    }

    @Test
    fun `non-wav and malformed names are rejected`() {
        createRecording("recording_20260102_120000_000.wav", 1_000L)

        assertNull(MicrophoneRecordingFiles.resolve(context, "notes.txt"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "recording.wav.part"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "REC.WAV"))
        assertNull(MicrophoneRecordingFiles.resolve(context, ".wav"))
        assertNull(MicrophoneRecordingFiles.resolve(context, "naïve.wav"))
    }

    @Test
    fun `symlink escaping the recordings directory is rejected`() {
        val outside = File(context.cacheDir, "outside.wav").apply { writeText("secret") }
        val link = File(directory, "evil.wav")
        Files.createSymbolicLink(link.toPath(), outside.toPath())

        assertNull(MicrophoneRecordingFiles.resolve(context, "evil.wav"))
    }

    @Test
    fun `symlink staying inside the recordings directory resolves`() {
        val recording = createRecording("recording_20260102_120000_000.wav", 1_000L)
        val link = File(directory, "alias.wav")
        Files.createSymbolicLink(link.toPath(), recording.toPath())

        assertEquals(recording.canonicalFile, MicrophoneRecordingFiles.resolve(context, "alias.wav"))
    }
}
