import { getStorage } from '../lib/storage.js';
import { getTodayDate } from '../lib/time.js';
import { supabase, getCurrentUser } from '../lib/supabaseClient.js';
import { getLevelProgress } from '../lib/gamification.js';
import { computeLimitForDate } from '../lib/limits.js';

const signedOutView = document.getElementById('signedOutView');
const signedInView = document.getElementById('signedInView');

document.getElementById('openAuthButton').addEventListener('click', () => {
  chrome.tabs.create({ url: chrome.runtime.getURL('auth/auth.html') });
});

document.getElementById('signOutButton').addEventListener('click', async () => {
  await supabase.auth.signOut();
  window.location.reload();
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

document.getElementById('emergencyButton').addEventListener('click', async () => {
  const res = await chrome.runtime.sendMessage({ action: 'requestEmergency' });
  if (!res.success) alert(res.message);
  render();
});

document.getElementById('startFocusModeButton').addEventListener('click', async () => {
  const duration = Number(document.getElementById('focusModeDurationInput').value) || 30;
  const delay = Number(document.getElementById('focusModeDelayInput').value) || 0;
  await chrome.runtime.sendMessage({ action: 'startFocusMode', duration, delay });
  render();
});
document.getElementById('stopFocusModeButton').addEventListener('click', async () => {
  await chrome.runtime.sendMessage({ action: 'stopFocusMode' });
  render();
});

function formatMinutes(ms) {
  if (!Number.isFinite(ms)) return '무제한';
  return `${Math.floor(ms / 60000)}분`;
}

async function render() {
  const user = await getCurrentUser();
  if (!user) {
    signedOutView.style.display = '';
    signedInView.style.display = 'none';
    return;
  }
  signedOutView.style.display = 'none';
  signedInView.style.display = '';

  const [{ usage_history }, { settingsCache }, { emergency_uses_today }, { focusModeActive }] = await Promise.all([
    getStorage(['usage_history']),
    getStorage(['settingsCache']),
    getStorage(['emergency_uses_today']),
    getStorage(['focusModeActive'])
  ]);

  const todayUsage = (usage_history || {})[getTodayDate()] || 0;
  const limitMs = computeLimitForDate(settingsCache, getTodayDate());
  const remaining = Number.isFinite(limitMs) ? Math.max(0, limitMs - todayUsage) : Infinity;

  document.getElementById('usageTime').textContent = formatMinutes(todayUsage);
  document.getElementById('untilBlock').textContent = formatMinutes(remaining);

  const progressCircle = document.getElementById('progressCircle');
  if (Number.isFinite(limitMs) && limitMs > 0) {
    const percentage = Math.min(100, Math.max(0, (todayUsage / limitMs) * 100));
    const degrees = (percentage / 100) * 360;
    progressCircle.style.background = `conic-gradient(var(--primary-color) ${degrees}deg, var(--light-gray) ${degrees}deg)`;
  } else {
    progressCircle.style.background = 'var(--primary-color)';
  }
  document.getElementById('emergencyCount').textContent = `${emergency_uses_today ?? (settingsCache?.emergency_config?.dailyUses ?? 3)}회`;

  const focusBadge = document.getElementById('focusModeStatus');
  focusBadge.innerHTML = focusModeActive
    ? '<span class="status-badge status-active">활성</span>'
    : '<span class="status-badge status-inactive">비활성</span>';

  const { data: streak } = await supabase.from('streaks').select('*').eq('user_id', user.id).maybeSingle();
  const currentStreak = streak?.current_streak || 0;
  const bestStreak = streak?.best_streak || 0;
  const xp = streak?.xp || 0;
  const { level, xpIntoLevel, xpForNextLevel } = getLevelProgress(xp);

  document.getElementById('streakNumber').textContent = currentStreak;
  document.getElementById('bestStreak').textContent = bestStreak;
  document.getElementById('levelNumber').textContent = level;
  document.getElementById('xpLabel').textContent = `${xpIntoLevel}/${xpForNextLevel} XP`;
  document.getElementById('xpBarFill').style.width = `${Math.min(100, (xpIntoLevel / xpForNextLevel) * 100)}%`;
}

chrome.runtime.onMessage.addListener((request) => {
  if (request.action === 'updateUI') render();
});

render();
