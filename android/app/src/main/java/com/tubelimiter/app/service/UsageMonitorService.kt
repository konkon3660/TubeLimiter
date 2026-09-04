package com.tubelimiter.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.tubelimiter.app.MainActivity
import com.tubelimiter.app.R
import com.tubelimiter.app.auth.AuthRepository
import com.tubelimiter.app.block.BlockContent
import com.tubelimiter.app.block.BlockOverlay
import com.tubelimiter.app.data.AppSettings
import com.tubelimiter.app.data.AppState
import com.tubelimiter.app.data.HISTORY_RETENTION_DAYS
import com.tubelimiter.app.data.RuntimeState
import com.tubelimiter.app.data.Settings
import com.tubelimiter.app.gamification.applyDayRollover
import com.tubelimiter.app.limit.BlockInputs
import com.tubelimiter.app.limit.EMERGENCY_DURATION_MILLIS
import com.tubelimiter.app.limit.blockReason
import com.tubelimiter.app.limit.computeLimitMillis
import com.tubelimiter.app.limit.emergencyGrantCooldownRemainingMillis
import com.tubelimiter.app.limit.emergencyOverlapMillis
import com.tubelimiter.app.limit.evaluateAlarms
import com.tubelimiter.app.limit.isUnlimited
import com.tubelimiter.app.limit.minutesToMillis
import com.tubelimiter.app.limit.ScheduleWindow
import com.tubelimiter.app.limit.isScheduleActive
import com.tubelimiter.app.limit.minutesUntilNextScheduleStart
import com.tubelimiter.app.limit.resolveFocusStopTime
import com.tubelimiter.app.limit.shouldDisableHardcore
import com.tubelimiter.app.sync.SyncRepository
import com.tubelimiter.app.sync.combinedUsedMillis
import com.tubelimiter.app.sync.usageDeltaSinceSync
import com.tubelimiter.app.usage.UsageStatsReader
import com.tubelimiter.app.usage.effectiveDate
import com.tubelimiter.app.usage.formatDuration
import com.tubelimiter.app.usage.hourOfDay
import com.tubelimiter.app.usage.lastNDates
import com.tubelimiter.app.usage.startOfEffectiveDayMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val TAG = "UsageMonitorService"
private const val CHANNEL_ONGOING = "usage_monitor"
private const val CHANNEL_NUDGE = "usage_nudge"
private const val NOTIFICATION_ID = 1

// Tight polling only matters while YouTube is on screen; otherwise back off to save battery.
private const val POLL_ACTIVE_MILLIS = 5_000L
private const val POLL_IDLE_MILLIS = 30_000L

/** Guards against a corrupt stored date walking the rollover loop forever. */
private const val MAX_ROLLOVER_DAYS = 400

private const val SETTINGS_REFRESH_INTERVAL_MILLIS = 30_000L
private const val DAILY_USAGE_SYNC_INTERVAL_MILLIS = 30_000L

class UsageMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var reader: UsageStatsReader
    private lateinit var overlay: BlockOverlay
    private lateinit var settingsStore: AppSettings
    private lateinit var stateStore: AppState
    private lateinit var sync: SyncRepository

    private var nudgeId = 100
    private var lastSettingsPullAt = 0L
    private var lastDailyUsageSyncAt = 0L

    /** When the previous tick settled usage, so a tick can tell which slice of its own window an
     * emergency pass covered. Null after a restart - that tick simply attributes nothing. */
    private var lastUsageTickAt: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        reader = UsageStatsReader(this)
        overlay = BlockOverlay(this)
        settingsStore = AppSettings(this)
        stateStore = AppState(this)
        sync = SyncRepository(AuthRepository(this), settingsStore, stateStore)

        createChannels()
        startForegroundCompat(ongoingNotification("감시 중", progressPercent = null))
        scope.launch { monitorLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        overlay.hide()
        super.onDestroy()
    }

    private suspend fun monitorLoop() {
        while (scope.isActive) {
            val wait = runCatching { tick() }
                .onFailure { Log.e(TAG, "Monitor tick failed", it) }
                .getOrDefault(POLL_IDLE_MILLIS)
            delay(wait)
        }
    }

    /** Mirrors the extension's `handleTick` ordering. Returns the wait before the next tick. */
    private suspend fun tick(): Long {
        val now = System.currentTimeMillis()
        refreshSettingsFromServer(now)
        val settings = settingsStore.settings.first()
        val today = effectiveDate(now)
        val todayKey = today.toString()

        val snapshot = reader.snapshot(
            windowStart = startOfEffectiveDayMillis(now),
            windowEnd = now,
        )

        // usage_history stores an absolute per-day total each tick, not a delta, so the hourly
        // breakdown has to diff against what was there before this write to find out how much
        // time passed since the last tick.
        val stateBefore = stateStore.state.first()
        val previousTodayMillis = stateBefore.usageHistory[todayKey] ?: 0L

        val retained = lastNDates(today, HISTORY_RETENTION_DAYS).map { it.toString() }.toSet()
        stateStore.recordUsage(todayKey, snapshot.usedMillis, retained)

        val hourlyDelta = (snapshot.usedMillis - previousTodayMillis).coerceAtLeast(0L)
        if (hourlyDelta > 0L) {
            stateStore.recordHourlyUsage(todayKey, hourOfDay(now), hourlyDelta, retained)

            // Whatever share of this tick fell inside an emergency pass is also booked as
            // emergency time. It stays part of the day's total usage; the rollover subtracts it
            // for the streak and bars the day from the perfect-day count.
            val tickStart = lastUsageTickAt ?: (now - hourlyDelta)
            val emergencyDelta = minOf(
                hourlyDelta,
                emergencyOverlapMillis(tickStart, now, stateBefore.lastEmergencyGrantedAtMillis),
            )
            if (emergencyDelta > 0L) {
                stateStore.recordEmergencyUsage(todayKey, emergencyDelta, retained)
            }
        }
        lastUsageTickAt = now

        settleFinishedDays(settings, today)
        releaseHardcoreIfCooledDown(settings, now)
        resetEmergencyAllowanceIfDue(settings, today)
        advanceFocusMode(now)
        expireEmergency(now)
        val scheduleWindow = checkScheduleTransition(settings, now)
        checkScheduleUpcomingNudge(settings, now, todayKey)
        refreshDailyUsageSync(now, todayKey, snapshot.usedMillis)

        // Re-read after the edits above so the block decision sees current state.
        val state = stateStore.state.first()
        val limitMillis = computeLimitMillis(settings.limit, today)

        // Fold in what other devices on the same account have reported today (documents/BACKEND.md).
        // usage_history above stays local-only on purpose — it feeds the streak/dashboard, which
        // already have their own device-local behaviour matching the extension.
        val effectiveUsedMillis = if (state.dailyUsageSyncDate == todayKey) {
            combinedUsedMillis(snapshot.usedMillis, state.dailyUsageSyncedMillis, state.dailyUsageCombinedMillis)
        } else {
            snapshot.usedMillis
        }

        runAlarms(state, settings, todayKey, effectiveUsedMillis, limitMillis)

        val inputs = BlockInputs(
            usedMillis = effectiveUsedMillis,
            limitMillis = limitMillis,
            emergencyActive = state.emergencyActiveAt(now),
            focusModeActive = state.focusActiveAt(now),
            scheduleBlockActive = scheduleWindow != null,
            manuallyBlocked = state.manuallyBlocked,
        )
        val reason = inputs.blockReason()

        withContext(Dispatchers.Main) {
            if (reason != null && snapshot.inForeground) {
                overlay.show(
                    BlockContent(
                        reason = reason,
                        usedMillis = effectiveUsedMillis,
                        limitMillis = limitMillis,
                        emergencyRemaining = state.emergencyRemaining ?: settings.emergencyAllowance,
                    ),
                ) { grantEmergency() }
            } else {
                overlay.hide()
            }
        }

        updateOngoingNotification(effectiveUsedMillis, limitMillis, reason != null)
        return if (snapshot.inForeground) POLL_ACTIVE_MILLIS else POLL_IDLE_MILLIS
    }

    /**
     * Same 30s throttle as the settings pull. Reports usage this device hasn't told the
     * server about yet and stores back the combined total so [tick] can fold other devices'
     * usage into the block decision.
     */
    private suspend fun refreshDailyUsageSync(now: Long, todayKey: String, localUsedMillis: Long) {
        if (now - lastDailyUsageSyncAt < DAILY_USAGE_SYNC_INTERVAL_MILLIS) return
        lastDailyUsageSyncAt = now

        val state = stateStore.state.first()
        val delta = usageDeltaSinceSync(localUsedMillis, state.dailyUsageSyncDate, state.dailyUsageSyncedMillis, todayKey)
        val localEmergencyMillis = state.emergencyMillisOn(todayKey)
        val emergencyDelta = usageDeltaSinceSync(
            localEmergencyMillis,
            state.dailyUsageSyncDate,
            state.dailyUsageEmergencySyncedMillis,
            todayKey,
        )
        val total = sync.syncDailyUsage(todayKey, delta, emergencyDeltaMs = emergencyDelta) ?: return
        stateStore.recordDailyUsageSync(
            todayKey,
            syncedMillis = localUsedMillis,
            combinedMillis = total.usageMs,
            emergencySyncedMillis = localEmergencyMillis,
        )
    }

    /**
     * Settles every day between the last rollover and today. Days the phone was never
     * used still count as successes — zero usage is inside any limit — so a break does
     * not unfairly end a streak.
     */
    private suspend fun settleFinishedDays(settings: Settings, today: LocalDate) {
        val state = stateStore.state.first()
        val lastKey = state.lastRolloverDate
        if (lastKey == null) {
            stateStore.setLastRolloverDate(today.toString())
            return
        }

        val last = runCatching { LocalDate.parse(lastKey) }.getOrNull()
        // A stored date in the future means the clock moved backwards; resync instead of looping.
        if (last == null || !last.isBefore(today)) {
            if (last == null || last.isAfter(today)) stateStore.setLastRolloverDate(today.toString())
            return
        }

        var cursor: LocalDate = last
        var record = state.streak
        var processed = 0
        while (cursor.isBefore(today) && processed < MAX_ROLLOVER_DAYS) {
            val key = cursor.toString()
            val used = state.usageHistory[key] ?: 0L
            val limit = computeLimitMillis(settings.limit, cursor)

            // Matching the extension: streaks are only earned while hardcore mode is on.
            if (settings.hardcoreMode) {
                val result = applyDayRollover(
                    previous = record,
                    dateKey = key,
                    usedMillis = used,
                    limitMillis = limit,
                    emergencyMillis = state.emergencyMillisOn(key),
                    emergencyUses = state.emergencyUsesOn(key),
                )
                record = result.record
                val unlockedKeys = result.unlockedKeys
                stateStore.saveRollover(
                    record = record,
                    unlockedKeys = unlockedKeys,
                    lastRolloverDate = key,
                )
                sync.pushStreak(record, unlockedKeys)
                result.unlockedMilestones.forEach {
                    nudge("${it}일 연속 달성! 뱃지를 얻었어요.")
                }
                result.unlockedPerfectMilestones.forEach {
                    nudge("긴급 시청 없이 ${it}일 연속! 완벽한 날 뱃지를 얻었어요.")
                }
            }
            cursor = cursor.plusDays(1)
            processed += 1
        }
        stateStore.setLastRolloverDate(today.toString())
    }

    /** Same 30s throttle the extension's service worker uses. No-op while signed out. */
    private suspend fun refreshSettingsFromServer(now: Long) {
        if (now - lastSettingsPullAt < SETTINGS_REFRESH_INTERVAL_MILLIS) return
        lastSettingsPullAt = now
        sync.pullSettings()
    }

    private suspend fun releaseHardcoreIfCooledDown(settings: Settings, now: Long) {
        if (!settings.hardcoreMode) return
        if (!shouldDisableHardcore(settings.hardcoreDisableRequestedAt, now)) return

        settingsStore.setHardcoreMode(false)
        // The settings screen warns that dropping hardcore resets the streak; honour it.
        stateStore.resetCurrentStreak()
        nudge("하드코어 모드가 해제되었습니다. 연속 기록이 초기화됐어요.")
    }

    private suspend fun resetEmergencyAllowanceIfDue(settings: Settings, today: LocalDate) {
        val state = stateStore.state.first()
        val key = com.tubelimiter.app.limit.emergencyResetKey(settings.emergencyResetFrequency, today)
        if (state.emergencyResetKey == key) return
        stateStore.resetEmergencyAllowance(key, settings.emergencyAllowance)
    }

    /** Promotes a scheduled focus session and retires a finished (or cooled-down-to-stop) one. */
    private suspend fun advanceFocusMode(now: Long) {
        val state = stateStore.state.first()

        val delayEnd = state.focusDelayEndMillis
        if (!state.focusActiveAt(now) && delayEnd != null && now >= delayEnd) {
            stateStore.startFocus(now + minutesToMillis(state.focusDelayDurationMinutes))
            nudge("집중 모드가 시작되었습니다.")
            return
        }

        // Actual stop time is whichever comes first: the session's own natural end, or the
        // cooldown after an early-stop request (resolveFocusStopTime) - a pending stop request
        // never extends a session past when it was going to end anyway.
        val stopAt = resolveFocusStopTime(state.focusEndMillis, state.focusStopRequestedAtMillis)
        if (stopAt != null && now >= stopAt) {
            stateStore.clearFocus()
            nudge("집중 모드가 종료되었습니다.")
        }
    }

    private suspend fun expireEmergency(now: Long) {
        val state = stateStore.state.first()
        val end = state.emergencyEndMillis ?: return
        if (now >= end) stateStore.clearEmergency()
    }

    /**
     * Scheduled blocks are automatic - the clock decides, not a user action - so unlike focus
     * mode's explicit start/stop, this has to detect the transition itself each tick by
     * comparing against the persisted last-known state (so a process restart mid-window does
     * not fire a spurious "started" nudge). Mirrors the extension's `checkUsageAndBlock`.
     */
    private suspend fun checkScheduleTransition(settings: Settings, now: Long): ScheduleWindow? {
        val activeWindow = isScheduleActive(now, settings.scheduleWindows)
        val active = activeWindow != null
        val state = stateStore.state.first()
        if (active != state.scheduleBlockWasActive) {
            stateStore.setScheduleBlockWasActive(active)
            nudge(if (active) "예약된 차단 시간이 시작되었습니다." else "예약된 차단 시간이 종료되었습니다.")
        }
        return activeWindow
    }

    /** Same once-a-day dedupe spirit as the alarm milestones, keyed by the effective date. */
    private suspend fun checkScheduleUpcomingNudge(settings: Settings, now: Long, todayKey: String) {
        val minutes = minutesUntilNextScheduleStart(now, settings.scheduleWindows) ?: return
        if (minutes <= 0 || minutes > 10) return

        val state = stateStore.state.first()
        if (state.scheduleStartNotifiedDate == todayKey) return
        stateStore.setScheduleStartNotifiedDate(todayKey)
        nudge("10분 후 예약된 차단이 시작됩니다.")
    }

    private suspend fun runAlarms(
        state: RuntimeState,
        settings: Settings,
        todayKey: String,
        usedMillis: Long,
        limitMillis: Long,
    ) {
        val outcome = evaluateAlarms(
            previous = state.alarm,
            todayKey = todayKey,
            usedMillis = usedMillis,
            limitMillis = limitMillis,
            intervalMinutes = settings.alarmIntervalMinutes,
            milestonesEnabled = settings.alarmMilestonesEnabled,
        )
        if (!outcome.changed) return
        stateStore.saveAlarmState(outcome.state)
        outcome.messages.forEach { nudge(it) }
    }

    private fun grantEmergency() {
        scope.launch {
            val now = System.currentTimeMillis()
            val state = stateStore.state.first()

            // Focus mode blocks "regardless of the daily limit" - emergency (meant as a safety
            // valve for usage-limit blocks) must not be able to cut that short. Usage-limit and
            // manual blocks are unaffected: this check only fires while focus mode is active.
            if (state.focusActiveAt(now)) {
                nudge("집중 모드 중에는 긴급 시청을 쓸 수 없어요.")
                return@launch
            }

            // Same principle for a scheduled block window - it is also a commitment device
            // that blocks regardless of the limit, so it gets the same emergency-grant gate.
            val settings = settingsStore.settings.first()
            if (isScheduleActive(now, settings.scheduleWindows) != null) {
                nudge("예약된 차단 시간에는 긴급 시청을 쓸 수 없어요.")
                return@launch
            }

            // Additive anti-mash gate, independent of the daily/weekly/monthly use-count check
            // consumeEmergency performs below.
            val cooldownRemaining = emergencyGrantCooldownRemainingMillis(state.lastEmergencyGrantedAtMillis, now)
            if (cooldownRemaining > 0) {
                val seconds = (cooldownRemaining / 1000L) + 1
                nudge("${seconds}초 후 다시 시도해주세요.")
                return@launch
            }

            val granted = stateStore.consumeEmergency(now, now + EMERGENCY_DURATION_MILLIS)
            if (granted) {
                // Spending a pass is itself what costs the day its perfect-day standing, even if
                // nothing ends up being watched on it.
                val today = effectiveDate(now)
                val retained = lastNDates(today, HISTORY_RETENTION_DAYS).map { it.toString() }.toSet()
                stateStore.recordEmergencyUse(today.toString(), retained)
                withContext(Dispatchers.Main) { overlay.hide() }
            } else {
                nudge("남은 긴급 시청 횟수가 없습니다.")
            }
        }
    }

    // --- notifications ---

    private fun updateOngoingNotification(usedMillis: Long, limitMillis: Long, blocked: Boolean) {
        val text: String
        val progressPercent: Int?
        when {
            blocked -> {
                text = "차단 중 · 오늘 ${formatDuration(usedMillis)} 사용"
                progressPercent = 100
            }
            isUnlimited(limitMillis) -> {
                text = "오늘 ${formatDuration(usedMillis)} 사용 · 한도 없음"
                progressPercent = null
            }
            else -> {
                val remaining = (limitMillis - usedMillis).coerceAtLeast(0L)
                text = "오늘 ${formatDuration(usedMillis)} 사용 · ${formatDuration(remaining)} 남음"
                progressPercent = ((usedMillis.toFloat() / limitMillis) * 100).toInt().coerceIn(0, 100)
            }
        }
        notificationManager()?.notify(NOTIFICATION_ID, ongoingNotification(text, progressPercent))
    }

    private fun nudge(message: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_NUDGE)
            .setContentTitle("TubeLimiter")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.brand_primary))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        // A fresh id each time, otherwise Android silently updates the existing
        // notification instead of showing a new banner.
        notificationManager()?.notify(nudgeId++, notification)
    }

    /**
     * Colorized + a progress bar so the persistent notification reads like the app's own
     * usage ring, not a generic "service running" banner. `setColorized` only takes effect
     * on a foreground-service notification, which this always is.
     */
    private fun ongoingNotification(text: String, progressPercent: Int?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("TubeLimiter")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(this, R.color.brand_primary))
            .setColorized(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .apply {
                if (progressPercent != null) setProgress(100, progressPercent, false)
            }
            .build()
    }

    private fun createChannels() {
        val manager = notificationManager() ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "사용시간 감시", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "유튜브 사용시간을 재는 동안 표시됩니다." },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NUDGE, "알림", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "남은 시간 알림, 집중 모드 시작·종료 등." },
        )
    }

    private fun notificationManager(): NotificationManager? =
        getSystemService(NotificationManager::class.java)

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, UsageMonitorService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Log.e(TAG, "Failed to start service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, UsageMonitorService::class.java))
        }
    }
}
