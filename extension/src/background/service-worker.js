import { getStorage, setStorage } from '../lib/storage.js';
import { getTodayDate, getWeekStartDate, getMonthStartDate } from '../lib/time.js';
import { supabase, getCurrentUser } from '../lib/supabaseClient.js';
import { applyDayRollover } from '../lib/gamification.js';
import { computeLimitForDate } from '../lib/limits.js';

const EMERGENCY_DURATION_MS = 5 * 60 * 1000;
const MAX_ELAPSED_MS = 10 * 60 * 1000; // 비정상적으로 큰 elapsed 값 방어
const DEFAULT_DAILY_LIMIT_MS = 30 * 60 * 1000;

let activeTabId = null;
let activeTabUrl = null;
let trackingStartTime = null;

let isYoutubeBlocked = false;
let isManuallyBlocked = false;
let focusModeActive = false;
let focusModeEndTime = null;
let focusModeDelayTimer = null;
let emergencyModeActive = false;
let emergencyModeTimer = null;

// --- 설정 캐시 (로컬 미러, source of truth는 로그인 시 Supabase settings 테이블) ---
let settingsCache = {
  daily_limit_ms: DEFAULT_DAILY_LIMIT_MS,
  daily_limit_by_day: {},
  daily_limit_reset_frequency: 'daily',
  always_block_shorts: false,
  whitelist: [],
  emergency_config: { dailyUses: 3, resetFrequency: 'daily' }
};

async function loadSettingsCache() {
  const local = await getStorage(['settingsCache']);
  if (local.settingsCache) settingsCache = { ...settingsCache, ...local.settingsCache };
}

let lastSettingsRefreshAt = 0;
const SETTINGS_REFRESH_INTERVAL_MS = 30 * 1000;

async function refreshSettingsFromSupabase(force = false) {
  if (!force && Date.now() - lastSettingsRefreshAt < SETTINGS_REFRESH_INTERVAL_MS) return;
  lastSettingsRefreshAt = Date.now();

  const user = await getCurrentUser();
  if (!user) return;
  const { data, error } = await supabase.from('settings').select('*').eq('user_id', user.id).maybeSingle();
  if (error || !data) return;
  settingsCache = { ...settingsCache, ...data };
  await setStorage({ settingsCache });
}

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== 'local') return;
  if (changes.settingsCache) {
    settingsCache = { ...settingsCache, ...changes.settingsCache.newValue };
    checkUsageAndBlock();
  }
});

// --- 사용 시간 저장 ---

async function getTodayUsage() {
  const { usage_history } = await getStorage(['usage_history']);
  return (usage_history || {})[getTodayDate()] || 0;
}

async function saveTodayUsage(ms) {
  const { usage_history } = await getStorage(['usage_history']);
  const history = usage_history || {};
  history[getTodayDate()] = ms;
  await setStorage({ usage_history: history });
}

async function addShortsUsage(elapsedMs) {
  const { usage_history_shorts } = await getStorage(['usage_history_shorts']);
  const history = usage_history_shorts || {};
  const today = getTodayDate();
  history[today] = (history[today] || 0) + elapsedMs;
  await setStorage({ usage_history_shorts: history });
}

async function trackUsage() {
  // await 도중 다른 탭 이벤트가 activeTabUrl/trackingStartTime을 바꿔버릴 수 있으니
  // 함수 시작 시점 값을 스냅샷 떠서 그것만 쓴다 (공유 변수 재참조 금지).
  const tabUrl = activeTabUrl;
  const startTime = trackingStartTime;

  if (!(activeTabId && tabUrl && tabUrl.includes('youtube.com') && startTime)) {
    trackingStartTime = null;
    return;
  }

  // isYoutubeBlocked 인메모리 값은 서비스워커가 재시작되면 잠깐 stale할 수 있으니
  // 실제로 시간을 더하기 직전엔 storage의 최신 값으로 다시 확인한다.
  const { isYoutubeBlocked: storedBlocked } = await getStorage(['isYoutubeBlocked']);
  if (storedBlocked) {
    isYoutubeBlocked = true;
    trackingStartTime = null;
    return;
  }

  const now = Date.now();
  const elapsed = now - startTime;
  if (elapsed < 0 || elapsed > MAX_ELAPSED_MS || !Number.isFinite(elapsed)) {
    trackingStartTime = now;
    return;
  }

  const newUsage = (await getTodayUsage()) + elapsed;
  await saveTodayUsage(newUsage);
  if (tabUrl.includes('/shorts')) {
    await addShortsUsage(elapsed);
  }

  trackingStartTime = now;
}

// --- 날짜 롤오버 (자정 넘어가면 어제 기록으로 스트릭/XP 갱신) ---

async function checkDateRollover() {
  const today = getTodayDate();
  const { local_current_date } = await getStorage(['local_current_date']);

  if (!local_current_date) {
    await setStorage({ local_current_date: today });
    return;
  }
  if (local_current_date === today) return;

  const { usage_history } = await getStorage(['usage_history']);
  const usageMs = (usage_history || {})[local_current_date] || 0;
  const limitMs = computeLimitForDate(settingsCache, local_current_date);

  const user = await getCurrentUser();
  if (user) {
    try {
      await applyDayRollover(supabase, user.id, { date: local_current_date, usageMs, limitMs });
    } catch (e) {
      console.error('[TubeLimiter] rollover failed:', e);
    }
  }

  await setStorage({ local_current_date: today });
}

// --- Supabase 동기화 ---

async function syncUsageToSupabase() {
  const user = await getCurrentUser();
  if (!user) return;

  const today = getTodayDate();
  const [{ usage_history }, { usage_history_shorts }] = await Promise.all([
    getStorage(['usage_history']),
    getStorage(['usage_history_shorts'])
  ]);

  await supabase.from('daily_usage').upsert({
    user_id: user.id,
    date: today,
    usage_ms: (usage_history || {})[today] || 0,
    shorts_ms: (usage_history_shorts || {})[today] || 0,
    updated_at: new Date().toISOString()
  });
}

// --- 긴급 시청 횟수 리셋 ---

async function checkAndResetEmergencyUses() {
  const today = getTodayDate();
  const { last_emergency_date } = await getStorage(['last_emergency_date']);
  const { resetFrequency } = settingsCache.emergency_config || { resetFrequency: 'daily' };

  let currentResetDate = today;
  if (resetFrequency === 'weekly') currentResetDate = getWeekStartDate();
  if (resetFrequency === 'monthly') currentResetDate = getMonthStartDate();

  if (last_emergency_date !== currentResetDate) {
    const initialUses = settingsCache.emergency_config?.dailyUses ?? 3;
    await setStorage({ emergency_uses_today: initialUses, last_emergency_date: currentResetDate });
    notifyUiUpdate();
  }
}

// --- 차단 판정 ---

async function checkUsageAndBlock() {
  const storedState = await getStorage([
    'isManuallyBlocked', 'focusModeActive', 'focusModeEndTime',
    'emergencyModeActive', 'emergencyEndTime',
    'focusModeDelayEndTime', 'focusModeDelayDuration'
  ]);
  isManuallyBlocked = storedState.isManuallyBlocked || false;
  focusModeActive = storedState.focusModeActive || false;
  focusModeEndTime = storedState.focusModeEndTime || null;
  emergencyModeActive = storedState.emergencyModeActive || false;

  if (focusModeActive && focusModeEndTime && Date.now() >= focusModeEndTime) {
    focusModeActive = false;
    focusModeEndTime = null;
    await setStorage({ focusModeActive: false, focusModeEndTime: null });
  }

  // 지연 시작 집중 모드: 서비스워커가 중간에 종료되면 setTimeout이 못 돌아오므로 여기서 복구
  if (!focusModeActive && storedState.focusModeDelayEndTime && Date.now() >= storedState.focusModeDelayEndTime) {
    focusModeActive = true;
    focusModeEndTime = Date.now() + (storedState.focusModeDelayDuration || 30) * 60 * 1000;
    await setStorage({ focusModeActive: true, focusModeEndTime, focusModeDelayEndTime: null });
  }

  // 긴급 시청 만료: 마찬가지로 setTimeout이 못 돌아오면 여기서 복구 (안 하면 이후 모든 차단이 영구히 풀림)
  if (emergencyModeActive && storedState.emergencyEndTime && Date.now() >= storedState.emergencyEndTime) {
    emergencyModeActive = false;
    await setStorage({ emergencyModeActive: false, emergencyEndTime: null });
  }

  const limitMs = computeLimitForDate(settingsCache, getTodayDate());
  const currentUsage = await getTodayUsage();
  const usageLimitExceeded = currentUsage >= limitMs;

  let shouldBlockGlobally = false;
  let blockReason = '';

  if (emergencyModeActive) {
    shouldBlockGlobally = false;
  } else if (focusModeActive) {
    shouldBlockGlobally = true;
    blockReason = 'focusMode';
  } else if (isManuallyBlocked) {
    shouldBlockGlobally = true;
    blockReason = 'manualBlock';
  } else if (usageLimitExceeded) {
    shouldBlockGlobally = true;
    blockReason = 'usageLimit';
  }

  const displayBlockState = focusModeActive || isManuallyBlocked || (usageLimitExceeded && !emergencyModeActive);
  if (displayBlockState !== isYoutubeBlocked) {
    isYoutubeBlocked = displayBlockState;
    await setStorage({ isYoutubeBlocked: displayBlockState });
  }

  const whitelist = settingsCache.whitelist || [];
  const alwaysBlockShorts = settingsCache.always_block_shorts || false;

  const tabs = await chrome.tabs.query({ url: '*://*.youtube.com/*' });
  for (const tab of tabs) {
    const isWhitelisted = tab.url && whitelist.some((entry) => tab.url.includes(entry));
    const isShortsTab = tab.url && tab.url.includes('youtube.com/shorts');

    let shouldBlockTab = shouldBlockGlobally;
    let finalReason = blockReason;

    if (emergencyModeActive) {
      shouldBlockTab = false;
    } else if (focusModeActive) {
      shouldBlockTab = true;
      finalReason = 'focusMode';
    } else if (isWhitelisted) {
      shouldBlockTab = false;
    } else if (alwaysBlockShorts && isShortsTab) {
      shouldBlockTab = true;
      finalReason = 'alwaysBlockShorts';
    }

    await sendBlockMessage(tab.id, shouldBlockTab, finalReason);
  }

  notifyUiUpdate();
}

async function sendBlockMessage(tabId, shouldBlock, reason) {
  const message = shouldBlock ? { action: 'blockYoutube', reason } : { action: 'unblockYoutube' };
  try {
    await chrome.tabs.sendMessage(tabId, message);
  } catch (e) {
    if (e.message.includes('Receiving end does not exist') || e.message.includes('Could not establish connection')) {
      try {
        await chrome.scripting.executeScript({ target: { tabId }, files: ['content/content.js'] });
        await chrome.tabs.sendMessage(tabId, message);
      } catch {
        /* 탭이 이미 닫혔거나 스크립트 주입 불가한 페이지 */
      }
    }
  }
}

function notifyUiUpdate() {
  chrome.runtime.sendMessage({ action: 'updateUI' }).catch(() => {});
}

// --- 메인 틱: 알람 또는 탭 이벤트에서 호출 ---

async function handleTick() {
  await refreshSettingsFromSupabase();
  await trackUsage();
  await checkDateRollover();
  await checkUsageAndBlock();
  await checkAndResetEmergencyUses();
  await syncUsageToSupabase();
}

chrome.tabs.onActivated.addListener(async (activeInfo) => {
  await trackUsage();
  activeTabId = activeInfo.tabId;
  try {
    const tab = await chrome.tabs.get(activeTabId);
    activeTabUrl = tab.url;
  } catch {
    activeTabId = null;
    activeTabUrl = null;
  }
  if (activeTabUrl && activeTabUrl.includes('youtube.com')) {
    trackingStartTime = Date.now();
    await handleTick();
  } else {
    activeTabId = null;
    activeTabUrl = null;
    trackingStartTime = null;
  }
});

chrome.tabs.onUpdated.addListener(async (tabId, changeInfo) => {
  if (tabId !== activeTabId || !changeInfo.url) return;
  await trackUsage();
  activeTabUrl = changeInfo.url;
  if (activeTabUrl.includes('youtube.com')) {
    trackingStartTime = Date.now();
    await handleTick();
  } else {
    activeTabId = null;
    activeTabUrl = null;
    trackingStartTime = null;
  }
});

chrome.windows.onRemoved.addListener(async () => {
  await trackUsage();
  await syncUsageToSupabase();
});

// chrome.alarms는 서비스워커가 잠들어도 브라우저가 깨워서 실행해준다.
// (프로덕션 빌드에서는 1분 미만 주기가 강제로 1분으로 올림 처리된다.)
chrome.alarms.create('usageTick', { periodInMinutes: 1 });
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'usageTick') handleTick();
});

// --- 메시지 핸들러 (팝업/옵션에서 오는 액션) ---

chrome.runtime.onMessage.addListener((request, _sender, sendResponse) => {
  if (request.action === 'requestEmergency') {
    (async () => {
      const { emergency_uses_today } = await getStorage(['emergency_uses_today']);
      let uses = emergency_uses_today;
      if (uses === undefined) uses = settingsCache.emergency_config?.dailyUses ?? 3;

      if (!isYoutubeBlocked) {
        sendResponse({ success: false, message: '현재 차단 상태가 아닙니다.' });
        return;
      }
      if (uses <= 0) {
        sendResponse({ success: false, message: '남은 긴급 시청 횟수가 없습니다.' });
        return;
      }

      uses -= 1;
      await setStorage({ emergency_uses_today: uses });

      emergencyModeActive = true;
      const emergencyEndTime = Date.now() + EMERGENCY_DURATION_MS;
      await setStorage({ emergencyModeActive: true, emergencyEndTime });

      if (emergencyModeTimer) clearTimeout(emergencyModeTimer);
      emergencyModeTimer = setTimeout(async () => {
        emergencyModeActive = false;
        await setStorage({ emergencyModeActive: false, emergencyEndTime: null });
        await checkUsageAndBlock();
      }, EMERGENCY_DURATION_MS);

      await checkUsageAndBlock();
      sendResponse({ success: true });
    })();
    return true;
  }

  if (request.action === 'manualBlock' || request.action === 'manualUnblock') {
    (async () => {
      isManuallyBlocked = request.action === 'manualBlock';
      await setStorage({ isManuallyBlocked });
      await checkUsageAndBlock();
      sendResponse({ success: true });
    })();
    return true;
  }

  if (request.action === 'startFocusMode') {
    (async () => {
      const { duration, delay = 0 } = request;
      if (focusModeDelayTimer) clearTimeout(focusModeDelayTimer);

      const start = async () => {
        focusModeActive = true;
        focusModeEndTime = Date.now() + duration * 60 * 1000;
        await setStorage({ focusModeActive: true, focusModeEndTime, focusModeDelayEndTime: null });
        await checkUsageAndBlock();
      };

      if (delay > 0) {
        const delayEndTime = Date.now() + delay * 60 * 1000;
        await setStorage({ focusModeDelayEndTime: delayEndTime, focusModeDelayDuration: duration });
        focusModeDelayTimer = setTimeout(start, delay * 60 * 1000);
      } else {
        await start();
      }
      sendResponse({ success: true });
    })();
    return true;
  }

  if (request.action === 'stopFocusMode') {
    (async () => {
      if (focusModeDelayTimer) clearTimeout(focusModeDelayTimer);
      focusModeActive = false;
      focusModeEndTime = null;
      await setStorage({ focusModeActive: false, focusModeEndTime: null, focusModeDelayEndTime: null });
      await checkUsageAndBlock();
      sendResponse({ success: true });
    })();
    return true;
  }

  if (request.action === 'settingsUpdated') {
    (async () => {
      await refreshSettingsFromSupabase(true);
      await checkUsageAndBlock();
      sendResponse({ success: true });
    })();
    return true;
  }
});

// --- 초기화 ---

(async () => {
  await loadSettingsCache();
  const stored = await getStorage(['isYoutubeBlocked', 'isManuallyBlocked', 'focusModeActive', 'focusModeEndTime']);
  isYoutubeBlocked = stored.isYoutubeBlocked || false;
  isManuallyBlocked = stored.isManuallyBlocked || false;
  focusModeActive = stored.focusModeActive || false;
  focusModeEndTime = stored.focusModeEndTime || null;

  await refreshSettingsFromSupabase();
  await checkDateRollover();
  await checkUsageAndBlock();
})();
