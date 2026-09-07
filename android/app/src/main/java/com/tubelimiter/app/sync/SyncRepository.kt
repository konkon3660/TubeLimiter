package com.tubelimiter.app.sync

import android.util.Log
import com.tubelimiter.app.auth.AuthRepository
import com.tubelimiter.app.data.AppSettings
import com.tubelimiter.app.data.AppState
import com.tubelimiter.app.diagnostics.DiagnosticKind
import com.tubelimiter.app.diagnostics.summarizeFailure
import com.tubelimiter.app.gamification.StreakRecord
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TAG = "SyncRepository"

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

    suspend fun pullSettings(): Boolean {
        val userId = activeUserIdOrNull() ?: return false
        return runCatching {
            val remote = postgrest["settings"]
                .select(Columns.list(SETTINGS_COLUMNS)) { filter { eq("user_id", userId) } }
                .decodeSingleOrNull<RemoteSettings>()

            if (remote == null) {
                // First sign-in on this account: seed the row from whatever is local.
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

    suspend fun pushSettings(): Boolean {
        val userId = activeUserIdOrNull() ?: return false
        return runCatching {
            val settings = settingsStore.settings.first()
            postgrest["settings"].upsert(settings.toRemoteJson(userId)) { onConflict = "user_id" }
            recordSuccess()
            true
        }.getOrElse {
            Log.w(TAG, "Settings push failed", it)
            recordFailure(DiagnosticKind.SYNC_SETTINGS, it, "push")
            false
        }
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
