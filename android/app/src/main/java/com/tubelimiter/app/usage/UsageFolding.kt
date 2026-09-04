package com.tubelimiter.app.usage

data class UsageTransition(
    val timestampMillis: Long,
    val type: Type,
) {
    enum class Type { FOREGROUND, BACKGROUND }
}

/**
 * A BACKGROUND transition with no preceding FOREGROUND means the app was already
 * on screen when the window opened, so the segment is credited from [windowStart].
 */
fun foldForegroundMillis(
    transitions: List<UsageTransition>,
    windowStart: Long,
    windowEnd: Long,
    startsInForeground: Boolean = false,
): Long {
    if (windowEnd <= windowStart) return 0L

    var total = 0L
    var enteredAt: Long? = if (startsInForeground) windowStart else null

    transitions
        .filter { it.timestampMillis in windowStart..windowEnd }
        .sortedBy { it.timestampMillis }
        .forEach { transition ->
            when (transition.type) {
                UsageTransition.Type.FOREGROUND ->
                    if (enteredAt == null) enteredAt = transition.timestampMillis

                UsageTransition.Type.BACKGROUND -> {
                    val start = enteredAt
                    if (start != null) {
                        total += transition.timestampMillis - start
                        enteredAt = null
                    }
                }
            }
        }

    enteredAt?.let { total += windowEnd - it }
    return total
}

/**
 * Whether the tracked app is on screen as of the newest transition.
 * A FOREGROUND and a BACKGROUND stamped the same millisecond resolve to "not on screen".
 */
fun isInForeground(transitions: List<UsageTransition>): Boolean =
    transitions
        .sortedWith(compareBy({ it.timestampMillis }, { it.type.ordinal }))
        .lastOrNull()
        ?.type == UsageTransition.Type.FOREGROUND
