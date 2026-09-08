import { getStorage, setStorage } from '../lib/storage.js';
import { getTodayDate, getWeekStartDate, getMonthStartDate } from '../lib/time.js';
import { supabase, getCurrentUser } from '../lib/supabaseClient.js';
import { getLevelProgress } from '../lib/gamification.js';
import { computeLimitForDate, computeShortsLimit } from '../lib/limits.js';
import { combinedUsedMillis, remainingEmergencyUses } from '../lib/usageMerge.js';
import { emergencyResetDate, DEFAULT_EMERGENCY_USES } from '../lib/dateRollover.js';
import { resolveFocusStopTime } from '../lib/focusMode.js';
import { EMERGENCY_GRANT_COOLDOWN_MS } from '../lib/emergency.js';
import { isScheduleActive } from '../lib/schedule.js';
import { staleSyncWarning, StaleSyncReason } from '../lib/syncDiagnostics.js';
import { readDiagnostics } from '../lib/diagnosticsStore.js';
import { applyI18n, pluralMessageKey, t, tCount } from '../lib/i18n.js';

// HTML의 고정 문구부터 채우고 시작한다. 아래 렌더가 같은 엘리먼트를 다시 덮어쓰는 곳도 있지만,
// 그 전에 한 번 채워둬야 네트워크를 기다리는 동안 라벨이 비어 보이지 않는다.
applyI18n();

const signedOutView = document.getElementById('signedOutView');
const signedInView = document.getElementById('signedInView');
const syncWarningEl = document.getElementById('syncWarning');

// 로그인 여부는 네트워크를 타는 renderRemote에서만 확인한다(renderLocal은 1초마다 돈다).
// 확인 전에는 false — 로그아웃 상태에서는 애초에 동기화 경고를 띄우지 않으므로 안전한 기본값이다.
let isSignedIn = false;

document.getElementById('openAuthButton').addEventListener('click', () => {
  chrome.tabs.create({ url: chrome.runtime.getURL('auth/auth.html') });
});

document.querySelectorAll('.tab-button').forEach((btn) => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.tab-button').forEach((b) => b.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach((c) => c.classList.remove('active'));
    btn.classList.add('active');
    document.getElementById(btn.dataset.tab).classList.add('active');
  });
});

document.getElementById('viewDashboardButton').addEventListener('click', () => {
  chrome.tabs.create({ url: chrome.runtime.getURL('dashboard/dashboard.html') });
});
document.getElementById('optionsButton').addEventListener('click', () => {
  chrome.runtime.openOptionsPage();
});

const emergencyButton = document.getElementById('emergencyButton');
const modeStatus = document.getElementById('modeStatus');
let emergencyCountdownTimer = null;
let focusCountdownTimer = null;

emergencyButton.addEventListener('click', async () => {
  const res = await chrome.runtime.sendMessage({ action: 'requestEmergency' });
  if (!res.success) alert(res.message);
  renderLocal();
});

const focusModeDurationInput = document.getElementById('focusModeDurationInput');
const focusModeDelayInput = document.getElementById('focusModeDelayInput');

// 팝업은 열 때마다 DOM이 새로 생성되어 입력값이 HTML 기본값으로 리셋된다.
// 지연(분)을 입력해놓고 팝업이 닫혔다 열리면 0으로 되돌아가 의도치 않게 즉시 차단되므로 storage에 보존한다.
getStorage(['focusModeFormDuration', 'focusModeFormDelay']).then(
  ({ focusModeFormDuration, focusModeFormDelay }) => {
    if (focusModeFormDuration != null) focusModeDurationInput.value = focusModeFormDuration;
    if (focusModeFormDelay != null) focusModeDelayInput.value = focusModeFormDelay;
  }
);
focusModeDurationInput.addEventListener('input', () => {
  setStorage({ focusModeFormDuration: focusModeDurationInput.value });
});
focusModeDelayInput.addEventListener('input', () => {
  setStorage({ focusModeFormDelay: focusModeDelayInput.value });
});

document.getElementById('startFocusModeButton').addEventListener('click', async () => {
  const duration = Number(focusModeDurationInput.value) || 30;
  const delay = Number(focusModeDelayInput.value) || 0;
  await chrome.runtime.sendMessage({ action: 'startFocusMode', duration, delay });
  renderLocal();
});
const stopFocusModeButton = document.getElementById('stopFocusModeButton');
const cancelFocusStopButton = document.getElementById('cancelFocusStopButton');
// 예약 취소(시작 전)와 활성 세션 종료 요청(10분 쿨다운) 둘 다 같은 액션으로 보낸다 -
// 어느 쪽인지는 background가 focusModeActive 여부로 판단해서 분기한다.
stopFocusModeButton.addEventListener('click', async () => {
  await chrome.runtime.sendMessage({ action: 'stopFocusMode' });
  renderLocal();
});
cancelFocusStopButton.addEventListener('click', async () => {
  await chrome.runtime.sendMessage({ action: 'cancelFocusStopRequest' });
  renderLocal();
});

function formatMinutes(ms) {
  if (!Number.isFinite(ms)) return t('common_unlimited');
  return tCount('minutes', Math.floor(ms / 60000));
}

function formatCountdown(ms) {
  const totalSeconds = Math.max(0, Math.ceil(ms / 1000));
  const m = Math.floor(totalSeconds / 60);
  const s = totalSeconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

// 예약 차단은 카운트다운이 아니라 "몇 시까지"로 보여준다 (options.js가 편집하는 벽시계 시각).
function formatMinuteOfDay(minutes) {
  const normalized = ((minutes % 1440) + 1440) % 1440;
  const h = Math.floor(normalized / 60);
  const m = normalized % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

// 뱃지 문구는 사용자가 입력한 라벨(예약 차단)까지 들어갈 수 있어 innerHTML로 끼워 넣지 않는다.
function setBadge(container, badgeClass, text) {
  const badge = document.createElement('span');
  badge.className = `status-badge ${badgeClass}`;
  badge.textContent = text;
  container.replaceChildren(badge);
}

function stopEmergencyCountdown() {
  if (emergencyCountdownTimer) {
    clearInterval(emergencyCountdownTimer);
    emergencyCountdownTimer = null;
  }
}

// 집중 모드와 긴급 시청은 동시에 활성화될 일이 없어(긴급 시청은 차단 상태에서만 요청 가능하고
// 요청 즉시 차단을 우회하므로) 하나의 뱃지로 표시한다.
function startEmergencyCountdown(emergencyEndTime) {
  stopEmergencyCountdown();
  const tick = () => {
    const remainingMs = emergencyEndTime - Date.now();
    if (remainingMs <= 0) {
      stopEmergencyCountdown();
      renderLocal();
      return;
    }
    setBadge(
      modeStatus,
      'status-active',
      t('popup_mode_emergency', [formatCountdown(remainingMs)])
    );
  };
  tick();
  emergencyCountdownTimer = setInterval(tick, 1000);
}

function stopFocusCountdown() {
  if (focusCountdownTimer) {
    clearInterval(focusCountdownTimer);
    focusCountdownTimer = null;
  }
}

function startFocusCountdown(endTime, labelKey, badgeClass) {
  stopFocusCountdown();
  const tick = () => {
    const remainingMs = endTime - Date.now();
    if (remainingMs <= 0) {
      stopFocusCountdown();
      renderLocal();
      return;
    }
    setBadge(modeStatus, badgeClass, t(labelKey, [formatCountdown(remainingMs)]));
  };
  tick();
  focusCountdownTimer = setInterval(tick, 1000);
}

// 동기화 경고는 판정(lib/syncDiagnostics.js staleSyncWarning)이 사유만 돌려주고, 문구는 여기서
// 고른다 — 그래야 그 판정이 chrome.i18n 없이 node:test에서 그대로 돌아간다.
function formatSyncWarning(warning) {
  if (!warning) return '';
  if (warning.reason === StaleSyncReason.NEVER_SUCCEEDED) return t('popup_sync_warning_never');
  return tCount('popup_sync_warning_stale', warning.hours);
}

// 로그인 상태 확인(supabase.auth.getUser())과 스트릭/XP 조회는 매번 네트워크를 탄다.
// 이걸 3초 폴링에 매번 끼워 넣으면 응답이 느려질 때 폴링 자체가 밀리면서
// "팝업 켠 시점 데이터로 고정된 것처럼" 보이는 문제가 생긴다.
// 그래서 로그인/스트릭(느림, 드물게 바뀜)과 사용량/모드(빠름, 자주 바뀜)를 분리해서
// 빠른 쪽만 짧은 주기로 갱신한다.
async function renderLocal() {
  // getTrackingStatus는 백그라운드의 트래킹을 그때그때 플러시시키는 부작용이 있다
  // (usage_history가 1분 알람에서만 기록되는 걸 팝업이 열려있는 동안엔 우회하기 위함).
  // storage를 읽기 전에 먼저 호출해야 방금 플러시된 최신 값을 바로 보여줄 수 있다.
  let isTracking = false;
  try {
    ({ isTracking } = await chrome.runtime.sendMessage({ action: 'getTrackingStatus' }));
  } catch (e) {
    console.error('[TubeLimiter] getTrackingStatus 실패:', e);
  }

  const [
    { usage_history },
    { usage_history_shorts },
    { settingsCache },
    { emergency_uses_today },
    { focusModeActive, focusModeEndTime, focusModeDelayEndTime, focusStopRequestedAt },
    { emergencyModeActive, emergencyEndTime, last_emergency_granted_at },
    dailySync,
    emergencyBucket
  ] = await Promise.all([
    getStorage(['usage_history']),
    getStorage(['usage_history_shorts']),
    getStorage(['settingsCache']),
    getStorage(['emergency_uses_today']),
    getStorage([
      'focusModeActive',
      'focusModeEndTime',
      'focusModeDelayEndTime',
      'focusStopRequestedAt'
    ]),
    getStorage(['emergencyModeActive', 'emergencyEndTime', 'last_emergency_granted_at']),
    getStorage([
      'dailyUsageSyncDate',
      'dailyUsageSyncedMillis',
      'dailyUsageCombinedMillis',
      'dailyUsageShortsSyncedMillis',
      'dailyUsageCombinedShortsMillis'
    ]),
    getStorage([
      'emergencyUsesBucketDate',
      'emergencyUsesBucketRemote',
      'emergencyUsesBucketReported'
    ])
  ]);

  const today = getTodayDate();
  const localUsage = (usage_history || {})[today] || 0;
  // 백그라운드가 주기적으로 다른 기기 몫을 동기화해두면(다음 기기 몫 합산은
  // service-worker.js의 syncUsageToSupabase 참고) 여기서도 같은 값을 보여준다.
  const todayUsage =
    dailySync.dailyUsageSyncDate === today
      ? combinedUsedMillis(
          localUsage,
          dailySync.dailyUsageSyncedMillis || 0,
          dailySync.dailyUsageCombinedMillis || 0
        )
      : localUsage;
  const limitMs = computeLimitForDate(settingsCache, today);
  const remaining = Number.isFinite(limitMs) ? Math.max(0, limitMs - todayUsage) : Infinity;

  document.getElementById('usageTime').textContent = formatMinutes(todayUsage);
  document.getElementById('untilBlock').textContent = Number.isFinite(remaining)
    ? formatCountdown(remaining)
    : t('common_unlimited');

  // Shorts는 전체 사용량과 같은 기준(로컬 + 다른 기기 몫)으로 보여준다 — 팝업 숫자와 실제
  // 차단 판정(service-worker.js getEffectiveTodayShortsUsage)이 어긋나면 "아직 남았는데 막혔다"가 된다.
  const localShorts = (usage_history_shorts || {})[today] || 0;
  const todayShorts =
    dailySync.dailyUsageSyncDate === today
      ? combinedUsedMillis(
          localShorts,
          dailySync.dailyUsageShortsSyncedMillis || 0,
          dailySync.dailyUsageCombinedShortsMillis || 0
        )
      : localShorts;
  const shortsLimitMs = computeShortsLimit(settingsCache);
  // 한도를 안 걸었으면 "12분 / 무제한"이 아니라 그냥 사용량만 보여준다 (없는 한도를 강조할 이유가 없다).
  document.getElementById('shortsUsage').textContent = Number.isFinite(shortsLimitMs)
    ? t('popup_usage_of_limit', [formatMinutes(todayShorts), formatMinutes(shortsLimitMs)])
    : formatMinutes(todayShorts);

  const progressCircle = document.getElementById('progressCircle');
  if (Number.isFinite(limitMs) && limitMs > 0) {
    const percentage = Math.min(100, Math.max(0, (todayUsage / limitMs) * 100));
    const degrees = (percentage / 100) * 360;
    progressCircle.style.background = `conic-gradient(var(--primary-color) ${degrees}deg, var(--light-gray) ${degrees}deg)`;
    progressCircle.title = t('popup_until_block_title', [formatCountdown(remaining)]);
  } else {
    progressCircle.style.background = 'var(--primary-color)';
    progressCircle.title = t('common_unlimited');
  }
  // 남은 횟수는 이 기기 카운터가 아니라 "다른 기기가 이 버킷에서 쓴 몫"까지 뺀 값이다.
  // 백그라운드가 동기화할 때 서버 버킷 합계를 캐시해둔다(service-worker.js
  // refreshEmergencyUsesBucket). 캐시가 다른 버킷 것이면 로컬 값 그대로 — 오프라인/로그아웃에선
  // 기존과 똑같이 보인다.
  const localEmergencyUses =
    emergency_uses_today ?? settingsCache?.emergency_config?.dailyUses ?? DEFAULT_EMERGENCY_USES;
  const emergencyBucketStart = emergencyResetDate(settingsCache?.emergency_config?.resetFrequency, {
    today,
    weekStart: getWeekStartDate(),
    monthStart: getMonthStartDate()
  });
  const emergencyLeft =
    emergencyBucket.emergencyUsesBucketDate === emergencyBucketStart
      ? remainingEmergencyUses(
          localEmergencyUses,
          emergencyBucket.emergencyUsesBucketReported || 0,
          emergencyBucket.emergencyUsesBucketRemote || 0
        )
      : localEmergencyUses;
  document.getElementById('emergencyCount').textContent = tCount('times', emergencyLeft);

  const focusActive = !!(focusModeActive && focusModeEndTime > Date.now());
  const focusScheduled =
    !focusActive && !!(focusModeDelayEndTime && focusModeDelayEndTime > Date.now());
  const focusStopPending = focusActive && !!focusStopRequestedAt;

  // 예약 차단: 옵션 페이지에서만 켜고 끌 수 있다 (의도적 - 팝업에 끄기 버튼을 안 두는 게
  // 진짜 커밋먼트 장치가 되는 핵심이라, 여기선 읽기 전용 상태 표시만 한다).
  const { active: scheduleActive, window: scheduleWindow } = isScheduleActive(
    new Date(),
    settingsCache?.scheduled_blocks || []
  );

  // 집중 모드 종료 버튼: 활성 세션은 "종료 요청"(10분 쿨다운 시작), 예약 대기 중은 즉시 취소.
  // 이미 종료를 요청해둔 상태면 종료 버튼 대신 "예약 취소"만 보여준다 (renderLocal은 1초마다
  // 다시 불리므로 이 토글도 그때그때 최신 상태를 반영한다).
  stopFocusModeButton.style.display = focusStopPending ? 'none' : '';
  cancelFocusStopButton.style.display = focusStopPending ? '' : 'none';
  stopFocusModeButton.textContent = focusScheduled
    ? t('action_cancel_focus_schedule')
    : t('action_stop_focus');

  if (emergencyModeActive && emergencyEndTime > Date.now()) {
    emergencyButton.disabled = true;
    emergencyButton.textContent = t('popup_emergency_in_use');
    // 지연 시작 대기 중이던 집중 모드 타이머가 살아있으면 두 인터벌이 같은 modeStatus를
    // 1초마다 번갈아 덮어쓴다 - 긴급 카운트다운을 켜기 전에 반드시 먼저 끈다.
    stopFocusCountdown();
    startEmergencyCountdown(emergencyEndTime);
  } else {
    stopEmergencyCountdown();

    // 집중 모드 중엔 긴급 시청으로 우회할 수 없으므로 버튼을 비활성화한다 (Fix 1).
    // 방금 긴급 시청을 썼다면 남용 방지용 15초 쿨다운이 끝날 때까지도 비활성화한다 (Fix 3).
    // 이 렌더 함수 자체가 1초마다 다시 불리므로(setInterval(renderLocal, 1000)) 별도 타이머 없이도
    // 카운트다운 표시가 저절로 갱신된다.
    const emergencyCooldownRemainingMs = last_emergency_granted_at
      ? EMERGENCY_GRANT_COOLDOWN_MS - (Date.now() - last_emergency_granted_at)
      : 0;

    if (focusActive) {
      emergencyButton.disabled = true;
      emergencyButton.textContent = t('popup_emergency_blocked_focus');
    } else if (scheduleActive) {
      emergencyButton.disabled = true;
      emergencyButton.textContent = t('popup_emergency_blocked_schedule');
    } else if (emergencyCooldownRemainingMs > 0) {
      emergencyButton.disabled = true;
      emergencyButton.textContent = tCount(
        'popup_emergency_cooldown',
        Math.ceil(emergencyCooldownRemainingMs / 1000)
      );
    } else {
      emergencyButton.disabled = false;
      emergencyButton.textContent = t('action_request_emergency');
    }

    if (focusStopPending) {
      const stopAtMillis = resolveFocusStopTime(focusModeEndTime, focusStopRequestedAt);
      startFocusCountdown(stopAtMillis, 'popup_mode_focus_stopping', 'status-warning');
    } else if (focusActive) {
      startFocusCountdown(focusModeEndTime, 'popup_mode_focus', 'status-active');
    } else if (focusScheduled) {
      startFocusCountdown(focusModeDelayEndTime, 'popup_mode_focus_pending', 'status-warning');
    } else if (scheduleActive) {
      // 집중 모드와 달리 카운트다운이 아니라 "몇 시까지"라 실시간 타이머(startFocusCountdown)는
      // 필요 없다 - renderLocal 자체가 1초마다 다시 불리므로 자정을 넘겨도 자연히 갱신된다.
      stopFocusCountdown();
      // 라벨은 사용자가 옵션 페이지에서 직접 입력한 값이라 textContent로만 넣는다(setBadge).
      const label = scheduleWindow?.label || t('popup_mode_scheduled_default_label');
      setBadge(
        modeStatus,
        'status-active',
        t('popup_mode_scheduled_until', [label, formatMinuteOfDay(scheduleWindow.endMinute)])
      );
    } else {
      stopFocusCountdown();
      setBadge(modeStatus, 'status-inactive', t('status_off'));
    }
  }

  setBadge(
    document.getElementById('consumingStatus'),
    isTracking ? 'status-active' : 'status-inactive',
    isTracking ? t('status_counting') : t('status_idle')
  );

  // 동기화가 며칠째 실패해도 지금까지는 화면에 아무 흔적이 없었다(백그라운드가 조용히 return한다).
  // 로그인 상태에서 마지막 성공이 24시간을 넘겼을 때만 한 줄로 알린다 — 판정은 순수 함수에
  // 맡기고(lib/syncDiagnostics.js staleSyncWarning) 여기선 문구를 붙여 표시만 한다. renderLocal이
  // 1초마다 다시 불리므로 임계값을 넘는 순간 별도 트리거 없이 뜬다(안드로이드 홈 화면과 같은 방식).
  const { events, lastSuccessAtMillis } = await readDiagnostics();
  const syncWarning = staleSyncWarning(
    isSignedIn,
    lastSuccessAtMillis,
    events.length > 0,
    Date.now()
  );
  syncWarningEl.textContent = formatSyncWarning(syncWarning);
  syncWarningEl.style.display = syncWarning ? '' : 'none';
}

// 로그인 여부 + 스트릭/XP는 네트워크를 타므로 팝업 열 때, 액션 직후, updateUI 알림 때만 조회한다.
async function renderRemote() {
  const user = await getCurrentUser();
  if (!user) {
    isSignedIn = false;
    signedOutView.style.display = '';
    signedInView.style.display = 'none';
    return;
  }
  isSignedIn = true;
  signedOutView.style.display = 'none';
  signedInView.style.display = '';

  const { settingsCache } = await getStorage(['settingsCache']);
  const streakSection = document.getElementById('streakSection');
  const hardcoreMode = !!settingsCache?.hardcore_mode;
  streakSection.style.display = hardcoreMode ? '' : 'none';

  if (hardcoreMode) {
    const { data: streak } = await supabase
      .from('streaks')
      .select('*')
      .eq('user_id', user.id)
      .maybeSingle();
    const xp = streak?.xp || 0;
    const { level, xpIntoLevel, xpForNextLevel } = getLevelProgress(xp);
    const currentStreak = streak?.current_streak || 0;

    document.getElementById('streakNumber').textContent = currentStreak;
    // "N일 연속 · 최고 M일"은 어순도 단복수도 언어마다 다르므로 조각을 잇지 않고 한 줄을 통째로
    // 만든다. 단복수는 위에 큼직하게 뜨는 현재 연속 일수로 가르고($1은 최고 기록 쪽이다).
    document.getElementById('streakCaption').textContent = t(
      pluralMessageKey('popup_streak_caption', currentStreak),
      [String(streak?.best_streak || 0)]
    );
    document.getElementById('levelNumber').textContent = level;
    document.getElementById('xpLabel').textContent = `${xpIntoLevel}/${xpForNextLevel} XP`;
    document.getElementById('xpBarFill').style.width =
      `${Math.min(100, (xpIntoLevel / xpForNextLevel) * 100)}%`;
  }

  await renderLocal();
}

chrome.runtime.onMessage.addListener((request) => {
  if (request.action === 'updateUI') renderRemote();
});

renderRemote();
setInterval(renderLocal, 1000);
