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
import com.tubelimiter.app.diagnostics.DiagnosticKind
import com.tubelimiter.app.diagnostics.summarizeFailure
import com.tubelimiter.app.gamification.applyDayRollover
import com.tubelimiter.app.limit.AlarmMessage
import com.tubelimiter.app.limit.BlockInputs
import com.tubelimiter.app.limit.EMERGENCY_DURATION_MILLIS
import com.tubelimiter.app.limit.LimitHistoryEntry
import com.tubelimiter.app.limit.ScheduleWindow
import com.tubelimiter.app.limit.blockReason
import com.tubelimiter.app.limit.computeLimitMillis
import com.tubelimiter.app.limit.effectiveEmergencyRemaining
import com.tubelimiter.app.limit.emergencyBucketDateKeys
import com.tubelimiter.app.limit.emergencyGrantCooldownRemainingMillis
import com.tubelimiter.app.limit.emergencyOverlapMillis
import com.tubelimiter.app.limit.emergencyResetKey
import com.tubelimiter.app.limit.evaluateAlarms
import com.tubelimiter.app.limit.isScheduleActive
import com.tubelimiter.app.limit.isUnlimited
import com.tubelimiter.app.limit.minutesToMillis
import com.tubelimiter.app.limit.minutesUntilNextScheduleStart
import com.tubelimiter.app.limit.resolveFocusStopTime
import com.tubelimiter.app.limit.shouldDisableHardcore
import com.tubelimiter.app.sync.SyncRepository
import com.tubelimiter.app.sync.combinedUsedMillis
import com.tubelimiter.app.sync.emergencyUsesDeltaSinceSync
import com.tubelimiter.app.sync.otherDeviceEmergencyUses
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
        startForegroundCompat(
            ongoingNotification(
                getString(R.string.notification_ongoing_starting),
                progressPercent = null,
            ),
        )
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
            val result = runCatching { tick() }
            result.exceptionOrNull()?.let { error ->
                Log.e(TAG, "Monitor tick failed", error)
                // 틱이 통째로 넘어지면 사용량도 안 쌓이고 차단도 안 걸리는데, 화면에는 여전히
                // "감시 중" 알림만 떠 있다. logcat을 볼 수 없는 실기기에서 이걸 알아챌 유일한
                // 흔적이라 진단 기록에 남긴다. 기록 자체가 또 실패해도 루프는 계속 돌아야 한다.
                runCatching {
                    stateStore.recordDiagnosticFailure(
                        atMillis = System.currentTimeMillis(),
                        kind = DiagnosticKind.MONITOR,
                        code = "tick/${summarizeFailure(error)}",
                    )
                }
            }
            delay(result.getOrDefault(POLL_IDLE_MILLIS))
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

        // 오늘 기록이 생겼으니 그 시간에 적용되던 한도도 같이 남긴다 — 롤오버를 거치기 전이라도
        // 대시보드가 오늘 칸을 추정치로 그리지 않게 한다. 확장의 `recordTodayLimit`이 사용시간을
        // 저장하는 자리에서 불리는 것과 같은 시점이다. 값이 그대로면 아무것도 쓰지 않는다.
        stateStore.recordLimitHistory(
            updates = listOf(LimitHistoryEntry(todayKey, computeLimitMillis(settings.limit, today))),
            keepKeys = retained,
        )

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

        settleFinishedDays(settings, today, retained)
        releaseHardcoreIfCooledDown(settings, now)
        resetEmergencyAllowanceIfDue(settings, today)
        advanceFocusMode(now)
        expireEmergency(now)
        val scheduleWindow = checkScheduleTransition(settings, now)
        checkScheduleUpcomingNudge(settings, now, todayKey)
        refreshDailyUsageSync(now, today, todayKey, snapshot.usedMillis, settings)

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
                        // 다른 기기가 쓴 몫까지 반영된 잔여 횟수 — 오버레이 문구와 실제 승인
                        // 판정(consumeEmergency)이 갈라지지 않게 같은 규칙을 쓴다.
                        emergencyRemaining = effectiveEmergencyRemaining(
                            state.emergencyRemaining,
                            settings.emergencyAllowance,
                            state.cachedOtherDeviceEmergencyUses(),
                        ),
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
     *
     * 긴급 시청 횟수도 같은 델타 패턴으로 실어 보낸다. 버킷 합계 조회까지 여기 얹은 건
     * 배터리와 요청 수 때문 — 유튜브가 떠 있으면 tick은 5초마다 돌지만 이 경로는 30초에 한 번뿐이다.
     */
    private suspend fun refreshDailyUsageSync(
        now: Long,
        today: LocalDate,
        todayKey: String,
        localUsedMillis: Long,
        settings: Settings,
    ) {
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
        val localUsesToday = state.emergencyUsesOn(todayKey)
        val usesDelta = emergencyUsesDeltaSinceSync(
            localUsesToday,
            state.dailyUsageSyncDate,
            state.dailyUsageEmergencyUsesSynced,
            todayKey,
        )
        val total = sync.syncDailyUsage(
            todayKey,
            delta,
            emergencyDeltaMs = emergencyDelta,
            emergencyUsesDelta = usesDelta,
        ) ?: return
        stateStore.recordDailyUsageSync(
            todayKey,
            syncedMillis = localUsedMillis,
            combinedMillis = total.usageMs,
            emergencySyncedMillis = localEmergencyMillis,
            emergencyUsesSynced = localUsesToday,
        )
        refreshEmergencyUsesBucket(settings, today, todayKey, state, localUsesToday, total.emergencyUses)
    }

    /**
     * 이번 리셋 버킷에서 **다른 기기가** 쓴 긴급 시청 횟수를 갱신한다.
     *
     * 리셋 주기가 daily면 방금 RPC가 돌려준 오늘 행 합계로 충분하다. weekly/monthly는 버킷이
     * 여러 날에 걸쳐 있어 오늘 행만 보면 틀리므로, 버킷 시작일 이후 행들을 한 번 더 읽어 합산한다.
     * 조회가 실패하면 아무것도 쓰지 않고 빠진다 — 마지막으로 성공했던 값이 남고, 그마저 없으면
     * 로컬 횟수만으로 판정한다.
     */
    private suspend fun refreshEmergencyUsesBucket(
        settings: Settings,
        today: LocalDate,
        todayKey: String,
        state: RuntimeState,
        localUsesToday: Int,
        remoteUsesToday: Int,
    ) {
        val frequency = settings.emergencyResetFrequency
        val bucketKeys = emergencyBucketDateKeys(frequency, today)
        val remoteByDate = if (bucketKeys.size <= 1) {
            mapOf(todayKey to remoteUsesToday)
        } else {
            sync.fetchEmergencyUsesSince(bucketKeys.first()) ?: return
        }

        val otherDevices = otherDeviceEmergencyUses(
            bucketDateKeys = bucketKeys,
            localUsesByDate = state.emergencyUsesByDate(),
            remoteUsesByDate = remoteByDate,
            todayKey = todayKey,
            // 방금 보고를 마쳤으므로 이 기기가 서버에 올린 오늘치는 곧 로컬 값과 같다.
            syncedTodayUses = localUsesToday,
        )
        stateStore.recordEmergencyUsesFromOtherDevices(emergencyResetKey(frequency, today), otherDevices)
    }

    /**
     * Settles every day between the last rollover and today. Days the phone was never
     * used still count as successes — zero usage is inside any limit — so a break does
     * not unfairly end a streak.
     */
    private suspend fun settleFinishedDays(settings: Settings, today: LocalDate, retained: Set<String>) {
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

        // 정산되는 지난 날짜들과 새로 시작하는 오늘의 한도를 남긴다. 지난 날짜는 이미 기록이
        // 있으면 건드리지 않는다(keepExisting) — 지금 설정은 그날 이후 바뀌었을 수 있어서, 그날
        // 남겨둔 값이 언제나 더 정확하다. 기록이 없는 날(앱을 안 켠 날)만 아래 applyDayRollover가
        // 쓰는 것과 같은 값으로 채워 히트맵과 스트릭 판정이 어긋나지 않게 한다.
        // 확장 `checkDateRolloverInner`의 recordLimitHistory 호출과 같은 자리·같은 규칙이다.
        // 보관 기간 밖의 날은 아예 적지 않는다 — usage_history가 이미 버린 날이라 그릴 곳이 없다.
        val settledDates = generateSequence(last) { it.plusDays(1) }
            .takeWhile { it.isBefore(today) }
            .take(MAX_ROLLOVER_DAYS)
            .filter { it.toString() in retained }
            .toList()
        stateStore.recordLimitHistory(
            updates = settledDates.map { date ->
                LimitHistoryEntry(
                    date = date.toString(),
                    limitMillis = computeLimitMillis(settings.limit, date),
                    keepExisting = true,
                )
            } + LimitHistoryEntry(today.toString(), computeLimitMillis(settings.limit, today)),
            keepKeys = retained,
        )

        var cursor: LocalDate = last
        var record = state.streak
        var processed = 0
        while (cursor.isBefore(today) && processed < MAX_ROLLOVER_DAYS) {
            val key = cursor.toString()
            val used = state.usageHistory[key] ?: 0L
            // 스트릭 정산은 스냅샷이 아니라 **현재 설정**으로 판정한다 — 확장
            // `checkDateRolloverInner`와 같은 규칙이다. streaks 행은 두 클라이언트가 공유하므로
            // 한쪽만 스냅샷으로 바꾸면 같은 날에 대해 서로 다른 스트릭을 계산해 서버 값이
            // 오간다. 위 keepExisting 기록이 "기록이 없던 날"의 판정 근거를 여기와 맞춰준다.
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
                    nudge(resources.getQuantityString(R.plurals.nudge_streak_milestone, it, it))
                }
                result.unlockedPerfectMilestones.forEach {
                    nudge(resources.getQuantityString(R.plurals.nudge_perfect_milestone, it, it))
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
        nudge(getString(R.string.nudge_hardcore_released))
    }

    private suspend fun resetEmergencyAllowanceIfDue(settings: Settings, today: LocalDate) {
        val state = stateStore.state.first()
        val key = emergencyResetKey(settings.emergencyResetFrequency, today)
        if (state.emergencyResetKey == key) return
        stateStore.resetEmergencyAllowance(key, settings.emergencyAllowance)
    }

    /** Promotes a scheduled focus session and retires a finished (or cooled-down-to-stop) one. */
    private suspend fun advanceFocusMode(now: Long) {
        val state = stateStore.state.first()

        val delayEnd = state.focusDelayEndMillis
        if (!state.focusActiveAt(now) && delayEnd != null && now >= delayEnd) {
            stateStore.startFocus(now + minutesToMillis(state.focusDelayDurationMinutes))
            nudge(getString(R.string.nudge_focus_started))
            return
        }

        // Actual stop time is whichever comes first: the session's own natural end, or the
        // cooldown after an early-stop request (resolveFocusStopTime) - a pending stop request
        // never extends a session past when it was going to end anyway.
        val stopAt = resolveFocusStopTime(state.focusEndMillis, state.focusStopRequestedAtMillis)
        if (stopAt != null && now >= stopAt) {
            stateStore.clearFocus()
            nudge(getString(R.string.nudge_focus_ended))
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
            nudge(
                getString(
                    if (active) R.string.nudge_schedule_started else R.string.nudge_schedule_ended,
                ),
            )
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
        nudge(getString(R.string.nudge_schedule_upcoming))
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
        outcome.messages.forEach { nudge(nudgeText(it)) }
    }

    /** [AlarmMessage] carries the numbers; the wording (and its plural) lives in the resources. */
    private fun nudgeText(message: AlarmMessage): String = when (message) {
        is AlarmMessage.WatchedMinutes -> resources.getQuantityString(
            R.plurals.alarm_watching_minutes,
            message.minutes.toInt(),
            message.minutes,
        )

        is AlarmMessage.RemainingMinutes -> resources.getQuantityString(
            R.plurals.alarm_remaining_minutes,
            message.minutes,
            message.minutes,
        )
    }

    private fun grantEmergency() {
        scope.launch {
            val now = System.currentTimeMillis()
            val state = stateStore.state.first()

            // Focus mode blocks "regardless of the daily limit" - emergency (meant as a safety
            // valve for usage-limit blocks) must not be able to cut that short. Usage-limit and
            // manual blocks are unaffected: this check only fires while focus mode is active.
            if (state.focusActiveAt(now)) {
                nudge(getString(R.string.nudge_emergency_blocked_focus))
                return@launch
            }

            // Same principle for a scheduled block window - it is also a commitment device
            // that blocks regardless of the limit, so it gets the same emergency-grant gate.
            val settings = settingsStore.settings.first()
            if (isScheduleActive(now, settings.scheduleWindows) != null) {
                nudge(getString(R.string.nudge_emergency_blocked_schedule))
                return@launch
            }

            // Additive anti-mash gate, independent of the daily/weekly/monthly use-count check
            // consumeEmergency performs below.
            val cooldownRemaining = emergencyGrantCooldownRemainingMillis(state.lastEmergencyGrantedAtMillis, now)
            if (cooldownRemaining > 0) {
                val seconds = ((cooldownRemaining / 1000L) + 1).toInt()
                nudge(resources.getQuantityString(R.plurals.nudge_emergency_cooldown, seconds, seconds))
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
                // 로컬 카운트다운은 남아 있는데 막혔다면, 이번 버킷 몫을 다른 기기가 이미 썼다는 뜻.
                val spentElsewhere = (state.emergencyRemaining ?: settings.emergencyAllowance) > 0
                nudge(
                    getString(
                        if (spentElsewhere) {
                            R.string.nudge_emergency_none_left_elsewhere
                        } else {
                            R.string.nudge_emergency_none_left
                        },
                    ),
                )
            }
        }
    }

    // --- notifications ---

    private fun updateOngoingNotification(usedMillis: Long, limitMillis: Long, blocked: Boolean) {
        val text: String
        val progressPercent: Int?
        when {
            blocked -> {
                text = getString(
                    R.string.notification_ongoing_blocked,
                    formatDuration(this, usedMillis),
                )
                progressPercent = 100
            }
            isUnlimited(limitMillis) -> {
                text = getString(
                    R.string.notification_ongoing_unlimited,
                    formatDuration(this, usedMillis),
                )
                progressPercent = null
            }
            else -> {
                val remaining = (limitMillis - usedMillis).coerceAtLeast(0L)
                text = getString(
                    R.string.notification_ongoing_with_limit,
                    formatDuration(this, usedMillis),
                    formatDuration(this, remaining),
                )
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
            NotificationChannel(
                CHANNEL_ONGOING,
                getString(R.string.notification_channel_ongoing_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.notification_channel_ongoing_description) },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NUDGE,
                getString(R.string.notification_channel_nudge_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = getString(R.string.notification_channel_nudge_description) },
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
