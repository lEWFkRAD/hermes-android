package com.hermesandroid.bridge.permission

import android.Manifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePermissionsTest {
    @Test
    fun `android 12 uses legacy shared storage permission`() {
        val permissions = BridgePermissions.runtimePermissionsForSdk(32)

        assertTrue(Manifest.permission.READ_EXTERNAL_STORAGE in permissions)
        assertFalse(Manifest.permission.READ_MEDIA_IMAGES in permissions)
        assertFalse(Manifest.permission.POST_NOTIFICATIONS in permissions)
    }

    @Test
    fun `android 13 requests notifications and granular media`() {
        val permissions = BridgePermissions.runtimePermissionsForSdk(33)

        assertTrue(Manifest.permission.POST_NOTIFICATIONS in permissions)
        assertTrue(Manifest.permission.READ_MEDIA_IMAGES in permissions)
        assertTrue(Manifest.permission.READ_MEDIA_VIDEO in permissions)
        assertTrue(Manifest.permission.READ_MEDIA_AUDIO in permissions)
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in permissions)
        assertFalse(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in permissions)
    }

    @Test
    fun `android 14 requests selected visual media state too`() {
        val permissions = BridgePermissions.runtimePermissionsForSdk(34)

        assertTrue(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in permissions)
        assertTrue(Manifest.permission.CAMERA in permissions)
        assertTrue(Manifest.permission.RECORD_AUDIO in permissions)
        assertTrue(Manifest.permission.ACCESS_FINE_LOCATION in permissions)
        assertTrue(Manifest.permission.READ_CONTACTS in permissions)
        assertTrue(Manifest.permission.CALL_PHONE in permissions)
        assertTrue(Manifest.permission.SEND_SMS in permissions)
    }
}
