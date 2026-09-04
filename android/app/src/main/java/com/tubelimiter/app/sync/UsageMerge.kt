package com.tubelimiter.app.sync

/**
 * How much of today's local usage hasn't been reported to the server yet. Zero once a new
 * day starts (`syncedDate` no longer matches `today`) — nothing to carry over.
 */
fun usageDeltaSinceSync(localUsedMillis: Long, syncedDate: String?, syncedMillis: Long, today: String): Long {
    val baseline = if (syncedDate == today) syncedMillis else 0L
    return (localUsedMillis - baseline).coerceAtLeast(0L)
}

/**
 * This device's own usage plus whatever the other devices have already pushed for today.
 * `remoteTotalMillis` already includes this device's last synced contribution, so that part
 * is subtracted back out before adding the (possibly newer) local figure.
 */
fun combinedUsedMillis(localUsedMillis: Long, syncedMillis: Long, remoteTotalMillis: Long): Long {
    val otherDevices = (remoteTotalMillis - syncedMillis).coerceAtLeast(0L)
    return localUsedMillis + otherDevices
}
