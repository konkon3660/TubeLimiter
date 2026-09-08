package com.tubelimiter.app.limit

import java.time.LocalDate

/**
 * "그날 실제로 적용됐던 한도"를 날짜별로 남기고 되읽는 순수 로직.
 *
 * 왜 필요한가: 대시보드 히트맵/막대그래프는 원래 과거 날짜에도 [computeLimitMillis]로 "지금
 * 설정된 한도"를 소급 적용해 성공/실패를 판정했다. 그래서 한도를 30분에서 2시간으로 올리면
 * 예전에 초과했던 날들이 한꺼번에 성공으로 바뀌어, 기록이 사실이 아니게 된다. 한도가 쓰이는
 * 그때그때 값을 남겨두면 나중에 설정을 바꿔도 지난 판정이 흔들리지 않는다.
 *
 * **확장 `src/lib/limitHistory.js`와 같은 규칙이다** — 센티널 값, 기록 시점, `keepExisting`
 * 의미, "기록이 없으면 추정치" 처리까지. 한쪽만 고치면 같은 계정을 두 클라이언트로 볼 때
 * 히트맵 판정이 갈라진다(documents/BACKEND.md가 양쪽 계약을 적어두는 것과 같은 이유).
 *
 * 저장은 DataStore의 `limit_history` 문자열 하나이고, 인코딩은 usage_history와 같은
 * [com.tubelimiter.app.data.encodeLongMap]이다 — 값이 `날짜 -> Long` 한 겹뿐이라 새 의존성
 * 없이 기존 포맷을 그대로 쓴다. 날짜 키도 usage_history와 같은
 * [com.tubelimiter.app.usage.effectiveDate](새벽 4시 컷오프) 기준이어야 한다. 다른 기준으로
 * 만들면 같은 날의 사용량과 한도가 서로 다른 칸에 들어간다.
 *
 * 읽기/쓰기는 호출자([com.tubelimiter.app.data.AppState]) 몫이고 이 파일은 안드로이드 API를
 * 전혀 건드리지 않아 유닛 테스트로 검증된다.
 */

/**
 * 무제한 표시용 저장 값. [UNLIMITED_MILLIS]는 `Long.MAX_VALUE`라 그대로 적어도 되지만, 확장이
 * `Infinity`를 담지 못해 쓰는 `-1` 센티널과 **같은 컨벤션**을 유지한다 — `daily_limit_by_day`의
 * [UNLIMITED_MINUTES]도 이미 `-1`이고, 언젠가 이 맵이 서버로 올라가더라도 두 클라이언트가
 * 같은 값을 읽게 된다.
 */
const val UNLIMITED_LIMIT_SENTINEL = -1L

/** 저장용 값으로 변환. 무제한은 센티널로, 음수(계산이 깨진 값)는 0으로 접는다. */
fun serializeLimitMillis(limitMillis: Long): Long =
    if (isUnlimited(limitMillis)) UNLIMITED_LIMIT_SENTINEL else limitMillis.coerceAtLeast(0L)

/**
 * [estimated]가 true면 기록이 없어 **현재 설정으로 근사**한 값이라는 뜻이다. 화면에서 추정임을
 * 밝혀야 하기 때문에 같이 돌려준다 — 근사치를 사실처럼 보여주면 원래 있던 소급 적용 문제를
 * 그대로 두면서 티만 안 나게 하는 셈이 된다.
 */
data class ResolvedLimit(val limitMillis: Long, val estimated: Boolean)

/**
 * 그날 판정에 쓸 한도.
 *
 * 기록이 있으면 그 값이 정답이고, 없으면(이 기능 이전 날짜, 또는 다른 기기에서만 보고 서버로만
 * 내려온 날) 지금 설정으로 계산한 근사치로 떨어진다.
 *
 * 손상된 인코딩은 [com.tubelimiter.app.data.decodeLongMap]이 그 항목을 통째로 버리므로 여기서는
 * "기록 없음"으로 보이고, 그대로 근사치로 간다. 무제한으로 넘겨버리면 그날이 조용히
 * "무조건 성공"이 된다 — 확장이 숫자가 아닌 값을 근사치로 떨어뜨리는 것과 같은 판단.
 */
fun resolveLimitForDate(
    limitHistory: Map<String, Long>,
    config: LimitConfig,
    date: LocalDate,
): ResolvedLimit {
    val recorded = limitHistory[date.toString()]
        ?: return ResolvedLimit(computeLimitMillis(config, date), estimated = true)
    return ResolvedLimit(
        limitMillis = if (recorded < 0L) UNLIMITED_MILLIS else recorded,
        estimated = false,
    )
}

/**
 * 기록 한 건. [keepExisting]이 true면 이미 기록이 있는 날은 건드리지 않는다 — 롤오버가 지난
 * 날짜를 채울 때 쓴다. 그 시점의 설정은 이미 바뀌었을 수 있어서, 그날 남겨둔 값이 언제나 더
 * 정확하다.
 */
data class LimitHistoryEntry(
    val date: String,
    val limitMillis: Long,
    val keepExisting: Boolean = false,
)

/**
 * [changed]가 false면 저장할 게 없다는 뜻이다. 이 계획은 매 틱마다 세워지므로, 값이 그대로일 때
 * 굳이 DataStore에 다시 쓰지 않게 하려는 신호다.
 */
data class LimitHistoryUpdate(val history: Map<String, Long>, val changed: Boolean)

/**
 * 기록을 갱신하고 보관 기간이 지난 날짜를 정리한 새 맵을 만든다(원본은 건드리지 않는다).
 *
 * [keepKeys]는 usage_history를 정리할 때 쓰는 것과 **같은 집합**을 넘겨야 한다
 * ([com.tubelimiter.app.data.pruneHistory] 참고). 한도 기록만 먼저 잘려나가면 아직 히트맵에
 * 그려지는 날이 추정치로 떨어진다. 확장은 "오늘 - 60일" 컷오프로 같은 일을 한다.
 *
 * [keepExisting]이 아닌 기본 동작은 덮어쓰기다. 오늘 날짜는 하루 사이에도 한도를 바꿀 수 있으니
 * 마지막 값으로 수렴시킨다.
 */
fun planLimitHistoryUpdate(
    history: Map<String, Long>,
    updates: List<LimitHistoryEntry>,
    keepKeys: Set<String>,
): LimitHistoryUpdate {
    val next = history.toMutableMap()
    var changed = false

    updates.forEach { update ->
        if (update.date.isBlank()) return@forEach
        val existing = next[update.date]
        if (update.keepExisting && existing != null) return@forEach
        val value = serializeLimitMillis(update.limitMillis)
        if (existing == value) return@forEach
        next[update.date] = value
        changed = true
    }

    val dropped = next.keys.filterNot { it in keepKeys }
    if (dropped.isNotEmpty()) {
        dropped.forEach { next.remove(it) }
        changed = true
    }

    return LimitHistoryUpdate(next, changed)
}

/**
 * 롤오버 한 번이 남길 한도 기록([historyUpdates])과, 그 기록을 반영한 뒤 각 날짜를 판정할
 * 한도([limitsByDate], 날짜 키 -> ms).
 *
 * **왜 한 함수인가**: 기록과 판정이 같은 값이어야 하기 때문이다. 예전에는 기록은
 * `keepExisting`으로 그날 값을 지키면서 스트릭 판정만 [computeLimitMillis](=현재 설정)로 했다.
 * 그래서 며칠 앱을 안 켠 사이 한도를 바꾸면 히트맵(기록값)과 스트릭(현재 설정)이 **같은 날을
 * 반대로 판정**했다 — 대시보드는 초과인데 스트릭은 성공으로 세는 식이다. 이제 판정도
 * [resolveLimitForDate]를 거친다: 기록이 있으면 그날 값, 없으면 현재 설정 추정치.
 *
 * **확장도 같은 규칙으로 고쳤다**(`checkDateRolloverInner`). `streaks` 행은 두 클라이언트가
 * 공유하므로 한쪽만 스냅샷을 쓰면 같은 날에 서로 다른 스트릭을 계산해 서버 값이 오간다.
 *
 * [retained]에 없는 날짜(보관 기간 밖)에는 기록을 남기지 않는다 — usage_history가 이미 버린
 * 날이라 히트맵에 그릴 곳이 없다. 그런 날은 자연히 현재 설정 추정치로 판정된다(예전과 동일).
 * 오늘은 `keepExisting` 없이 덮어쓴다: 하루 사이에 한도를 바꿀 수 있으니 마지막 값으로
 * 수렴시켜야 하고, 이건 매 틱 기록하는 자리와 같은 규칙이다.
 *
 * [limitHistory]는 아직 [historyUpdates]가 반영되지 않은 **쓰기 전** 맵을 넘긴다. 갱신 결과는
 * 여기서 [planLimitHistoryUpdate]로 다시 계산하므로, 호출자는 DataStore를 두 번 읽지 않아도 된다.
 */
data class RolloverLimitPlan(
    val historyUpdates: List<LimitHistoryEntry>,
    val limitsByDate: Map<String, Long>,
)

fun planRolloverLimits(
    limitHistory: Map<String, Long>,
    config: LimitConfig,
    settledDates: List<LocalDate>,
    today: LocalDate,
    retained: Set<String>,
): RolloverLimitPlan {
    val updates = settledDates
        .filter { it.toString() in retained }
        .map { date ->
            LimitHistoryEntry(
                date = date.toString(),
                limitMillis = computeLimitMillis(config, date),
                keepExisting = true,
            )
        } + LimitHistoryEntry(today.toString(), computeLimitMillis(config, today))

    // AppState.recordLimitHistory가 쓰는 keepKeys와 같은 집합이어야 갱신 뒤의 맵이 실제 저장될
    // 값과 일치한다(그쪽도 keepKeys에 updates의 날짜를 더한다).
    val recorded = planLimitHistoryUpdate(
        history = limitHistory,
        updates = updates,
        keepKeys = retained + updates.map { it.date },
    ).history

    return RolloverLimitPlan(
        historyUpdates = updates,
        limitsByDate = settledDates.associate { date ->
            date.toString() to resolveLimitForDate(recorded, config, date).limitMillis
        },
    )
}
