import { setStorage } from '../lib/storage.js';
import { supabase, getCurrentUser } from '../lib/supabaseClient.js';
import { HARDCORE_DISABLE_COOLDOWN_MS } from '../lib/hardcore.js';

const signedOutView = document.getElementById('signedOutView');
const signedInView = document.getElementById('signedInView');
const accountDeletedView = document.getElementById('accountDeletedView');

document.getElementById('openAuthButton').addEventListener('click', () => {
  chrome.tabs.create({ url: chrome.runtime.getURL('auth/auth.html') });
});

document.getElementById('signOutButton').addEventListener('click', async () => {
  await supabase.auth.signOut();
  window.location.reload();
});

const byDayToggle = document.getElementById('byDayToggle');
const simpleLimitBlock = document.getElementById('simpleLimitBlock');
const byDayLimitBlock = document.getElementById('byDayLimitBlock');
byDayToggle.addEventListener('change', () => {
  simpleLimitBlock.style.display = byDayToggle.checked ? 'none' : '';
  byDayLimitBlock.style.display = byDayToggle.checked ? '' : 'none';
});

// 하드코어 모드: 켜져 있으면 한도를 못 건드리게 잠근다.
// 끄는 것도 즉시 반영되면 충동적으로 껐다 켰다 할 수 있으므로, "해제 예약"만 남겨두고
// 실제로는 24시간 뒤(백그라운드에서) 꺼진다 — checkHardcoreDisableCooldown 참고.
const limitLockHint = document.getElementById('limitLockHint');
const hardcoreOffBlock = document.getElementById('hardcoreOffBlock');
const hardcoreOnBlock = document.getElementById('hardcoreOnBlock');
const hardcorePendingBlock = document.getElementById('hardcorePendingBlock');
const hardcorePendingText = document.getElementById('hardcorePendingText');

let currentSettings = null;
let hardcorePendingInterval = null;

function applyLimitLockState(locked) {
  byDayToggle.disabled = locked;
  document.getElementById('dailyLimitInput').disabled = locked;
  document.querySelectorAll('.day-limit-row input').forEach((input) => {
    input.disabled = locked;
  });
  limitLockHint.style.display = locked ? '' : 'none';
}

function renderHardcoreState() {
  const isOn = !!currentSettings?.hardcore_mode;
  const requestedAt = currentSettings?.hardcore_disable_requested_at
    ? new Date(currentSettings.hardcore_disable_requested_at).getTime()
    : null;
  const pending = isOn && requestedAt !== null && Date.now() < requestedAt + HARDCORE_DISABLE_COOLDOWN_MS;

  hardcoreOffBlock.style.display = isOn ? 'none' : '';
  hardcoreOnBlock.style.display = isOn && !pending ? '' : 'none';
  hardcorePendingBlock.style.display = pending ? '' : 'none';
  applyLimitLockState(isOn);

  clearInterval(hardcorePendingInterval);
  if (pending) {
    const updateCountdown = () => {
      const remaining = requestedAt + HARDCORE_DISABLE_COOLDOWN_MS - Date.now();
      if (remaining <= 0) {
        hardcorePendingText.textContent = '⏳ 곧 해제됩니다...';
        return;
      }
      const totalSeconds = Math.floor(remaining / 1000);
      const m = Math.floor(totalSeconds / 60);
      const s = totalSeconds % 60;
      hardcorePendingText.textContent = `⏳ 해제까지 ${m}분 ${String(s).padStart(2, '0')}초 남음`;
    };
    updateCountdown();
    hardcorePendingInterval = setInterval(updateCountdown, 1000);
  }
}

async function updateHardcoreFields(fields) {
  const user = await getCurrentUser();
  if (!user) return;

  const patch = { user_id: user.id, ...fields, updated_at: new Date().toISOString() };
  const { data, error } = await supabase.from('settings').upsert(patch).select().maybeSingle();
  if (error) {
    alert(`처리 실패: ${error.message}`);
    return;
  }
  currentSettings = data || { ...currentSettings, ...patch };
  await setStorage({ settingsCache: currentSettings });
  await chrome.runtime.sendMessage({ action: 'settingsUpdated' });
  renderHardcoreState();
}

document.getElementById('enableHardcoreButton').addEventListener('click', () => {
  updateHardcoreFields({ hardcore_mode: true, hardcore_disable_requested_at: null });
});
document.getElementById('requestHardcoreOffButton').addEventListener('click', async () => {
  const user = await getCurrentUser();
  if (!user) return;
  const { data: streak } = await supabase.from('streaks').select('current_streak').eq('user_id', user.id).maybeSingle();
  const currentStreak = streak?.current_streak || 0;

  const streakWarning = currentStreak > 0
    ? `지금 끄면 ${currentStreak}일 연속 기록이 0으로 초기화돼요. `
    : '';
  if (!confirm(`${streakWarning}하드코어 모드를 끌까요? 지금부터 1시간 뒤에 실제로 꺼지고, 그 전엔 언제든 취소할 수 있어요. 그래도 해제하시겠어요?`)) return;

  updateHardcoreFields({ hardcore_disable_requested_at: new Date().toISOString() });
});
document.getElementById('cancelHardcoreOffButton').addEventListener('click', () => {
  updateHardcoreFields({ hardcore_disable_requested_at: null });
});

let whitelist = [];
const whitelistListEl = document.getElementById('whitelistList');
function renderWhitelist() {
  whitelistListEl.innerHTML = '';
  whitelist.forEach((entry, idx) => {
    const row = document.createElement('div');
    row.className = 'whitelist-item';

    const input = document.createElement('input');
    input.type = 'text';
    input.value = entry;
    input.dataset.idx = idx;

    const removeButton = document.createElement('button');
    removeButton.className = 'btn-secondary';
    removeButton.dataset.remove = idx;
    removeButton.textContent = '삭제';

    row.appendChild(input);
    row.appendChild(removeButton);
    whitelistListEl.appendChild(row);
  });
  whitelistListEl.querySelectorAll('input').forEach((input) => {
    input.addEventListener('change', (e) => {
      whitelist[Number(e.target.dataset.idx)] = e.target.value;
    });
  });
  whitelistListEl.querySelectorAll('button[data-remove]').forEach((btn) => {
    btn.addEventListener('click', (e) => {
      whitelist.splice(Number(e.target.dataset.remove), 1);
      renderWhitelist();
    });
  });
}
document.getElementById('addWhitelistButton').addEventListener('click', () => {
  const input = document.getElementById('whitelistInput');
  const value = input.value.trim();
  if (!value) return;
  whitelist.push(value);
  input.value = '';
  renderWhitelist();
});

// --- 예약 차단(요일별 반복 시간대 자동 차단) ---
// 판정 로직(wrap 규칙 등)은 lib/schedule.js, 여기선 편집 UI만 담당한다.
const DAY_LABELS_SHORT = ['일', '월', '화', '수', '목', '금', '토'];

function minutesToTimeInputValue(minutes) {
  const normalized = ((minutes % 1440) + 1440) % 1440;
  const h = Math.floor(normalized / 60);
  const m = normalized % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

function timeInputValueToMinutes(value) {
  const [h, m] = (value || '0:0').split(':').map(Number);
  return (Number.isFinite(h) ? h : 0) * 60 + (Number.isFinite(m) ? m : 0);
}

let scheduledBlocks = [];
const scheduleListEl = document.getElementById('scheduleList');

function renderScheduledBlocks() {
  scheduleListEl.innerHTML = '';
  scheduledBlocks.forEach((block, idx) => {
    if (!Array.isArray(block.days) || block.days.length !== 7) block.days = [1, 1, 1, 1, 1, 1, 1];

    const row = document.createElement('div');
    row.className = 'schedule-item';

    const header = document.createElement('div');
    header.className = 'schedule-item-header';

    const labelInput = document.createElement('input');
    labelInput.type = 'text';
    labelInput.className = 'schedule-label';
    labelInput.placeholder = '라벨 (예: 밤 시간)';
    labelInput.value = block.label || '';
    labelInput.addEventListener('change', () => {
      scheduledBlocks[idx].label = labelInput.value;
    });

    const enabledLabel = document.createElement('label');
    enabledLabel.className = 'checkbox-row schedule-enabled-toggle';
    const enabledInput = document.createElement('input');
    enabledInput.type = 'checkbox';
    enabledInput.checked = block.enabled !== false;
    enabledInput.addEventListener('change', () => {
      scheduledBlocks[idx].enabled = enabledInput.checked;
    });
    enabledLabel.appendChild(enabledInput);
    enabledLabel.appendChild(document.createTextNode('켜짐'));

    const removeButton = document.createElement('button');
    removeButton.className = 'btn-secondary';
    removeButton.textContent = '삭제';
    removeButton.addEventListener('click', () => {
      scheduledBlocks.splice(idx, 1);
      renderScheduledBlocks();
    });

    header.appendChild(labelInput);
    header.appendChild(enabledLabel);
    header.appendChild(removeButton);

    const daysRow = document.createElement('div');
    daysRow.className = 'schedule-days';
    DAY_LABELS_SHORT.forEach((label, dayIdx) => {
      const dayLabel = document.createElement('label');
      dayLabel.className = 'day-toggle-chip';
      const dayInput = document.createElement('input');
      dayInput.type = 'checkbox';
      dayInput.checked = !!block.days[dayIdx];
      dayInput.addEventListener('change', () => {
        scheduledBlocks[idx].days[dayIdx] = dayInput.checked ? 1 : 0;
      });
      dayLabel.appendChild(dayInput);
      dayLabel.appendChild(document.createTextNode(label));
      daysRow.appendChild(dayLabel);
    });

    const timesRow = document.createElement('div');
    timesRow.className = 'schedule-times';

    const startInput = document.createElement('input');
    startInput.type = 'time';
    startInput.value = minutesToTimeInputValue(block.startMinute ?? 22 * 60);
    startInput.addEventListener('change', () => {
      scheduledBlocks[idx].startMinute = timeInputValueToMinutes(startInput.value);
    });

    const tilde = document.createElement('span');
    tilde.textContent = '~';

    const endInput = document.createElement('input');
    endInput.type = 'time';
    endInput.value = minutesToTimeInputValue(block.endMinute ?? 7 * 60);
    endInput.addEventListener('change', () => {
      scheduledBlocks[idx].endMinute = timeInputValueToMinutes(endInput.value);
    });

    timesRow.appendChild(startInput);
    timesRow.appendChild(tilde);
    timesRow.appendChild(endInput);

    row.appendChild(header);
    row.appendChild(daysRow);
    row.appendChild(timesRow);
    scheduleListEl.appendChild(row);
  });
}

document.getElementById('addScheduleButton').addEventListener('click', () => {
  scheduledBlocks.push({
    id: `sb-${Date.now()}`,
    label: '밤 시간',
    days: [1, 1, 1, 1, 1, 1, 1],
    startMinute: 22 * 60,
    endMinute: 7 * 60,
    enabled: true
  });
  renderScheduledBlocks();
});

function fillForm(settings) {
  const byDay = settings.daily_limit_reset_frequency === 'by_day';
  byDayToggle.checked = byDay;
  simpleLimitBlock.style.display = byDay ? 'none' : '';
  byDayLimitBlock.style.display = byDay ? '' : 'none';

  document.getElementById('dailyLimitInput').value = settings.daily_limit_ms ? settings.daily_limit_ms / 60000 : 0;

  document.querySelectorAll('.day-limit-row').forEach((row) => {
    const day = row.dataset.day;
    const val = settings.daily_limit_by_day?.[day];
    row.querySelector('input').value = val === undefined ? 30 : val;
  });

  document.getElementById('alwaysBlockShortsToggle').checked = !!settings.always_block_shorts;

  whitelist = Array.isArray(settings.whitelist) ? [...settings.whitelist] : [];
  renderWhitelist();

  document.getElementById('emergencyUsesInput').value = settings.emergency_config?.dailyUses ?? 3;
  document.getElementById('emergencyResetSelect').value = settings.emergency_config?.resetFrequency || 'daily';

  document.getElementById('alarmIntervalInput').value = settings.alarm_interval_minutes ?? 0;
  document.getElementById('alarmMilestonesToggle').checked = settings.alarm_milestones_enabled !== false;

  scheduledBlocks = Array.isArray(settings.scheduled_blocks)
    ? settings.scheduled_blocks.map((b) => ({ ...b, days: Array.isArray(b.days) ? [...b.days] : [1, 1, 1, 1, 1, 1, 1] }))
    : [];
  renderScheduledBlocks();

  currentSettings = settings;
  renderHardcoreState();
}

function collectSettings() {
  const byDay = byDayToggle.checked;
  const dailyLimitByDay = {};
  document.querySelectorAll('.day-limit-row').forEach((row) => {
    dailyLimitByDay[row.dataset.day] = Number(row.querySelector('input').value);
  });

  return {
    daily_limit_reset_frequency: byDay ? 'by_day' : 'daily',
    daily_limit_ms: Number(document.getElementById('dailyLimitInput').value) * 60000,
    daily_limit_by_day: dailyLimitByDay,
    always_block_shorts: document.getElementById('alwaysBlockShortsToggle').checked,
    whitelist,
    emergency_config: {
      dailyUses: Number(document.getElementById('emergencyUsesInput').value),
      resetFrequency: document.getElementById('emergencyResetSelect').value
    },
    alarm_interval_minutes: Number(document.getElementById('alarmIntervalInput').value),
    alarm_milestones_enabled: document.getElementById('alarmMilestonesToggle').checked,
    scheduled_blocks: scheduledBlocks
  };
}

document.getElementById('saveButton').addEventListener('click', async () => {
  const user = await getCurrentUser();
  if (!user) return;

  const settings = collectSettings();
  const statusEl = document.getElementById('saveStatus');
  statusEl.textContent = '저장 중...';

  const { error } = await supabase.from('settings').upsert({ user_id: user.id, ...settings, updated_at: new Date().toISOString() });
  if (error) {
    statusEl.textContent = `저장 실패: ${error.message}`;
    return;
  }

  currentSettings = { ...currentSettings, ...settings };
  await setStorage({ settingsCache: currentSettings });
  await chrome.runtime.sendMessage({ action: 'settingsUpdated' });
  statusEl.textContent = '저장됨.';
  setTimeout(() => (statusEl.textContent = ''), 2000);
});

// --- 계정 삭제(위험 구역) ---
// 되돌릴 수 없는 동작이라 두 단계로 막는다: confirm 한 번 + 본인 이메일 직접 입력.
// 순서도 중요하다 — 서버(Edge Function) 삭제가 성공했을 때만 로그아웃하고 로컬을 비운다.
// 반대로 하면 삭제가 실패했는데 내 기록만 날아가는 최악의 경우가 생긴다.
const deleteAccountButton = document.getElementById('deleteAccountButton');
const deleteAccountConfirmBlock = document.getElementById('deleteAccountConfirmBlock');
const deleteAccountPhraseEl = document.getElementById('deleteAccountPhrase');
const deleteAccountConfirmInput = document.getElementById('deleteAccountConfirmInput');
const confirmDeleteAccountButton = document.getElementById('confirmDeleteAccountButton');
const cancelDeleteAccountButton = document.getElementById('cancelDeleteAccountButton');
const deleteAccountStatus = document.getElementById('deleteAccountStatus');

// 이메일 없이 가입된 계정(소셜 등)이면 확인 문구로 '삭제'를 쓴다.
const DELETE_FALLBACK_PHRASE = '삭제';
let deleteConfirmPhrase = '';
let deleteInFlight = false;

function normalizeDeletePhrase(value) {
  return (value || '').trim().toLowerCase();
}

function renderDeleteConfirmState() {
  const matched = !!deleteConfirmPhrase
    && normalizeDeletePhrase(deleteAccountConfirmInput.value) === normalizeDeletePhrase(deleteConfirmPhrase);
  confirmDeleteAccountButton.disabled = deleteInFlight || !matched;
}

function setDeleteControlsDisabled(disabled) {
  deleteAccountButton.disabled = disabled;
  cancelDeleteAccountButton.disabled = disabled;
  deleteAccountConfirmInput.disabled = disabled;
}

deleteAccountConfirmInput.addEventListener('input', renderDeleteConfirmState);

deleteAccountButton.addEventListener('click', () => {
  if (deleteInFlight) return;
  if (!confirm('계정을 삭제하면 계정, 사용 시간 기록, 스트릭·XP, 뱃지, 설정이 모두 영구 삭제됩니다. 되돌릴 수 없습니다. 계속할까요?')) return;

  deleteAccountConfirmBlock.style.display = '';
  deleteAccountConfirmInput.value = '';
  deleteAccountStatus.textContent = '';
  renderDeleteConfirmState();
  deleteAccountConfirmInput.focus();
});

cancelDeleteAccountButton.addEventListener('click', () => {
  if (deleteInFlight) return;
  deleteAccountConfirmBlock.style.display = 'none';
  deleteAccountConfirmInput.value = '';
  deleteAccountStatus.textContent = '';
  renderDeleteConfirmState();
});

confirmDeleteAccountButton.addEventListener('click', async () => {
  // 버튼 disabled와 별개로 한 번 더 막는다 — 연타로 두 번 삭제가 나가면 안 된다.
  if (deleteInFlight) return;
  if (normalizeDeletePhrase(deleteAccountConfirmInput.value) !== normalizeDeletePhrase(deleteConfirmPhrase)) return;

  deleteInFlight = true;
  confirmDeleteAccountButton.disabled = true;
  setDeleteControlsDisabled(true);
  deleteAccountStatus.textContent = '계정을 삭제하는 중...';

  const { error } = await supabase.functions.invoke('delete-account');
  if (error) {
    // 서버가 거절했으면 로컬은 아무것도 건드리지 않는다. 로그인 상태 그대로 두고 다시 시도할 수 있게.
    deleteInFlight = false;
    setDeleteControlsDisabled(false);
    deleteAccountStatus.textContent = `삭제 실패: ${error.message} — 계정과 기록은 그대로 남아 있습니다.`;
    renderDeleteConfirmState();
    return;
  }

  // 서버 세션은 이미 죽었으니 네트워크를 타지 않는 로컬 로그아웃만 한다.
  await supabase.auth.signOut({ scope: 'local' });
  // chrome.storage.local엔 이 확장이 쓰는 값(설정 캐시 · 사용/긴급 기록 · 집중/차단 상태 ·
  // 알람 상태 · 동기화 마커 · Supabase 세션)만 들어 있어서 통째로 비우는 게 가장 확실하다.
  await chrome.storage.local.clear();
  // 다른 설정 변경과 같은 경로로 백그라운드에 알린다. 이미 지운 뒤라 실패해도 되돌릴 게 없다.
  await chrome.runtime.sendMessage({ action: 'settingsUpdated' }).catch(() => {});

  clearInterval(hardcorePendingInterval);
  signedOutView.style.display = 'none';
  signedInView.style.display = 'none';
  accountDeletedView.style.display = '';
});

document.getElementById('deletedSignUpButton').addEventListener('click', () => {
  chrome.tabs.create({ url: chrome.runtime.getURL('auth/auth.html') });
});

async function init() {
  const user = await getCurrentUser();
  if (!user) {
    signedOutView.style.display = '';
    signedInView.style.display = 'none';
    return;
  }
  signedOutView.style.display = 'none';
  signedInView.style.display = '';

  deleteConfirmPhrase = user.email || DELETE_FALLBACK_PHRASE;
  deleteAccountPhraseEl.textContent = deleteConfirmPhrase;
  renderDeleteConfirmState();

  const { data } = await supabase.from('settings').select('*').eq('user_id', user.id).maybeSingle();
  fillForm(
    data || {
      daily_limit_ms: 30 * 60000,
      daily_limit_by_day: {},
      daily_limit_reset_frequency: 'daily',
      always_block_shorts: false,
      whitelist: [],
      emergency_config: { dailyUses: 3, resetFrequency: 'daily' },
      alarm_interval_minutes: 0,
      alarm_milestones_enabled: true,
      hardcore_mode: false,
      hardcore_disable_requested_at: null,
      scheduled_blocks: []
    }
  );
}

init();
