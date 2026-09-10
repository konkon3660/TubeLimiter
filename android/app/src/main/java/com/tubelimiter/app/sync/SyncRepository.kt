package com.tubelimiter.app.sync

import android.util.Log
import com.tubelimiter.app.auth.AuthRepository
import com.tubelimiter.app.data.AppSettings
import com.tubelimiter.app.data.AppState
import com.tubelimiter.app.data.Settings
import com.tubelimiter.app.data.planAccountSwitch
import com.tubelimiter.app.diagnostics.DiagnosticKind
import com.tubelimiter.app.diagnostics.summarizeFailure
import com.tubelimiter.app.gamification.StreakRecord
import com.tubelimiter.app.limit.HardcoreViolation
import com.tubelimiter.app.limit.isHardcoreChangeAllowed
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "SyncRepository"

/** [SyncRepository.saveSettings]의 결과. */
sealed interface SettingsSaveResult {
    /** 로컬에 반영됐다(서버 반영은 성공했을 수도, 대기분으로 밀렸을 수도 있다). */
    data object Applied : SettingsSaveResult

    /** 하드코어 잠금이 막았다. 로컬에도 서버에도 아무것도 쓰지 않았다. */
    data class Rejected(val violations: List<HardcoreViolation>) : SettingsSaveResult
}

/**
 * Keeps the local stores in step with the same Supabase project the extension uses.
 *
 * The server is the source of truth for settings, mirroring how the extension treats
 * them: edits are pushed immediately and a periodic pull brings back anything another
 * client changed.
 *
 * 모든 경로가 실패를 삼키고 `false`/`null`을 돌려주므로(호출자는 그냥 이전 값을 쓴다),
 * 여기서 진단 링버퍼에 남기지 않으면 며칠째 동기화가 죽어 있어도 사용자가 알 방법이 없다.
 * 성공은 이벤트를 쌓지 않고 마지막 성공 시각만 갱신한다 — [AppState.recordSyncSuccess] 참고.
 */
class SyncRepository(
    private val auth: AuthRepository,
    private val settingsStore: AppSettings,
    private val stateStore: AppState,
) {

    private val postgrest get() = auth.postgrest

    /**
     * 설정 변경의 **유일한 관문**. 화면의 모든 편집 콜백이 여기로 들어온다
     * ([com.tubelimiter.app.data.AppSettings]에는 개별 필드 setter가 남아 있지 않다 — 옆문을
     * 없애야 "모든 저장이 같은 판정을 거친다"가 성립한다).
     *
     * 순서가 계약이다:
     *
     * 1. **하드코어 판정을 맨 먼저** 태운다([isHardcoreChangeAllowed]). 온라인/오프라인 분기보다
     *    먼저여야 한다 — 뒤로 미루면 "비행기 모드로 바꾸고 한도를 올린다"가 새 우회로가 된다
     *    (documents/BACKEND.md "오프라인 편집 대기분" 6번).
     * 2. 통과하면 **로컬에 먼저 쓴다.** DataStore가 1차 저장소라 차단 판정이 즉시 새 값을 본다.
     * 3. 그 다음 서버로 올린다. 실패가 "서버에 못 닿음"이면 바뀐 컬럼을 대기분에 쌓는다.
     */
    suspend fun saveSettings(transform: (Settings) -> Settings): SettingsSaveResult {
        val current = settingsStore.settings.first()
        val next = transform(current)

        val check = isHardcoreChangeAllowed(current, next)
        if (!check.allowed) return SettingsSaveResult.Rejected(check.violations)
        if (next == current) return SettingsSaveResult.Applied

        settingsStore.replaceAll(next)
        pushSettings(changedSettingsColumns(current, next))
        return SettingsSaveResult.Applied
    }

    suspend fun pullSettings(): Boolean {
        val userId = activeUserIdOrNull() ?: return false

        // 계정 전환 가드가 여기 있는 이유: 화면도 서비스도 설정 동기화를 이 함수로 시작하므로,
        // 로그인한 user_id를 알게 되는 모든 경로가 반드시 이 판정을 지난다.
        applyAccountSwitch(userId)

        // 대기분이 있으면 pull보다 먼저 올린다. 기본 계약("settings는 서버가 진실의 원천")을
        // 이 한 줄만 뒤집는다 — 대기분은 사용자가 방금 명시적으로 한 변경이라, 서버 값으로
        // 덮으면 "저장했는데 되돌아왔다"가 된다.
        val plan = resolveSettingsSyncPlan(stateStore.state.first().pendingSettings, userId)
        plan.pending?.let { return pushPendingSettings(userId, it.columns) }

        return runCatching {
            val remote = postgrest["settings"]
                .select(Columns.list(SETTINGS_COLUMNS)) { filter { eq("user_id", userId) } }
                .decodeSingleOrNull<RemoteSettings>()

            if (remote == null) {
                // 행이 없다 = 이 계정이 아직 아무것도 저장한 적이 없다(갓 가입이거나 첫 로그인).
                // 지금 로컬 값으로 행을 만든다. 계정 전환이었다면 위 가드가 이미 기본값으로
                // 되돌려놨으므로 직전 계정의 하드코어 잠금이 새 계정 행으로 넘어가지 않는다.
                // **조회 실패는 이 경로로 오지 않는다** — 예외는 아래 getOrElse로 빠진다.
                pushSettings()
            } else {
                val local = settingsStore.settings.first()
                val merged = remote.toSettings(local)
                if (merged != local) settingsStore.replaceAll(merged)
            }
            recordSuccess()
            true
        }.getOrElse {
            Log.w(TAG, "Settings pull failed", it)
            recordFailure(DiagnosticKind.SYNC_SETTINGS, it, "pull")
            false
        }
    }

    /**
     * 지금 로컬 설정을 통째로 올린다. [changedColumns]는 실패했을 때 대기분에 넣을 목록이고,
     * 비워두면(주기적 seed 같은 경우) 실패해도 대기분을 쌓지 않는다.
     *
     * 올릴 때 통째로 보내는 건 기존 동작 그대로다(마지막 쓰기 승리). 컬럼을 골라 보내는 건
     * **대기분을 올릴 때뿐**이고, 이유는 [pushPendingSettings] 주석에 있다.
     */
    suspend fun pushSettings(changedColumns: Set<String> = emptySet()): Boolean {
        val userId = activeUserIdOrNull() ?: return false
        return runCatching {
            val settings = settingsStore.settings.first()
            postgrest["settings"].upsert(settings.toRemoteJson(userId)) { onConflict = "user_id" }
            // 통째로 올렸으니 쌓여 있던 대기분도 이 요청에 다 실려 갔다.
            if (stateStore.state.first().pendingSettings != null) stateStore.savePendingSettings(null)
            recordSuccess()
            true
        }.getOrElse { error ->
            Log.w(TAG, "Settings push failed", error)
            recordFailure(DiagnosticKind.SYNC_SETTINGS, error, "push")
            // 서버가 4xx/PostgREST 코드로 **답을 한** 실패는 세션·요청 문제이므로 대기분에 넣지
            // 않는다. 뭉뚱그리면 토큰이 취소된 계정의 편집이 영원히 쌓인다([isOfflineFailure]).
            if (changedColumns.isNotEmpty() && isOfflineFailure(error)) {
                queuePendingSettings(userId, changedColumns)
            }
            false
        }
    }

    /**
     * 대기분을 올린다. **대기분에 든 컬럼만** 보낸다 — 로컬 설정을 통째로 올리면 오프라인이던
     * 사이 다른 기기가 바꾼 항목까지 옛 값으로 되돌린다. 값 자체는 DataStore에서 그 자리에서
     * 읽는다(대기분은 "어느 컬럼을 못 올렸는가"만 들고 있다 — [OfflineSettings] 파일 주석 참고).
     *
     * 성공했을 때만 대기분을 지운다. 실패하면 그대로 남아 다음 주기에 다시 시도한다.
     */
    private suspend fun pushPendingSettings(userId: String, columns: Set<String>): Boolean =
        runCatching {
            val local = settingsStore.settings.first()

            // 올리기 직전에 **서버의 현재 값**을 기준으로 하드코어 게이트를 다시 태운다. 대기분은
            // 오프라인 편집 시점의 로컬 값으로 통과한 것이라, 그 사이 다른 기기가 규칙을 조였다면
            // 그대로 올리는 순간 잠금이 게이트 바깥에서 되돌아간다(QA_REVIEW §10.4). 규칙은
            // [planPendingSettingsPush]가 갖고 있고 확장과 같은 계약이다.
            val server = postgrest["settings"]
                .select(Columns.list(SETTINGS_COLUMNS)) { filter { eq("user_id", userId) } }
                .decodeSingleOrNull<RemoteSettings>()
                ?.toSettings(local)

            // 서버에 행이 아직 없으면 비교할 기준이 없다 — 그대로 올린다.
            val plan = server?.let { planPendingSettingsPush(it, local, columns) }

            if (plan != null) {
                // 거부된 컬럼은 서버 값이 이긴다. 로컬에 남겨두면 화면에는 반영된 것처럼 보인다.
                if (plan.settings != local) settingsStore.replaceAll(plan.settings)
                // 조용히 버리지 않는다 — 설정 화면이 무엇이 왜 빠졌는지 띄운다.
                if (plan.rejected.isNotEmpty()) {
                    stateStore.savePendingSettingsRejected(plan.rejected.map { it.name }.toSet())
                }
            }

            val pushable = plan?.columns ?: columns
            if (pushable.isNotEmpty()) {
                val payload = (plan?.settings ?: local).toRemoteJson(userId, pushable)
                postgrest["settings"].upsert(payload) { onConflict = "user_id" }
            }
            // 올릴 게 하나도 안 남았어도 대기분은 비운다 — 전부 거부됐다는 뜻이고, 남겨두면
            // 같은 요청이 주기마다 되풀이된다.
            stateStore.savePendingSettings(null)
            recordSuccess()
            true
        }.getOrElse {
            Log.w(TAG, "Pending settings push failed", it)
            recordFailure(DiagnosticKind.SYNC_SETTINGS, it, "pending")
            false
        }

    private suspend fun queuePendingSettings(userId: String, columns: Set<String>) {
        val stored = stateStore.state.first().pendingSettings
        stateStore.savePendingSettings(accumulatePendingSettings(stored, userId, columns))
    }

    /**
     * 로그인한 계정이 이 기기의 주인과 다르면 계정에서 온 로컬 값을 비운다. 판정은 순수 함수
     * ([planAccountSwitch])가 하고 여기서는 결론대로 지우고 표식만 남긴다.
     *
     * 지우는 것과 남기는 것의 목록은 [com.tubelimiter.app.data.ACCOUNT_SWITCH_REMOVED_KEYS] /
     * [com.tubelimiter.app.data.ACCOUNT_SWITCH_PRESERVED_KEYS]에 있다. 특히 설정 캐시를
     * 기본값으로 되돌리는 것이 중요하다 — 안 그러면 갓 가입한 계정이 직전 계정의 하드코어
     * 잠금을 물려받는다.
     */
    private suspend fun applyAccountSwitch(userId: String) {
        val plan = planAccountSwitch(stateStore.state.first().accountOwnerUserId, userId)
        if (plan.switched) {
            stateStore.clearAccountData()
            settingsStore.resetSyncedToDefaults()
        }
        plan.ownerToStore?.let { stateStore.setAccountOwner(it) }
    }

    suspend fun pullStreak(): Boolean {
        val userId = activeUserIdOrNull() ?: return false
        return runCatching {
            val remote = postgrest["streaks"]
                .select(Columns.list(STREAK_COLUMNS)) { filter { eq("user_id", userId) } }
                .decodeSingleOrNull<RemoteStreak>()
                // 행이 아직 없는 것도 왕복 자체는 성공이다 — 여기서 성공 시각을 안 찍으면
                // 새 계정이 스트릭을 처음 올리기 전까지 "동기화 안 됨"으로 보인다.
                ?: run {
                    recordSuccess()
                    return@runCatching true
                }

            val unlocked = postgrest["achievements"]
                .select(Columns.list("key")) { filter { eq("user_id", userId) } }
                .decodeList<RemoteAchievement>()
                .map { it.key }
                .toSet()

            val local = stateStore.state.first()
            val merged = mergeStreaks(local.streak, remote.toStreakRecord())
            stateStore.saveStreak(merged, local.achievements + unlocked)
            recordSuccess()
            true
        }.getOrElse {
            Log.w(TAG, "Streak pull failed", it)
            recordFailure(DiagnosticKind.SYNC_STREAK, it, "pull")
            false
        }
    }

    /**
     * Reports usage this device hasn't told the server about yet, for one calendar date, and
     * returns the row's new totals — the server sums deltas from every device sharing the
     * account, via the `increment_daily_usage` RPC (see `documents/BACKEND.md`). Passing a
     * zero delta is harmless and still returns the latest total, which is how a caller with
     * nothing new to report picks up another device's contribution.
     */
    suspend fun syncDailyUsage(
        dateKey: String,
        usageDeltaMs: Long,
        shortsDeltaMs: Long = 0L,
        emergencyDeltaMs: Long = 0L,
        emergencyUsesDelta: Int = 0,
    ): RemoteDailyUsageTotal? {
        if (activeUserIdOrNull() == null) return null
        return runCatching {
            val params = buildJsonObject {
                put("p_date", dateKey)
                put("p_usage_delta_ms", usageDeltaMs)
                put("p_shorts_delta_ms", shortsDeltaMs)
                put("p_emergency_delta_ms", emergencyDeltaMs)
                put("p_emergency_uses_delta", emergencyUsesDelta)
            }
            val total = postgrest.rpc("increment_daily_usage", params).decodeSingle<RemoteDailyUsageTotal>()
            recordSuccess()
            total
        }.getOrElse {
            Log.w(TAG, "daily_usage sync failed", it)
            recordFailure(DiagnosticKind.SYNC_USAGE, it, "rpc")
            null
        }
    }

    /**
     * [startDateKey] 이후 각 날짜의 긴급 시청 횟수(계정 전체 합계). 주간/월간 리셋에서는 버킷이
     * 여러 날에 걸쳐 있어 [syncDailyUsage]가 돌려주는 오늘 행만으로는 합계를 알 수 없다.
     *
     * 실패하거나 로그아웃이면 null — 호출자는 마지막으로 성공한 값을 그대로 두고, 결국 로컬
     * 횟수만으로 계속 동작한다(합산은 어디까지나 보너스). 조용히 넘어가는 만큼 진단에는 남긴다.
     */
    suspend fun fetchEmergencyUsesSince(startDateKey: String): Map<String, Int>? {
        val userId = activeUserIdOrNull() ?: return null
        return runCatching {
            val rows = postgrest["daily_usage"]
                .select(Columns.list(EMERGENCY_USES_COLUMNS)) {
                    filter {
                        eq("user_id", userId)
                        gte("date", startDateKey)
                    }
                }
                .decodeList<RemoteEmergencyUsesRow>()
                .associate { it.date to it.emergencyUses }
            recordSuccess()
            rows
        }.getOrElse {
            Log.w(TAG, "emergency_uses fetch failed", it)
            recordFailure(DiagnosticKind.EMERGENCY_FETCH, it, "select")
            null
        }
    }

    /**
     * 대시보드용 날짜별 기록. [startDateKey](포함) 이후의 `daily_usage` 행을 통째로 읽어
     * [mergeHistories]가 로컬 기록과 날짜별 max로 합친다. 이게 없으면 앱을 재설치했거나 다른
     * 기기에서만 본 날이 히트맵에 통째로 비어 보인다 — 계정 기록은 서버에 이미 다 쌓여 있는데도.
     *
     * 실패하거나 로그아웃이면 null. 호출부(대시보드)는 조용히 로컬 기록만으로 그린다 —
     * 대시보드가 통째로 비는 것보다 이 기기 기록이라도 보이는 게 낫다. 조용히 넘어가는 만큼
     * 진단에는 남긴다.
     */
    suspend fun fetchDailyUsageSince(startDateKey: String): List<RemoteDailyUsageRow>? {
        val userId = activeUserIdOrNull() ?: return null
        return runCatching {
            val rows = postgrest["daily_usage"]
                .select(Columns.list(DAILY_USAGE_HISTORY_COLUMNS)) {
                    filter {
                        eq("user_id", userId)
                        gte("date", startDateKey)
                    }
                }
                .decodeList<RemoteDailyUsageRow>()
            recordSuccess()
            rows
        }.getOrElse {
            Log.w(TAG, "daily_usage history fetch failed", it)
            recordFailure(DiagnosticKind.SYNC_USAGE, it, "history")
            null
        }
    }

    suspend fun pushStreak(record: StreakRecord, unlockedKeys: Set<String>): Boolean {
        val userId = activeUserIdOrNull() ?: return false
        return runCatching {
            postgrest["streaks"].upsert(record.toRemoteJson(userId)) { onConflict = "user_id" }
            if (unlockedKeys.isNotEmpty()) {
                val rows = unlockedKeys.map { key ->
                    buildJsonObject {
                        put("user_id", userId)
                        put("key", key)
                    }
                }
                postgrest["achievements"].upsert(rows) {
                    onConflict = "user_id,key"
                    ignoreDuplicates = true
                }
            }
            recordSuccess()
            true
        }.getOrElse {
            Log.w(TAG, "Streak push failed", it)
            recordFailure(DiagnosticKind.SYNC_STREAK, it, "push")
            false
        }
    }

    /**
     * 로그인 상태가 아니면 동기화를 건너뛴다. 다만 **토큰 갱신 실패**로 세션이 사라진 경우는
     * 그냥 로그아웃한 것과 구별해서 남긴다: 갱신이 며칠째 막혀 있어도 화면에는 계정 확인
     * 스피너로만 보이고 모든 동기화가 조용히 `false`를 돌려주기 때문에, 기록해두지 않으면
     * 원인을 찾을 단서가 아무것도 없다.
     */
    private suspend fun activeUserIdOrNull(): String? {
        val userId = auth.currentUserIdOrNull()
        if (userId == null && auth.sessionRefreshFailed()) {
            stateStore.recordDiagnosticFailure(
                atMillis = System.currentTimeMillis(),
                kind = DiagnosticKind.AUTH,
                code = "session_refresh_failed",
            )
        }
        return userId
    }

    /**
     * 예외는 [summarizeFailure]를 거쳐 **클래스 이름 + 상태 코드**로만 줄여 저장한다. 서버가
     * 돌려준 문구를 그대로 넣으면 조건에 걸린 이메일이나 user_id가 진단 화면에 그대로 뜬다.
     * [step]은 우리가 직접 붙이는 고정 문자열이라 안전하다.
     */
    private suspend fun recordFailure(kind: String, error: Throwable, step: String) {
        stateStore.recordDiagnosticFailure(
            atMillis = System.currentTimeMillis(),
            kind = kind,
            code = "$step/${summarizeFailure(error)}",
        )
    }

    private suspend fun recordSuccess() {
        stateStore.recordSyncSuccess(System.currentTimeMillis())
    }
}
