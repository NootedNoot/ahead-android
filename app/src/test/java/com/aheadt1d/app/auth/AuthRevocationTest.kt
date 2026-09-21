package com.aheadt1d.app.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aheadt1d.app.network.BackendClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthRevocationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AuthPrefs.clear(context)
    }

    @Test
    fun `default upload revoked state is false`() {
        assertFalse(AuthPrefs.isUploadRevoked(context))
    }

    @Test
    fun `setting upload revoked toggles state`() {
        AuthPrefs.setUploadRevoked(context, true)
        assertTrue(AuthPrefs.isUploadRevoked(context))

        AuthPrefs.setUploadRevoked(context, false)
        assertFalse(AuthPrefs.isUploadRevoked(context))
    }

    @Test
    fun `saving a new device key automatically clears upload revoked state`() {
        AuthPrefs.setUploadRevoked(context, true)
        assertTrue(AuthPrefs.isUploadRevoked(context))

        AuthPrefs.saveDevice(context, "new_device_id", "ahead_dk_test123")
        assertFalse("saveDevice must reset uploadRevoked to false", AuthPrefs.isUploadRevoked(context))
        assertEquals("ahead_dk_test123", AuthPrefs.deviceApiKey(context))
    }

    @Test
    fun `AuthRevokedException is an IOException with 401 code context`() {
        val ex = BackendClient.AuthRevokedException()
        assertTrue(ex is IOException)
        assertTrue(ex.message!!.contains("401"))
    }
}
