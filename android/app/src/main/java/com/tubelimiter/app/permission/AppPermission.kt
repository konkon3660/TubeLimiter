package com.tubelimiter.app.permission

enum class AppPermission {
    USAGE_ACCESS,
    OVERLAY,
    BATTERY_UNRESTRICTED,
    NOTIFICATIONS,
}

fun requiredPermissions(sdkInt: Int): List<AppPermission> = buildList {
    add(AppPermission.USAGE_ACCESS)
    add(AppPermission.OVERLAY)
    add(AppPermission.BATTERY_UNRESTRICTED)
    if (sdkInt >= 33) add(AppPermission.NOTIFICATIONS)
}

fun nextMissingPermission(
    required: List<AppPermission>,
    granted: Map<AppPermission, Boolean>,
): AppPermission? = required.firstOrNull { granted[it] != true }

fun allGranted(
    required: List<AppPermission>,
    granted: Map<AppPermission, Boolean>,
): Boolean = nextMissingPermission(required, granted) == null
