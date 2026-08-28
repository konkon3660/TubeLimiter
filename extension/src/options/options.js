import { setStorage } from '../lib/storage.js';
import { supabase, getCurrentUser } from '../lib/supabaseClient.js';

const signedOutView = document.getElementById('signedOutView');
const signedInView = document.getElementById('signedInView');

document.getElementById('openAuthButton').addEventListener('click', () => {
  chrome.tabs.create({ url: chrome.runtime.getURL('auth/auth.html') });
});

const byDayToggle = document.getElementById('byDayToggle');
const simpleLimitBlock = document.getElementById('simpleLimitBlock');
const byDayLimitBlock = document.getElementById('byDayLimitBlock');
byDayToggle.addEventListener('change', () => {
  simpleLimitBlock.style.display = byDayToggle.checked ? 'none' : '';
  byDayLimitBlock.style.display = byDayToggle.checked ? '' : 'none';
});

let whitelist = [];
const whitelistListEl = document.getElementById('whitelistList');
function renderWhitelist() {
  whitelistListEl.innerHTML = '';
  whitelist.forEach((entry, idx) => {
    const row = document.createElement('div');
    row.className = 'whitelist-item';
    row.innerHTML = `<input type="text" value="${entry}" data-idx="${idx}"><button class="btn-secondary" data-remove="${idx}">삭제</button>`;
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
    }
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

  await setStorage({ settingsCache: settings });
  await chrome.runtime.sendMessage({ action: 'settingsUpdated' });
  statusEl.textContent = '저장됨.';
  setTimeout(() => (statusEl.textContent = ''), 2000);
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

  const { data } = await supabase.from('settings').select('*').eq('user_id', user.id).maybeSingle();
  fillForm(
    data || {
      daily_limit_ms: 30 * 60000,
      daily_limit_by_day: {},
      daily_limit_reset_frequency: 'daily',
      always_block_shorts: false,
      whitelist: [],
      emergency_config: { dailyUses: 3, resetFrequency: 'daily' }
    }
  );
}

init();
