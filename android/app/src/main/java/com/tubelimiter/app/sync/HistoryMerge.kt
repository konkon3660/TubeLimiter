package com.tubelimiter.app.sync

/**
 * 대시보드가 그리는 "날짜별 기록"을 로컬(DataStore)과 서버(`daily_usage`) 양쪽에서 합치는
 * 순수 함수. Supabase에 전혀 의존하지 않아 유닛 테스트로 검증된다.
 *
 * 왜 날짜별 max인가 (확장 `src/lib/historyMerge.js`와 **같은 규칙이어야 하는 계약**,
 * documents/BACKEND.md `daily_usage` 절):
 *  - 서버 `daily_usage`는 계정 단위 행이라 보통 이 기기 몫보다 크다. 다른 기기(크롬 확장,
 *    두 번째 폰)가 보낸 델타까지 서버가 누적해 두기 때문이다.
 *  - 그렇다고 서버 값을 무조건 채택하면 안 된다. `refreshDailyUsageSync`는 30초 스로틀이고
 *    오프라인이면 더 길게 벌어지므로, 방금 이 기기에서 늘어난 몫은 아직 서버에 없을 수 있다.
 *  - **더할 수는 없다.** 서버 합계에는 이 기기가 이미 보고한 몫이 들어 있어서 로컬 값을 더하면
 *    같은 시간을 두 번 세게 된다([combinedUsedMillis]가 오늘치에 대해 "이미 보고한 만큼을 빼고"
 *    합치는 이유와 같다. 다만 지난 날짜는 기기별 보고분을 되짚을 근거가 남아 있지 않다).
 * 그래서 둘 중 큰 쪽 = "지금까지 알려진 최대"를 택한다. 어느 쪽이 뒤처져 있어도 기록이 뒤로
 * 가지 않는다는 게 이 규칙의 핵심이다.
 *
 * 날짜 경계: `daily_usage.date`는 확장/안드로이드가 각자 새벽 4시 컷오프
 * ([com.tubelimiter.app.usage.effectiveDate], 확장 `lib/time.js`)로 만든 `YYYY-MM-DD`를 그대로
 * 써 넣은 값이라, 로컬 기록 키와 이미 같은 컨벤션이다. 여기서 UTC 자정 기준 등으로 다시
 * 계산하면 오히려 하루씩 어긋난다.
 *
 * Shorts(`shorts_ms`)는 합치지 않는다 — 안드로이드는 화면 내용을 몰라 Shorts를 로컬에 기록하지도
 * 대시보드에 그리지도 않는다(documents/BACKEND.md: 항상 델타 0으로 보냄). 합쳐봐야 그릴 곳이 없다.
 */

/** 그날의 긴급 시청 시간과 횟수. 판정은 [com.tubelimiter.app.gamification.isPerfectDay]가 한다. */
data class MergedEmergencyDay(val millis: Long, val uses: Int)

data class MergedHistories(
    val usage: Map<String, Long>,
    val emergency: Map<String, MergedEmergencyDay>,
)

/** 서버가 음수/이상값을 주더라도 0 이상으로 정규화한다 — max 계산이 오염되면 안 된다. */
private fun Long.nonNegative(): Long = coerceAtLeast(0L)

private fun Int.nonNegative(): Int = coerceAtLeast(0)

/**
 * 날짜별 밀리초 맵 하나를 서버 행의 컬럼 하나([column]로 뽑는다)와 합친다.
 * 같은 날짜가 서버 응답에 두 번 오면 뒤엣것이 이긴다 — `user_id + date` 복합 PK라 실제로는
 * 일어나지 않지만, 맵으로 접어두면 그런 응답에도 결과가 흔들리지 않는다.
 */
fun mergeMillisByDate(
    local: Map<String, Long>,
    serverRows: List<RemoteDailyUsageRow>,
    column: (RemoteDailyUsageRow) -> Long,
): Map<String, Long> {
    val merged = local.mapValues { it.value.nonNegative() }.toMutableMap()
    serverRows.forEach { row ->
        if (row.date.isBlank()) return@forEach
        merged[row.date] = maxOf(merged[row.date] ?: 0L, column(row).nonNegative())
    }
    return merged
}

/**
 * 대시보드가 쓰는 기록을 한 번에 합친다.
 *
 * **긴급 시청 "횟수"에서 확장과 갈라진다.** 확장 `historyMerge.js`는 횟수를 로컬 값만 쓰고
 * 서버에만 있는 날은 `null`로 둔다 — `emergency_uses` 컬럼이 이번에 추가됐지만 확장 대시보드
 * 경로에는 아직 붙어 있지 않기 때문이다. 안드로이드는 [RemoteDailyUsageRow]로 그 컬럼을 같이
 * 읽어 오므로 시간과 **똑같이 날짜별 max**로 합친다. 그래서 다른 기기에서 긴급 시청을 쓴 날도
 * 완벽한 날에서 제대로 걸러진다. 확장이 나중에 같은 컬럼을 읽게 되면 그쪽 규칙이 이쪽으로
 * 와야 하고, 그 전까지는 "같은 날을 확장은 횟수 미상, 앱은 실제 횟수로 본다"가 의도된 차이다.
 *
 * 사용량 기록이 있는 날은 긴급 항목도 반드시 만들어 둔다 — 호출부가 "기록이 없는 날"과
 * "긴급 시청이 0인 날"을 헷갈리지 않게.
 */
fun mergeHistories(
    localUsage: Map<String, Long>,
    localEmergencyMillis: Map<String, Long>,
    localEmergencyUses: Map<String, Int>,
    serverRows: List<RemoteDailyUsageRow>,
): MergedHistories {
    val usage = mergeMillisByDate(localUsage, serverRows) { it.usageMs }
    val emergencyMillis = mergeMillisByDate(localEmergencyMillis, serverRows) { it.emergencyMs }

    val serverUsesByDate = serverRows.filter { it.date.isNotBlank() }.associate { it.date to it.emergencyUses }
    val dates = usage.keys + emergencyMillis.keys + localEmergencyUses.keys + serverUsesByDate.keys
    val emergency = dates.associateWith { date ->
        MergedEmergencyDay(
            millis = emergencyMillis[date] ?: 0L,
            uses = maxOf(
                localEmergencyUses[date]?.nonNegative() ?: 0,
                serverUsesByDate[date]?.nonNegative() ?: 0,
            ),
        )
    }

    return MergedHistories(usage = usage, emergency = emergency)
}
