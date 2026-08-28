// background.js
// 사용 시간 추적 및 차단 로직 구현

let activeTabId = null;
let activeTabUrl = null;
let trackingStartTime = null;

let DAILY_LIMIT_MS = 30 * 60 * 1000; // 30분
const EMERGENCY_DURATION_MS = 5 * 60 * 1000; // 5분
let emergencyModeActive = false;
let emergencyModeTimer = null;
let isYoutubeBlocked = false; 
let isManuallyBlocked = false; 
let focusModeActive = false; // 집중 모드 상태
let focusModeEndTime = null; // 집중 모드 종료 시간
let focusModeDelayTimer = null; // 집중 모드 지연 타이머
const EMERGENCY_USES_SETTING_KEY = 'initial_daily_emergency_uses'; // 긴급 시청 횟수 설정 키 정의

let useStudyAI = false;
let probStudy = null;
let probStudyUpdatedAt = null;
let studyAiThreshold = 0.5;
const PROB_STUDY_MAX_AGE_MS = 30000;

// Study AI inference timing
let lastInferenceUrl = null;
let inferenceTimeout = null;

// Study session tracking
let isCurrentlyStudying = false;
let studyStartTime = null;

// Play session tracking
let isCurrentlyPlaying = false;
let playStartTime = null;

// 스토리지에서 초기 상태 로드
(async () => {
  const storedState = await getStorage(['isYoutubeBlocked', 'isManuallyBlocked', 'emergencyModeActive', 'focusModeActive', 'focusModeEndTime', 'focusModeDelayEndTime', 'focusModeDelayDuration', 'daily_limit_by_day', 'emergencyEndTime']);
  isYoutubeBlocked = storedState.isYoutubeBlocked || false;
  isManuallyBlocked = storedState.isManuallyBlocked || false;
  emergencyModeActive = storedState.emergencyModeActive || false;
  focusModeActive = storedState.focusModeActive || false;
  focusModeEndTime = storedState.focusModeEndTime || null;

  const aiState = await getStorage(['useStudyAI', 'probStudy', 'probStudyUpdatedAt', 'studyAiThreshold']);
  useStudyAI = aiState.useStudyAI || false;
  probStudy = typeof aiState.probStudy === 'number' ? aiState.probStudy : null;
  probStudyUpdatedAt = typeof aiState.probStudyUpdatedAt === 'number' ? aiState.probStudyUpdatedAt : null;
  studyAiThreshold = typeof aiState.studyAiThreshold === 'number' ? aiState.studyAiThreshold : 0.5;

  // 긴급 모드 타이머 복원
  if (emergencyModeActive && storedState.emergencyEndTime) {
    const remainingTime = storedState.emergencyEndTime - Date.now();
    if (remainingTime > 0) {
      emergencyModeTimer = setTimeout(async () => {
        emergencyModeActive = false;
        await setStorage({ emergencyModeActive: false, emergencyEndTime: null });
        console.log('Emergency mode deactivated (restored timer).');
        await checkUsageAndBlock();
      }, remainingTime);
    } else {
      // 만료된 긴급 모드 정리
      emergencyModeActive = false;
      await setStorage({ emergencyModeActive: false, emergencyEndTime: null });
      await checkUsageAndBlock();
    }
  }

  // 집중 모드 지연 타이머 복원
  if (storedState.focusModeDelayEndTime) {
    const remainingDelay = storedState.focusModeDelayEndTime - Date.now();
    if (remainingDelay > 0) {
      const duration = storedState.focusModeDelayDuration || 30;
      focusModeDelayTimer = setTimeout(async () => {
        focusModeActive = true;
        focusModeEndTime = Date.now() + duration * 60 * 1000;
        focusModeDelayTimer = null;
        await setStorage({
          focusModeActive: true,
          focusModeEndTime: focusModeEndTime,
          focusModeDelayEndTime: null,
          focusModeDelayDuration: null
        });
        console.log('Focus mode started (restored delay timer).');
        await checkUsageAndBlock();
        chrome.runtime.sendMessage({ action: "updateOptionsUI" }).catch(()=>{/* ignore */});
        chrome.runtime.sendMessage({ action: "updatePopupUI" }).catch(()=>{/* ignore */});
      }, remainingDelay);
      console.log(`Focus mode delay timer restored. Remaining: ${Math.floor(remainingDelay / 1000)}s, Duration: ${duration}min`);
    } else {
      // 만료된 지연 정리 - 이미 지연 시간이 지났으면 즉시 집중 모드 시작
      const duration = storedState.focusModeDelayDuration || 30;
      focusModeActive = true;
      focusModeEndTime = Date.now() + duration * 60 * 1000;
      await setStorage({
        focusModeActive: true,
        focusModeEndTime: focusModeEndTime,
        focusModeDelayEndTime: null,
        focusModeDelayDuration: null
      });
      console.log('Focus mode started immediately (delay expired during restart).');
      await checkUsageAndBlock();
    }
  }

  console.log(`Initial state loaded: isYoutubeBlocked=${isYoutubeBlocked}, isManuallyBlocked=${isManuallyBlocked}, emergencyModeActive=${emergencyModeActive}, focusModeActive=${focusModeActive}, focusModeEndTime=${focusModeEndTime}`);
})();

chrome.storage.onChanged.addListener((changes, areaName) => {
  if (areaName !== 'local') return;
  if (changes.useStudyAI) useStudyAI = Boolean(changes.useStudyAI.newValue);
  if (changes.probStudy) probStudy = typeof changes.probStudy.newValue === 'number' ? changes.probStudy.newValue : null;
  if (changes.probStudyUpdatedAt) probStudyUpdatedAt = typeof changes.probStudyUpdatedAt.newValue === 'number' ? changes.probStudyUpdatedAt.newValue : null;
  if (changes.studyAiThreshold) studyAiThreshold = typeof changes.studyAiThreshold.newValue === 'number' ? changes.studyAiThreshold.newValue : 0.5;
});

// 오늘 날짜를 YYYY-MM-DD 형식으로 반환
import { getTodayDate, getWeekStartDate, getMonthStartDate } from '/utils/time.js';
import { getStorage, setStorage } from '/utils/storage.js';

// Helper function to extract video ID from YouTube URL
function extractVideoIdFromUrl(url) {
  if (!url) return null;
  const patterns = [
    /(?:youtube\.com\/watch\?v=|youtu\.be\/)([^&\n?#]+)/,
    /youtube\.com\/embed\/([^&\n?#]+)/,
    /youtube\.com\/v\/([^&\n?#]+)/,
    /youtube\.com\/shorts\/([^&\n?#]+)/
  ];
  for (const pattern of patterns) {
    const match = url.match(pattern);
    if (match && match[1]) {
      return match[1];
    }
  }
  return null;
}

// 스토리지에서 오늘 사용 시간 가져오기
async function getTodayUsage() {
  const today = getTodayDate();
  const result = await getStorage(['usage_history']);
  const usageHistory = result.usage_history || {};
  return usageHistory[today] || 0;
}

// 스토리지에 오늘 사용 시간 저장
async function saveTodayUsage(timeInMs) {
  const today = getTodayDate();
  const result = await getStorage(['usage_history']);
  const usageHistory = result.usage_history || {};
  usageHistory[today] = timeInMs;
  await setStorage({ usage_history: usageHistory });
}

// 스토리지에 특정 타입의 사용 시간 저장 (shorts, study, play)
async function saveUsageByType(timeInMs, type) {
  const today = getTodayDate();
  const storageKey = `usage_history_${type}`;
  const result = await getStorage([storageKey]);
  const usageHistory = result[storageKey] || {};
  usageHistory[today] = (usageHistory[today] || 0) + timeInMs;
  await setStorage({ [storageKey]: usageHistory });
}

// 탭 활성화/업데이트 시 사용 시간 추적 시작/중지
async function trackUsage() {
  console.log(`[trackUsage] Called - activeTabId: ${activeTabId}, activeTabUrl: ${activeTabUrl}, trackingStartTime: ${trackingStartTime}, isYoutubeBlocked: ${isYoutubeBlocked}`);

  // 차단 상태가 아닐 때만 사용 시간을 추적
  if (activeTabId && activeTabUrl && activeTabUrl.includes('youtube.com') && trackingStartTime && !isYoutubeBlocked) {
    let shouldCountUsage = true;
    let isStudying = false;

    // 1. Check if current URL is whitelisted as a study video (highest priority)
    const storedData = await getStorage(['whitelist']);
    const whitelist = storedData.whitelist || [];
    const isWhitelistedStudyVideo = whitelist.some(item => {
      if (typeof item === 'object' && item.isStudy === true) {
        // Extract video ID from both URLs for comparison
        const itemVideoId = extractVideoIdFromUrl(item.url);
        const currentVideoId = extractVideoIdFromUrl(activeTabUrl);
        return itemVideoId && currentVideoId && itemVideoId === currentVideoId;
      }
      return false;
    });

    if (isWhitelistedStudyVideo) {
      // Study video - always treat as studying, regardless of AI state
      shouldCountUsage = false;
      isStudying = true;
      console.log('[Study Video Whitelist] ✓ URL is whitelisted as study video - bypassing AI detection');
      console.log('  - activeTabUrl:', activeTabUrl);
      console.log('  - shouldCountUsage:', shouldCountUsage);
      console.log('  - isStudying:', isStudying);
    } else if (useStudyAI) {
      // 2. Use AI detection if not a whitelisted study video
      const isFresh = typeof probStudyUpdatedAt === 'number' && (Date.now() - probStudyUpdatedAt) <= PROB_STUDY_MAX_AGE_MS;
      const effectiveProb = (typeof probStudy === 'number' && isFresh) ? probStudy : null;

      // AI 로직:
      // - effectiveProb === null (데이터 없음) → 기본적으로 시간 카운트 (true), 놀이로 분류
      // - probStudy >= threshold (공부 중) → 시간 카운트 안 함 (false), 공부로 분류
      // - probStudy < threshold (오락 중) → 시간 카운트 (true), 놀이로 분류
      shouldCountUsage = effectiveProb === null ? true : (effectiveProb < studyAiThreshold);
      isStudying = effectiveProb !== null && effectiveProb >= studyAiThreshold;

      console.log('[Study AI] Usage tracking decision:');
      console.log('  - probStudy:', probStudy);
      console.log('  - threshold:', studyAiThreshold);
      console.log('  - isFresh:', isFresh, '(age:', typeof probStudyUpdatedAt === 'number' ? Date.now() - probStudyUpdatedAt : 'N/A', 'ms)');
      console.log('  - effectiveProb:', effectiveProb);
      console.log('  - shouldCountUsage:', shouldCountUsage);
      console.log('  - isStudying:', isStudying);
    } else {
      // 3. AI가 꺼져있을 때: 기본적으로 놀이로 분류하지만 전체 사용 시간에는 카운트
      // (화이트리스트 공부 영상은 위에서 이미 처리됨)
      shouldCountUsage = true;
      isStudying = false;
      console.log('[No AI] Treating as play time (whitelist study videos excluded above)');
    }

    const now = Date.now();
    const elapsed = now - trackingStartTime;

    // 비정상적인 시간 값 검증 (최대 10분으로 제한)
    const MAX_ELAPSED_MS = 10 * 60 * 1000; // 10분
    if (elapsed < 0 || elapsed > MAX_ELAPSED_MS || !Number.isFinite(elapsed)) {
      console.warn(`[Usage Tracking] ⚠ Abnormal elapsed time detected: ${elapsed}ms (${Math.floor(elapsed / 60000)}min).`);
      console.warn(`[Usage Tracking]   - trackingStartTime: ${trackingStartTime} (${new Date(trackingStartTime).toISOString()})`);
      console.warn(`[Usage Tracking]   - now: ${now} (${new Date(now).toISOString()})`);
      console.warn(`[Usage Tracking]   - Resetting trackingStartTime to prevent data corruption.`);
      trackingStartTime = now;
      return;
    }

    const isShortsUrl = activeTabUrl ? activeTabUrl.includes('/shorts') : false;

    if (shouldCountUsage) {
      // 전체 사용 시간 저장 (기존 로직)
      const currentUsage = await getTodayUsage();
      const newUsage = currentUsage + elapsed;
      await saveTodayUsage(newUsage);
      console.log(`[Usage Tracking] ✓ Usage counted: +${elapsed}ms, total: ${newUsage}ms`);

      // Shorts 사용 시간 별도 저장
      if (isShortsUrl) {
        await saveUsageByType(elapsed, 'shorts');
        console.log(`[Usage Tracking] ✓ Shorts usage tracked: +${elapsed}ms`);
      }

      // 놀이 시간 저장 (AI 사용 여부와 관계없이 항상 저장)
      await saveUsageByType(elapsed, 'play');
      console.log(`[Usage Tracking] ✓ Play time tracked: +${elapsed}ms`);
    } else if (isStudying) {
      // 공부 시간 저장 (전체 사용 시간에는 카운트하지 않음)
      await saveUsageByType(elapsed, 'study');
      console.log(`[Usage Tracking] ⊘ Study mode detected - usage NOT counted (probStudy=${probStudy?.toFixed(4)}, threshold=${studyAiThreshold})`);
      console.log(`[Usage Tracking] ✓ Study time tracked: +${elapsed}ms`);

      // Shorts에서 공부하는 경우도 Shorts로 추적
      if (isShortsUrl) {
        await saveUsageByType(elapsed, 'shorts');
        console.log(`[Usage Tracking] ✓ Shorts usage tracked (during study): +${elapsed}ms`);
      }
    }

    // 공부 세션 추적 업데이트
    if (isStudying && !isCurrentlyStudying) {
      // 공부 시작
      isCurrentlyStudying = true;
      studyStartTime = now;
      await setStorage({ studyStartTime: studyStartTime });
      console.log(`[Study Session] Started at ${studyStartTime}`);
    } else if (!isStudying && isCurrentlyStudying) {
      // 공부 종료
      isCurrentlyStudying = false;
      studyStartTime = null;
      await setStorage({ studyStartTime: null });
      console.log(`[Study Session] Ended`);
    }

    // 놀이 세션 추적 업데이트
    if (shouldCountUsage && !isCurrentlyPlaying) {
      // 놀이 시작
      isCurrentlyPlaying = true;
      playStartTime = now;
      await setStorage({ playStartTime: playStartTime });
      console.log(`[Play Session] Started at ${playStartTime}`);
    } else if (!shouldCountUsage && isCurrentlyPlaying) {
      // 놀이 종료
      isCurrentlyPlaying = false;
      playStartTime = null;
      await setStorage({ playStartTime: null });
      console.log(`[Play Session] Ended`);
    }

    // 유튜브 탭에서만 시간을 재설정 (다른 탭으로 이동 시에는 재설정하지 않음)
    const newTrackingTime = Date.now();
    trackingStartTime = newTrackingTime;
    console.log(`[Usage Tracking] trackingStartTime updated to: ${newTrackingTime}`);
  } else {
    // 유튜브가 아닌 곳으로 이동했거나 차단 상태일 때는 추적 시작 시간을 null로 설정
    trackingStartTime = null;

    // 공부 세션도 종료
    if (isCurrentlyStudying) {
      isCurrentlyStudying = false;
      studyStartTime = null;
      await setStorage({ studyStartTime: null });
      console.log(`[Study Session] Ended (left YouTube)`);
    }

    // 놀이 세션도 종료
    if (isCurrentlyPlaying) {
      isCurrentlyPlaying = false;
      playStartTime = null;
      await setStorage({ playStartTime: null });
      console.log(`[Play Session] Ended (left YouTube)`);
    }

    console.log(`[trackUsage] Tracking stopped - not on YouTube or blocked`);
  }
}

async function ensureOffscreenDocument() {
  if (!useStudyAI) {
    console.log('Study AI is disabled, skipping offscreen document creation');
    return false;
  }
  
  if (!chrome.offscreen || !chrome.offscreen.createDocument || !chrome.offscreen.hasDocument) {
    console.error('Offscreen API not available');
    return false;
  }
  
  try {
    const has = await chrome.offscreen.hasDocument();
    if (has) {
      console.log('Offscreen document already exists');
      return true;
    }
    
    console.log('Creating offscreen document...');
    await chrome.offscreen.createDocument({
      url: 'offscreen.html',
      reasons: ['DOM_SCRAPING'],  // Use string instead of enum constant
      justification: 'Capture the visible tab and run study detection model.'
    });
    console.log('Offscreen document created successfully');
    return true;
  } catch (e) {
    console.error('Failed to create offscreen document:', e);
    return false;
  }
}

// Schedule a quick inference when URL changes
function scheduleInferenceOnUrlChange() {
  if (!useStudyAI) {
    return;
  }

  // Check if URL actually changed
  if (activeTabUrl === lastInferenceUrl) {
    console.log('[Study AI] URL unchanged, no quick inference needed');
    return;
  }

  console.log('[Study AI] URL changed detected:', lastInferenceUrl, '→', activeTabUrl);

  // Clear any existing timeout
  if (inferenceTimeout) {
    clearTimeout(inferenceTimeout);
    console.log('[Study AI] Cleared previous inference timeout');
  }

  // Schedule inference after 3 seconds
  console.log('[Study AI] Scheduling quick inference in 3 seconds');
  inferenceTimeout = setTimeout(async () => {
    console.log('[Study AI] Quick inference timeout triggered');
    await triggerStudyInference();
  }, 3000);
}

async function triggerStudyInference() {
  if (!useStudyAI) {
    return; // Silently skip if AI is disabled
  }

  if (!activeTabId || !activeTabUrl || !activeTabUrl.includes('youtube.com')) {
    return; // Silently skip if not on YouTube
  }

  console.log('[Study AI] ====== Starting Study Detection ======');
  console.log('[Study AI] Active tab:', activeTabId, 'URL:', activeTabUrl);
  console.log('[Study AI] Last inference URL:', lastInferenceUrl);

  try {
    console.log('[Study AI] Ensuring offscreen document...');
    const offscreenReady = await ensureOffscreenDocument();
    if (!offscreenReady) {
      console.warn('[Study AI] ✗ Offscreen document not ready');
      return;
    }
    console.log('[Study AI] ✓ Offscreen document ready');

    const tab = await chrome.tabs.get(activeTabId);
    const windowId = tab?.windowId;
    if (!windowId) {
      console.warn('[Study AI] ✗ No window ID available for tab:', activeTabId);
      return;
    }
    console.log('[Study AI] Window ID:', windowId);

    // Capture the tab screenshot (background script has access to chrome.tabs API)
    console.log('[Study AI] Capturing visible tab...');
    let dataUrl;
    try {
      dataUrl = await chrome.tabs.captureVisibleTab(windowId, { format: 'png' });
      if (!dataUrl) {
        throw new Error('Failed to capture tab');
      }
      console.log('[Study AI] ✓ Tab captured successfully, dataUrl length:', dataUrl.length);
    } catch (e) {
      console.error('[Study AI] ✗ Failed to capture tab:', e);
      await setStorage({
        probStudy: null,
        probStudyUpdatedAt: Date.now(),
        probStudyError: 'Failed to capture tab: ' + (e?.message || 'Unknown error')
      });
      return;
    }

    // Send the captured image to offscreen for inference
    console.log('[Study AI] Sending inference request to offscreen...');
    try {
      // Store the dataUrl temporarily so offscreen can access it
      await setStorage({
        pendingInferenceDataUrl: dataUrl,
        pendingInferenceTimestamp: Date.now()
      });

      // Send message to offscreen document specifically
      const response = await chrome.runtime.sendMessage({
        action: 'inferStudy',
        dataUrl,
        timestamp: Date.now()
      });
      console.log('[Study AI] ✓ Inference response:', response);

      // Update last inference URL
      lastInferenceUrl = activeTabUrl;
      console.log('[Study AI] Updated lastInferenceUrl to:', lastInferenceUrl);
    } catch (e) {
      console.error('[Study AI] ✗ Failed to send inference request:', e);
    }
  } catch (e) {
    console.error('[Study AI] ✗ Failed to trigger inference:', e);
  }
}

async function updateDailyLimit() {
  const result = await getStorage(['daily_limit_ms', 'daily_limit_reset_frequency', 'daily_limit_by_day']);
  const dailyLimitResetFrequency = result.daily_limit_reset_frequency || 'daily';

  if (dailyLimitResetFrequency === 'by_day') {
    const dayOfWeek = new Date().getDay();
    const dailyLimitByDay = result.daily_limit_by_day || {};
    let limit = dailyLimitByDay[dayOfWeek];
    if (limit === undefined || limit === -1) { // 무제한이거나 설정되지 않은 경우
      DAILY_LIMIT_MS = Infinity; // 무제한
    } else {
      DAILY_LIMIT_MS = limit * 60 * 1000; // 분을 밀리초로 변환
    }
  } else {
    if (result.daily_limit_ms !== undefined && result.daily_limit_ms !== null) {
      // Infinity 또는 숫자 값 처리
      DAILY_LIMIT_MS = result.daily_limit_ms === Infinity || result.daily_limit_ms === 0 ? Infinity : result.daily_limit_ms;
    } else {
      DAILY_LIMIT_MS = 30 * 60 * 1000; // 기본값
    }
  }
  console.log(`Daily limit updated to: ${DAILY_LIMIT_MS === Infinity ? 'Unlimited' : DAILY_LIMIT_MS / (60 * 1000) + ' minutes'}`);
}

// 탭이 활성화될 때
chrome.tabs.onActivated.addListener(async (activeInfo) => {
  await updateDailyLimit();
  await trackUsage(); // 이전 탭 사용 시간 저장 (await 추가)
  activeTabId = activeInfo.tabId;
  const tab = await chrome.tabs.get(activeTabId);
  activeTabUrl = tab.url;
  if (activeTabUrl && activeTabUrl.includes('youtube.com')) {
    const now = Date.now();
    trackingStartTime = now;
    console.log(`YouTube tab activated: ${activeTabUrl}, trackingStartTime set to: ${now}`);
    checkUsageAndBlock(); // 탭 활성화 시 바로 차단 여부 확인

    // Trigger quick inference if URL changed
    scheduleInferenceOnUrlChange();
  } else {
    activeTabId = null;
    activeTabUrl = null;
    trackingStartTime = null;
    console.log(`Non-YouTube tab activated, tracking reset`);
  }
});

// 탭이 업데이트될 때 (URL 변경 등)
chrome.tabs.onUpdated.addListener(async (tabId, changeInfo, tab) => {
  if (tabId === activeTabId && changeInfo.url) {
    await updateDailyLimit();
    await trackUsage(); // 이전 URL 사용 시간 저장 (await 추가)
    activeTabUrl = changeInfo.url;
    if (activeTabUrl && activeTabUrl.includes('youtube.com')) {
      const now = Date.now();
      trackingStartTime = now;
      console.log(`YouTube tab updated: ${activeTabUrl}, trackingStartTime set to: ${now}`);
      checkUsageAndBlock(); // 탭 업데이트 시 바로 차단 여부 확인

      // Trigger quick inference if URL changed
      scheduleInferenceOnUrlChange();
    } else {
      activeTabId = null;
      activeTabUrl = null;
      trackingStartTime = null;
      console.log(`Non-YouTube URL updated, tracking reset`);
    }
  }
});

// 확장 프로그램이 종료될 때 (브라우저 종료 등) 마지막 사용 시간 저장
chrome.windows.onRemoved.addListener(async (windowId) => {
  await updateDailyLimit();
  trackUsage();
});

// 긴급 시청 횟수 자동 초기화
async function checkAndResetEmergencyUses() {
  const today = getTodayDate();
  const result = await getStorage(['last_emergency_date', EMERGENCY_USES_SETTING_KEY, 'emergency_reset_frequency', 'emergency_uses_by_day']);
  const lastEmergencyDate = result.last_emergency_date;
  const emergencyResetFrequency = result.emergency_reset_frequency || 'daily';
  const emergencyUsesByDay = result.emergency_uses_by_day || {};

  let initialEmergencyUses;
  if (emergencyResetFrequency === 'by_day') {
    const dayOfWeek = new Date().getDay();
    initialEmergencyUses = emergencyUsesByDay[dayOfWeek] !== undefined ? emergencyUsesByDay[dayOfWeek] : 0;
  } else {
    initialEmergencyUses = result[EMERGENCY_USES_SETTING_KEY];
    if (initialEmergencyUses === undefined) {
      initialEmergencyUses = 3; // Default value
    }
  }

  let shouldReset = false;
  let currentResetDate;

  if (emergencyResetFrequency === 'daily' || emergencyResetFrequency === 'by_day') {
    currentResetDate = today;
    if (lastEmergencyDate !== currentResetDate) shouldReset = true;
  } else if (emergencyResetFrequency === 'weekly') {
    currentResetDate = getWeekStartDate();
    if (lastEmergencyDate !== currentResetDate) shouldReset = true;
  } else if (emergencyResetFrequency === 'monthly') {
    currentResetDate = getMonthStartDate();
    if (lastEmergencyDate !== currentResetDate) shouldReset = true;
  }

  if (shouldReset) {
    console.log(`Resetting emergency uses for ${emergencyResetFrequency} period.`);
    await setStorage({ emergency_uses_today: initialEmergencyUses, last_emergency_date: currentResetDate });
    // UI 업데이트 메시지 전송
    chrome.runtime.sendMessage({ action: "updateOptionsUI" }).catch(()=>{/* ignore */});
    chrome.runtime.sendMessage({ action: "updatePopupUI" }).catch(()=>{/* ignore */});
  }
}

// 긴급 시청 요청 처리
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
  // Handle storage requests from offscreen document
  if (request.action === "getStorage") {
    (async () => {
      const data = await getStorage(request.keys);
      sendResponse({ data });
    })();
    return true; // Keep message channel open for async response
  } else if (request.action === "setStorage") {
    (async () => {
      await setStorage(request.items);
      sendResponse({ success: true });
    })();
    return true; // Keep message channel open for async response
  } else if (request.action === "inferStudy") {
    // This message is intended for the offscreen document, not the background
    // Just acknowledge it and let it propagate to offscreen
    return false; // Don't keep channel open, let it reach offscreen
  }

  if (request.action === "requestEmergency") {
    (async () => {
      const result = await getStorage(['emergency_uses_today', 'isYoutubeBlocked', 'emergency_reset_frequency', 'emergency_uses_by_day', EMERGENCY_USES_SETTING_KEY, 'last_emergency_date']);
      let emergencyUses = result.emergency_uses_today;
      const isYoutubeBlockedStorage = result.isYoutubeBlocked || false;
      const emergencyResetFrequency = result.emergency_reset_frequency || 'daily';
      const emergencyUsesByDay = result.emergency_uses_by_day || {};

      let initialEmergencyUses;
      if (emergencyResetFrequency === 'by_day') {
        const dayOfWeek = new Date().getDay();
        initialEmergencyUses = emergencyUsesByDay[dayOfWeek] !== undefined ? emergencyUsesByDay[dayOfWeek] : 0;
        if (initialEmergencyUses === -1) {
          sendResponse({ success: false, message: "오늘은 긴급 시청이 무제한으로 설정되어 있습니다." });
          return;
        }
      } else {
        initialEmergencyUses = result[EMERGENCY_USES_SETTING_KEY];
        if (initialEmergencyUses === undefined) {
          initialEmergencyUses = 3;
        }
      }

      if (!isYoutubeBlockedStorage) {
        sendResponse({ success: false, message: "현재 차단상태가 아닙니다. 차단된 상태에서만 긴급 시청이 가능합니다." });
        return;
      }

      // Get current reset date to associate the usage with the correct period
      let currentResetDate;
      const today = getTodayDate();
      if (emergencyResetFrequency === 'daily' || emergencyResetFrequency === 'by_day') {
        currentResetDate = today;
      } else if (emergencyResetFrequency === 'weekly') {
        currentResetDate = getWeekStartDate();
      } else if (emergencyResetFrequency === 'monthly') {
        currentResetDate = getMonthStartDate();
      } else {
        currentResetDate = today; // Fallback
      }

      if (emergencyUses === undefined) {
        emergencyUses = initialEmergencyUses;
      }

      if (emergencyUses > 0) {
        emergencyUses--;
        await setStorage({ emergency_uses_today: emergencyUses, last_emergency_date: currentResetDate });

        emergencyModeActive = true;
        const emergencyEndTime = Date.now() + EMERGENCY_DURATION_MS;
        await setStorage({ emergencyModeActive: true, emergencyEndTime: emergencyEndTime });

        if (emergencyModeTimer) clearTimeout(emergencyModeTimer);

        emergencyModeTimer = setTimeout(async () => {
          emergencyModeActive = false;
          await setStorage({ emergencyModeActive: false, emergencyEndTime: null });
          await checkUsageAndBlock();
        }, EMERGENCY_DURATION_MS);

        const tabs = await chrome.tabs.query({ url: "*://*.youtube.com/*" });
        for (const tab of tabs) {
          try {
            await chrome.tabs.sendMessage(tab.id, { action: "unblockYoutube" });
          } catch (e) {
            if (!e.message.includes('Receiving end does not exist') && !e.message.includes('Could not establish connection')) {
              console.warn(`Error sending unblock message to tab ${tab.id}:`, e.message);
            }
          }
        }

        chrome.runtime.sendMessage({ action: "updateOptionsUI" }).catch(()=>{/* ignore */});
        chrome.runtime.sendMessage({ action: "updatePopupUI" }).catch(()=>{/* ignore */});
        sendResponse({ success: true });
      } else {
        sendResponse({ success: false, message: "남은 긴급 시청 횟수가 없습니다." });
      }
    })();
    return true;
  } else if (request.action === "manualBlock") {
    (async () => {
      if (!isYoutubeBlocked) {
        isYoutubeBlocked = true;
        isManuallyBlocked = true;
        await setStorage({ isYoutubeBlocked: true, isManuallyBlocked: true });
        const tabs = await chrome.tabs.query({ url: "*://*.youtube.com/*" });
        for (const tab of tabs) {
          try {
            await chrome.tabs.sendMessage(tab.id, { action: "blockYoutube", reason: "manualBlock" });
          } catch (e) {
            if (!e.message.includes('Receiving end does not exist') && !e.message.includes('Could not establish connection')) {
              console.warn(`Error sending block message to tab ${tab.id}:`, e.message);
            }
          }
        }
        chrome.runtime.sendMessage({ action: "updateOptionsUI" }).catch(()=>{/* ignore */});
        chrome.runtime.sendMessage({ action: "updatePopupUI" }).catch(()=>{/* ignore */});
      }
      sendResponse({ success: true });
    })();
    return true;
  } else if (request.action === "manualUnblock") {
    (async () => {
      if (isYoutubeBlocked) {
        isYoutubeBlocked = false;
        isManuallyBlocked = false;
        await setStorage({ isYoutubeBlocked: false, isManuallyBlocked: false });
        const tabs = await chrome.tabs.query({ url: "*://*.youtube.com/*" });
        for (const tab of tabs) {
          try {
            await chrome.tabs.sendMessage(tab.id, { action: "unblockYoutube" });
          } catch (e) {
            if (!e.message.includes('Receiving end does not exist') && !e.message.includes('Could not establish connection')) {
              console.warn(`Error sending unblock message to tab ${tab.id}:`, e.message);
            }
          }
        }
        chrome.runtime.sendMessage({ action: "updateOptionsUI" }).catch(()=>{/* ignore */});
        chrome.runtime.sendMessage({ action: "updatePopupUI" }).catch(()=>{/* ignore */});
      }
      sendResponse({ success: true });
    })();
    return true;
  } else if (request.action === "startFocusMode") {
    (async () => {
      const duration = request.duration;
      const delay = request.delay || 0;

      // 기존 지연 타이머가 있으면 취소
      if (focusModeDelayTimer) {
        clearTimeout(focusModeDelayTimer);
        focusModeDelayTimer = null;
      }

      if (delay > 0) {
        // 지연 시작: delay 분 후에 집중 모드 시작
        const delayEndTime = Date.now() + delay * 60 * 1000;
        await setStorage({
          focusModeDelayEndTime: delayEndTime,
          focusModeDelayDuration: duration // 지연 후 실행할 지속 시간 저장
        });

        focusModeDelayTimer = setTimeout(async () => {
          focusModeActive = true;
          focusModeEndTime = Date.now() + duration * 60 * 1000;
          focusModeDelayTimer = null;
          await setStorage({
            focusModeActive: true,
            focusModeEndTime: focusModeEndTime,
            focusModeDelayEndTime: null,
            focusModeDelayDuration: null
          });
          await checkUsageAndBlock();
          chrome.runtime.sendMessage({ action: "updateOptionsUI" }).catch(()=>{/* ignore */});
          chrome.runtime.sendMessage({ action: "updatePopupUI" }).catch(()=>{/* ignore */});
        }, delay * 60 * 1000);
      } else {
        // 즉시 시작
        focusModeActive = true;
        focusModeEndTime = Date.now() + duration * 60 * 1000;
        await setStorage({
          focusModeActive: true,
          focusModeEndTime: focusModeEndTime,
          focusModeDelayEndTime: null,
          focusModeDelayDuration: null
        });
        await checkUsageAndBlock();
      }

      chrome.runtime.sendMessage({ action: "updateOptionsUI" }).catch(()=>{/* ignore */});
      chrome.runtime.sendMessage({ action: "updatePopupUI" }).catch(()=>{/* ignore */});
      sendResponse({ success: true });
    })();
    return true;
  } else if (request.action === "stopFocusMode") {
    (async () => {
      // 지연 타이머가 있으면 취소
      if (focusModeDelayTimer) {
        clearTimeout(focusModeDelayTimer);
        focusModeDelayTimer = null;
      }

      focusModeActive = false;
      focusModeEndTime = null;
      await setStorage({
        focusModeActive: false,
        focusModeEndTime: null,
        focusModeDelayEndTime: null,
        focusModeDelayDuration: null
      });
      await checkUsageAndBlock();
      chrome.runtime.sendMessage({ action: "updateOptionsUI" }).catch(()=>{/* ignore */});
      chrome.runtime.sendMessage({ action: "updatePopupUI" }).catch(()=>{/* ignore */});
      sendResponse({ success: true });
    })();
    return true;
  }
});

async function checkUsageAndBlock() {

  await updateDailyLimit();
  // 스토리지에서 최신 상태를 가져와 변수에 반영
  const storedState = await getStorage(['isManuallyBlocked', 'alwaysBlockShorts', 'whitelist', 'focusModeActive', 'focusModeEndTime', 'emergencyModeActive']);
  isManuallyBlocked = storedState.isManuallyBlocked || false;
  const alwaysBlockShorts = storedState.alwaysBlockShorts || false;
  const whitelist = storedState.whitelist || [];
  focusModeActive = storedState.focusModeActive || false;
  focusModeEndTime = storedState.focusModeEndTime || null;
  emergencyModeActive = storedState.emergencyModeActive || false;

  // 집중 모드 시간 만료 확인
  if (focusModeActive && focusModeEndTime && Date.now() >= focusModeEndTime) {
    focusModeActive = false;
    focusModeEndTime = null;
    await setStorage({ focusModeActive: false, focusModeEndTime: null });
    console.log('Focus mode ended.');
  }

  console.log(`checkUsageAndBlock called. emergencyModeActive: ${emergencyModeActive}, isManuallyBlocked: ${isManuallyBlocked}, alwaysBlockShorts: ${alwaysBlockShorts}, focusModeActive: ${focusModeActive}`);

  let blockReason = '';
  let shouldBlockGlobally;

  // 1. 긴급 모드, 집중 모드는 다른 모든 규칙보다 우선
  if (emergencyModeActive) {
    console.log('Emergency mode is active, not blocking.');
    shouldBlockGlobally = false;
  } else if (focusModeActive) {
    console.log('Focus mode is active, blocking YouTube.');
    shouldBlockGlobally = true;
    blockReason = 'focusMode';
  } else {
    // 2. 일반적인 차단 규칙 결정
    const currentUsage = await getTodayUsage();
    if (isManuallyBlocked) {
      shouldBlockGlobally = true;
      blockReason = 'manualBlock';
    } else if (currentUsage >= DAILY_LIMIT_MS) {
      shouldBlockGlobally = true;
      blockReason = 'usageLimit';
    } else {
      shouldBlockGlobally = false;
    }
  }

  // 전역 차단 상태 업데이트 (UI 표시용)
  // 만약 모든 탭이 화이트리스트 처리되어 실제로는 차단되지 않더라도,
  // 사용 제한을 넘었다는 "상태"는 유지하는 것이 사용자에게 명확할 수 있음.
  const usageLimitExceeded = (await getTodayUsage()) >= DAILY_LIMIT_MS;
  const displayBlockState = focusModeActive || isManuallyBlocked || (usageLimitExceeded && !emergencyModeActive);

  if (displayBlockState !== isYoutubeBlocked) {
      isYoutubeBlocked = displayBlockState;
      await setStorage({ isYoutubeBlocked: displayBlockState });
  }


  const tabs = await chrome.tabs.query({ url: "*://*.youtube.com/*" });
  for (const tab of tabs) {
    const isTabWhitelisted = tab.url && whitelist.some(whitelistedUrl => tab.url.includes(whitelistedUrl));

    // 화이트리스트의 공부 영상인지 확인
    const isWhitelistedStudyVideo = tab.url && whitelist.some(item => {
      if (typeof item === 'object' && item.isStudy === true) {
        const itemVideoId = extractVideoIdFromUrl(item.url);
        const currentVideoId = extractVideoIdFromUrl(tab.url);
        return itemVideoId && currentVideoId && itemVideoId === currentVideoId;
      }
      return false;
    });

    const isShortsTab = tab.url && tab.url.includes('youtube.com/shorts');

    let shouldBlockTab = shouldBlockGlobally;
    let finalBlockReason = blockReason;

    // 3. 개별 탭에 대한 최종 차단 여부 결정
    if (emergencyModeActive) {
        shouldBlockTab = false;
    } else if (isWhitelistedStudyVideo) {
        // 화이트리스트 공부 영상은 집중 모드에서도 허용
        shouldBlockTab = false;
    } else if (focusModeActive) {
        shouldBlockTab = true;
        finalBlockReason = 'focusMode';
    } else if (isTabWhitelisted) {
        shouldBlockTab = false; // 화이트리스트는 다른 규칙보다 우선
    } else if (alwaysBlockShorts && isShortsTab) {
        shouldBlockTab = true; // Shorts 차단은 화이트리스트 다음
        finalBlockReason = 'alwaysBlockShorts';
    }
    // `shouldBlockGlobally`는 수동차단, 시간제한을 이미 포함.

    try {
      // Try to send message first
      if (shouldBlockTab) {
        await chrome.tabs.sendMessage(tab.id, { action: "blockYoutube", reason: finalBlockReason });
        console.log(`Sent blockYoutube message to tab ${tab.id} for reason: ${finalBlockReason}.`);
      } else {
        await chrome.tabs.sendMessage(tab.id, { action: "unblockYoutube" });
        console.log(`Sent unblockYoutube message to tab ${tab.id}.`);
      }
    } catch (e) {
      if (e.message.includes('Receiving end does not exist') || e.message.includes('Could not establish connection')) {
        // Content script not injected yet, inject it and try again
        try {
          await chrome.scripting.executeScript({ target: { tabId: tab.id }, files: ['/content.js'] });
          // Retry sending the message
          if (shouldBlockTab) {
            await chrome.tabs.sendMessage(tab.id, { action: "blockYoutube", reason: finalBlockReason });
          } else {
            await chrome.tabs.sendMessage(tab.id, { action: "unblockYoutube" });
          }
        } catch (injectError) {
          console.warn(`Could not inject or message tab ${tab.id}:`, injectError.message);
        }
      } else {
        console.warn(`Could not send message to tab ${tab.id}:`, e.message);
      }
    }
  }

  // UI 업데이트 메시지 전송
  chrome.runtime.sendMessage({ action: "updatePopupUI" }).catch(()=>{/* ignore */});
}

// 주기적으로 사용 시간 확인
setInterval(async () => {
  try {
    await trackUsage();
  } catch (e) {
    console.error("Error in trackUsage interval:", e);
  }
}, 1000); // 1초마다 사용 시간 추적 및 저장

setInterval(async () => {
  try {
    await triggerStudyInference();
  } catch (e) {
    console.error('Error in study inference interval:', e);
  }
}, 10000); // 10초마다 추론 (URL 변경 시 3초 후 빠른 추론 발동)

setInterval(async () => {
  try {
    await checkUsageAndBlock();
    await checkAndResetEmergencyUses(); // 주기적으로 긴급 시청 횟수 초기화 확인
  } catch (e) {
    console.error("Error in checkUsageAndBlock interval:", e);
  }
}, 5000); // 5초마다 차단 여부 확인

console.log('TubeLimiter background script loaded.');