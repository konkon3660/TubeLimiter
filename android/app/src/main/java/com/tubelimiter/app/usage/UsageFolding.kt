package com.tubelimiter.app.usage

data class UsageTransition(
    val timestampMillis: Long,
    val type: Type,
) {
    enum class Type { FOREGROUND, BACKGROUND }
}

/**
 * 감시 대상 앱 하나가 화면에 떠 있던 구간 하나. 여러 앱을 동시에 보게 되면서
 * ([watchedPackages]) "합계"를 내는 방법을 고를 필요가 생겼는데, 그 선택지를 [unionMillis]에
 * 남기려고 접기(fold) 결과를 밀리초 하나가 아니라 구간 목록으로도 꺼낼 수 있게 했다.
 */
data class ForegroundInterval(val startMillis: Long, val endMillis: Long)

/**
 * A BACKGROUND transition with no preceding FOREGROUND means the app was already
 * on screen when the window opened, so the segment is credited from [windowStart].
 */
fun foregroundIntervals(
    transitions: List<UsageTransition>,
    windowStart: Long,
    windowEnd: Long,
    startsInForeground: Boolean = false,
): List<ForegroundInterval> {
    if (windowEnd <= windowStart) return emptyList()

    val intervals = mutableListOf<ForegroundInterval>()
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
                        intervals += ForegroundInterval(start, transition.timestampMillis)
                        enteredAt = null
                    }
                }
            }
        }

    enteredAt?.let { intervals += ForegroundInterval(it, windowEnd) }
    return intervals
}

fun foldForegroundMillis(
    transitions: List<UsageTransition>,
    windowStart: Long,
    windowEnd: Long,
    startsInForeground: Boolean = false,
): Long = foregroundIntervals(transitions, windowStart, windowEnd, startsInForeground)
    .sumOf { it.endMillis - it.startMillis }

/**
 * 여러 앱의 구간을 **겹치지 않게** 합친 총 밀리초.
 *
 * 앱별로 접은 뒤 그냥 더하지 않는 이유: 유튜브 → 유튜브 뮤직으로 넘어갈 때 두 앱의
 * RESUMED/PAUSED가 어떤 순서로 오는지는 OS가 정하고 기기마다 다르다. 겹쳐 들어오면 단순 합은
 * 벽시계 시간보다 큰 값을 내놓고, 그러면 한도가 실제보다 빨리 소진된다. 반대로 이벤트를 한
 * 목록에 섞어 한 번에 접으면 넘어가는 구간이 통째로 사라진다. 합집합만이 두 경우 모두에서
 * "감시 대상 중 하나라도 화면에 있던 시간"이라는 정의와 맞는다.
 */
fun unionMillis(intervals: List<ForegroundInterval>): Long {
    if (intervals.isEmpty()) return 0L
    var total = 0L
    var start = Long.MIN_VALUE
    var end = Long.MIN_VALUE
    intervals.sortedBy { it.startMillis }.forEach { interval ->
        if (interval.endMillis <= interval.startMillis) return@forEach
        if (start == Long.MIN_VALUE) {
            start = interval.startMillis
            end = interval.endMillis
        } else if (interval.startMillis > end) {
            total += end - start
            start = interval.startMillis
            end = interval.endMillis
        } else if (interval.endMillis > end) {
            end = interval.endMillis
        }
    }
    if (start != Long.MIN_VALUE) total += end - start
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
