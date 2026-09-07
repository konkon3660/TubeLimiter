import { getStorage, setStorage } from '../lib/storage.js';
import { getTodayDate, getWeekStartDate, getMonthStartDate, addDaysToDate } from '../lib/time.js';
// 백그라운드는 getCurrentUser() 대신 아래 getActiveUser()를 쓴다 — "로그아웃"과 "세션 갱신 실패"를
// 구별해서 후자만 진단에 남기기 위해서다.
import { supabase } from '../lib/supabaseClient.js';
import { applyDayRollover } from '../lib/gamification.js';
import { computeLimitForDate, computeShortsLimit } from '../lib/limits.js';
import { HARDCORE_DISABLE_COOLDOWN_MS } from '../lib/hardcore.js';
import { FOCUS_STOP_COOLDOWN_MS, resolveFocusStopTime } from '../lib/focusMode.js';
import { EMERGENCY_GRANT_COOLDOWN_MS, EMERGENCY_DURATION_MS, emergencyOverlapMs } from '../lib/emergency.js';
import {
  usageDeltaSinceSync,
  combinedUsedMillis,
  sumEmergencyUsesInBucket,
  reportedEmergencyUsesInBucket,
  remainingEmergencyUses
} from '../lib/usageMerge.js';
import { isScheduleActive, minutesUntilNextScheduleStart } from '../lib/schedule.js';
import { focusStateBeforeTransition } from '../lib/focusTransition.js';
import {
  resolveBlockDecision,
  resolveTabBlock,
  isWhitelistedUrl,
  isShortsUrl,
  isShortsLimitExceeded
} from '../lib/blockDecision.js';
import { ALARM_KIND, evaluateAlarms } from '../lib/alarmRules.js';
import {
  planDateRollover,
  planEmergencyReset,
  emergencyResetDate,
  DEFAULT_EMERGENCY_USES
} from '../lib/dateRollover.js';
import { planLimitHistoryUpdate } from '../lib/limitHistory.js';
import { DiagnosticKind, summarizeFailure } from '../lib/syncDiagnostics.js';
import { recordDiagnosticFailure, recordSyncSuccess } from '../lib/diagnosticsStore.js';
import { t, tCount } from '../lib/i18n.js';

const MAX_ELAPSED_MS = 10 * 60 * 1000; // 비정상적으로 큰 elapsed 값 방어
const DEFAULT_DAILY_LIMIT_MS = 30 * 60 * 1000;

// 홈/검색 페이지는 소모 시간에서 제외한다. 홈 화면 썸네일 미리보기도 <video> 태그라
// 재생 여부만으로는 걸러지지 않으므로, 실제 시청 페이지(watch/shorts)인지 URL로 구분한다.
// 차단 대상 범위(유튜브 전체)와는 별개 기준이라 checkUsageAndBlock 쪽은 그대로 둔다.
function isTrackableYoutubeUrl(url) {
  return !!url && url.includes('youtube.com') && (url.includes('/watch') || url.includes('/shorts'));
}

let activeTabId = null;
let activeTabUrl = null;
let trackingStartTime = null;
let isVideoPlaying = true; // content script가 실제 재생 상태를 보고하기 전까지 낙관적으로 true

let isYoutubeBlocked = false;
// 사용시간 집계를 멈춰야 하는지(= 지금 실제로 볼 수 없는 상태인지). 표시용 isYoutubeBlocked와
// 갈라지는 값이라 따로 들고 있는다 - 긴급 시청 중엔 차단 사유가 무엇이든 집계는 계속돼야 한다
// (lib/blockDecision.js의 trackingBlocked 주석 참고).
// null은 "이번 세션에서 아직 판정한 적 없음"이라 첫 판정이 반드시 storage에 기록된다
// (이전 버전에서 올라온 저장소엔 이 키가 아예 없다).
let trackingBlocked = null;
let isManuallyBlocked = false;
let focusModeActive = false;
let focusModeEndTime = null;
let focusModeDelayTimer = null;
// 활성 집중 모드를 "종료 요청"한 시각. null이면 요청이 없는 상태.
// 지연(분) 대기 중인, 아직 시작 전인 예약을 취소하는 것과는 무관 - 그건 즉시 처리된다.
let focusStopRequestedAt = null;
let emergencyModeActive = false;
let emergencyModeTimer = null;
// Shorts 전용 한도를 넘겼는지. 탭 단위 차단이라 전역 isYoutubeBlocked에는 반영하지 않지만
// (일반 영상은 계속 봐야 한다), 긴급 시청 발급은 "지금 뭔가 막혀 있어야" 가능하므로 그 관문에서
// 이 값을 같이 본다 — 안 그러면 Shorts만 막힌 상태를 긴급 시청으로 뚫을 방법이 없어진다.
let shortsLimitBlocked = false;
// 예약 차단(요일별 반복 시간대). 사용자가 직접 켜고 끄는 게 아니라 시계에 따라 자동으로
// 바뀌므로, 집중 모드 시작/종료 알림과 같은 방식으로 "전이"를 감지해 알리려면 직전 값을
// storage에 남겨둬야 한다 (서비스워커가 재시작돼도 스퓨리어스 "시작" 알림이 안 뜨게).
let scheduleBlockActive = false;

// --- 설정 캐시 (로컬 미러, source of truth는 로그인 시 Supabase settings 테이블) ---
let settingsCache = {
  daily_limit_ms: DEFAULT_DAILY_LIMIT_MS,
  daily_limit_by_day: {},
  daily_limit_reset_frequency: 'daily',
  always_block_shorts: false,
  // Shorts 전용 일일 한도(ms). 0 = 미설정 = 한도 없음 (schema.sql의 settings 절 참고).
  shorts_limit_ms: 0,
  whitelist: [],
  emergency_config: { dailyUses: 3, resetFrequency: 'daily' },
  alarm_interval_minutes: 0,
  alarm_milestones_enabled: true,
  hardcore_mode: false,
  hardcore_disable_requested_at: null,
  scheduled_blocks: []
};

async function loadSettingsCache() {
  const local = await getStorage(['settingsCache']);
  if (local.settingsCache) settingsCache = { ...settingsCache, ...local.settingsCache };
}

// --- 진단 기록 ---
//
// MV3 서비스워커는 유휴 상태면 죽고 그때 콘솔도 같이 날아간다. console.error만 남기던 실패는
// 사실상 흔적이 없어서, 며칠째 동기화가 막혀도 알 방법이 없었다. 아래 헬퍼로 실패를
// chrome.storage 링버퍼에 남긴다(lib/syncDiagnostics.js, 안드로이드 SyncDiagnostics.kt와 같은 규칙).
//
// 콘솔 출력은 그대로 둔다 — devtools를 열어둔 개발 중에는 여전히 그쪽이 빠르다.

/**
 * 예외/오류 객체는 반드시 summarizeFailure를 거쳐 **이름 + 상태 코드**로만 줄여 저장한다.
 * 서버가 돌려준 문구를 그대로 넣으면 조건에 걸린 이메일이나 user_id가 진단 화면에 뜨고,
 * 사용자가 그걸 그대로 복사해 남에게 보내게 된다. step은 우리가 직접 붙이는 고정 문자열이라 안전하다.
 */
function recordFailure(kind, step, error) {
  return recordDiagnosticFailure(kind, `${step}/${summarizeFailure(error)}`);
}

/**
 * 로그인한 사용자, 없으면 null.
 *
 * supabaseClient의 getCurrentUser()는 세션이 없을 때도 갱신에 실패했을 때도 똑같이 null을 돌려줘서, 토큰 갱신이
 * 며칠째 막힌 상태가 "그냥 로그아웃"과 구별되지 않는다. 세션 자체가 없으면(=진짜 로그아웃)
 * 조용히 넘어가고, 세션은 있는데 사용자를 못 얻은 경우에만 진단에 남긴다 —
 * 안드로이드 SyncRepository.activeUserIdOrNull()과 같은 판정이다.
 */
async function getActiveUser() {
  const { data, error } = await supabase.auth.getUser();
  if (!error && data?.user) return data.user;

  // getSession()은 로컬 세션만 읽으므로(만료됐으면 갱신 시도) 여기서 네트워크가 한 번 더 나가지 않는다.
  const { data: sessionData, error: sessionError } = await supabase.auth.getSession();
  if (sessionError) {
    await recordFailure(DiagnosticKind.AUTH, 'session_refresh', sessionError);
  } else if (sessionData?.session) {
    await recordFailure(DiagnosticKind.AUTH, 'get_user', error);
  }
  return null;
}

let lastSettingsRefreshAt = 0;
const SETTINGS_REFRESH_INTERVAL_MS = 30 * 1000;

async function refreshSettingsFromSupabase(force = false) {
  if (!force && Date.now() - lastSettingsRefreshAt < SETTINGS_REFRESH_INTERVAL_MS) return;
  lastSettingsRefreshAt = Date.now();

  const user = await getActiveUser();
  if (!user) return;
  const { data, error } = await supabase.from('settings').select('*').eq('user_id', user.id).maybeSingle();
  if (error) {
    console.error('[TubeLimiter] 설정 동기화 실패:', error);
    await recordFailure(DiagnosticKind.SYNC_SETTINGS, 'pull', error);
    return;
  }
  // 왕복 자체는 성공했으므로 행이 아직 없어도(가입 직후) 성공으로 친다.
  await recordSyncSuccess();
  if (!data) return;
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

// 그날 실제로 적용된 한도를 날짜별로 남긴다(limit_history). 이게 없으면 대시보드가 과거
// 날짜까지 "지금 설정된 한도"로 소급 판정해서, 한도를 올리는 순간 예전에 초과했던 날들이
// 한꺼번에 성공으로 바뀐다. 판정/정리 규칙은 lib/limitHistory.js에 있고 여기선 읽고 쓰기만 한다.
async function recordLimitHistory(updates) {
  if (!updates.length) return;
  const { limit_history } = await getStorage(['limit_history']);
  const { history, changed } = planLimitHistoryUpdate(limit_history || {}, updates, getTodayDate());
  // 값이 그대로면 쓰지 않는다 - 이 함수는 사용시간이 기록될 때마다(그리고 매 틱마다) 불린다.
  if (changed) await setStorage({ limit_history: history });
}

/**
 * 오늘치 한도 기록. 롤오버를 한 번도 안 거친 오늘 날짜에 구멍이 생기지 않도록,
 * "오늘 기록이 처음 생기는" 자리(사용시간 저장)와 롤오버 양쪽에서 부른다.
 */
async function recordTodayLimit() {
  const today = getTodayDate();
  await recordLimitHistory([{ date: today, limitMs: computeLimitForDate(settingsCache, today) }]);
}

async function addShortsUsage(elapsedMs) {
  const { usage_history_shorts } = await getStorage(['usage_history_shorts']);
  const history = usage_history_shorts || {};
  const today = getTodayDate();
  history[today] = (history[today] || 0) + elapsedMs;
  await setStorage({ usage_history_shorts: history });
}

async function getTodayShortsUsage() {
  const { usage_history_shorts } = await getStorage(['usage_history_shorts']);
  return (usage_history_shorts || {})[getTodayDate()] || 0;
}

// 긴급 시청 기록(날짜별 { uses, ms }). 긴급 시청으로 본 시간은 usage_history에도 그대로
// 쌓이지만(총 시청시간은 사실대로 기록), 자정 롤오버 때 스트릭 판정에서 빼주려면 그중 얼마가
// 긴급분이었는지를 알아야 해서 여기에 따로 남긴다. uses는 "그날 긴급 시청을 한 번이라도
// 썼는가"를 판정하는 값 — 부여만 받고 안 봐서 ms가 0인 날도 완벽한 날에서는 빠져야 한다.
async function getEmergencyHistory() {
  const { emergency_history } = await getStorage(['emergency_history']);
  return emergency_history || {};
}

function emergencyEntryFor(history, date) {
  const entry = (history || {})[date];
  return { uses: entry?.uses || 0, ms: entry?.ms || 0 };
}

async function addEmergencyUsage(elapsedMs) {
  const history = await getEmergencyHistory();
  const today = getTodayDate();
  const entry = emergencyEntryFor(history, today);
  history[today] = { uses: entry.uses, ms: entry.ms + elapsedMs };
  await setStorage({ emergency_history: history });
}

async function addEmergencyUse() {
  const history = await getEmergencyHistory();
  const today = getTodayDate();
  const entry = emergencyEntryFor(history, today);
  history[today] = { uses: entry.uses + 1, ms: entry.ms };
  await setStorage({ emergency_history: history });
}

// usage_history_hourly는 대시보드의 "시간대별 이용 패턴" 차트 전용 데이터라 usage_history처럼
// 무기한 보관할 필요가 없다 (히트맵/스트릭 계산은 이 데이터를 쓰지 않음). 계속 쌓이면 storage
// 용량을 불필요하게 차지하므로, 쓸 때마다 오래된 날짜를 정리한다.
const HOURLY_HISTORY_RETENTION_DAYS = 60;

function pruneOldHourlyHistory(history) {
  const cutoff = addDaysToDate(getTodayDate(), -HOURLY_HISTORY_RETENTION_DAYS);
  for (const date in history) {
    if (date < cutoff) delete history[date];
  }
}

async function addHourlyUsage(elapsedMs, now) {
  const { usage_history_hourly } = await getStorage(['usage_history_hourly']);
  const history = usage_history_hourly || {};
  const today = getTodayDate();
  const hour = String(new Date(now).getHours());
  const dayBucket = history[today] || {};
  dayBucket[hour] = (dayBucket[hour] || 0) + elapsedMs;
  history[today] = dayBucket;
  pruneOldHourlyHistory(history);
  await setStorage({ usage_history_hourly: history });
}

// 브라우저 창의 OS 포커스 여부는 캐시하지 않고 그때그때 직접 조회한다.
// chrome.windows.onFocusChanged로 캐싱한 값은 서비스워커 재시작이나 팝업 열고 닫는 동작
// 중에 실제 상태와 어긋나 캐시가 뒤집힌 채 고착되는 경우가 있었다 (포커스 판정 반전 버그).
async function isChromeWindowFocused() {
  try {
    // windowTypes로 'normal'만 필터링하면 팝업(우리 확장 자체 팝업 포함)이 떠서
    // OS 포커스를 가져간 순간엔 원래 창이 focused:false로 나와버린다.
    // 크롬 창(어떤 타입이든) 중 하나라도 OS 포커스가 있으면 "포커스 있음"으로 본다.
    const win = await chrome.windows.getLastFocused();
    return !!win && win.focused;
  } catch {
    return true; // 조회 실패 시 소모 방지 기능 때문에 트래킹이 영구히 멈추지 않도록 fail-open
  }
}

// 재생 상태의 source of truth는 content script다. 서비스워커가 재시작했거나 탭/URL이 막 바뀐
// 직후엔 인메모리 isVideoPlaying이 없어 낙관적으로 true를 깔아두는데, content script는 상태가
// "바뀔 때만" 보고하므로 일시정지해둔 영상은 그 낙관값이 영영 정정되지 않는다.
// 그래서 낙관적으로 가정해야 하는 자리마다 지금 값을 직접 물어봐서 맞춘다.
async function syncPlaybackStateFromTab(tabId) {
  if (!tabId) return;
  try {
    const response = await chrome.tabs.sendMessage(tabId, { action: 'requestPlaybackState' });
    if (typeof response?.playing === 'boolean') isVideoPlaying = response.playing;
  } catch {
    /* content script가 아직 없거나 탭이 닫힘 - 기존 낙관값 유지 */
  }
}

// content script의 재생 보고는 activeTabId와 일치할 때만 반영하는데, 서비스워커가 재시작하면
// activeTabId가 비어있고 탭 전환/URL 변경이 없으면 이를 되살릴 이벤트도 없다. 그 상태에서
// 보고를 버리면 일시정지가 반영되지 않아 계속 시청 중으로 집계된다. 보고한 탭이 실제로 지금
// 활성화된 유튜브 시청 탭이면(= 어차피 우리가 추적해야 할 탭) activeTabId를 그 탭으로 맞춘다.
async function reconcileActiveTab(tabId) {
  if (!tabId || tabId === activeTabId) return;
  try {
    const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
    if (!tab || tab.id !== tabId || !isTrackableYoutubeUrl(tab.url)) return;
    await trackUsage(); // 이전 탭 구간을 먼저 정리하고 넘어간다
    activeTabId = tab.id;
    activeTabUrl = tab.url;
    trackingStartTime = Date.now();
  } catch {
    /* 조회 실패 시 기존 상태 유지 */
  }
}

let trackingInProgress = false;

// trackUsage는 알람/탭 이벤트/윈도우 이벤트/팝업 폴링(getTrackingStatus, 팝업 켜져있는 동안
// 초당 1회) 등 여러 경로에서 겹쳐 호출될 수 있다. 내부적으로 startTime을 스냅샷 뜬 뒤
// getStorage/isChromeWindowFocused 같은 await를 여러 번 거치는데, 그사이 다른 호출이 끼어들면
// 둘 다 같은 trackingStartTime을 기준으로 elapsed를 계산해 같은 구간을 두 번 저장해버린다
// (checkDateRollover가 겪었던 것과 같은 종류의 겹침 문제라 같은 락 패턴을 쓴다).
async function trackUsage(opts) {
  if (trackingInProgress) return;
  trackingInProgress = true;
  try {
    await trackUsageInner(opts);
  } finally {
    trackingInProgress = false;
  }
}

async function trackUsageInner({ assumeFocused = false, focusedOverride = null } = {}) {
  // 아래 두 복구 경로에서 isVideoPlaying을 낙관적으로 true로 깔았는지 표시.
  // 차단 중이면 어차피 집계하지 않으므로 storage 확인 뒤에 한 번만 실제 값을 물어본다.
  let playbackStateAssumed = false;

  // 서비스워커가 유휴 상태로 죽었다 알람에 깨어나면 activeTabId 등 인메모리 상태가 초기화된다.
  // 탭 전환/URL 변경 없이 같은 유튜브 영상을 계속 보고 있으면 이를 되살릴 이벤트가 따로 없으므로,
  // activeTabId가 비어있을 땐 매번 실제 활성 탭을 조회해서 다시 잡아준다.
  if (!activeTabId) {
    try {
      const [tab] = await chrome.tabs.query({ active: true, lastFocusedWindow: true });
      if (tab && isTrackableYoutubeUrl(tab.url)) {
        activeTabId = tab.id;
        activeTabUrl = tab.url;
        trackingStartTime = Date.now();
        isVideoPlaying = true;
        playbackStateAssumed = true;
      }
    } catch {
      /* 조회 실패 시 기존 상태 유지 */
    }
  }

  // 차단(수동/집중모드/한도) 중엔 trackingStartTime을 null로 비워두는데, 탭 전환이나 URL 변경
  // 없이 같은 탭에서 차단이 풀리면(수동 해제, 집중모드 종료 등) 이 값을 되살려줄 이벤트가 없다.
  // activeTabId/URL은 그대로 유효하니, null이면 지금 시점 기준으로 다시 시작한다 - 아래
  // storedBlocked 체크에서 여전히 차단 중이면 어차피 곧바로 다시 null로 리셋되니 안전하다.
  if (activeTabId && isTrackableYoutubeUrl(activeTabUrl) && !trackingStartTime) {
    trackingStartTime = Date.now();
    isVideoPlaying = true;
    playbackStateAssumed = true;
  }

  // await 도중 다른 탭 이벤트가 activeTabUrl/trackingStartTime을 바꿔버릴 수 있으니
  // 함수 시작 시점 값을 스냅샷 떠서 그것만 쓴다 (공유 변수 재참조 금지).
  const tabUrl = activeTabUrl;
  const startTime = trackingStartTime;
  const wasPlaying = isVideoPlaying;

  if (!(activeTabId && isTrackableYoutubeUrl(tabUrl) && startTime)) {
    trackingStartTime = null;
    return;
  }

  // 인메모리 차단 상태는 서비스워커가 재시작되면 잠깐 stale할 수 있으니 실제로 시간을 더하기
  // 직전엔 storage의 최신 값으로 다시 확인한다.
  //
  // 집계를 멈추는 기준은 표시용 isYoutubeBlocked가 아니라 trackingBlocked다. 표시용 값은 긴급
  // 시청 중에도 수동 차단을 살려두기 때문에, 그걸로 게이트를 걸면 "수동 차단 위에서 쓴 긴급
  // 시청은 탭은 풀리는데 시간은 집계되지 않는" 비대칭이 생긴다(한도 초과 위에서 쓴 긴급 시청은
  // 집계됐다). 이제 긴급 시청으로 허용된 시간은 차단 사유와 무관하게 집계된다.
  const {
    isYoutubeBlocked: storedBlocked,
    trackingBlocked: storedTrackingBlocked,
    last_emergency_granted_at: emergencyGrantedAt
  } = await getStorage(['isYoutubeBlocked', 'trackingBlocked', 'last_emergency_granted_at']);
  if (storedBlocked) isYoutubeBlocked = true;
  // 이전 버전에서 막 올라와 아직 checkUsageAndBlock이 한 번도 안 돈 경우엔 이 키가 없다 -
  // 그동안은 예전 기준(표시용 값)을 그대로 쓴다.
  if (storedTrackingBlocked ?? !!storedBlocked) {
    trackingStartTime = null;
    return;
  }

  // 낙관적으로 깔아둔 재생 상태를 content script의 실제 값으로 교체한다. 위에서 스냅샷을
  // 이미 떴으므로 여기서 바뀐 값은 다음 구간부터 적용되는데, 복구 경로는 trackingStartTime을
  // 방금 리셋해 이번 구간의 elapsed가 0이라 어차피 잃을 게 없다.
  if (playbackStateAssumed) await syncPlaybackStateFromTab(activeTabId);

  // 팝업이 상태를 물어보며 트리거한 호출(assumeFocused)은 팝업 자체가 OS 포커스를
  // 가져간 상태라 isChromeWindowFocused()가 false로 오판한다. 팝업이 떠 있다는 것
  // 자체가 이미 브라우저를 보고 있다는 뜻이므로 이 경우엔 포커스 조회를 건너뛴다.
  // 창 포커스 전환 이벤트는 전환이 끝난 뒤에 오므로 지금 조회하면 직전 구간을 새 상태로
  // 판정해버린다 - 그런 호출은 전환 직전의 포커스 값을 focusedOverride로 직접 넘겨준다.
  const wasFocused = focusedOverride !== null ? focusedOverride : (assumeFocused || (await isChromeWindowFocused()));
  const now = Date.now();
  const elapsed = now - startTime;
  // 재생/포커스 상태가 바뀐 구간이 다음 구간에 섞이지 않도록 항상 여기서 리셋.
  trackingStartTime = now;

  if (elapsed < 0 || elapsed > MAX_ELAPSED_MS || !Number.isFinite(elapsed)) return;

  // 영상이 멈춰있거나(방치/잠수) 브라우저 창이 포커스를 잃은 구간은 소모 시간에서 제외.
  if (!wasPlaying || !wasFocused) return;

  const newUsage = (await getTodayUsage()) + elapsed;
  await saveTodayUsage(newUsage);
  // 오늘 기록이 생겼으니 그 시간에 적용되던 한도도 같이 남긴다 - 롤오버를 거치기 전이라도
  // 대시보드가 오늘 칸을 추정치로 그리지 않게 한다.
  await recordTodayLimit();
  await addHourlyUsage(elapsed, now);
  // 이 구간 중 긴급 시청 창과 겹친 만큼은 긴급분으로도 따로 남긴다 (usage_history엔 이미 포함).
  const emergencyPortion = Math.min(elapsed, emergencyOverlapMs(startTime, now, emergencyGrantedAt));
  if (emergencyPortion > 0) {
    await addEmergencyUsage(emergencyPortion);
  }
  if (tabUrl.includes('/shorts')) {
    await addShortsUsage(elapsed);
  }
}

// --- 날짜 롤오버 (자정 넘어가면 어제 기록으로 스트릭/XP 갱신) ---

let rolloverInProgress = false;

// handleTick이 탭 이벤트/알람 등 여러 경로에서 겹쳐 호출될 수 있어, 자정 경계에 두 호출이
// 동시에 들어오면 같은 날짜를 두 번 롤오버 처리해 스트릭/XP가 중복 증가할 수 있다.
// 락으로 한 번에 하나만 실행되게 막는다.
async function checkDateRollover() {
  if (rolloverInProgress) return;
  rolloverInProgress = true;
  try {
    await checkDateRolloverInner();
  } finally {
    rolloverInProgress = false;
  }
}

async function checkDateRolloverInner() {
  const today = getTodayDate();
  const { local_current_date } = await getStorage(['local_current_date']);

  // 어떤 날짜들을 정산해야 하는지는 순수 판정에 맡기고(lib/dateRollover.js), 여기선
  // 그 결과대로 Supabase에 반영하고 기준점을 옮기는 일만 한다.
  const plan = planDateRollover(local_current_date, today);

  // 정산되는 지난 날짜들과 새로 시작하는 오늘의 한도를 남긴다. 지난 날짜는 이미 기록이 있으면
  // 건드리지 않는다(keepExisting) - 지금 settingsCache는 그날 이후 바뀌었을 수 있어서, 그날
  // 남겨둔 값이 언제나 더 정확하다. 기록이 없는 날(브라우저를 안 켠 날)만 아래 applyDayRollover가
  // 쓰는 것과 같은 값으로 채워 히트맵과 스트릭 판정이 어긋나지 않게 한다.
  await recordLimitHistory([
    ...plan.dates.map((date) => ({
      date,
      limitMs: computeLimitForDate(settingsCache, date),
      keepExisting: true
    })),
    { date: today, limitMs: computeLimitForDate(settingsCache, today) }
  ]);

  if (plan.dates.length === 0) {
    if (plan.nextStoredDate) await setStorage({ local_current_date: plan.nextStoredDate });
    return;
  }

  const { usage_history } = await getStorage(['usage_history']);
  const emergencyHistory = await getEmergencyHistory();
  const user = await getActiveUser();

  for (const date of plan.dates) {
    const usageMs = (usage_history || {})[date] || 0;
    const limitMs = computeLimitForDate(settingsCache, date);
    const emergency = emergencyEntryFor(emergencyHistory, date);

    if (user && settingsCache.hardcore_mode) {
      try {
        await applyDayRollover(supabase, user.id, {
          date,
          usageMs,
          limitMs,
          emergencyMs: emergency.ms,
          emergencyUses: emergency.uses
        });
        await recordSyncSuccess();
      } catch (e) {
        console.error('[TubeLimiter] rollover failed:', e);
        // 스트릭/XP/뱃지 push는 자정에 딱 한 번 도는 경로라 실패하면 그날 기록이 통째로
        // 서버에 안 올라간다. 다음 기회도 하루 뒤라 콘솔만으로는 절대 못 잡는다.
        await recordFailure(DiagnosticKind.SYNC_STREAK, 'rollover', e);
      }
    }
  }

  await setStorage({ local_current_date: plan.nextStoredDate });
}

// --- 하드코어 모드 해제 쿨다운 ---
// "끄기 요청"이 있고 쿨다운이 다 지났으면 실제로 hardcore_mode를 끈다.
// 요청 후 쿨다운이 지나기 전에 취소되면 hardcore_disable_requested_at이 null로 되돌아가므로 여기 걸리지 않는다.
// 옵션 페이지에서 "끄면 스트릭이 초기화된다"고 경고했으므로, 실제 해제 시점에 current_streak을 0으로
// 리셋해서 경고가 허언이 되지 않게 한다 (best_streak/total_success_days는 보존).

async function checkHardcoreDisableCooldown() {
  if (!settingsCache.hardcore_mode || !settingsCache.hardcore_disable_requested_at) return;

  const requestedAt = new Date(settingsCache.hardcore_disable_requested_at).getTime();
  if (Date.now() < requestedAt + HARDCORE_DISABLE_COOLDOWN_MS) return;

  const user = await getActiveUser();
  if (!user) return;

  const updated = { hardcore_mode: false, hardcore_disable_requested_at: null };
  const { error } = await supabase.from('settings').update(updated).eq('user_id', user.id);
  if (error) {
    console.error('[TubeLimiter] 하드코어 모드 해제 반영 실패:', error);
    // 사용자는 이미 24시간을 기다렸는데 해제가 조용히 안 되는 상태다. 화면엔 "곧 해제됩니다"만
    // 계속 뜨므로, 남겨두지 않으면 왜 안 풀리는지 알 단서가 없다.
    await recordFailure(DiagnosticKind.SYNC_SETTINGS, 'hardcore_disable', error);
    return;
  }
  await recordSyncSuccess();
  settingsCache = { ...settingsCache, ...updated };
  await setStorage({ settingsCache });

  // 완벽한 날 연속 기록도 같은 성격의 진행 중 기록이라 함께 리셋한다 (누적 perfect_days와
  // best_perfect_streak은 지난 성과라 보존).
  const { error: streakError } = await supabase
    .from('streaks')
    .update({ current_streak: 0, current_perfect_streak: 0 })
    .eq('user_id', user.id);
  // 여기가 조용히 실패하면 "끄면 연속 기록이 초기화된다"는 경고가 허언이 되는데, 지금까지는
  // 반환값을 아예 안 봐서 실패해도 아무 데도 안 남았다.
  if (streakError) await recordFailure(DiagnosticKind.SYNC_STREAK, 'hardcore_reset', streakError);

  notifyUiUpdate();
}

// --- Supabase 동기화 ---

// 확장과 안드로이드 둘 다 같은 계정으로 daily_usage를 건드리므로, 그냥 upsert하면 나중 쓴
// 기기가 먼저 쓴 기기 값을 덮어써서 사용시간이 사라진다. 그래서 절대값 upsert 대신
// increment_daily_usage RPC로 "지난 호출 이후 늘어난 만큼"만 서버에 더하고, 응답으로
// 돌아오는 합계(다른 기기 몫 포함)를 dailyUsageCombinedMillis에 저장해 차단 판정에 쓴다.
// (documents/BACKEND.md 참고)
//
// 매 틱마다 그대로 부르면(탭 전환마다 발생) 델타가 0이어도 매번 네트워크를 타므로,
// 설정 새로고침과 같은 30초 스로틀을 둔다. 창 종료 시엔 다음 기회가 언제일지 몰라 force로 우회한다.
let lastDailyUsageSyncAt = 0;
const DAILY_USAGE_SYNC_INTERVAL_MS = 30 * 1000;
let syncInProgress = false;

// increment_daily_usage는 서버에서 usage_ms를 절대값으로 덮어쓰지 않고 델타만큼 더한다
// (기기별 몫이 안 사라지게 하려는 설계). 그런데 두 호출이(handleTick의 30초 스로틀 동기화와
// windows.onRemoved의 강제 동기화 등) 겹치면 둘 다 "마지막 동기화 이후" 기준점을 아직
// 갱신되기 전의 같은 값으로 읽어서 각자 델타를 계산하고, 그 델타가 서버에 두 번 더해져버린다.
// 로컬 저장소처럼 나중 쓰기가 이전 값을 덮어쓰는 게 아니라 진짜로 두 배로 누적되는 것이라
// 대시보드(로컬 usage_history 그대로 표시)와 팝업(서버 합산값을 얹어 표시)이 벌어지게 된다.
// trackUsage/checkDateRollover와 같은 락 패턴으로 겹침 자체를 막는다.
async function syncUsageToSupabase(force = false) {
  if (syncInProgress) return;
  syncInProgress = true;
  try {
    await syncUsageToSupabaseInner(force);
  } finally {
    syncInProgress = false;
  }
}

async function syncUsageToSupabaseInner(force = false) {
  if (!force && Date.now() - lastDailyUsageSyncAt < DAILY_USAGE_SYNC_INTERVAL_MS) return;
  lastDailyUsageSyncAt = Date.now();

  const user = await getActiveUser();
  if (!user) return;

  const today = getTodayDate();
  const [{ usage_history }, { usage_history_shorts }, emergencyHistory, synced] = await Promise.all([
    getStorage(['usage_history']),
    getStorage(['usage_history_shorts']),
    getEmergencyHistory(),
    getStorage([
      'dailyUsageSyncDate',
      'dailyUsageSyncedMillis',
      'dailyUsageShortsSyncedMillis',
      'dailyUsageEmergencySyncedMillis',
      'dailyUsageEmergencyUsesSyncedCount'
    ])
  ]);

  const localUsage = (usage_history || {})[today] || 0;
  const localShorts = (usage_history_shorts || {})[today] || 0;
  const todayEmergency = emergencyEntryFor(emergencyHistory, today);
  const localEmergency = todayEmergency.ms;
  // 긴급 시청 "횟수"도 시간과 같은 델타 방식으로 올린다. 여기까지 올려야 다른 기기가 남은
  // 횟수를 제대로 알 수 있다 — 안 올리면 기기를 바꿔서 횟수를 다시 채우는 우회가 가능하다.
  const localEmergencyUses = todayEmergency.uses;
  const usageDelta = usageDeltaSinceSync(localUsage, synced.dailyUsageSyncDate, synced.dailyUsageSyncedMillis, today);
  const shortsDelta = usageDeltaSinceSync(localShorts, synced.dailyUsageSyncDate, synced.dailyUsageShortsSyncedMillis, today);
  const emergencyDelta = usageDeltaSinceSync(localEmergency, synced.dailyUsageSyncDate, synced.dailyUsageEmergencySyncedMillis, today);
  const emergencyUsesDelta = usageDeltaSinceSync(
    localEmergencyUses, synced.dailyUsageSyncDate, synced.dailyUsageEmergencyUsesSyncedCount, today
  );

  const { data, error } = await supabase.rpc('increment_daily_usage', {
    p_date: today,
    p_usage_delta_ms: usageDelta,
    p_shorts_delta_ms: shortsDelta,
    p_emergency_delta_ms: emergencyDelta,
    p_emergency_uses_delta: emergencyUsesDelta
  });
  if (error) {
    console.error('[TubeLimiter] daily_usage 동기화 실패:', error);
    // 30초마다 도는 경로라 같은 실패가 하루 2880번 난다 — 링버퍼가 같은 (종류, 코드)를
    // 합산해주므로 한 줄에 횟수만 올라간다(appendDiagnosticEvent 참고).
    await recordFailure(DiagnosticKind.SYNC_USAGE, 'rpc', error);
    return;
  }
  await recordSyncSuccess();

  const row = Array.isArray(data) ? data[0] : data;
  const remoteTodayUses = row?.emergency_uses ?? localEmergencyUses;
  await setStorage({
    dailyUsageSyncDate: today,
    dailyUsageSyncedMillis: localUsage,
    dailyUsageShortsSyncedMillis: localShorts,
    dailyUsageEmergencySyncedMillis: localEmergency,
    dailyUsageEmergencyUsesSyncedCount: localEmergencyUses,
    dailyUsageCombinedMillis: row?.usage_ms ?? localUsage,
    // Shorts 합계도 같이 남긴다 — Shorts 한도 판정(getEffectiveTodayShortsUsage)과 팝업 표시가
    // 전체 사용량과 같은 "로컬 + 다른 기기 몫" 기준을 쓰게 하려면 이 값이 있어야 한다.
    dailyUsageCombinedShortsMillis: row?.shorts_ms ?? localShorts,
    dailyUsageCombinedEmergencyUses: remoteTodayUses
  });

  await refreshEmergencyUsesBucket(user, today, emergencyHistory, localEmergencyUses, remoteTodayUses);
}

// 남은 긴급 시청 횟수는 버킷(일/주/월) 단위인데 서버 daily_usage는 날짜별 행이라, weekly/monthly
// 설정에서는 버킷 시작일 이후 행들의 emergency_uses를 select 해서 합산해야 진짜 합계가 나온다
// (오늘 행 하나만 보면 주간/월간에서 틀린다). 매 틱마다 select를 날릴 순 없으니 동기화
// 스로틀(30초)에 얹고, 서비스워커는 자주 깼다 죽으므로 결과는 chrome.storage에 캐시한다.
// 조회에 실패하면 캐시를 그대로 둔다 — 실패를 0으로 덮어쓰면 잔여 횟수가 엉뚱하게 흔들린다.
async function refreshEmergencyUsesBucket(user, today, emergencyHistory, syncedTodayUses, remoteTodayUses) {
  // 리셋 판정(planEmergencyReset)이 쓰는 것과 같은 규칙 — 리셋 키가 곧 버킷 시작일이다.
  const bucketStart = emergencyResetDate(settingsCache.emergency_config?.resetFrequency, {
    today,
    weekStart: getWeekStartDate(),
    monthStart: getMonthStartDate()
  });

  let remoteBucketUses = remoteTodayUses;
  if (bucketStart !== today) {
    // 주간/월간이면 오늘 행 하나로는 모자라니 버킷 구간을 통째로 읽는다.
    const { data, error } = await supabase
      .from('daily_usage')
      .select('emergency_uses')
      .eq('user_id', user.id)
      .gte('date', bucketStart)
      .lte('date', today);
    if (error) {
      console.error('[TubeLimiter] 긴급 시청 횟수 합계 조회 실패:', error);
      // 실패하면 캐시를 그대로 두고 조용히 빠져나가므로(잔여 횟수가 흔들리지 않게), 계속
      // 실패해도 화면상으로는 아무 일도 없는 것처럼 보인다. 그래서 더더욱 기록이 필요하다.
      await recordFailure(DiagnosticKind.EMERGENCY_FETCH, 'select', error);
      return;
    }
    remoteBucketUses = (data || []).reduce((sum, r) => sum + (r.emergency_uses || 0), 0);
  }

  const localBucketUses = sumEmergencyUsesInBucket(emergencyHistory, bucketStart, today);
  await setStorage({
    emergencyUsesBucketDate: bucketStart,
    emergencyUsesBucketRemote: remoteBucketUses,
    emergencyUsesBucketReported: reportedEmergencyUsesInBucket(
      localBucketUses, emergencyEntryFor(emergencyHistory, today).uses, syncedTodayUses
    )
  });
}

/** 로컬 오늘 사용량 위에 다른 기기 몫을 더한 값. 아직 동기화 전이면 로컬 값 그대로. */
async function getEffectiveTodayUsage(localUsage) {
  const today = getTodayDate();
  const synced = await getStorage(['dailyUsageSyncDate', 'dailyUsageSyncedMillis', 'dailyUsageCombinedMillis']);
  if (synced.dailyUsageSyncDate !== today) return localUsage;
  return combinedUsedMillis(localUsage, synced.dailyUsageSyncedMillis || 0, synced.dailyUsageCombinedMillis || 0);
}

/**
 * 오늘 Shorts 사용량도 전체 사용량과 똑같이 다른 기기 몫을 합쳐서 본다. 안 합치면 브라우저를
 * 두 개(다른 PC) 쓰는 사람이 Shorts 한도를 기기 수만큼 쓸 수 있다.
 * (안드로이드는 Shorts를 구분 못 해 항상 델타 0을 보내므로 폰 몫은 애초에 섞이지 않는다.)
 */
async function getEffectiveTodayShortsUsage(localShorts) {
  const today = getTodayDate();
  const synced = await getStorage([
    'dailyUsageSyncDate', 'dailyUsageShortsSyncedMillis', 'dailyUsageCombinedShortsMillis'
  ]);
  if (synced.dailyUsageSyncDate !== today) return localShorts;
  return combinedUsedMillis(
    localShorts, synced.dailyUsageShortsSyncedMillis || 0, synced.dailyUsageCombinedShortsMillis || 0
  );
}

// --- 긴급 시청 횟수 리셋 ---

/** 지금 버킷의 시작일. 리셋 판정과 서버 합산 구간이 같은 규칙을 쓰도록 여기 하나로 모은다. */
function currentEmergencyBucketStart() {
  return emergencyResetDate(settingsCache.emergency_config?.resetFrequency, {
    today: getTodayDate(),
    weekStart: getWeekStartDate(),
    monthStart: getMonthStartDate()
  });
}

/**
 * 남은 긴급 시청 횟수. 로컬 카운터(emergency_uses_today)에서 "다른 기기가 이 버킷에서 이미 쓴
 * 만큼"을 뺀 값이다. 로컬 카운터만 보면 기기를 바꿔 횟수를 다시 채울 수 있다.
 *
 * 캐시가 다른 버킷 것이거나(리셋 직후) 아직 한 번도 동기화되지 않았으면 로컬 값 그대로 돌려준다 —
 * 오프라인/로그아웃에서도 기존과 똑같이 동작해야 하기 때문이다(documents/BACKEND.md의 설계).
 *
 * @returns {Promise<{local: number, effective: number}>} local은 차감에 쓸 로컬 카운터 값.
 */
async function getEffectiveEmergencyUses() {
  const stored = await getStorage([
    'emergency_uses_today',
    'emergencyUsesBucketDate',
    'emergencyUsesBucketRemote',
    'emergencyUsesBucketReported'
  ]);
  const local = stored.emergency_uses_today ?? (settingsCache.emergency_config?.dailyUses ?? DEFAULT_EMERGENCY_USES);
  if (stored.emergencyUsesBucketDate !== currentEmergencyBucketStart()) return { local, effective: local };
  return {
    local,
    effective: remainingEmergencyUses(
      local, stored.emergencyUsesBucketReported || 0, stored.emergencyUsesBucketRemote || 0
    )
  };
}

async function checkAndResetEmergencyUses() {
  const { last_emergency_date } = await getStorage(['last_emergency_date']);

  const plan = planEmergencyReset({
    lastResetDate: last_emergency_date,
    frequency: settingsCache.emergency_config?.resetFrequency,
    dailyUses: settingsCache.emergency_config?.dailyUses,
    today: getTodayDate(),
    weekStart: getWeekStartDate(),
    monthStart: getMonthStartDate()
  });

  if (!plan.shouldReset) return;
  await setStorage({ emergency_uses_today: plan.uses, last_emergency_date: plan.resetDate });
  notifyUiUpdate();
}

// --- 알람 (N분마다 / 남은 시간 마일스톤) ---

function notify(id, title, message) {
  chrome.notifications.create(id, {
    type: 'basic',
    iconUrl: chrome.runtime.getURL('assets/icon128.png'),
    title,
    message
  }, () => {
    // chrome.notifications.create는 실패해도 예외를 던지지 않고 조용히 넘어간다
    // (권한 거부, OS 알림 차단 등). lastError 안 찍으면 왜 안 뜨는지 알 방법이 없다.
    if (chrome.runtime.lastError) {
      console.error('[TubeLimiter] 알림 생성 실패:', chrome.runtime.lastError.message);
    }
  });

  // OS 알림은 방해금지 모드 등에 묻히기 쉬우니, 지금 보고 있는 유튜브 화면 좌상단에도
  // 직접 띄운다. 탭이 없거나 콘텐츠 스크립트가 없으면 그냥 무시.
  if (activeTabId) {
    chrome.tabs.sendMessage(activeTabId, { action: 'showAlarmToast', message }).catch(() => {});
  }
}

// 주기/예약 알림 id는 매번 고유해야 한다. 같은 id로 create()하면 크롬이 기존 알림을
// "업데이트"만 하고 토스트 배너를 다시 띄우지 않아, 하루 첫 알림 말고는 안 보일 수 있다.
// (마일스톤은 분 단위로 한 번씩만 뜨므로 고정 id로 충분하다.)
function alarmNotificationId(notification) {
  if (notification.kind === ALARM_KIND.milestone) return `tube-limiter-milestone-${notification.minutes}`;
  if (notification.kind === ALARM_KIND.scheduleSoon) return `tube-limiter-schedule-soon-${Date.now()}`;
  return `tube-limiter-interval-${Date.now()}`;
}

// 알림 문구는 판정(lib/alarmRules.js)이 아니라 여기서 붙인다 — 그쪽은 chrome.* 없이 도는
// 순수 함수라야 하고, 문구는 언어마다 어순도 단복수도 달라 messages.json이 정해야 한다.
const ALARM_MESSAGE_KEYS = {
  [ALARM_KIND.interval]: 'notify_alarm_interval',
  [ALARM_KIND.milestone]: 'notify_alarm_milestone',
  [ALARM_KIND.scheduleSoon]: 'notify_alarm_schedule_soon'
};

function alarmNotificationMessage(notification) {
  return tCount(ALARM_MESSAGE_KEYS[notification.kind], notification.minutes);
}

async function checkAlarms(currentUsage, limitMs) {
  const { alarm_state } = await getStorage(['alarm_state']);

  // "무엇을 띄울지 / 오늘 이미 띄웠는지"는 순수 판정(lib/alarmRules.js)에 맡기고,
  // 여기선 실제 알림 발화와 장부 저장만 한다.
  const { state, changed, notifications } = evaluateAlarms(alarm_state, {
    date: getTodayDate(),
    usedMs: currentUsage,
    limitMs,
    intervalMinutes: settingsCache.alarm_interval_minutes || 0,
    milestonesEnabled: settingsCache.alarm_milestones_enabled !== false,
    // 이미 활성 중이거나 예약이 없으면 null이라 예약 알림은 자연히 조용해진다.
    minutesUntilScheduleStart: minutesUntilNextScheduleStart(new Date(), settingsCache.scheduled_blocks || [])
  });

  for (const notification of notifications) {
    notify(alarmNotificationId(notification), 'TubeLimiter', alarmNotificationMessage(notification));
  }

  if (changed) await setStorage({ alarm_state: state });
}

// --- 차단 판정 ---

async function checkUsageAndBlock() {
  const storedState = await getStorage([
    'isManuallyBlocked', 'focusModeActive', 'focusModeEndTime', 'focusStopRequestedAt',
    'emergencyModeActive', 'emergencyEndTime',
    'focusModeDelayEndTime', 'focusModeDelayDuration'
  ]);
  isManuallyBlocked = storedState.isManuallyBlocked || false;
  focusModeActive = storedState.focusModeActive || false;
  focusModeEndTime = storedState.focusModeEndTime || null;
  focusStopRequestedAt = storedState.focusStopRequestedAt || null;
  emergencyModeActive = storedState.emergencyModeActive || false;

  // 자연 종료 시각과 "종료 요청 후 쿨다운이 끝나는 시각" 중 더 빠른 쪽에 실제로 끈다
  // (resolveFocusStopTime, extension/src/lib/focusMode.js 참고).
  if (focusModeActive) {
    const stopAtMillis = resolveFocusStopTime(focusModeEndTime, focusStopRequestedAt, FOCUS_STOP_COOLDOWN_MS);
    if (stopAtMillis != null && Date.now() >= stopAtMillis) {
      focusModeActive = false;
      focusModeEndTime = null;
      focusStopRequestedAt = null;
      await setStorage({ focusModeActive: false, focusModeEndTime: null, focusStopRequestedAt: null });
      notify(`tube-limiter-focus-end-${Date.now()}`, 'TubeLimiter', t('notify_focus_end'));
    }
  }

  // 지연 시작 집중 모드: 서비스워커가 중간에 종료되면 setTimeout이 못 돌아오므로 여기서 복구
  if (!focusModeActive && storedState.focusModeDelayEndTime && Date.now() >= storedState.focusModeDelayEndTime) {
    focusModeActive = true;
    focusModeEndTime = Date.now() + (storedState.focusModeDelayDuration || 30) * 60 * 1000;
    focusStopRequestedAt = null;
    await setStorage({ focusModeActive: true, focusModeEndTime, focusModeDelayEndTime: null, focusStopRequestedAt: null });
    notify(`tube-limiter-focus-start-${Date.now()}`, 'TubeLimiter', t('notify_focus_start'));
  }

  // 긴급 시청 만료: 마찬가지로 setTimeout이 못 돌아오면 여기서 복구 (안 하면 이후 모든 차단이 영구히 풀림)
  if (emergencyModeActive && storedState.emergencyEndTime && Date.now() >= storedState.emergencyEndTime) {
    emergencyModeActive = false;
    await setStorage({ emergencyModeActive: false, emergencyEndTime: null });
  }

  const limitMs = computeLimitForDate(settingsCache, getTodayDate());
  const currentUsage = await getEffectiveTodayUsage(await getTodayUsage());
  // Shorts 전용 한도는 전체 한도와 독립이라 값도 따로 읽는다 (미설정이면 Infinity).
  const shortsLimitMs = computeShortsLimit(settingsCache);
  const shortsUsage = await getEffectiveTodayShortsUsage(await getTodayShortsUsage());

  // 알람(N분마다 / 남은 시간 마일스톤)은 전체 한도 기준 그대로다. Shorts 한도까지 같은 채널로
  // 알리면 한도가 둘이라 "남은 시간 5분" 알림이 어느 쪽 얘기인지 알 수 없게 된다.
  await checkAlarms(currentUsage, limitMs);

  // 예약 차단: 사용자가 켜고 끄는 게 아니라 시계 기준으로 자동 판정된다 (lib/schedule.js,
  // 4시 사용량 컷오프와 무관한 실제 벽시계 요일/시각). 집중 모드와 같은 급으로 취급해서
  // 긴급 시청으로도 우회할 수 없게 한다 (아래 우선순위 참고).
  const { active: scheduleActiveNow } = isScheduleActive(new Date(), settingsCache.scheduled_blocks || []);
  scheduleBlockActive = scheduleActiveNow;

  // 서비스워커 재시작에도 살아남는 "직전 값"과 비교해 시작/종료 전이에만 알림을 띄운다
  // (집중 모드의 시작/종료 notify와 같은 스타일 - 다만 예약은 사용자 액션이 아니라 매 틱마다
  // 여기서 스스로 감지해야 한다).
  const { scheduleBlockWasActive } = await getStorage(['scheduleBlockWasActive']);
  if (scheduleBlockActive !== !!scheduleBlockWasActive) {
    await setStorage({ scheduleBlockWasActive: scheduleBlockActive });
    notify(
      `tube-limiter-schedule-${scheduleBlockActive ? 'start' : 'end'}-${Date.now()}`,
      'TubeLimiter',
      scheduleBlockActive ? t('notify_schedule_start') : t('notify_schedule_end')
    );
  }

  // 우선순위(집중 모드 > 예약 차단 > 긴급 시청 > 수동 차단 > 사용 한도)와 탭별 예외 규칙은
  // 전부 lib/blockDecision.js에 있다 (안드로이드 BlockDecision.kt와 같은 규칙).
  // 여기선 그 판정 결과를 storage/탭에 반영하는 일만 한다.
  const blockInputs = {
    usedMs: currentUsage,
    limitMs,
    shortsUsedMs: shortsUsage,
    shortsLimitMs,
    focusModeActive,
    scheduleBlockActive,
    emergencyModeActive,
    manuallyBlocked: isManuallyBlocked
  };
  const decision = resolveBlockDecision(blockInputs);

  // Shorts 한도는 탭 단위 차단이라 전역 판정(displayBlocked/trackingBlocked)에는 들어가지
  // 않는다 — Shorts를 다 썼다고 일반 영상 시청까지 막히거나 집계가 멈추면 안 된다.
  // 다만 긴급 시청 발급 관문이 "지금 차단 상태인가"를 보므로, Shorts만 막힌 상태도 발급 가능
  // 상태로 쳐주려고 이 플래그만 따로 들고 다닌다 (팝업 표시에도 쓴다).
  const nextShortsLimitBlocked = isShortsLimitExceeded(shortsUsage, shortsLimitMs);

  // 표시용(isYoutubeBlocked)과 집계 게이트용(trackingBlocked)을 같이 남긴다. trackUsage는
  // 인메모리 값을 못 믿어 storage에서 다시 읽으므로 둘 다 저장돼 있어야 한다.
  if (
    decision.displayBlocked !== isYoutubeBlocked ||
    decision.trackingBlocked !== trackingBlocked ||
    nextShortsLimitBlocked !== shortsLimitBlocked
  ) {
    isYoutubeBlocked = decision.displayBlocked;
    trackingBlocked = decision.trackingBlocked;
    shortsLimitBlocked = nextShortsLimitBlocked;
    await setStorage({ isYoutubeBlocked, trackingBlocked, shortsLimitBlocked });
  }

  const whitelist = settingsCache.whitelist || [];
  const alwaysBlockShorts = settingsCache.always_block_shorts || false;

  const tabs = await chrome.tabs.query({ url: '*://*.youtube.com/*' });
  for (const tab of tabs) {
    const tabDecision = resolveTabBlock(blockInputs, {
      isWhitelisted: isWhitelistedUrl(tab.url, whitelist),
      isShortsTab: isShortsUrl(tab.url),
      alwaysBlockShorts
    });
    await sendBlockMessage(tab.id, tabDecision.shouldBlock, tabDecision.reason);
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

// 팝업(우리 확장 자체 UI)이 이 상태를 물어본다는 것 자체가 사용자가 브라우저를 보고 있다는
// 뜻이므로, 여기선 포커스 여부를 별도로 조회하지 않는다. isChromeWindowFocused()로 포커스를
// 조회하면 하필 팝업이 OS 포커스를 가져간 상태라 "포커스 없음"으로 오판해 항상 대기중으로 나왔었다.
// 실제 사용시간 집계(trackUsage)는 이거와 무관하게 자체적으로 포커스를 확인해 방치를 걸러낸다.
function isCurrentlyTracking() {
  return !!(
    activeTabId &&
    isTrackableYoutubeUrl(activeTabUrl) &&
    trackingStartTime &&
    isVideoPlaying &&
    // 표시용 isYoutubeBlocked가 아니라 실제 집계 게이트를 본다 - 수동 차단 위에서 긴급 시청을
    // 쓰는 동안은 "차단해둔 상태"로 표시되지만 시간은 집계되므로, 여기서 표시용 값을 쓰면
    // 팝업만 "대기중"이라고 거짓말을 하게 된다. (null = 아직 판정 전 → 기존 기본값과 같이 통과)
    !trackingBlocked
  );
}

function notifyUiUpdate() {
  chrome.runtime.sendMessage({ action: 'updateUI' }).catch(() => {});
}

// --- 메인 틱: 알람 또는 탭 이벤트에서 호출 ---

async function handleTick() {
  await refreshSettingsFromSupabase();
  await trackUsage();
  await checkDateRollover();
  await checkHardcoreDisableCooldown();
  // 차단 판정이 다른 기기 몫을 최신으로 보게, 판정 전에 동기화한다 (스로틀은 함수 내부에 있음).
  await syncUsageToSupabase();
  await checkUsageAndBlock();
  await checkAndResetEmergencyUses();
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
  if (isTrackableYoutubeUrl(activeTabUrl)) {
    trackingStartTime = Date.now();
    isVideoPlaying = true; // content script가 실제 상태를 보고할 때까지 낙관적으로 시작
    // 다만 상태가 그대로면 content script는 보고하지 않으므로(일시정지된 탭으로 돌아온 경우)
    // 낙관값을 믿고 기다리지 말고 지금 값을 직접 물어본다.
    await syncPlaybackStateFromTab(activeTabId);
    await handleTick();
  } else if (activeTabUrl && activeTabUrl.includes('youtube.com')) {
    // 홈/검색 등 시청 페이지가 아닌 유튜브 탭: 탭 자체는 계속 추적하되 집계는 안 한다.
    trackingStartTime = null;
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
  if (isTrackableYoutubeUrl(activeTabUrl)) {
    trackingStartTime = Date.now();
    isVideoPlaying = true;
    await syncPlaybackStateFromTab(activeTabId);
    await handleTick();
  } else if (activeTabUrl.includes('youtube.com')) {
    trackingStartTime = null;
  } else {
    activeTabId = null;
    activeTabUrl = null;
    trackingStartTime = null;
  }
});

chrome.windows.onRemoved.addListener(async () => {
  await trackUsage();
  // 창이 닫히면 다음 스로틀 주기가 언제 올지 모르니(브라우저를 아예 끌 수도 있음) 강제로 보낸다.
  await syncUsageToSupabase(true);
});

// 브라우저 창이 포커스를 잃으면(다른 창/프로그램으로 전환) 유튜브 탭이 열려있어도 방치로 간주.
// 이 이벤트는 전환이 "이미 끝난 뒤"에 오므로, 직전 구간은 "전환 전" 포커스 값으로 정리한 뒤에
// 새 상태를 적용한다 (왜 그래야 하는지는 lib/focusTransition.js에 정리해뒀다).
// 직전 포커스 창은 이 이벤트에서만 갱신하는 별도 값으로 기억한다 - 포커스 판정 자체를
// 캐시하는 게 아니라(그건 뒤집힌 채 고착된 전력이 있다) 이 전환 한 번을 정산할 때만 쓴다.
let lastFocusedWindowId = null; // 서비스워커가 막 재시작했으면 직전 창을 모르는 상태

chrome.windows.onFocusChanged.addListener(async (windowId) => {
  const focusedBefore = focusStateBeforeTransition(
    lastFocusedWindowId, windowId, chrome.windows.WINDOW_ID_NONE
  );
  lastFocusedWindowId = windowId;
  await trackUsage({ focusedOverride: focusedBefore });
  await checkUsageAndBlock();
  notifyUiUpdate();
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
      const { last_emergency_granted_at } = await getStorage(['last_emergency_granted_at']);
      // 판정은 로컬 카운터가 아니라 "다른 기기가 쓴 몫까지 뺀" 합계 기준으로 한다.
      // 차감은 여전히 로컬 카운터에 하고(오프라인에서도 돌아야 하므로), 다른 기기 몫은
      // 동기화 때 캐시해둔 서버 버킷 합계로 매번 다시 뺀다.
      const { local: localUses, effective: remainingUses } = await getEffectiveEmergencyUses();

      // Shorts 한도만 넘긴 상태는 전역 isYoutubeBlocked가 false다(일반 영상은 계속 볼 수 있으니).
      // 그래도 "막힌 게 있는" 상태이므로 발급을 허용한다 — Shorts 한도는 전체 한도와 같은 급의
      // 한도 차단이라 긴급 시청으로 뚫을 수 있어야 한다.
      if (!isYoutubeBlocked && !shortsLimitBlocked) {
        sendResponse({ success: false, message: t('emergency_error_not_blocked') });
        return;
      }

      // 집중 모드는 "한도와 무관하게" 무조건 차단하는 게 핵심이므로, 긴급 시청으로도
      // 우회할 수 없게 막는다 (사용 시청-한도 차단, 수동 차단은 기존대로 우회 가능).
      const { focusModeActive: fmActive, focusModeEndTime: fmEndTime } = await getStorage([
        'focusModeActive', 'focusModeEndTime'
      ]);
      if (fmActive && (!fmEndTime || Date.now() < fmEndTime)) {
        sendResponse({ success: false, message: t('emergency_error_focus_mode') });
        return;
      }

      // 예약 차단도 집중 모드와 같은 급 - 한도와 무관하게 무조건 차단하는 커밋먼트 장치이므로
      // 긴급 시청 발급 자체를 거부한다 (checkUsageAndBlock의 우선순위와 동일한 원칙).
      if (isScheduleActive(new Date(), settingsCache.scheduled_blocks || []).active) {
        sendResponse({ success: false, message: t('emergency_error_scheduled') });
        return;
      }

      // 사용 횟수와는 별개의 관문: 방금 썼다면 짧은 시간 안에 다시 쓰지 못하게 막는다.
      if (last_emergency_granted_at) {
        const remainingMs = EMERGENCY_GRANT_COOLDOWN_MS - (Date.now() - last_emergency_granted_at);
        if (remainingMs > 0) {
          sendResponse({
            success: false,
            message: tCount('emergency_error_cooldown', Math.ceil(remainingMs / 1000))
          });
          return;
        }
      }

      if (remainingUses <= 0) {
        sendResponse({ success: false, message: t('emergency_error_no_uses') });
        return;
      }

      const grantedAt = Date.now();
      await setStorage({ emergency_uses_today: Math.max(0, localUses - 1), last_emergency_granted_at: grantedAt });
      // 그날 긴급 시청을 썼다는 사실 자체를 남긴다 — 부여받고 안 봐도 완벽한 날은 아니다.
      await addEmergencyUse();
      // 다음 30초 틱을 기다리면 그 사이에 다른 기기가 같은 횟수를 또 쓸 수 있으므로 바로 올린다.
      // 실패해도(오프라인) 로컬 차감은 이미 끝났으니 이 기기 동작에는 영향이 없다.
      await syncUsageToSupabase(true).catch(() => {});

      emergencyModeActive = true;
      const emergencyEndTime = grantedAt + EMERGENCY_DURATION_MS;
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

      const start = async (viaDelay) => {
        focusModeActive = true;
        focusModeEndTime = Date.now() + duration * 60 * 1000;
        focusStopRequestedAt = null;
        await setStorage({ focusModeActive: true, focusModeEndTime, focusModeDelayEndTime: null, focusStopRequestedAt: null });
        await checkUsageAndBlock();
        if (viaDelay) notify(`tube-limiter-focus-start-${Date.now()}`, 'TubeLimiter', t('notify_focus_start'));
      };

      if (delay > 0) {
        const delayEndTime = Date.now() + delay * 60 * 1000;
        await setStorage({ focusModeDelayEndTime: delayEndTime, focusModeDelayDuration: duration });
        focusModeDelayTimer = setTimeout(() => start(true), delay * 60 * 1000);
      } else {
        await start(false);
      }
      sendResponse({ success: true });
    })();
    return true;
  }

  if (request.action === 'stopFocusMode') {
    (async () => {
      // 서비스워커가 재시작되었을 수 있으니 인메모리 값을 믿지 말고 storage에서 다시 읽는다.
      const { focusModeActive: fmActive, focusStopRequestedAt: existingRequest } = await getStorage([
        'focusModeActive', 'focusStopRequestedAt'
      ]);
      focusModeActive = fmActive || false;

      // 아직 시작 전(지연 대기 중)인 예약을 취소하는 건 즉시 처리한다 - 이미 차단을 시작한
      // 적이 없으니 하드코어식 쿨다운을 걸 이유가 없다.
      if (!focusModeActive) {
        if (focusModeDelayTimer) clearTimeout(focusModeDelayTimer);
        focusModeDelayTimer = null;
        await setStorage({ focusModeDelayEndTime: null, focusModeDelayDuration: null });
        await checkUsageAndBlock();
        sendResponse({ success: true, pending: false });
        return;
      }

      // 이미 시작되어 차단 중인 세션은 하드코어 모드 해제처럼 즉시 끄지 않고 요청 시각만
      // 기록한다 - FOCUS_STOP_COOLDOWN_MS가 지나거나 원래 종료 시각이 먼저 오면 그때 꺼진다
      // (checkUsageAndBlock의 resolveFocusStopTime 처리 참고). 이미 요청된 상태면 그대로 둔다.
      if (!existingRequest) {
        focusStopRequestedAt = Date.now();
        await setStorage({ focusStopRequestedAt });
        await checkUsageAndBlock();
      }
      sendResponse({ success: true, pending: true });
    })();
    return true;
  }

  if (request.action === 'cancelFocusStopRequest') {
    (async () => {
      focusStopRequestedAt = null;
      await setStorage({ focusStopRequestedAt: null });
      await checkUsageAndBlock();
      sendResponse({ success: true });
    })();
    return true;
  }

  if (request.action === 'videoPlaybackState') {
    (async () => {
      const senderTabId = _sender.tab?.id;
      if (senderTabId) {
        // activeTabId가 낡았다는 이유만으로 보고를 버리면(서비스워커 재시작 직후엔 아예 비어있다)
        // 일시정지가 반영되지 않아 계속 시청 중으로 집계된다. 실제 활성 시청 탭이 보낸 것이면
        // activeTabId를 그 탭으로 맞춘 뒤 반영한다.
        await reconcileActiveTab(senderTabId);
        if (senderTabId === activeTabId) {
          // 상태가 바뀌기 직전까지의 구간은 이전 상태로 정리한 뒤 새 값을 적용한다.
          await trackUsage();
          isVideoPlaying = !!request.playing;
          notifyUiUpdate();
        }
      }
      sendResponse({ success: true });
    })();
    return true;
  }

  if (request.action === 'getTrackingStatus') {
    (async () => {
      // usage_history는 1분 알람에서만 기록되기 때문에 팝업이 1초마다 다시 그려도
      // 그 사이엔 값이 그대로다. 팝업이 살아있는 동안은 어차피 이 메시지가 1초마다 오니,
      // 여기서도 매번 트래킹을 플러시해서 팝업 켜놓은 동안은 초 단위로 갱신되게 한다.
      await trackUsage({ assumeFocused: true });
      sendResponse({ isTracking: isCurrentlyTracking() });
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
  const stored = await getStorage([
    'isYoutubeBlocked', 'shortsLimitBlocked', 'isManuallyBlocked',
    'focusModeActive', 'focusModeEndTime', 'focusStopRequestedAt'
  ]);
  isYoutubeBlocked = stored.isYoutubeBlocked || false;
  // 아래 checkUsageAndBlock이 곧 다시 판정하지만, 그 전에 팝업이 긴급 시청을 요청할 수 있으므로
  // 직전 값으로 깔아둔다 (isYoutubeBlocked를 복원하는 이유와 같다).
  shortsLimitBlocked = stored.shortsLimitBlocked || false;
  isManuallyBlocked = stored.isManuallyBlocked || false;
  focusModeActive = stored.focusModeActive || false;
  focusModeEndTime = stored.focusModeEndTime || null;
  focusStopRequestedAt = stored.focusStopRequestedAt || null;

  await refreshSettingsFromSupabase();
  await checkDateRollover();
  await checkUsageAndBlock();
})();
