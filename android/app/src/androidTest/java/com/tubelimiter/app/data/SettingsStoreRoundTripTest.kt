package com.tubelimiter.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tubelimiter.app.limit.EmergencyResetFrequency
import com.tubelimiter.app.limit.LimitConfig
import com.tubelimiter.app.limit.LimitFrequency
import com.tubelimiter.app.limit.ScheduleWindow
import com.tubelimiter.app.limit.UNLIMITED_MINUTES
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 이 저장소의 **첫 계측 테스트**다. 왜 하필 이것인지를 남겨둔다.
 *
 * QA 리뷰(`documents/QA_REVIEW.md` §5)가 "계측 테스트 0개"를 최우선으로 꼽으면서 예시로 든 것은
 * "앱 실행 → 오버레이 표시 확인"이었다. **그걸 고르지 않았다.**
 * [com.tubelimiter.app.block.BlockOverlay]는 `SYSTEM_ALERT_WINDOW` 특수 권한을 요구하고
 * (`Settings.canDrawOverlays`가 false면 그리지 않고 그냥 돌아온다), 이건 런타임 권한이 아니라
 * `GrantPermissionRule`로 줄 수 없다. 에뮬레이터에서 주려면 테스트가 셸로
 * `appops set … SYSTEM_ALERT_WINDOW allow`를 쏘고 반영을 기다려야 하고, "떴는지" 확인하려면
 * 다른 앱 창 위에 그려진 뷰를 UiAutomator로 찾아야 한다. 첫 테스트가 권한 타이밍과 창 계층
 * 위에 얹혀 있으면 **빨간불이 떴을 때 제품이 깨진 건지 CI가 깨진 건지 구별할 수 없다.**
 * 0 → 1에서 필요한 건 신호가 분명한 1이다.
 *
 * **대신 고른 것: DataStore 왕복.** 이 앱의 모든 설정과 모든 런타임 상태는 [AppSettings]와
 * [AppState]를 거쳐 **하나의** Preferences DataStore 파일(`tubelimiter_settings`)로 들어간다.
 * 그런데 지금까지 유닛 테스트가 검증한 것은 `Encoding.kt`의 문자열 인코딩뿐이고,
 * **DataStore 자체는 한 번도 실행된 적이 없다.** 그래서 다음 고장은 유닛 테스트를 전부 통과한다:
 *
 * 1. `replaceAll`이 쓰는 키와 읽는 쪽(`toSettings()`)이 어긋나 저장이 조용히 증발하는 경우.
 * 2. `resetToDefaults`가 키를 빠뜨려 삭제된 계정의 설정이 기기에 남는 경우.
 * 3. **[AppSettings]와 [AppState]가 같은 파일을 공유**하기 때문에 한쪽 초기화가 다른 쪽 값을
 *    같이 날리는 경우. `ACCOUNT_DATA_KEYS` 주석이 "통째 삭제(`prefs.clear()`)를 쓰면 안 된다"고
 *    못 박은 계약이 바로 이것이고, 깨지면 **계정 전환이 곧 차단 해제 수단**이 된다.
 *
 * 셋 다 사용자에게는 "설정이 사라졌다" 또는 "차단이 풀렸다"로 나타나는, 가장 크게 다치는
 * 종류의 고장이다. 그리고 셋 다 실제 안드로이드 런타임에서 파일에 쓰고 다시 읽어봐야만 잡힌다.
 *
 * 주의: 계측 테스트는 앱 프로세스 안에서 돌기 때문에 여기서 쓰는 DataStore는 **앱이 실제로 쓰는
 * 그 파일**이다(`preferencesDataStore` 위임이 프로세스당 하나다). 그래서 각 테스트는 앞뒤로
 * 자기가 건드린 값을 되돌린다.
 */
@RunWith(AndroidJUnit4::class)
class SettingsStoreRoundTripTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settingsStore = AppSettings(context)
    private val stateStore = AppState(context)

    @Before
    fun setUp() = resetStore()

    @After
    fun tearDown() = resetStore()

    private fun resetStore() = runBlocking {
        settingsStore.resetToDefaults()
        stateStore.clearAccountData()
        stateStore.setManuallyBlocked(false)
    }

    /**
     * 동기화되는 설정 한 벌을 저장하고 다시 읽는다. 라벨에 한글을 넣은 것은 의도적이다 —
     * 예약 차단은 사용자가 친 자유 텍스트를 제어문자 구분자로 이어 붙여 한 문자열로 저장하는데,
     * 그 문자열이 DataStore를 왕복하고도 그대로인지는 여기서만 확인된다.
     */
    @Test
    fun replaceAll_thenRead_roundTripsEverySyncedField() = runBlocking {
        val window = ScheduleWindow(
            id = "curfew-1",
            label = "취침 전 차단",
            days = listOf(true, false, true, false, true, false, true),
            startMinute = 23 * 60,
            endMinute = 6 * 60,
            enabled = true,
        )
        val custom = Settings(
            limit = LimitConfig(
                dailyLimitMinutes = 47,
                byDayMinutes = listOf(10, 20, 30, UNLIMITED_MINUTES, 50, 60, 70),
                frequency = LimitFrequency.BY_DAY,
            ),
            monitoringEnabled = false,
            emergencyAllowance = 4,
            emergencyResetFrequency = EmergencyResetFrequency.WEEKLY,
            alarmIntervalMinutes = 15,
            alarmMilestonesEnabled = false,
            hardcoreMode = true,
            hardcoreDisableRequestedAt = 1_760_000_000_000L,
            scheduleWindows = listOf(window),
        )

        settingsStore.replaceAll(custom)
        val loaded = settingsStore.settings.first()

        assertEquals(custom.limit, loaded.limit)
        assertEquals(custom.monitoringEnabled, loaded.monitoringEnabled)
        assertEquals(custom.emergencyAllowance, loaded.emergencyAllowance)
        assertEquals(custom.emergencyResetFrequency, loaded.emergencyResetFrequency)
        assertEquals(custom.alarmIntervalMinutes, loaded.alarmIntervalMinutes)
        assertEquals(custom.alarmMilestonesEnabled, loaded.alarmMilestonesEnabled)
        assertEquals(custom.hardcoreMode, loaded.hardcoreMode)
        assertEquals(custom.hardcoreDisableRequestedAt, loaded.hardcoreDisableRequestedAt)
        assertEquals(custom.scheduleWindows, loaded.scheduleWindows)
    }

    /**
     * 서버에서 받은 설정을 통째로 덮어써도 기기별 값은 살아남아야 한다. `replaceAll`이 이 두
     * 키를 일부러 안 쓰는 것이 계약이고([DEVICE_LOCAL_SETTINGS_KEYS] 주석), 실수로 넣으면
     * 동기화가 돌 때마다 사용자의 차트 범위와 뮤직 감시 설정이 되돌아간다.
     */
    @Test
    fun replaceAll_leavesDeviceLocalPreferencesAlone() = runBlocking {
        settingsStore.setChartRangeDays(30)
        settingsStore.setWatchYouTubeMusic(true)

        settingsStore.replaceAll(Settings())
        val loaded = settingsStore.settings.first()

        assertEquals(30, loaded.chartRangeDays)
        assertTrue(loaded.watchYouTubeMusic)
    }

    /**
     * 계정 삭제 경로. 설정은 기본값으로 돌아가되, **같은 파일에 들어 있는 [AppState]의 기록과
     * 지금 걸려 있는 차단은 [AppSettings]가 건드리면 안 된다.** 이게 깨지면 설정 초기화가
     * 사용량 기록을 지우거나 수동 차단을 풀어버린다.
     */
    @Test
    fun resetToDefaults_clearsSettingsButNotTheStateSharingTheSameFile() = runBlocking {
        val dateKey = "2026-09-10"
        stateStore.recordUsage(dateKey, 123_000L, keepKeys = setOf(dateKey))
        stateStore.setManuallyBlocked(true)
        settingsStore.replaceAll(Settings(limit = LimitConfig(dailyLimitMinutes = 5), hardcoreMode = true))

        settingsStore.resetToDefaults()

        val loadedSettings = settingsStore.settings.first()
        assertEquals(Settings(), loadedSettings)

        val loadedState = stateStore.state.first()
        assertEquals(123_000L, loadedState.usageHistory[dateKey])
        assertTrue(loadedState.manuallyBlocked)
    }

    /**
     * 계정 전환 경로. 새 주인이 로그인했을 때 `hardcore_mode`가 남으면 직전 계정의 잠금을
     * 물려받는다(BACKEND.md "계정 전환" 7번). 반대로 기기별 값까지 날리면 안 된다 —
     * 계정 삭제와 계정 전환이 다른 사건이라는 것이 [AppSettings.resetSyncedToDefaults]의 존재
     * 이유 전부다.
     */
    @Test
    fun resetSyncedToDefaults_dropsTheAccountsLockButKeepsThisDevicesPreferences() = runBlocking {
        settingsStore.replaceAll(Settings(hardcoreMode = true, emergencyAllowance = 9))
        settingsStore.setChartRangeDays(7)
        settingsStore.setWatchYouTubeMusic(true)

        settingsStore.resetSyncedToDefaults()
        val loaded = settingsStore.settings.first()

        assertEquals(false, loaded.hardcoreMode)
        assertEquals(Settings().emergencyAllowance, loaded.emergencyAllowance)
        assertEquals(7, loaded.chartRangeDays)
        assertTrue(loaded.watchYouTubeMusic)
    }
}
