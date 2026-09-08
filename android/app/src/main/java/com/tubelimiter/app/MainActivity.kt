package com.tubelimiter.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tubelimiter.app.auth.AccountState
import com.tubelimiter.app.auth.AuthError
import com.tubelimiter.app.auth.AuthMode
import com.tubelimiter.app.auth.AuthRepository
import com.tubelimiter.app.auth.AuthResult
import com.tubelimiter.app.auth.text
import com.tubelimiter.app.data.AppSettings
import com.tubelimiter.app.data.AppState
import com.tubelimiter.app.data.RuntimeState
import com.tubelimiter.app.data.Settings
import com.tubelimiter.app.diagnostics.buildDiagnosticsReport
import com.tubelimiter.app.diagnostics.staleSyncWarning
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
import com.tubelimiter.app.sync.RemoteDailyUsageRow
import com.tubelimiter.app.sync.SyncRepository
import com.tubelimiter.app.sync.combinedUsedMillis
import com.tubelimiter.app.sync.mergeHistories
import com.tubelimiter.app.ui.AuthScreen
import com.tubelimiter.app.ui.DashboardScreen
import com.tubelimiter.app.ui.HistorySource
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

/**
 * 대시보드가 서버에서 읽어올 기간. 히트맵 28일 + 막대그래프 최대 30일을 다 채우면 충분하고, 더
 * 길게 읽어봐야 그릴 곳이 없이 응답만 커진다 (확장 대시보드의 `HISTORY_LOOKBACK_DAYS`와 같은 값).
 */
private const val DASHBOARD_HISTORY_LOOKBACK_DAYS = 30

private enum class Tab(val labelRes: Int, val glyph: String) {
    HOME(R.string.nav_home, "🏠"),
    DASHBOARD(R.string.nav_dashboard, "📊"),
    SETTINGS(R.string.nav_settings, "⚙️"),
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
            text = stringResource(R.string.app_header_title),
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

    // 대시보드가 그릴 서버 기록. null은 "아직 못 얻었다"(로그아웃·오프라인·조회 실패)라는 뜻이고,
    // 그때는 로컬 기록만으로 그린다 — 대시보드가 통째로 비는 것보다 낫다.
    var serverHistory by remember { mutableStateOf<List<RemoteDailyUsageRow>?>(null) }

    var showAuth by remember { mutableStateOf(false) }
    var authBusy by remember { mutableStateOf(false) }
    var deleteAccountBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<AuthError?>(null) }
    // String resource for the notice under the auth fields, resolved by the screen itself.
    var authNoticeRes by remember { mutableStateOf<Int?>(null) }

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

    // 통계 탭을 열 때마다 서버 기록을 한 번 읽는다. 매 틱이 아니라 탭 진입에 묶은 건, 이 조회가
    // 화면에 그릴 때만 필요하고 차단 판정에는 쓰이지 않기 때문이다(차단은 오늘치 RPC 합계를 쓴다).
    // 로그아웃하면 즉시 비워서 이전 계정 기록이 화면에 남지 않게 한다.
    LaunchedEffect(account.userId, tab) {
        serverHistory = if (tab == Tab.DASHBOARD && account.userId != null) {
            val since = effectiveDate(System.currentTimeMillis())
                .minusDays((DASHBOARD_HISTORY_LOOKBACK_DAYS - 1).toLong())
            sync.fetchDailyUsageSince(since.toString())
        } else {
            null
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
                            Toast.makeText(
                                context,
                                context.getString(R.string.permission_settings_unavailable),
                                Toast.LENGTH_SHORT,
                            ).show()
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
                error = authError,
                noticeRes = authNoticeRes,
                modifier = Modifier.padding(padding),
                onClearMessages = {
                    authError = null
                    authNoticeRes = null
                },
                onCancel = {
                    showAuth = false
                    authError = null
                    authNoticeRes = null
                },
                onSubmit = { mode, email, password ->
                    scope.launch {
                        authBusy = true
                        authError = null
                        authNoticeRes = null
                        val result = when (mode) {
                            AuthMode.SIGN_IN -> authRepository.signIn(email, password)
                            AuthMode.SIGN_UP -> authRepository.signUp(email, password)
                        }
                        authBusy = false
                        when (result) {
                            AuthResult.Success -> showAuth = false
                            AuthResult.ConfirmationRequired ->
                                authNoticeRes = R.string.auth_notice_confirmation_sent

                            is AuthResult.Failed -> authError = result.error
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

    // 로그인 상태에서 마지막 동기화 성공이 하루를 넘겼는지. nowMillis가 1초마다 갱신되므로
    // 임계값을 넘는 순간 별도 트리거 없이 홈 화면에 한 줄이 뜬다.
    val syncWarning = staleSyncWarning(
        signedIn = account.signedIn,
        lastSuccessAtMillis = state.lastSyncSuccessAtMillis,
        hasRecordedFailure = state.diagnosticEvents.isNotEmpty(),
        nowMillis = nowMillis,
    )

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
                        label = { Text(stringResource(entry.labelRes)) },
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
                syncWarning = syncWarning,
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

            Tab.DASHBOARD -> {
                // 로컬 기록과 서버 기록을 날짜별 max로 합친다(합산 금지 — 서버 합계에 이 기기
                // 몫이 이미 들어 있다). 규칙은 확장 `lib/historyMerge.js`와 같아야 한다.
                val merged = mergeHistories(
                    localUsage = state.usageHistory,
                    localEmergencyMillis = state.emergencyMillisHistory,
                    localEmergencyUses = state.emergencyUsesByDate(),
                    serverRows = serverHistory.orEmpty(),
                )
                DashboardScreen(
                    today = today,
                    usageHistory = merged.usage,
                    usageHistoryHourly = state.usageHistoryHourly,
                    emergencyHistory = merged.emergency,
                    limitHistory = state.limitHistory,
                    limitConfig = settings.limit,
                    historySource = when {
                        serverHistory != null -> HistorySource.MERGED
                        account.signedIn -> HistorySource.LOCAL_ONLY
                        else -> HistorySource.SIGNED_OUT
                    },
                    streak = state.streak,
                    achievements = state.achievements,
                    hardcoreMode = settings.hardcoreMode,
                    chartRangeDays = settings.chartRangeDays,
                    // Local-only display preference: written directly rather than through
                    // editSettings, since it isn't part of the account-synced settings row.
                    onChartRangeChange = { scope.launch { settingsStore.setChartRangeDays(it) } },
                    modifier = contentModifier,
                )
            }

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
                            Toast.makeText(context, result.error.text(context), Toast.LENGTH_SHORT).show()
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
                                is AuthResult.Failed -> result.error.text(context)
                                else -> context.getString(R.string.settings_account_deleted)
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
                lastSyncSuccessAtMillis = state.lastSyncSuccessAtMillis,
                diagnosticEvents = state.diagnosticEvents,
                onClearDiagnostics = { scope.launch { stateStore.clearDiagnostics() } },
                // 클립보드에 나가는 건 시각·종류·코드뿐이다 — buildDiagnosticsReport가 그것만
                // 조립하고, 코드 자체도 저장 시점에 이미 걸러진 값이다
                // (diagnostics/SyncDiagnostics.kt의 "민감정보 금지" 주석 참고).
                onCopyDiagnostics = {
                    val report = buildDiagnosticsReport(state.lastSyncSuccessAtMillis, state.diagnosticEvents)
                    val clipLabel = context.getString(R.string.settings_diagnostics_clip_label)
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(clipLabel, report))
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_diagnostics_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = contentModifier,
            )
        }
    }
}
