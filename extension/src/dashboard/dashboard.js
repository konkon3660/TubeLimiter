import { getStorage } from '../lib/storage.js';
import { formatDate } from '../lib/time.js';
import { supabase, getCurrentUser } from '../lib/supabaseClient.js';
import { getLevelProgress, STREAK_MILESTONES, milestoneAchievementKey } from '../lib/gamification.js';
import { computeLimitForDate } from '../lib/limits.js';

const signedOutView = document.getElementById('signedOutView');
const signedInView = document.getElementById('signedInView');

function lastNDates(n) {
  const dates = [];
  for (let i = n - 1; i >= 0; i -= 1) {
    const d = new Date();
    d.setDate(d.getDate() - i);
    dates.push(formatDate(d));
  }
  return dates;
}

async function renderHeatmap(usageHistory, settings) {
  const dates = lastNDates(28);
  const today = formatDate(new Date());
  const container = document.getElementById('heatmap');
  container.innerHTML = '';
  dates.forEach((date) => {
    const cell = document.createElement('div');
    cell.className = 'day-cell';
    cell.title = date;
    const usage = usageHistory[date];
    if (usage !== undefined) {
      const limit = computeLimitForDate(settings, date);
      cell.classList.add(usage <= limit ? 'success' : 'fail');
    }
    if (date === today) cell.classList.add('today');
    container.appendChild(cell);
  });
}

async function renderBadges(userId) {
  const { data: unlocked } = await supabase.from('achievements').select('key').eq('user_id', userId);
  const unlockedKeys = new Set((unlocked || []).map((r) => r.key));
  const grid = document.getElementById('badgeGrid');
  grid.innerHTML = '';
  STREAK_MILESTONES.forEach((days) => {
    const key = milestoneAchievementKey(days);
    const item = document.createElement('div');
    item.className = 'badge-item' + (unlockedKeys.has(key) ? ' unlocked' : '');
    item.innerHTML = `🏅<span class="badge-caption">${days}일</span>`;
    grid.appendChild(item);
  });
}

function renderChart(usageHistory) {
  const dates = lastNDates(14);
  const ctx = document.getElementById('usageChart');
  new Chart(ctx, {
    type: 'bar',
    data: {
      labels: dates.map((d) => d.slice(5)),
      datasets: [
        {
          label: '사용 시간(분)',
          data: dates.map((d) => Math.round((usageHistory[d] || 0) / 60000)),
          backgroundColor: '#ff7a45'
        }
      ]
    },
    options: {
      scales: { y: { beginAtZero: true } },
      plugins: { legend: { display: false } }
    }
  });
}

async function init() {
  const user = await getCurrentUser();
  if (!user) {
    signedOutView.style.display = '';
    signedInView.style.display = 'none';
    return;
  }
  signedOutView.style.display = 'none';
  signedInView.style.display = '';

  const [{ usage_history }, { settingsCache }, { data: streak }] = await Promise.all([
    getStorage(['usage_history']),
    getStorage(['settingsCache']),
    supabase.from('streaks').select('*').eq('user_id', user.id).maybeSingle()
  ]);

  const history = usage_history || {};
  const currentStreak = streak?.current_streak || 0;
  const bestStreak = streak?.best_streak || 0;
  const totalSuccess = streak?.total_success_days || 0;
  const xp = streak?.xp || 0;
  const { level, xpIntoLevel, xpForNextLevel } = getLevelProgress(xp);

  document.getElementById('streakNumber').textContent = currentStreak;
  document.getElementById('bestStreak').textContent = bestStreak;
  document.getElementById('totalSuccess').textContent = totalSuccess;
  document.getElementById('levelNumber').textContent = level;
  document.getElementById('xpLabel').textContent = `${xpIntoLevel}/${xpForNextLevel} XP`;
  document.getElementById('xpBarFill').style.width = `${Math.min(100, (xpIntoLevel / xpForNextLevel) * 100)}%`;

  await renderHeatmap(history, settingsCache);
  await renderBadges(user.id);
  renderChart(history);
}

init();
