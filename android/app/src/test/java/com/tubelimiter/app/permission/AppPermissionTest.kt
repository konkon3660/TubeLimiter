package com.tubelimiter.app.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionTest {

    @Test
    fun `notifications are only required from api 33`() {
        assertFalse(AppPermission.NOTIFICATIONS in requiredPermissions(32))
        assertTrue(AppPermission.NOTIFICATIONS in requiredPermissions(33))
    }

    @Test
    fun `onboarding surfaces the first ungranted permission in order`() {
        val required = requiredPermissions(34)
        val granted = mapOf(AppPermission.USAGE_ACCESS to true)
        assertEquals(AppPermission.OVERLAY, nextMissingPermission(required, granted))
    }

    @Test
    fun `all granted only when every required permission is present`() {
        val required = requiredPermissions(34)
        val partial = required.dropLast(1).associateWith { true }
        assertFalse(allGranted(required, partial))
        assertTrue(allGranted(required, required.associateWith { true }))
        assertNull(nextMissingPermission(required, required.associateWith { true }))
    }
}
