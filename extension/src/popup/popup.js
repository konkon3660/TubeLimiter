import { getStorage, setStorage } from '../lib/storage.js';
import { getTodayDate } from '../lib/time.js';
import { supabase, getCurrentUser } from '../lib/supabaseClient.js';
import { getLevelProgress } from '../lib/gamification.js';
import { computeLimitForDate } from '../lib/limits.js';
import { combinedUsedMillis } from '../lib/usageMerge.js';
import { resolveFocusStopTime } from '../lib/focusMode.js';
import { EMERGENCY_GRANT_COOLDOWN_MS } from '../lib/emergency.js';
import { isScheduleActive } from '../lib/schedule.js';

const signedOutView = document.getElementById('signedOutView');
const signedInView = document.getElementById('signedInView');

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
getStorage(['focusModeFormDuration', 'focusModeFormDelay']).then(({ focusModeFormDuration, focusModeFormDelay }) => {
  if (focusModeFormDuration != null) focusModeDurationInput.value = focusModeFormDuration;
  if (focusModeFormDelay != null) focusModeDelayInput.value = focusModeFormDelay;
});
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
  if (!Number.isFinite(ms)) return '무제한';
  return `${Math.floor(ms / 60000)}분`;
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
    modeStatus.innerHTML = `<span class="status-badge status-active">긴급 시청 · ${formatCountdown(remainingMs)}</span>`;
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

function startFocusCountdown(endTime, label, badgeClass) {
  stopFocusCountdown();
  const tick = () => {
    const remainingMs = endTime - Date.now();
    if (remainingMs <= 0) {
      stopFocusCountdown();
      renderLocal();
      return;
    }
    modeStatus.innerHTML = `<span class="status-badge ${badgeClass}">${label} · ${formatCountdown(remainingMs)}</span>`;
  };
  tick();
  focusCountdownTimer = setInterval(tick, 1000);
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

  const [{ usage_history }, { settingsCache }, { emergency_uses_today }, { focusModeActive, focusModeEndTime, focusModeDelayEndTime, focusStopRequestedAt }, { emergencyModeActive, emergencyEndTime, last_emergency_granted_at }, dailySync] = await Promise.all([
    getStorage(['usage_history']),
    getStorage(['settingsCache']),
    getStorage(['emergency_uses_today']),
    getStorage(['focusModeActive', 'focusModeEndTime', 'focusModeDelayEndTime', 'focusStopRequestedAt']),
    getStorage(['emergencyModeActive', 'emergencyEndTime', 'last_emergency_granted_at']),
    getStorage(['dailyUsageSyncDate', 'dailyUsageSyncedMillis', 'dailyUsageCombinedMillis'])
  ]);

  const today = getTodayDate();
  const localUsage = (usage_history || {})[today] || 0;
  // 백그라운드가 주기적으로 다른 기기 몫을 동기화해두면(다음 기기 몫 합산은
  // service-worker.js의 syncUsageToSupabase 참고) 여기서도 같은 값을 보여준다.
  const todayUsage = dailySync.dailyUsageSyncDate === today
    ? combinedUsedMillis(localUsage, dailySync.dailyUsageSyncedMillis || 0, dailySync.dailyUsageCombinedMillis || 0)
    : localUsage;
  const limitMs = computeLimitForDate(settingsCache, today);
  const remaining = Number.isFinite(limitMs) ? Math.max(0, limitMs - todayUsage) : Infinity;

  document.getElementById('usageTime').textContent = formatMinutes(todayUsage);
  document.getElementById('untilBlock').textContent = Number.isFinite(remaining) ? formatCountdown(remaining) : '무제한';

  const progressCircle = document.getElementById('progressCircle');
  if (Number.isFinite(limitMs) && limitMs > 0) {
    const percentage = Math.min(100, Math.max(0, (todayUsage / limitMs) * 100));
    const degrees = (percentage / 100) * 360;
    progressCircle.style.background = `conic-gradient(var(--primary-color) ${degrees}deg, var(--light-gray) ${degrees}deg)`;
    progressCircle.title = `차단까지 ${formatCountdown(remaining)} 남음`;
  } else {
    progressCircle.style.background = 'var(--primary-color)';
    progressCircle.title = '무제한';
  }
  document.getElementById('emergencyCount').textContent = `${emergency_uses_today ?? (settingsCache?.emergency_config?.dailyUses ?? 3)}회`;

  const focusActive = !!(focusModeActive && focusModeEndTime > Date.now());
  const focusScheduled = !focusActive && !!(focusModeDelayEndTime && focusModeDelayEndTime > Date.now());
  const focusStopPending = focusActive && !!focusStopRequestedAt;

  // 예약 차단: 옵션 페이지에서만 켜고 끌 수 있다 (의도적 - 팝업에 끄기 버튼을 안 두는 게
  // 진짜 커밋먼트 장치가 되는 핵심이라, 여기선 읽기 전용 상태 표시만 한다).
  const { active: scheduleActive, window: scheduleWindow } = isScheduleActive(new Date(), settingsCache?.scheduled_blocks || []);

  // 집중 모드 종료 버튼: 활성 세션은 "종료 요청"(10분 쿨다운 시작), 예약 대기 중은 즉시 취소.
  // 이미 종료를 요청해둔 상태면 종료 버튼 대신 "예약 취소"만 보여준다 (renderLocal은 1초마다
  // 다시 불리므로 이 토글도 그때그때 최신 상태를 반영한다).
  stopFocusModeButton.style.display = focusStopPending ? 'none' : '';
  cancelFocusStopButton.style.display = focusStopPending ? '' : 'none';
  stopFocusModeButton.textContent = focusScheduled ? '집중 모드 예약 취소' : '집중 모드 종료';

  if (emergencyModeActive && emergencyEndTime > Date.now()) {
    emergencyButton.disabled = true;
    emergencyButton.textContent = '긴급 시청 사용 중';
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
      emergencyButton.textContent = '집중 모드 중 사용 불가';
    } else if (scheduleActive) {
      emergencyButton.disabled = true;
      emergencyButton.textContent = '예약된 차단 중 사용 불가';
    } else if (emergencyCooldownRemainingMs > 0) {
      emergencyButton.disabled = true;
      emergencyButton.textContent = `잠시 후 다시 시도 (${Math.ceil(emergencyCooldownRemainingMs / 1000)}초)`;
    } else {
      emergencyButton.disabled = false;
      emergencyButton.textContent = '긴급 시청 요청';
    }

    if (focusStopPending) {
      const stopAtMillis = resolveFocusStopTime(focusModeEndTime, focusStopRequestedAt);
      startFocusCountdown(stopAtMillis, '집중 모드 종료 대기', 'status-warning');
    } else if (focusActive) {
      startFocusCountdown(focusModeEndTime, '집중 모드', 'status-active');
    } else if (focusScheduled) {
      startFocusCountdown(focusModeDelayEndTime, '집중 모드 대기 중', 'status-warning');
    } else if (scheduleActive) {
      // 집중 모드와 달리 카운트다운이 아니라 "몇 시까지"라 실시간 타이머(startFocusCountdown)는
      // 필요 없다 - renderLocal 자체가 1초마다 다시 불리므로 자정을 넘겨도 자연히 갱신된다.
      stopFocusCountdown();
      // 라벨은 사용자가 옵션 페이지에서 직접 입력한 값이라 innerHTML로 끼워 넣지 않는다.
      const label = scheduleWindow?.label || '예약된 차단';
      const badge = document.createElement('span');
      badge.className = 'status-badge status-active';
      badge.textContent = `${label} · ${formatMinuteOfDay(scheduleWindow.endMinute)}까지`;
      modeStatus.replaceChildren(badge);
    } else {
      stopFocusCountdown();
      modeStatus.innerHTML = '<span class="status-badge status-inactive">비활성</span>';
    }
  }

  document.getElementById('consumingStatus').innerHTML = isTracking
    ? '<span class="status-badge status-active">소모 중</span>'
    : '<span class="status-badge status-inactive">대기 중</span>';
}

// 로그인 여부 + 스트릭/XP는 네트워크를 타므로 팝업 열 때, 액션 직후, updateUI 알림 때만 조회한다.
async function renderRemote() {
  const user = await getCurrentUser();
  if (!user) {
    signedOutView.style.display = '';
    signedInView.style.display = 'none';
    return;
  }
  signedOutView.style.display = 'none';
  signedInView.style.display = '';

  const { settingsCache } = await getStorage(['settingsCache']);
  const streakSection = document.getElementById('streakSection');
  const hardcoreMode = !!settingsCache?.hardcore_mode;
  streakSection.style.display = hardcoreMode ? '' : 'none';

  if (hardcoreMode) {
    const { data: streak } = await supabase.from('streaks').select('*').eq('user_id', user.id).maybeSingle();
    const xp = streak?.xp || 0;
    const { level, xpIntoLevel, xpForNextLevel } = getLevelProgress(xp);

    document.getElementById('streakNumber').textContent = streak?.current_streak || 0;
    document.getElementById('bestStreak').textContent = streak?.best_streak || 0;
    document.getElementById('levelNumber').textContent = level;
    document.getElementById('xpLabel').textContent = `${xpIntoLevel}/${xpForNextLevel} XP`;
    document.getElementById('xpBarFill').style.width = `${Math.min(100, (xpIntoLevel / xpForNextLevel) * 100)}%`;
  }

  await renderLocal();
}

chrome.runtime.onMessage.addListener((request) => {
  if (request.action === 'updateUI') renderRemote();
});

renderRemote();
setInterval(renderLocal, 1000);
