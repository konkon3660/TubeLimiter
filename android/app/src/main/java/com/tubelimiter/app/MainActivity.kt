package com.tubelimiter.app

import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tubelimiter.app.auth.AccountState
import com.tubelimiter.app.auth.AuthMode
import com.tubelimiter.app.auth.AuthRepository
import com.tubelimiter.app.auth.AuthResult
import com.tubelimiter.app.data.AppSettings
import com.tubelimiter.app.data.AppState
import com.tubelimiter.app.data.RuntimeState
import com.tubelimiter.app.data.Settings
import com.tubelimiter.app.limit.BlockInputs
import com.tubelimiter.app.limit.blockReason
import com.tubelimiter.app.limit.computeLimitMillis
import com.tubelimiter.app.limit.effectiveEmergencyRemaining
import com.tubelimiter.app.limit.hardcoreCooldownRemainingMillis
import com.tubelimiter.app.limit.isScheduleActive
import com.tubelimiter.app.limit.minutesToMillis
import com.tubelimiter.app.permission.AppPermission
import com.tubelimiter.app.permission.PermissionChecker
import com.tubelimiter.app.permission.allGranted
import com.tubelimiter.app.permission.requiredPermissions
import com.tubelimiter.app.service.UsageMonitorService
import com.tubelimiter.app.sync.SyncRepository
import com.tubelimiter.app.sync.combinedUsedMillis
import com.tubelimiter.app.ui.AuthScreen
import com.tubelimiter.app.ui.DashboardScreen
import com.tubelimiter.app.ui.HomeScreen
import com.tubelimiter.app.ui.OnboardingScreen
import com.tubelimiter.app.ui.SettingsScreen
import com.tubelimiter.app.ui.theme.BrandPrimary
import com.tubelimiter.app.ui.theme.BrandPrimaryHover
import com.tubelimiter.app.ui.theme.TubeLimiterTheme
import com.tubelimiter.app.usage.UsageStatsReader
import com.tubelimiter.app.usage.effectiveDate
import com.tubelimiter.app.usage.startOfEffectiveDayMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TubeLimiterTheme {
                AppRoot()
            }
        }
    }
}

private enum class Tab(val label: String, val glyph: String) {
    HOME("홈", "🏠"),
    DASHBOARD("통계", "📊"),
    SETTINGS("설정", "⚙️"),
}

/** Same indigo gradient banner as the extension popup's `.header`, so both clients read as one app. */
@Composable
private fun AppHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(BrandPrimary, BrandPrimaryHover)))
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "🎯 TubeLimiter",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
        )
    }
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val settingsStore = remember { AppSettings(context) }
    val stateStore = remember { AppState(context) }
    val checker = remember { PermissionChecker(context) }
    val reader = remember { UsageStatsReader(context) }
    val authRepository = remember { AuthRepository(context) }
    val sync = remember { SyncRepository(authRepository, settingsStore, stateStore) }

    val scope = rememberCoroutineScope()
    val required = remember { requiredPermissions(Build.VERSION.SDK_INT) }

    var granted by remember { mutableStateOf(checker.snapshot()) }
    var usedMillis by remember { mutableStateOf(0L) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var tab by remember { mutableStateOf(Tab.HOME) }

    var showAuth by remember { mutableStateOf(false) }
    var authBusy by remember { mutableStateOf(false) }
    var deleteAccountBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var authNotice by remember { mutableStateOf<String?>(null) }

    val settings by settingsStore.settings.collectAsStateWithLifecycle(Settings())
    val state by stateStore.state.collectAsStateWithLifecycle(RuntimeState())
    val account by authRepository.account.collectAsStateWithLifecycle(AccountState())

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = checker.snapshot() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = checker.snapshot()
    }

    val ready = allGranted(required, granted)

    // Local write first so the UI reacts at once, then mirror it to the account.
    fun editSettings(block: suspend () -> Unit) {
        scope.launch {
            block()
            sync.pushSettings()
        }
    }

    // Signing in adopts whatever the account already has, including the extension's settings.
    LaunchedEffect(account.userId) {
        if (account.userId != null) {
            sync.pullSettings()
            sync.pullStreak()
        }
    }

    LaunchedEffect(ready, settings.monitoringEnabled) {
        if (ready && settings.monitoringEnabled) {
            UsageMonitorService.start(context)
        } else {
            UsageMonitorService.stop(context)
        }
    }

    // While the screen is up, refresh often enough to watch the numbers move.
    LaunchedEffect(ready) {
        while (ready) {
            val now = System.currentTimeMillis()
            nowMillis = now
            usedMillis = withContext(Dispatchers.IO) {
                reader.snapshot(
                    windowStart = startOfEffectiveDayMillis(now),
                    windowEnd = now,
                ).usedMillis
            }
            delay(1_000)
        }
    }

    if (!ready) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = { AppHeader() }) { padding ->
            OnboardingScreen(
                required = required,
                granted = granted,
                modifier = Modifier.padding(padding),
                onRequest = { permission ->
                    if (permission == AppPermission.NOTIFICATIONS) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val intent = checker.settingsIntentFor(permission)
                        try {
                            if (intent != null) context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
        }
        return
    }

    if (showAuth) {
        Scaffold(modifier = Modifier.fillMaxSize(), topBar = { AppHeader() }) { padding ->
            AuthScreen(
                busy = authBusy,
                errorMessage = authError,
                noticeMessage = authNotice,
                modifier = Modifier.padding(padding),
                onClearMessages = {
                    authError = null
                    authNotice = null
                },
                onCancel = {
                    showAuth = false
                    authError = null
                    authNotice = null
                },
                onSubmit = { mode, email, password ->
                    scope.launch {
                        authBusy = true
                        authError = null
                        authNotice = null
                        val result = when (mode) {
                            AuthMode.SIGN_IN -> authRepository.signIn(email, password)
                            AuthMode.SIGN_UP -> authRepository.signUp(email, password)
                        }
                        authBusy = false
                        when (result) {
                            AuthResult.Success -> showAuth = false
                            AuthResult.ConfirmationRequired ->
                                authNotice = "가입 확인 메일을 보냈어요. 메일의 링크를 누른 뒤 로그인해주세요."

                            is AuthResult.Failed -> authError = result.message
                        }
                    }
                },
            )
        }
        return
    }

    val today = effectiveDate(nowMillis)
    val todayKey = today.toString()
    val limitMillis = computeLimitMillis(settings.limit, today)

    // Same combined figure the background service blocks on (documents/BACKEND.md) — this device's
    // own reading plus whatever other devices on the account have reported for today.
    val effectiveUsedMillis = if (state.dailyUsageSyncDate == todayKey) {
        combinedUsedMillis(usedMillis, state.dailyUsageSyncedMillis, state.dailyUsageCombinedMillis)
    } else {
        usedMillis
    }

    val scheduleWindow = isScheduleActive(nowMillis, settings.scheduleWindows)

    val blockReason = BlockInputs(
        usedMillis = effectiveUsedMillis,
        limitMillis = limitMillis,
        emergencyActive = state.emergencyActiveAt(nowMillis),
        focusModeActive = state.focusActiveAt(nowMillis),
        scheduleBlockActive = scheduleWindow != null,
        manuallyBlocked = state.manuallyBlocked,
    ).blockReason()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { AppHeader() },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Text(entry.glyph) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (tab) {
            Tab.HOME -> HomeScreen(
                usedMillis = effectiveUsedMillis,
                limitMillis = limitMillis,
                blockReason = blockReason,
                focusEndMillis = state.focusEndMillis,
                focusDelayEndMillis = state.focusDelayEndMillis,
                focusDelayDurationMinutes = state.focusDelayDurationMinutes,
                focusStopRequestedAtMillis = state.focusStopRequestedAtMillis,
                manuallyBlocked = state.manuallyBlocked,
                // 홈 화면의 "긴급 시청 남음"도 계정 합계 기준 — 다른 기기가 쓴 몫이 빠진다.
                emergencyRemaining = effectiveEmergencyRemaining(
                    state.emergencyRemaining,
                    settings.emergencyAllowance,
                    state.cachedOtherDeviceEmergencyUses(),
                ),
                monitoringEnabled = settings.monitoringEnabled,
                hardcoreMode = settings.hardcoreMode,
                streak = state.streak,
                scheduleWindow = scheduleWindow,
                nowMillis = nowMillis,
                onStartFocus = { delayMinutes, durationMinutes ->
                    scope.launch {
                        if (delayMinutes > 0) {
                            stateStore.scheduleFocus(
                                delayEndMillis = System.currentTimeMillis() + minutesToMillis(delayMinutes),
                                durationMinutes = durationMinutes,
                            )
                        } else {
                            stateStore.startFocus(System.currentTimeMillis() + minutesToMillis(durationMinutes))
                        }
                    }
                },
                // A session already active is asked to stop (10-minute cooldown, see
                // FOCUS_STOP_COOLDOWN_MILLIS); a not-yet-started scheduled session is canceled
                // outright, since it never started blocking. Re-read state fresh rather than
                // trusting the composable's snapshot, in case it's a tick stale.
                onStopFocus = {
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val current = stateStore.state.first()
                        if (current.focusActiveAt(now)) {
                            stateStore.requestFocusStop(now)
                        } else {
                            stateStore.clearFocus()
                        }
                    }
                },
                onCancelFocusStop = { scope.launch { stateStore.cancelFocusStop() } },
                onManualBlockChange = { scope.launch { stateStore.setManuallyBlocked(it) } },
                onMonitoringChange = { scope.launch { settingsStore.setMonitoringEnabled(it) } },
                modifier = contentModifier,
            )

            Tab.DASHBOARD -> DashboardScreen(
                today = today,
                usageHistory = state.usageHistory,
                usageHistoryHourly = state.usageHistoryHourly,
                emergencyMillisHistory = state.emergencyMillisHistory,
                emergencyUseHistory = state.emergencyUseHistory,
                limitConfig = settings.limit,
                streak = state.streak,
                achievements = state.achievements,
                hardcoreMode = settings.hardcoreMode,
                chartRangeDays = settings.chartRangeDays,
                // Local-only display preference: written directly rather than through
                // editSettings, since it isn't part of the account-synced settings row.
                onChartRangeChange = { scope.launch { settingsStore.setChartRangeDays(it) } },
                modifier = contentModifier,
            )

            Tab.SETTINGS -> SettingsScreen(
                settings = settings,
                hardcoreCooldownRemaining = hardcoreCooldownRemainingMillis(
                    settings.hardcoreDisableRequestedAt,
                    nowMillis,
                ),
                accountEmail = account.email,
                accountLoading = account.loading,
                onSignInClick = { showAuth = true },
                onSignOut = {
                    scope.launch {
                        val result = authRepository.signOut()
                        if (result is AuthResult.Failed) {
                            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                deleteAccountInFlight = deleteAccountBusy,
                // Unlike a sign-out, which leaves this device's data in place for the next
                // sign-in, deletion has to take the local copy with it: the account it was
                // synced from no longer exists. Local wipe only on success, and written
                // straight to the stores rather than through editSettings, which would try
                // to push the reset back up to the row that was just deleted.
                onDeleteAccount = {
                    if (!deleteAccountBusy) {
                        scope.launch {
                            deleteAccountBusy = true
                            val result = authRepository.deleteAccount()
                            if (result is AuthResult.Success) {
                                stateStore.clearAccountData()
                                settingsStore.resetToDefaults()
                            }
                            deleteAccountBusy = false
                            val notice = when (result) {
                                is AuthResult.Failed -> result.message
                                else -> "계정이 삭제되었습니다."
                            }
                            Toast.makeText(context, notice, Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onDailyLimitChange = { editSettings { settingsStore.setDailyLimitMinutes(it) } },
                onByDayChange = { index, minutes ->
                    editSettings {
                        val updated = settings.limit.byDayMinutes.toMutableList()
                        updated[index] = minutes
                        settingsStore.setByDayMinutes(updated)
                    }
                },
                onFrequencyChange = { editSettings { settingsStore.setLimitFrequency(it) } },
                onEmergencyAllowanceChange = { editSettings { settingsStore.setEmergencyAllowance(it) } },
                onEmergencyResetChange = { editSettings { settingsStore.setEmergencyResetFrequency(it) } },
                onAlarmIntervalChange = { editSettings { settingsStore.setAlarmIntervalMinutes(it) } },
                onAlarmMilestonesChange = { editSettings { settingsStore.setAlarmMilestonesEnabled(it) } },
                onHardcoreEnable = { editSettings { settingsStore.setHardcoreMode(true) } },
                onHardcoreDisableRequest = {
                    editSettings { settingsStore.requestHardcoreDisable(System.currentTimeMillis()) }
                },
                onHardcoreDisableCancel = { editSettings { settingsStore.cancelHardcoreDisable() } },
                scheduleWindows = settings.scheduleWindows,
                onScheduleWindowsChange = { editSettings { settingsStore.setScheduleWindows(it) } },
                modifier = contentModifier,
            )
        }
    }
}
