import { getStorage, setStorage } from '/utils/storage.js';
import { getTodayDate } from '/utils/time.js';

let elements; // Declare globally, initialize in DOMContentLoaded

const daysOfWeek = ['월', '화', '수', '목', '금', '토', '일'];

function showStatus(element, message, isSuccess) {
  element.textContent = message;
  element.className = `status-message ${isSuccess ? 'status-success' : 'status-error'}`;
  element.style.display = 'block';
  setTimeout(() => { element.style.display = 'none'; }, 3000);
}

// YouTube 비디오 ID 추출 함수
function extractVideoId(url) {
  const patterns = [
    /(?:youtube\.com\/watch\?v=|youtu\.be\/)([^&\n?#]+)/,
    /youtube\.com\/embed\/([^&\n?#]+)/,
    /youtube\.com\/v\/([^&\n?#]+)/,
    /youtube\.com\/shorts\/([^&\n?#]+)/ // Shorts 지원 추가
  ];

  for (const pattern of patterns) {
    const match = url.match(pattern);
    if (match && match[1]) {
      return match[1];
    }
  }
  return null;
}

// Shorts URL 체크 함수
function isShorts(url) {
  return url.includes('/shorts/');
}

// YouTube 영상 정보 가져오기 (oEmbed API 사용)
async function fetchVideoInfo(url) {
  try {
    const videoId = extractVideoId(url);
    const shorts = isShorts(url);

    if (!videoId) {
      return { title: url, thumbnail: null, isShorts: false };
    }

    // Shorts의 경우 일반 watch URL로 변환하여 oEmbed API 호출
    const watchUrl = `https://www.youtube.com/watch?v=${videoId}`;
    const oembedUrl = `https://www.youtube.com/oembed?url=${encodeURIComponent(watchUrl)}&format=json`;
    const response = await fetch(oembedUrl);

    if (!response.ok) {
      throw new Error('Failed to fetch video info');
    }

    const data = await response.json();
    return {
      title: data.title || url,
      thumbnail: data.thumbnail_url || `https://img.youtube.com/vi/${videoId}/mqdefault.jpg`,
      videoId: videoId,
      isShorts: shorts
    };
  } catch (error) {
    console.error('Error fetching video info:', error);
    const videoId = extractVideoId(url);
    return {
      title: url,
      thumbnail: videoId ? `https://img.youtube.com/vi/${videoId}/mqdefault.jpg` : null,
      videoId: videoId,
      isShorts: isShorts(url)
    };
  }
}

// --- 렌더링 함수들 ---
function renderSingleSetting(elements, container, id, label, value) {
  const isUnlimited = value === -1 || value === Infinity;
  const displayValue = isUnlimited ? 0 : value;

  container.innerHTML = `
    <div class="form-group">
      <div style="margin-bottom: 12px;">
        <input type="number" id="${id}" class="form-input" min="1" value="${displayValue}" ${isUnlimited ? 'disabled' : ''}>
        <div class="form-description">1분 이상 설정 가능합니다</div>
      </div>
      <div class="checkbox-group">
        <input type="checkbox" id="${id}_unlimited" ${isUnlimited ? 'checked' : ''}>
        <label for="${id}_unlimited">무제한</label>
      </div>
    </div>
  `;

  // 체크박스 이벤트 리스너 추가
  setTimeout(() => {
    const checkbox = document.getElementById(`${id}_unlimited`);
    const input = document.getElementById(id);
    if (checkbox && input) {
      checkbox.addEventListener('change', () => {
        input.disabled = checkbox.checked;
        if (!checkbox.checked && input.value === '0') {
          input.value = '30'; // 체크 해제 시 기본값 설정
        }
      });
    }
  }, 0);
}

function renderDayBasedSetting(elements, container, idPrefix, values) {
  container.innerHTML = `<div class="day-setting-grid"></div>`;
  const grid = container.querySelector('.day-setting-grid');
  daysOfWeek.forEach((day, index) => {
    const dayIndex = (index + 1) % 7; // 1(월) ~ 0(일)
    const dayValue = values && values[dayIndex] !== undefined ? values[dayIndex] : -1;
    const isUnlimited = dayValue === -1;
    const unit = idPrefix === 'dailyLimit' ? '분' : '회';
    const minValue = idPrefix === 'dailyLimit' ? 1 : 0; // 시간은 최소 1분, 횟수는 최소 0회
    const defaultValue = idPrefix === 'dailyLimit' ? 30 : 3;
    grid.innerHTML += `
      <div class="day-setting">
        <div class="day-setting-title">${day}요일</div>
        <div class="input-group">
          <input type="number" id="${idPrefix}_${dayIndex}" min="${minValue}" value="${isUnlimited ? defaultValue : dayValue}" ${isUnlimited ? 'disabled' : ''}>
          <span class="unit">${unit}</span>
        </div>
        <div class="checkbox-group">
          <input type="checkbox" id="${idPrefix}_unlimited_${dayIndex}" ${isUnlimited ? 'checked' : ''}>
          <label for="${idPrefix}_unlimited_${dayIndex}">무제한</label>
        </div>
      </div>
    `;
  });

  // 체크박스 이벤트 리스너 추가
  daysOfWeek.forEach((day, index) => {
    const dayIndex = (index + 1) % 7;
    const checkbox = document.getElementById(`${idPrefix}_unlimited_${dayIndex}`);
    const input = document.getElementById(`${idPrefix}_${dayIndex}`);
    checkbox.addEventListener('change', () => {
      input.disabled = checkbox.checked;
    });
  });
}

async function renderWhitelist(elements, whitelist) {
  elements.whitelistList.innerHTML = '';
  if (!whitelist || whitelist.length === 0) {
    elements.whitelistList.innerHTML = '<li style="text-align: center; padding: 20px; color: var(--gray);">화이트리스트에 추가된 URL이 없습니다.</li>';
    return;
  }

  // 필터링
  const filterValue = document.querySelector('input[name="whitelistFilter"]:checked')?.value || 'all';
  const filteredWhitelist = whitelist.filter(item => {
    if (filterValue === 'all') return true;
    if (filterValue === 'study') return item.isStudy === true;
    if (filterValue === 'non-study') return item.isStudy !== true;
    return true;
  });

  if (filteredWhitelist.length === 0) {
    elements.whitelistList.innerHTML = '<li style="text-align: center; padding: 20px; color: var(--gray);">필터링된 항목이 없습니다.</li>';
    return;
  }

  // 각 URL에 대한 정보를 비동기로 가져오기
  for (const item of filteredWhitelist) {
    const url = typeof item === 'string' ? item : item.url;
    const li = document.createElement('li');

    // 로딩 상태 표시
    li.innerHTML = `
      <div style="flex: 1; display: flex; align-items: center; gap: 12px;">
        <div class="whitelist-item-thumbnail" style="background: var(--border-color);"></div>
        <div class="whitelist-item-info">
          <div class="whitelist-item-title">로딩 중...</div>
          <div class="whitelist-item-url">${url}</div>
        </div>
      </div>
    `;
    elements.whitelistList.appendChild(li);

    // 비디오 정보 가져오기
    const videoInfo = await fetchVideoInfo(url);

    // UI 업데이트
    const isStudy = typeof item === 'object' && item.isStudy;
    let badges = '';
    if (videoInfo.isShorts) {
      badges += '<span class="shorts-badge">Shorts</span>';
    }
    if (isStudy) {
      badges += '<span class="study-badge">공부</span>';
    }

    const titleHtml = badges
      ? `<span style="flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">${videoInfo.title}</span>${badges}`
      : videoInfo.title;

    li.innerHTML = `
      ${videoInfo.thumbnail ? `<img src="${videoInfo.thumbnail}" class="whitelist-item-thumbnail" alt="Thumbnail">` : '<div class="whitelist-item-thumbnail"></div>'}
      <div class="whitelist-item-info">
        <div class="whitelist-item-title">${titleHtml}</div>
        <div class="whitelist-item-url">${url}</div>
      </div>
      <div class="whitelist-item-actions">
        <button class="btn btn-secondary btn-visit" data-url="${url}">🔗 이동</button>
        <button class="btn btn-danger btn-delete" data-url="${url}">🗑️ 삭제</button>
      </div>
    `;

    // 이동 버튼 이벤트
    li.querySelector('.btn-visit').addEventListener('click', () => {
      window.open(url, '_blank');
    });

    // 삭제 버튼 이벤트
    li.querySelector('.btn-delete').addEventListener('click', async () => {
      let { whitelist: currentWhitelist = [] } = await getStorage(['whitelist']);
      currentWhitelist = currentWhitelist.filter(whitelistItem => {
        const itemUrl = typeof whitelistItem === 'string' ? whitelistItem : whitelistItem.url;
        return itemUrl !== url;
      });
      await setStorage({ whitelist: currentWhitelist });
      showStatus(elements.whitelistStatus, 'URL이 삭제되었습니다!', true);
      updateUI(elements);
    });
  }
}

// --- UI 업데이트 ---
async function updateUI(elements, tempData = null) {
  const data = tempData || await getStorage([
    'emergency_reset_frequency', 'daily_limit_reset_frequency',
    'initial_daily_emergency_uses', 'daily_limit_ms',
    'emergency_uses_by_day', 'daily_limit_by_day',
    'useStudyAI', 'alwaysBlockShorts', 'whitelist'
  ]);
  console.log('Loaded data from storage:', data);

  const emergencyFrequency = data.emergency_reset_frequency || 'daily';
  const limitFrequency = data.daily_limit_reset_frequency || 'daily';

  // 긴급 시청 초기화 주기 라디오 버튼 설정
  if (emergencyFrequency === 'by_day') {
    elements.resetByDayRadio.checked = true;
  } else if (emergencyFrequency === 'daily') {
    elements.resetDailyRadio.checked = true;
  } else if (emergencyFrequency === 'weekly') {
    elements.resetWeeklyRadio.checked = true;
  } else if (emergencyFrequency === 'monthly') {
    elements.resetMonthlyRadio.checked = true;
  }

  // 시간 제한 초기화 주기 라디오 버튼 설정
  if (limitFrequency === 'by_day') {
    elements.resetByDayLimitRadio.checked = true;
  } else if (limitFrequency === 'daily') {
    elements.resetDailyLimitRadio.checked = true;
  } else if (limitFrequency === 'weekly') {
    elements.resetWeeklyLimitRadio.checked = true;
  } else if (limitFrequency === 'monthly') {
    elements.resetMonthlyLimitRadio.checked = true;
  }

  // 긴급 시청 횟수 설정
  if (emergencyFrequency === 'by_day') {
    renderDayBasedSetting(elements, elements.emergencyUsesSettingContainer, 'emergencyUses', data.emergency_uses_by_day || null);
  } else {
    const initialUses = data.initial_daily_emergency_uses !== undefined ? data.initial_daily_emergency_uses : 3;
    renderSingleSetting(elements, elements.emergencyUsesSettingContainer, 'emergencyUsesInput', '긴급 시청 횟수', initialUses);
  }

  // 시간 제한 설정
  if (limitFrequency === 'by_day') {
    renderDayBasedSetting(elements, elements.dailyLimitSettingContainer, 'dailyLimit', data.daily_limit_by_day || null);
  } else {
    const limitMs = data.daily_limit_ms;
    // Infinity 값이면 Infinity 그대로 전달, undefined/null이면 30분 기본값, 아니면 분 단위로 변환
    const limitMinutes = (limitMs === Infinity)
      ? Infinity
      : ((limitMs === undefined || limitMs === null) ? 30 : Math.floor(limitMs / 60000));
    renderSingleSetting(elements, elements.dailyLimitSettingContainer, 'dailyLimitInput', '일일 사용 시간 제한 (분)', limitMinutes);
  }

  // 규칙 관리
  elements.useStudyAICheckbox.checked = data.useStudyAI || false;
  elements.alwaysBlockShortsCheckbox.checked = data.alwaysBlockShorts || false;
  await renderWhitelist(elements, data.whitelist);
}

// --- 이벤트 리스너 ---
function setupEventListeners(elements) {
  // 탭 네비게이션
  const tabButtons = document.querySelectorAll('.tab-button');
  const tabContents = document.querySelectorAll('.tab-content');

  tabButtons.forEach(button => {
    button.addEventListener('click', () => {
      const targetTab = button.dataset.tab;

      // 모든 탭 버튼과 콘텐츠에서 active 클래스 제거
      tabButtons.forEach(btn => btn.classList.remove('active'));
      tabContents.forEach(content => content.classList.remove('active'));

      // 클릭된 탭 활성화
      button.classList.add('active');
      document.getElementById(targetTab).classList.add('active');
    });
  });

  // 긴급 시청 초기화 주기 라디오 버튼 변경 시 UI 즉시 업데이트
  document.querySelectorAll('input[name="emergencyResetFrequency"]').forEach(radio => {
    radio.addEventListener('change', () => {
      const tempData = { emergency_reset_frequency: radio.value };
      getStorage(['emergency_uses_by_day', 'initial_daily_emergency_uses', 'daily_limit_reset_frequency', 'daily_limit_by_day', 'daily_limit_ms']).then(savedData => {
        updateUI(elements, { ...savedData, ...tempData });
      });
    });
  });

  // 시간 제한 초기화 주기 라디오 버튼 변경 시 UI 즉시 업데이트
  document.querySelectorAll('input[name="dailyLimitResetFrequency"]').forEach(radio => {
    radio.addEventListener('change', () => {
      const tempData = { daily_limit_reset_frequency: radio.value };
      getStorage(['emergency_reset_frequency', 'emergency_uses_by_day', 'initial_daily_emergency_uses', 'daily_limit_by_day', 'daily_limit_ms']).then(savedData => {
        updateUI(elements, { ...savedData, ...tempData });
      });
    });
  });

  // 긴급 시청 설정 저장
  elements.saveEmergencyResetFrequencyButton.addEventListener('click', async () => {
    const frequency = document.querySelector('input[name="emergencyResetFrequency"]:checked').value;
    await setStorage({ emergency_reset_frequency: frequency });
    showStatus(elements.emergencyResetFrequencyStatus, '저장되었습니다!', true);
    updateUI(elements);
  });

  elements.saveEmergencyUsesSettingButton.addEventListener('click', async () => {
    const selectedFrequency = document.querySelector('input[name="emergencyResetFrequency"]:checked');
    const frequency = selectedFrequency ? selectedFrequency.value : 'daily';
    if (frequency === 'by_day') {
      const emergency_uses_by_day = {};
      daysOfWeek.forEach((day, index) => {
        const dayIndex = (index + 1) % 7;
        const checkbox = document.getElementById(`emergencyUses_unlimited_${dayIndex}`);
        if (checkbox && checkbox.checked) {
          emergency_uses_by_day[dayIndex] = -1;
        } else {
          const input = document.getElementById(`emergencyUses_${dayIndex}`);
          const inputValue = input ? parseInt(input.value, 10) : 3;
          // 음수 방지, 최소 0
          emergency_uses_by_day[dayIndex] = Math.max(0, inputValue);
        }
      });
      await setStorage({ emergency_uses_by_day });
    } else {
      const unlimitedCheckbox = document.getElementById('emergencyUsesInput_unlimited');
      if (unlimitedCheckbox && unlimitedCheckbox.checked) {
        await setStorage({ initial_daily_emergency_uses: -1 });
      } else {
        const input = document.getElementById('emergencyUsesInput');
        const inputValue = input ? parseInt(input.value, 10) : 3;
        // 음수 방지, 최소 0
        const value = Math.max(0, inputValue);
        await setStorage({ initial_daily_emergency_uses: value });
      }
    }
    showStatus(elements.emergencyUsesSettingStatus, '저장되었습니다!', true);
    updateUI(elements);
  });

  // 시간 제한 초기화 주기 저장
  elements.saveDailyLimitResetFrequencyButton.addEventListener('click', async () => {
    const frequency = document.querySelector('input[name="dailyLimitResetFrequency"]:checked').value;
    await setStorage({ daily_limit_reset_frequency: frequency });
    showStatus(elements.dailyLimitResetFrequencyStatus, '저장되었습니다!', true);
    updateUI(elements);
  });

  // 시간 제한 값 저장
  elements.saveDailyLimitSettingButton.addEventListener('click', async () => {
    const selectedFrequency = document.querySelector('input[name="dailyLimitResetFrequency"]:checked');
    const frequency = selectedFrequency ? selectedFrequency.value : 'daily';

    // 시간 제한 값 저장
    if (frequency === 'by_day') {
      const daily_limit_by_day = {};
      daysOfWeek.forEach((day, index) => {
        const dayIndex = (index + 1) % 7;
        const checkbox = document.getElementById(`dailyLimit_unlimited_${dayIndex}`);
        if (checkbox && checkbox.checked) {
          daily_limit_by_day[dayIndex] = -1;
        } else {
          const input = document.getElementById(`dailyLimit_${dayIndex}`);
          const inputValue = input ? parseInt(input.value, 10) : 30;
          // 음수 방지, 최소 1분
          daily_limit_by_day[dayIndex] = Math.max(1, inputValue);
        }
      });
      await setStorage({ daily_limit_by_day });
    } else {
      const unlimitedCheckbox = document.getElementById('dailyLimitInput_unlimited');
      if (unlimitedCheckbox && unlimitedCheckbox.checked) {
        await setStorage({ daily_limit_ms: Infinity });
      } else {
        const input = document.getElementById('dailyLimitInput');
        const inputValue = input ? parseInt(input.value, 10) : 30;
        // 음수 방지, 최소 1분
        const validValue = Math.max(1, inputValue);
        const value = validValue * 60000;
        await setStorage({ daily_limit_ms: value });
      }
    }
    showStatus(elements.dailyLimitSettingStatus, '저장되었습니다!', true);
    updateUI(elements);
  });

  // AI 및 차단 규칙 설정 저장 (통합)
  elements.saveRulesButton.addEventListener('click', async () => {
    await setStorage({
      useStudyAI: elements.useStudyAICheckbox.checked,
      alwaysBlockShorts: elements.alwaysBlockShortsCheckbox.checked
    });
    showStatus(elements.rulesStatus, '저장되었습니다!', true);
  });

  // 화이트리스트 추가
  elements.addWhitelistButton.addEventListener('click', async () => {
    const url = elements.whitelistUrlInput.value.trim();
    if (!url) {
      showStatus(elements.whitelistStatus, 'URL을 입력해주세요.', false);
      return;
    }

    // YouTube URL 검증
    if (!url.includes('youtube.com') && !url.includes('youtu.be')) {
      showStatus(elements.whitelistStatus, 'YouTube URL을 입력해주세요.', false);
      return;
    }

    const isStudy = elements.isStudyVideoCheckbox.checked;
    let { whitelist = [] } = await getStorage(['whitelist']);

    // 중복 체크 (문자열과 객체 모두 고려)
    const isDuplicate = whitelist.some(item => {
      const itemUrl = typeof item === 'string' ? item : item.url;
      return itemUrl === url;
    });

    if (isDuplicate) {
      showStatus(elements.whitelistStatus, '이미 추가된 URL입니다.', false);
      return;
    }

    // 공부 영상이면 객체로, 아니면 문자열로 저장
    if (isStudy) {
      whitelist.push({ url, isStudy: true });
    } else {
      whitelist.push(url);
    }

    await setStorage({ whitelist });
    elements.whitelistUrlInput.value = '';
    elements.isStudyVideoCheckbox.checked = false;
    showStatus(elements.whitelistStatus, '추가되었습니다!', true);
    updateUI(elements);
  });

  // 필터 변경 시 목록 업데이트
  document.querySelectorAll('input[name="whitelistFilter"]').forEach(radio => {
    radio.addEventListener('change', () => {
      updateUI(elements);
    });
  });
}

document.addEventListener('DOMContentLoaded', () => {
  elements = {
    // 긴급 시청 설정
    resetDailyRadio: document.getElementById('resetDaily'),
    resetWeeklyRadio: document.getElementById('resetWeekly'),
    resetMonthlyRadio: document.getElementById('resetMonthly'),
    resetByDayRadio: document.getElementById('resetByDay'),
    saveEmergencyResetFrequencyButton: document.getElementById('saveEmergencyResetFrequencyButton'),
    emergencyResetFrequencyStatus: document.getElementById('emergencyResetFrequencyStatus'),
    emergencyUsesSettingContainer: document.getElementById('emergencyUsesSettingContainer'),
    saveEmergencyUsesSettingButton: document.getElementById('saveEmergencyUsesSettingButton'),
    emergencyUsesSettingStatus: document.getElementById('emergencyUsesSettingStatus'),
    // 시간 제한 설정
    resetDailyLimitRadio: document.getElementById('resetDailyLimit'),
    resetWeeklyLimitRadio: document.getElementById('resetWeeklyLimit'),
    resetMonthlyLimitRadio: document.getElementById('resetMonthlyLimit'),
    resetByDayLimitRadio: document.getElementById('resetByDayLimit'),
    saveDailyLimitResetFrequencyButton: document.getElementById('saveDailyLimitResetFrequencyButton'),
    dailyLimitResetFrequencyStatus: document.getElementById('dailyLimitResetFrequencyStatus'),
    dailyLimitSettingContainer: document.getElementById('dailyLimitSettingContainer'),
    saveDailyLimitSettingButton: document.getElementById('saveDailyLimitSettingButton'),
    dailyLimitSettingStatus: document.getElementById('dailyLimitSettingStatus'),
    // 규칙 관리
    useStudyAICheckbox: document.getElementById('useStudyAI'),
    alwaysBlockShortsCheckbox: document.getElementById('alwaysBlockShorts'),
    saveRulesButton: document.getElementById('saveRulesButton'),
    rulesStatus: document.getElementById('rulesStatus'),
    // 화이트리스트
    whitelistUrlInput: document.getElementById('whitelistUrlInput'),
    isStudyVideoCheckbox: document.getElementById('isStudyVideo'),
    addWhitelistButton: document.getElementById('addWhitelistButton'),
    whitelistList: document.getElementById('whitelistList'),
    whitelistStatus: document.getElementById('whitelistStatus'),
  };

  setupEventListeners(elements);
  updateUI(elements);
});

chrome.runtime.onMessage.addListener(request => {
  if (request.action === "updateOptionsUI") {
    // 사용자가 시간/횟수 설정 입력 필드에 입력 중일 때는 UI 업데이트를 건너뛰어 포커스를 잃지 않도록 함
    const activeElement = document.activeElement;
    if (activeElement && activeElement.tagName === 'INPUT' &&
        (elements.emergencyUsesSettingContainer.contains(activeElement) ||
        elements.dailyLimitSettingContainer.contains(activeElement))) {
      console.log("Skipping UI update to prevent losing input focus.");
      return;
    }
    updateUI(elements);
  }
});
