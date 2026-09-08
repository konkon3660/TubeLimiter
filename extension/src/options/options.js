import { setStorage } from '../lib/storage.js';
import { supabase, getCurrentUser } from '../lib/supabaseClient.js';
import { HARDCORE_DISABLE_COOLDOWN_MS } from '../lib/hardcore.js';
import {
  buildDiagnosticsReport,
  diagnosticKindMessageKey,
  formatDiagnosticTime
} from '../lib/syncDiagnostics.js';
import {
  DIAGNOSTIC_STORAGE_KEYS,
  clearDiagnostics,
  readDiagnostics
} from '../lib/diagnosticsStore.js';
import { applyI18n, t, tCount } from '../lib/i18n.js';

applyI18n();

const signedOutView = document.getElementById('signedOutView');
const signedInView = document.getElementById('signedInView');
const accountDeletedView = document.getElementById('accountDeletedView');

document.getElementById('openAuthButton').addEventListener('click', () => {
  chrome.tabs.create({ url: chrome.runtime.getURL('auth/auth.html') });
});

document.getElementById('signOutButton').addEventListener('click', async () => {
  await supabase.auth.signOut();
  // 로그아웃하면 서버 버킷 합계는 더 이상 이 기기 것이 아니다. 남겨두면 다른 기기가 쓴 몫만큼
  // 남은 긴급 시청 횟수가 깎인 채로 굳는다 — 로그아웃 상태는 로컬 값만으로 동작해야 한다.
  //
  // 진단 기록도 지운 계정과의 통신 기록이다. 남겨두면 이미 로그아웃한 계정의 실패 목록이 계속
  // 보이고, "마지막 성공" 시각이 다음 계정의 24시간 판정에 그대로 끼어든다
  // (안드로이드 AppState.clearAccountData가 같은 이유로 같이 지운다).
  await chrome.storage.local.remove([
    'emergencyUsesBucketDate',
    'emergencyUsesBucketRemote',
    'emergencyUsesBucketReported',
    ...DIAGNOSTIC_STORAGE_KEYS
  ]);
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
  // Shorts 한도도 같은 잠금에 걸린다. 여기서 빠뜨리면 하드코어 모드 중에 Shorts 한도만
  // 늘려서 커밋먼트를 우회할 수 있는 구멍이 된다 (전체 한도만 잠그는 건 반쪽짜리 잠금).
  document.getElementById('shortsLimitInput').disabled = locked;
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
  const pending =
    isOn && requestedAt !== null && Date.now() < requestedAt + HARDCORE_DISABLE_COOLDOWN_MS;

  hardcoreOffBlock.style.display = isOn ? 'none' : '';
  hardcoreOnBlock.style.display = isOn && !pending ? '' : 'none';
  hardcorePendingBlock.style.display = pending ? '' : 'none';
  applyLimitLockState(isOn);

  clearInterval(hardcorePendingInterval);
  if (pending) {
    const updateCountdown = () => {
      const remaining = requestedAt + HARDCORE_DISABLE_COOLDOWN_MS - Date.now();
      if (remaining <= 0) {
        hardcorePendingText.textContent = t('options_hardcore_unlocking_soon');
        return;
      }
      const totalSeconds = Math.floor(remaining / 1000);
      const m = Math.floor(totalSeconds / 60);
      const s = totalSeconds % 60;
      // 분·초를 따로 넘겨 문구 쪽에서 어순을 정한다 (카운트다운이라 단복수는 나누지 않는다).
      hardcorePendingText.textContent = t('options_hardcore_pending_countdown', [
        String(m),
        String(s).padStart(2, '0')
      ]);
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
    alert(t('options_action_failed', [error.message]));
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
  const { data: streak } = await supabase
    .from('streaks')
    .select('current_streak')
    .eq('user_id', user.id)
    .maybeSingle();
  const currentStreak = streak?.current_streak || 0;

  // 영어에서도 "your 1-day streak / your 5-day streak"로 형태가 같아 단복수를 나누지 않는다.
  const streakWarning =
    currentStreak > 0 ? t('options_hardcore_off_streak_warning', [String(currentStreak)]) : '';
  if (!confirm(t('options_hardcore_off_confirm', [streakWarning]))) return;

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
    removeButton.textContent = t('action_delete');

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
const DAY_LABEL_KEYS = [
  'weekday_short_sun',
  'weekday_short_mon',
  'weekday_short_tue',
  'weekday_short_wed',
  'weekday_short_thu',
  'weekday_short_fri',
  'weekday_short_sat'
];

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
    labelInput.placeholder = t('options_schedule_label_placeholder');
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
    enabledLabel.appendChild(document.createTextNode(t('options_schedule_enabled')));

    const removeButton = document.createElement('button');
    removeButton.className = 'btn-secondary';
    removeButton.textContent = t('action_delete');
    removeButton.addEventListener('click', () => {
      scheduledBlocks.splice(idx, 1);
      renderScheduledBlocks();
    });

    header.appendChild(labelInput);
    header.appendChild(enabledLabel);
    header.appendChild(removeButton);

    const daysRow = document.createElement('div');
    daysRow.className = 'schedule-days';
    DAY_LABEL_KEYS.forEach((labelKey, dayIdx) => {
      const dayLabel = document.createElement('label');
      dayLabel.className = 'day-toggle-chip';
      const dayInput = document.createElement('input');
      dayInput.type = 'checkbox';
      dayInput.checked = !!block.days[dayIdx];
      dayInput.addEventListener('change', () => {
        scheduledBlocks[idx].days[dayIdx] = dayInput.checked ? 1 : 0;
      });
      dayLabel.appendChild(dayInput);
      dayLabel.appendChild(document.createTextNode(t(labelKey)));
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
    label: t('options_schedule_default_label'),
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

  document.getElementById('dailyLimitInput').value = settings.daily_limit_ms
    ? settings.daily_limit_ms / 60000
    : 0;

  document.querySelectorAll('.day-limit-row').forEach((row) => {
    const day = row.dataset.day;
    const val = settings.daily_limit_by_day?.[day];
    row.querySelector('input').value = val === undefined ? 30 : val;
  });

  // 0 = 제한 없음 (schema.sql의 shorts_limit_ms 주석과 같은 컨벤션). 서버에 컬럼이 없던 시절의
  // 행이면 undefined로 내려오는데, 그것도 0(제한 없음)으로 보여주는 게 맞다.
  document.getElementById('shortsLimitInput').value = settings.shorts_limit_ms
    ? settings.shorts_limit_ms / 60000
    : 0;

  document.getElementById('alwaysBlockShortsToggle').checked = !!settings.always_block_shorts;

  whitelist = Array.isArray(settings.whitelist) ? [...settings.whitelist] : [];
  renderWhitelist();

  document.getElementById('emergencyUsesInput').value = settings.emergency_config?.dailyUses ?? 3;
  document.getElementById('emergencyResetSelect').value =
    settings.emergency_config?.resetFrequency || 'daily';

  document.getElementById('alarmIntervalInput').value = settings.alarm_interval_minutes ?? 0;
  document.getElementById('alarmMilestonesToggle').checked =
    settings.alarm_milestones_enabled !== false;

  scheduledBlocks = Array.isArray(settings.scheduled_blocks)
    ? settings.scheduled_blocks.map((b) => ({
        ...b,
        days: Array.isArray(b.days) ? [...b.days] : [1, 1, 1, 1, 1, 1, 1]
      }))
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
    // 입력이 비어 있으면 Number('')가 NaN이라 bigint 컬럼 upsert가 통째로 실패한다 — 0(제한 없음)으로 접는다.
    shorts_limit_ms:
      Math.max(0, Number(document.getElementById('shortsLimitInput').value) || 0) * 60000,
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
  statusEl.textContent = t('options_saving');

  const { error } = await supabase
    .from('settings')
    .upsert({ user_id: user.id, ...settings, updated_at: new Date().toISOString() });
  if (error) {
    statusEl.textContent = t('options_save_failed', [error.message]);
    return;
  }

  currentSettings = { ...currentSettings, ...settings };
  await setStorage({ settingsCache: currentSettings });
  await chrome.runtime.sendMessage({ action: 'settingsUpdated' });
  statusEl.textContent = t('options_saved');
  setTimeout(() => (statusEl.textContent = ''), 2000);
});

// --- 동기화 상태 / 진단 ---
// 백그라운드가 남긴 실패 링버퍼(lib/diagnosticsStore.js)를 그대로 보여준다. 여기 뜨는 값은
// 시각·종류·짧은 코드·반복 횟수뿐이라 복사해서 남에게 보내도 계정이 특정되지 않는다
// (무엇을 걸러내는지는 lib/syncDiagnostics.js 맨 위 주석 참고).

const diagnosticsLastSuccessEl = document.getElementById('diagnosticsLastSuccess');
const diagnosticsListEl = document.getElementById('diagnosticsList');
const diagnosticsStatusEl = document.getElementById('diagnosticsStatus');

let diagnosticsSnapshot = { events: [], lastSuccessAtMillis: null };

// formatDiagnosticTime은 읽을 수 없는 값이면 null을 준다 — 어떤 말로 적을지는 화면이 정한다.
function formatTimeLabel(millis) {
  return formatDiagnosticTime(millis) ?? t('common_unknown');
}

function lastSuccessLabel(millis) {
  return millis === null ? t('common_none') : formatTimeLabel(millis);
}

function renderDiagnosticsRow(event) {
  const row = document.createElement('div');
  row.className = 'diagnostics-row';

  const time = document.createElement('span');
  time.className = 'diag-time';
  time.textContent = formatTimeLabel(event.atMillis);

  const kind = document.createElement('span');
  kind.className = 'diag-kind';
  // 모르는 종류(예전 버전이 남긴 값)는 번역할 이름이 없으니 저장된 값을 그대로 보여준다.
  const kindKey = diagnosticKindMessageKey(event.kind);
  kind.textContent = kindKey ? t(kindKey) : event.kind;

  // 코드는 오류 메시지에서 뽑아낸 값이라 innerHTML로 끼워 넣지 않는다(화이트리스트 렌더와 같은 원칙).
  const code = document.createElement('span');
  code.className = 'diag-code';
  code.textContent = event.code;

  row.append(time, kind, code);

  if (event.count > 1) {
    const count = document.createElement('span');
    count.className = 'diag-count';
    count.textContent = `x${event.count}`;
    row.appendChild(count);
  }
  return row;
}

async function renderDiagnostics() {
  diagnosticsSnapshot = await readDiagnostics();
  const { events, lastSuccessAtMillis } = diagnosticsSnapshot;

  diagnosticsLastSuccessEl.textContent = lastSuccessLabel(lastSuccessAtMillis);

  diagnosticsListEl.replaceChildren();
  if (events.length === 0) {
    const empty = document.createElement('div');
    empty.className = 'diagnostics-empty';
    empty.textContent = t('options_diagnostics_empty');
    diagnosticsListEl.appendChild(empty);
    return;
  }
  events.forEach((event) => diagnosticsListEl.appendChild(renderDiagnosticsRow(event)));
}

document.getElementById('refreshDiagnosticsButton').addEventListener('click', async () => {
  await renderDiagnostics();
  diagnosticsStatusEl.textContent = '';
});

document.getElementById('copyDiagnosticsButton').addEventListener('click', async () => {
  // 방금 실패가 더 쌓였을 수 있으니 화면에 그려둔 값이 아니라 저장소를 다시 읽고 복사한다.
  await renderDiagnostics();
  // 줄 순서와 "무엇을 내보내고 무엇을 버리는가"는 순수 함수가 정하고, 머리말 문구만 여기서 넣는다.
  const report = buildDiagnosticsReport(diagnosticsSnapshot.events, {
    title: t('diag_report_title'),
    lastSuccess: t('diag_report_last_success', [
      lastSuccessLabel(diagnosticsSnapshot.lastSuccessAtMillis)
    ]),
    noFailures: t('diag_report_no_failures'),
    failureCount: tCount('diag_report_failure_count', diagnosticsSnapshot.events.length)
  });
  try {
    await navigator.clipboard.writeText(report);
    diagnosticsStatusEl.textContent = t('options_diagnostics_copied');
  } catch {
    // 클립보드 권한이 막혀 있으면(포커스 없음 등) 조용히 실패한다 — 이 기능이 진단 도구인데
    // 여기서까지 소리 없이 넘어가면 곤란하다.
    diagnosticsStatusEl.textContent = t('options_diagnostics_copy_failed');
  }
  setTimeout(() => (diagnosticsStatusEl.textContent = ''), 3000);
});

document.getElementById('clearDiagnosticsButton').addEventListener('click', async () => {
  await clearDiagnostics();
  await renderDiagnostics();
  diagnosticsStatusEl.textContent = t('options_diagnostics_cleared');
  setTimeout(() => (diagnosticsStatusEl.textContent = ''), 3000);
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

// 이메일 없이 가입된 계정(소셜 등)이면 확인 문구로 짧은 낱말 하나를 쓴다(ko '삭제' / en 'delete').
const DELETE_FALLBACK_PHRASE = t('options_delete_fallback_phrase');
let deleteConfirmPhrase = '';
let deleteInFlight = false;

function normalizeDeletePhrase(value) {
  return (value || '').trim().toLowerCase();
}

function renderDeleteConfirmState() {
  const matched =
    !!deleteConfirmPhrase &&
    normalizeDeletePhrase(deleteAccountConfirmInput.value) ===
      normalizeDeletePhrase(deleteConfirmPhrase);
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
  if (!confirm(t('options_delete_confirm_message'))) return;

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
  if (
    normalizeDeletePhrase(deleteAccountConfirmInput.value) !==
    normalizeDeletePhrase(deleteConfirmPhrase)
  )
    return;

  deleteInFlight = true;
  confirmDeleteAccountButton.disabled = true;
  setDeleteControlsDisabled(true);
  deleteAccountStatus.textContent = t('options_deleting_account');

  const { error } = await supabase.functions.invoke('delete-account');
  if (error) {
    // 서버가 거절했으면 로컬은 아무것도 건드리지 않는다. 로그인 상태 그대로 두고 다시 시도할 수 있게.
    deleteInFlight = false;
    setDeleteControlsDisabled(false);
    deleteAccountStatus.textContent = t('options_delete_failed', [error.message]);
    renderDeleteConfirmState();
    return;
  }

  // 서버 세션은 이미 죽었으니 네트워크를 타지 않는 로컬 로그아웃만 한다.
  await supabase.auth.signOut({ scope: 'local' });
  // chrome.storage.local엔 이 확장이 쓰는 값(설정 캐시 · 사용/긴급 기록 · 집중/차단 상태 ·
  // 알람 상태 · 동기화 마커 · 진단 기록 · Supabase 세션)만 들어 있어서 통째로 비우는 게 가장 확실하다.
  // 진단 기록도 지운 계정과의 통신 기록이라 여기서 같이 사라져야 한다(로그아웃 쪽과 같은 이유).
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

  await renderDiagnostics();

  const { data } = await supabase.from('settings').select('*').eq('user_id', user.id).maybeSingle();
  fillForm(
    data || {
      daily_limit_ms: 30 * 60000,
      daily_limit_by_day: {},
      daily_limit_reset_frequency: 'daily',
      shorts_limit_ms: 0,
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
