console.log('popup.js loaded');
import { getTodayDate } from '/utils/time.js';
import { getStorage } from '/utils/storage.js';

// 다크모드 감지 및 적용
function applyTheme() {
  if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
    document.body.classList.add('dark-mode');
  } else {
    document.body.classList.remove('dark-mode');
  }
}

// 초기 테마 적용
applyTheme();

// 시스템 테마 변경 감지
if (window.matchMedia) {
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);
}

// DOM 요소 캐싱
const elements = {
  // 상태 탭
  usageTime: document.getElementById('usageTime'),
  untilBlock: document.getElementById('untilBlock'),
  emergencyCount: document.getElementById('emergencyCount'),
  focusModeStatus: document.getElementById('focusModeStatus'),
  studyAiStatus: document.getElementById('studyAiStatus'),
  shortsBlockStatus: document.getElementById('shortsBlockStatus'),
  emergencyTimeLeft: document.getElementById('emergencyTimeLeft'),
  progressCircle: document.getElementById('progressCircle'),
  // 기능 탭
  emergencyButton: document.getElementById('emergencyButton'),
  focusModeDelayInput: document.getElementById('focusModeDelayInput'),
  focusModeDurationInput: document.getElementById('focusModeDurationInput'),
  startFocusModeButton: document.getElementById('startFocusModeButton'),
  stopFocusModeButton: document.getElementById('stopFocusModeButton'),
  // 하단 버튼
  viewStatsButton: document.getElementById('viewStatsButton'),
  optionsButton: document.getElementById('optionsButton'),
  // 탭
  tabs: document.querySelectorAll('.tab-button'),
  tabContents: document.querySelectorAll('.tab-content')
};

for (const key in elements) {
  if (elements[key] === null || (elements[key] instanceof NodeList && elements[key].length === 0)) {
    console.error(`Element with ID or class '${key}' not found or is empty!`, elements[key]);
  }
}

// 시간 포맷팅 함수
function formatTime(seconds) {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const secs = seconds % 60;
  
  if (hours > 0) {
    return `${hours}시간 ${minutes}분`;
  } else if (minutes > 0) {
    return `${minutes}분 ${secs}초`;
  } else {
    return `${secs}초`;
  }
}

function formatMinutes(minutes) {
  const hours = Math.floor(minutes / 60);
  const mins = minutes % 60;
  
  if (hours > 0) {
    return `${hours}시간 ${mins}분`;
  } else {
    return `${mins}분`;
  }
}

// 프로그레스 원 업데이트 함수
function updateProgressCircle(percentage) {
  const circle = elements.progressCircle;
  if (!circle) return;
  const degrees = (percentage / 100) * 360;
  circle.style.background = `conic-gradient(var(--primary-color) ${degrees}deg, var(--light-gray) ${degrees}deg)`;
}

// 알림 표시 함수
function showNotification(message, type = 'info') {
  console.log(`Notification: ${message} (${type})`); // 알림 로그 추가
  const notification = document.createElement('div');
  notification.className = `notification notification-${type}`;
  notification.textContent = message;
  notification.style.cssText = `
    position: fixed;
    top: 20px;
    right: 20px;
    padding: 12px 16px;
    border-radius: 8px;
    color: white;
    font-weight: 600;
    z-index: 1000;
    animation: slideIn 0.3s ease;
    max-width: 280px;
    box-shadow: var(--shadow-lg);
  `;
  
  switch(type) {
    case 'success':
      notification.style.background = 'linear-gradient(135deg, var(--success-color), #059669)';
      break;
    case 'warning':
      notification.style.background = 'linear-gradient(135deg, var(--warning-color), #d97706)';
      break;
    case 'error':
      notification.style.background = 'linear-gradient(135deg, var(--danger-color), var(--danger-hover))';
      break;
    default:
      notification.style.background = 'linear-gradient(135deg, var(--primary-color), var(--primary-hover))';
  }
  
  document.body.appendChild(notification);
  
  setTimeout(() => {
    notification.style.animation = 'slideOut 0.3s ease';
    setTimeout(() => notification.remove(), 300);
  }, 3000);
}

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

async function updatePopupInfo() {
  console.log('updatePopupInfo called.'); // 함수 호출 로그
  const result = await getStorage([
    'emergency_uses_today',
    'initial_daily_emergency_uses',
    'daily_limit_ms',
    'emergencyModeActive',
    'emergencyEndTime',
    'isYoutubeBlocked',
    'emergency_reset_frequency',
    'daily_limit_reset_frequency',
    'usage_history',
    'focusModeActive',
    'focusModeEndTime',
    'focusModeDelayEndTime',
    'emergency_uses_by_day',
    'daily_limit_by_day',
    'useStudyAI',
    'alwaysBlockShorts',
    'probStudy',
    'probStudyUpdatedAt',
    'studyAiThreshold',
    'whitelist',
    'studyStartTime',
    'playStartTime'
  ]);
  console.log('Storage result:', result); // 스토리지 결과 로그

  const today = getTodayDate();
  const usageHistory = result.usage_history || {};
  const usageMs = usageHistory[today] || 0;
  const usageMinutes = Math.floor(usageMs / (1000 * 60));
  const usageSeconds = Math.floor((usageMs % (1000 * 60)) / 1000);
  elements.usageTime.textContent = `${usageMinutes}분 ${usageSeconds}초`;

  const emergencyResetFrequency = result['emergency_reset_frequency'] || 'daily';
  const dailyLimitResetFrequency = result['daily_limit_reset_frequency'] || 'daily';
  let dailyLimitMs = result['daily_limit_ms'] || 30 * 60 * 1000;

  if (dailyLimitResetFrequency === 'by_day') {
    const dayOfWeek = new Date().getDay();
    const dailyLimitByDay = result['daily_limit_by_day'] || {};
    let limit = dailyLimitByDay[dayOfWeek];
    if (limit === -1) dailyLimitMs = Infinity;
    else if (limit !== undefined) dailyLimitMs = limit * 60 * 1000;
  }

  // 프로그레스 바 및 남은 시간 업데이트
  if (dailyLimitMs === Infinity) {
    elements.untilBlock.textContent = '무제한';
    elements.progressCircle.style.background = 'var(--primary-color)';
  } else {
    let untilBlockMs = dailyLimitMs - usageMs;
    if (untilBlockMs < 0) untilBlockMs = 0;
    let untilBlockSeconds = Math.floor(untilBlockMs / 1000);
    if (untilBlockSeconds < 0) untilBlockSeconds = 0; // Ensure it's not negative
    const untilBlockMinutes = Math.floor(untilBlockSeconds / 60);
    const formattedUntilBlockTime = `${untilBlockMinutes}분`;
    elements.untilBlock.textContent = formattedUntilBlockTime;
    elements.progressCircle.title = formatTime(untilBlockSeconds); // 프로그레스 원에 툴팁 추가
    console.log(`Setting untilBlock to: ${formattedUntilBlockTime}, progressCircle tooltip: ${elements.progressCircle.title}`);
    const percentage = Math.max(0, (usageMs / dailyLimitMs) * 100);
    updateProgressCircle(percentage);
  }

  // 긴급 시청 횟수
  let initialDailyEmergencyUses = result['initial_daily_emergency_uses'];
  if (emergencyResetFrequency === 'by_day') {
    const dayOfWeek = new Date().getDay();
    const emergencyUsesByDay = result['emergency_uses_by_day'] || {};
    initialDailyEmergencyUses = emergencyUsesByDay[dayOfWeek];
  }
  if (initialDailyEmergencyUses === undefined) initialDailyEmergencyUses = 3;

  let emergencyUses = result['emergency_uses_today'];
  if (emergencyUses === undefined) emergencyUses = initialDailyEmergencyUses;
  
  if (initialDailyEmergencyUses === -1) {
    elements.emergencyCount.textContent = '무제한';
    elements.emergencyButton.textContent = '긴급 시청 (무제한)';
    elements.emergencyButton.disabled = true;
  } else {
    elements.emergencyCount.textContent = `${emergencyUses}회`;
    elements.emergencyButton.textContent = `긴급 시청 요청 (${emergencyUses}회 남음)`;
    elements.emergencyButton.disabled = !(result['isYoutubeBlocked'] && emergencyUses > 0 && !result['emergencyModeActive']);
  }

  // 긴급 시청/집중 모드 상태
  await updateDynamicStates(result);
  console.log('Popup UI updated successfully.');
}

async function updateDynamicStates(result) {
  const { emergencyModeActive, emergencyEndTime, focusModeActive, focusModeEndTime, focusModeDelayEndTime, useStudyAI, alwaysBlockShorts, probStudy, probStudyUpdatedAt, studyAiThreshold, whitelist, studyStartTime, playStartTime } = result;

  if (emergencyModeActive && emergencyEndTime) {
    const left = Math.max(0, Math.floor((emergencyEndTime - Date.now()) / 1000));
    elements.emergencyTimeLeft.textContent = `긴급 시청 남은 시간: ${Math.floor(left / 60)}분 ${left % 60}초`;
    elements.emergencyTimeLeft.style.display = 'block';
  } else {
    elements.emergencyTimeLeft.style.display = 'none';
  }

  // 집중 모드 상태 표시 (지연 포함)
  if (focusModeDelayEndTime && focusModeDelayEndTime > Date.now()) {
    const left = Math.max(0, Math.floor((focusModeDelayEndTime - Date.now()) / 1000));
    let delayText;
    if (left >= 60) {
      const minutes = Math.floor(left / 60);
      const seconds = left % 60;
      delayText = seconds > 0 ? `${minutes}분 ${seconds}초` : `${minutes}분`;
    } else {
      delayText = `${left}초`;
    }
    elements.focusModeStatus.innerHTML = `<span class="status-badge status-warning">${delayText} 후 집중모드 실행</span>`;
    elements.startFocusModeButton.disabled = true;
    elements.stopFocusModeButton.disabled = false;
  } else if (focusModeActive && focusModeEndTime) {
    const left = Math.max(0, Math.floor((focusModeEndTime - Date.now()) / 1000));
    let remainingText;
    if (left >= 60) {
      const minutes = Math.floor(left / 60);
      const seconds = left % 60;
      remainingText = seconds > 0 ? `${minutes}분 ${seconds}초` : `${minutes}분`;
    } else {
      remainingText = `${left}초`;
    }
    elements.focusModeStatus.innerHTML = `<span class="status-badge status-active">${remainingText} 남음</span>`;
    elements.startFocusModeButton.disabled = true;
    elements.stopFocusModeButton.disabled = false;
  } else {
    elements.focusModeStatus.innerHTML = '<span class="status-badge status-inactive">비활성</span>';
    elements.startFocusModeButton.disabled = false;
    elements.stopFocusModeButton.disabled = true;
  }

  // Check if current YouTube tab is a whitelisted study video
  let isWhitelistedStudyVideo = false;
  try {
    const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tabs.length > 0 && tabs[0].url && tabs[0].url.includes('youtube.com')) {
      const currentUrl = tabs[0].url;
      const currentVideoId = extractVideoIdFromUrl(currentUrl);

      if (currentVideoId && whitelist) {
        isWhitelistedStudyVideo = whitelist.some(item => {
          if (typeof item === 'object' && item.isStudy === true) {
            const itemVideoId = extractVideoIdFromUrl(item.url);
            return itemVideoId && itemVideoId === currentVideoId;
          }
          return false;
        });
      }
    }
  } catch (e) {
    console.error('Error checking whitelist:', e);
  }

  // Study AI 상태 표시 (공부 영상 화이트리스트 우선)
  // 공부 시간 계산
  let studyDurationText = '';
  if (studyStartTime) {
    const studyElapsed = Math.floor((Date.now() - studyStartTime) / 1000);
    const studyMinutes = Math.floor(studyElapsed / 60);
    const studySeconds = studyElapsed % 60;
    studyDurationText = studySeconds > 0 ? ` (${studyMinutes}분 ${studySeconds}초)` : ` (${studyMinutes}분)`;
  }

  // 놀이 시간 계산
  let playDurationText = '';
  if (playStartTime) {
    const playElapsed = Math.floor((Date.now() - playStartTime) / 1000);
    const playMinutes = Math.floor(playElapsed / 60);
    const playSeconds = playElapsed % 60;
    playDurationText = playSeconds > 0 ? ` (${playMinutes}분 ${playSeconds}초)` : ` (${playMinutes}분)`;
  }

  if (isWhitelistedStudyVideo) {
    // 공부 영상으로 등록된 경우 - AI 상태와 관계없이 항상 "공부 중"으로 표시
    if (useStudyAI) {
      elements.studyAiStatus.innerHTML = `<span class="status-badge status-active">공부 중 (등록)${studyDurationText}</span>`;
    } else {
      elements.studyAiStatus.innerHTML = `<span class="status-badge status-inactive">비활성</span> <span class="status-badge status-active">공부 중${studyDurationText}</span>`;
    }
  } else if (useStudyAI) {
    const threshold = typeof studyAiThreshold === 'number' ? studyAiThreshold : 0.5;
    const PROB_STUDY_MAX_AGE_MS = 30000;
    const isFresh = typeof probStudyUpdatedAt === 'number' && (Date.now() - probStudyUpdatedAt) <= PROB_STUDY_MAX_AGE_MS;
    const effectiveProb = (typeof probStudy === 'number' && isFresh) ? probStudy : null;

    if (effectiveProb !== null) {
      if (effectiveProb >= threshold) {
        // 공부 중
        elements.studyAiStatus.innerHTML = `<span class="status-badge status-active">공부 중${studyDurationText}</span>`;
      } else {
        // 놀이 중
        elements.studyAiStatus.innerHTML = `<span class="status-badge status-warning">놀이 중${playDurationText}</span>`;
      }
    } else {
      // 데이터 없음 (기본적으로 놀이로 간주)
      elements.studyAiStatus.innerHTML = '<span class="status-badge status-inactive">활성 (대기)</span>';
    }
  } else {
    // AI가 꺼져있지만 화이트리스트 공부 영상은 표시
    if (isWhitelistedStudyVideo) {
      elements.studyAiStatus.innerHTML = `<span class="status-badge status-inactive">비활성</span> <span class="status-badge status-active">공부 중${studyDurationText}</span>`;
    } else {
      elements.studyAiStatus.innerHTML = '<span class="status-badge status-inactive">비활성</span>';
    }
  }

  // Shorts 차단 상태 표시
  if (alwaysBlockShorts) {
    elements.shortsBlockStatus.innerHTML = '<span class="status-badge status-active">활성</span>';
  } else {
    elements.shortsBlockStatus.innerHTML = '<span class="status-badge status-inactive">비활성</span>';
  }
}

function setupEventListeners() {
  console.log('setupEventListeners called.'); // 함수 호출 로그
  // 탭 전환
  elements.tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      console.log('Tab button clicked:', tab.dataset.tab); // 탭 클릭 로그
      elements.tabs.forEach(t => t.classList.remove('active'));
      elements.tabContents.forEach(c => c.classList.remove('active'));
      tab.classList.add('active');
      document.getElementById(tab.dataset.tab).classList.add('active');
    });
  });

  // 기능 버튼
  elements.emergencyButton.addEventListener('click', async () => {
    console.log('Emergency button clicked.'); // 버튼 클릭 로그
    const response = await chrome.runtime.sendMessage({ action: "requestEmergency" });
    if (response && !response.success) {
      alert(response.message || '긴급 시청 요청에 실패했습니다.');
    }
    updatePopupInfo();
  });

  elements.startFocusModeButton.addEventListener('click', async () => {
    console.log('Start Focus Mode button clicked.'); // 버튼 클릭 로그
    const delay = parseInt(elements.focusModeDelayInput.value, 10) || 0;
    const duration = parseInt(elements.focusModeDurationInput.value, 10);

    if (isNaN(duration) || duration <= 0) {
      alert('유효한 집중 시간을 입력해주세요.');
      return;
    }

    if (delay < 0) {
      alert('유효한 지연 시간을 입력해주세요.');
      return;
    }

    await chrome.runtime.sendMessage({ action: "startFocusMode", duration, delay });

    if (delay > 0) {
      showNotification(`${delay}분 후 집중 모드가 ${duration}분 동안 시작됩니다.`, 'success');
    } else {
      showNotification(`집중 모드가 ${duration}분 동안 시작되었습니다.`, 'success');
    }

    updatePopupInfo();
  });

  elements.stopFocusModeButton.addEventListener('click', async () => {
    console.log('Stop Focus Mode button clicked.'); // 버튼 클릭 로그
    await chrome.runtime.sendMessage({ action: "stopFocusMode" });
    updatePopupInfo();
  });

  // 하단 버튼
  elements.viewStatsButton.addEventListener('click', () => {
    console.log('View Stats button clicked.'); // 버튼 클릭 로그
    showNotification('통계 페이지로 이동합니다.', 'info');
    chrome.tabs.create({ url: 'usage_stats.html' });
  });
  elements.optionsButton.addEventListener('click', () => {
    console.log('Options button clicked.'); // 버튼 클릭 로그
    showNotification('옵션 페이지로 이동합니다.', 'info');
    chrome.runtime.openOptionsPage();
  });
}

document.addEventListener('DOMContentLoaded', async () => {
  const result = await chrome.storage.local.get('darkMode');
  if (result.darkMode) {
    document.body.classList.add('dark-mode');
  }
  setupEventListeners();
  updatePopupInfo();
  setInterval(updatePopupInfo, 1000);
});

chrome.runtime.onMessage.addListener(request => {
  if (request.action === "updatePopupUI") {
    console.log('Received updatePopupUI message.'); // 메시지 수신 로그
    updatePopupInfo();
  }
});
