package com.tubelimiter.app.sync

import android.util.Log
import com.tubelimiter.app.auth.AuthRepository
import com.tubelimiter.app.data.AppSettings
import com.tubelimiter.app.data.AppState
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
 */
class SyncRepository(
    private val auth: AuthRepository,
    private val settingsStore: AppSettings,
    private val stateStore: AppState,
) {

    private val postgrest get() = auth.postgrest

    suspend fun pullSettings(): Boolean {
        val userId = auth.currentUserIdOrNull() ?: return false
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
            true
        }.getOrElse {
            Log.w(TAG, "Settings pull failed", it)
            false
        }
    }

    suspend fun pushSettings(): Boolean {
        val userId = auth.currentUserIdOrNull() ?: return false
        return runCatching {
            val settings = settingsStore.settings.first()
            postgrest["settings"].upsert(settings.toRemoteJson(userId)) { onConflict = "user_id" }
            true
        }.getOrElse {
            Log.w(TAG, "Settings push failed", it)
            false
        }
    }

    suspend fun pullStreak(): Boolean {
        val userId = auth.currentUserIdOrNull() ?: return false
        return runCatching {
            val remote = postgrest["streaks"]
                .select(Columns.list(STREAK_COLUMNS)) { filter { eq("user_id", userId) } }
                .decodeSingleOrNull<RemoteStreak>()
                ?: return@runCatching true

            val unlocked = postgrest["achievements"]
                .select(Columns.list("key")) { filter { eq("user_id", userId) } }
                .decodeList<RemoteAchievement>()
                .map { it.key }
                .toSet()

            val local = stateStore.state.first()
            val merged = mergeStreaks(local.streak, remote.toStreakRecord())
            stateStore.saveStreak(merged, local.achievements + unlocked)
            true
        }.getOrElse {
            Log.w(TAG, "Streak pull failed", it)
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
    ): RemoteDailyUsageTotal? {
        if (auth.currentUserIdOrNull() == null) return null
        return runCatching {
            val params = buildJsonObject {
                put("p_date", dateKey)
                put("p_usage_delta_ms", usageDeltaMs)
                put("p_shorts_delta_ms", shortsDeltaMs)
                put("p_emergency_delta_ms", emergencyDeltaMs)
            }
            postgrest.rpc("increment_daily_usage", params).decodeSingle<RemoteDailyUsageTotal>()
        }.getOrElse {
            Log.w(TAG, "daily_usage sync failed", it)
            null
        }
    }

    suspend fun pushStreak(record: StreakRecord, unlockedKeys: Set<String>): Boolean {
        val userId = auth.currentUserIdOrNull() ?: return false
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
            true
        }.getOrElse {
            Log.w(TAG, "Streak push failed", it)
            false
        }
    }
}
