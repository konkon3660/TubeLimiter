package com.tubelimiter.app.permission

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

class PermissionChecker(private val context: Context) {

    fun snapshot(): Map<AppPermission, Boolean> = mapOf(
        AppPermission.USAGE_ACCESS to hasUsageAccess(),
        AppPermission.OVERLAY to hasOverlay(),
        AppPermission.BATTERY_UNRESTRICTED to isBatteryUnrestricted(),
        AppPermission.NOTIFICATIONS to hasNotifications(),
    )

    // AppOps is still the only way to read PACKAGE_USAGE_STATS state; both check variants
    // are deprecated with no replacement offered for a plain permission check.
    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val uid = android.os.Process.myUid()
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, uid, context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasOverlay(): Boolean = Settings.canDrawOverlays(context)

    private fun isBatteryUnrestricted(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun hasNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    fun settingsIntentFor(permission: AppPermission): Intent? = when (permission) {
        AppPermission.USAGE_ACCESS ->
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

        AppPermission.OVERLAY ->
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())

        AppPermission.BATTERY_UNRESTRICTED ->
            @Suppress("BatteryLife")
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri())

        // Runtime dialog, not a settings screen.
        AppPermission.NOTIFICATIONS -> null
    }

    private fun packageUri(): Uri = "package:${context.packageName}".toUri()
}
