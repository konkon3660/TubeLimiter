import { getStorage, setStorage } from '../lib/storage.js';
import { getTodayDate, addDaysToDate } from '../lib/time.js';
import { supabase, getCurrentUser } from '../lib/supabaseClient.js';
import {
  getLevelProgress,
  getLevelTier,
  STREAK_MILESTONES,
  PERFECT_MILESTONES,
  milestoneAchievementKey,
  perfectAchievementKey,
  isDaySuccess,
  isPerfectDay
} from '../lib/gamification.js';
import { computeLimitForDate } from '../lib/limits.js';

const signedOutView = document.getElementById('signedOutView');
const signedInView = document.getElementById('signedInView');

const CHART_RANGE_OPTIONS = [7, 14, 30];
let chartRangeDays = 14;
let chartMode = 'minutes'; // 'minutes' | 'percent' — 세션 중에만 유지, 기본값은 항상 '분'
let usageChartInstance = null;
let hourlyChartInstance = null;

// usage_history는 lib/time.js의 getTodayDate() (새벽 4시 기준) 키로 저장되므로,
// 여기서도 같은 기준으로 날짜를 생성해야 자정~새벽 4시 사이에 히트맵/차트가 어긋나지 않는다.
function lastNDates(n) {
  const today = getTodayDate();
  const dates = [];
  for (let i = n - 1; i >= 0; i -= 1) {
    dates.push(addDaysToDate(today, -i));
  }
  return dates;
}

async function renderHeatmap(usageHistory, settings, emergencyHistory = {}) {
  const dates = lastNDates(28);
  const today = getTodayDate();
  const container = document.getElementById('heatmap');
  container.innerHTML = '';
  dates.forEach((date) => {
    const cell = document.createElement('div');
    cell.className = 'day-cell';
    cell.title = date;
    const usage = usageHistory[date];
    if (usage !== undefined) {
      const limit = computeLimitForDate(settings, date);
      // 스트릭과 같은 규칙으로 판정한다: 긴급 시청 시간은 빼고 보되(실패 아님),
      // 긴급 시청을 쓴 날은 완벽한 날이 아니라 한 단계 옅게 표시된다.
      const emergency = emergencyHistory[date] || {};
      const emergencyMs = emergency.ms || 0;
      const emergencyUses = emergency.uses || 0;
      if (isPerfectDay(usage, limit, emergencyMs, emergencyUses)) {
        cell.classList.add('perfect');
        cell.title = `${date} · 완벽한 날`;
      } else if (isDaySuccess(usage, limit, emergencyMs)) {
        cell.classList.add('success');
        if (emergencyUses > 0) cell.title = `${date} · 긴급 시청 ${emergencyUses}회`;
      } else {
        cell.classList.add('fail');
      }
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
  // 완벽한 날(긴급 시청 0회) 연속 기록 뱃지 — 스트릭 뱃지와 같은 그리드에 이어서 붙인다.
  PERFECT_MILESTONES.forEach((days) => {
    const key = perfectAchievementKey(days);
    const item = document.createElement('div');
    item.className = 'badge-item perfect-badge' + (unlockedKeys.has(key) ? ' unlocked' : '');
    item.title = `긴급 시청 없이 ${days}일 연속 한도 준수`;
    item.innerHTML = `💎<span class="badge-caption">완벽 ${days}일</span>`;
    grid.appendChild(item);
  });
}

function renderChart(usageHistory, settings) {
  const dates = lastNDates(chartRangeDays);
  const ctx = document.getElementById('usageChart');
  const axisColor = '#475569';
  const gridColor = 'rgba(100, 116, 139, 0.15)';
  const isPercent = chartMode === 'percent';

  const values = dates.map((d) => {
    const usedMs = usageHistory[d] || 0;
    if (!isPercent) return Math.round(usedMs / 60000);
    const limitMs = computeLimitForDate(settings, d);
    // 한도가 무제한(Infinity)인 날은 "한도 대비 사용률"이 정의되지 않으므로 0%로 표시한다.
    if (!Number.isFinite(limitMs) || limitMs <= 0) return 0;
    return Math.round((usedMs / limitMs) * 1000) / 10; // 소수 첫째 자리까지
  });

  if (usageChartInstance) {
    usageChartInstance.destroy();
    usageChartInstance = null;
  }
  usageChartInstance = new Chart(ctx, {
    type: 'bar',
    data: {
      labels: dates.map((d) => d.slice(5)),
      datasets: [
        {
          label: isPercent ? '한도 대비 사용률(%)' : '사용 시간(분)',
          data: values,
          backgroundColor: '#f97316',
          borderRadius: 4
        }
      ]
    },
    options: {
      scales: {
        y: {
          beginAtZero: true,
          // 퍼센트 모드에서는 100%를 넘는 날도 의미 있는 정보라 강제로 자르지 않는다.
          // suggestedMax는 "최소 이만큼은 보여달라"는 힌트라 값이 이를 넘으면 축이 자동으로 늘어난다.
          suggestedMax: isPercent ? 100 : undefined,
          ticks: {
            color: axisColor,
            callback: isPercent ? (value) => `${value}%` : undefined
          },
          grid: { color: gridColor }
        },
        x: { ticks: { color: axisColor }, grid: { display: false } }
      },
      plugins: { legend: { display: false } }
    }
  });
}

function renderHourlyChart(usageHistoryHourly) {
  const dates = lastNDates(chartRangeDays);
  const hourTotalsMs = new Array(24).fill(0);
  dates.forEach((date) => {
    const dayBucket = usageHistoryHourly[date];
    if (!dayBucket) return; // 이 기능 출시 이전 날짜이거나 기록이 없는 날 — 0으로 취급
    for (let hour = 0; hour < 24; hour += 1) {
      hourTotalsMs[hour] += dayBucket[String(hour)] || 0;
    }
  });
  // 선택한 기간에 걸친 "시간대별 평균 사용 시간"으로 표시 — 총합이면 7일/30일 선택에 따라
  // 막대 높이가 크게 달라져 패턴 비교가 어려워진다.
  const avgMinutesByHour = hourTotalsMs.map((ms) => Math.round(ms / dates.length / 60000));

  const ctx = document.getElementById('hourlyChart');
  const axisColor = '#475569';
  const gridColor = 'rgba(100, 116, 139, 0.15)';

  if (hourlyChartInstance) {
    hourlyChartInstance.destroy();
    hourlyChartInstance = null;
  }
  hourlyChartInstance = new Chart(ctx, {
    type: 'bar',
    data: {
      labels: Array.from({ length: 24 }, (_, hour) => `${hour}시`),
      datasets: [
        {
          label: '평균 사용 시간(분)',
          data: avgMinutesByHour,
          backgroundColor: '#4f46e5',
          borderRadius: 4
        }
      ]
    },
    options: {
      scales: {
        y: { beginAtZero: true, ticks: { color: axisColor }, grid: { color: gridColor } },
        x: { ticks: { color: axisColor }, grid: { display: false } }
      },
      plugins: { legend: { display: false } }
    }
  });
}

function updateChipGroupUI(groupEl, dataAttr, activeValue) {
  groupEl.querySelectorAll('.chip').forEach((btn) => {
    btn.classList.toggle('active', btn.dataset[dataAttr] === String(activeValue));
  });
}

function setupChartControls(history, settings, hourlyHistory) {
  const rangeGroup = document.getElementById('chartRangeGroup');
  const modeGroup = document.getElementById('chartModeGroup');

  updateChipGroupUI(rangeGroup, 'range', chartRangeDays);
  updateChipGroupUI(modeGroup, 'mode', chartMode);

  rangeGroup.addEventListener('click', async (e) => {
    const btn = e.target.closest('.chip');
    if (!btn) return;
    const range = Number(btn.dataset.range);
    if (!CHART_RANGE_OPTIONS.includes(range) || range === chartRangeDays) return;
    chartRangeDays = range;
    updateChipGroupUI(rangeGroup, 'range', chartRangeDays);
    await setStorage({ dashboardChartRangeDays: chartRangeDays });
    renderChart(history, settings);
    renderHourlyChart(hourlyHistory);
  });

  modeGroup.addEventListener('click', (e) => {
    const btn = e.target.closest('.chip');
    if (!btn) return;
    const mode = btn.dataset.mode;
    if ((mode !== 'minutes' && mode !== 'percent') || mode === chartMode) return;
    chartMode = mode;
    updateChipGroupUI(modeGroup, 'mode', chartMode);
    renderChart(history, settings);
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

  const [
    { usage_history },
    { settingsCache },
    { usage_history_hourly },
    { emergency_history },
    { dashboardChartRangeDays }
  ] = await Promise.all([
    getStorage(['usage_history']),
    getStorage(['settingsCache']),
    getStorage(['usage_history_hourly']),
    getStorage(['emergency_history']),
    getStorage(['dashboardChartRangeDays'])
  ]);

  if (CHART_RANGE_OPTIONS.includes(dashboardChartRangeDays)) {
    chartRangeDays = dashboardChartRangeDays;
  }

  const history = usage_history || {};
  const hourlyHistory = usage_history_hourly || {};
  const emergencyHistory = emergency_history || {};
  const hardcoreMode = !!settingsCache?.hardcore_mode;
  document.getElementById('streakSection').style.display = hardcoreMode ? '' : 'none';
  document.getElementById('streakLockedHint').style.display = hardcoreMode ? 'none' : '';

  if (hardcoreMode) {
    const { data: streak } = await supabase.from('streaks').select('*').eq('user_id', user.id).maybeSingle();
    const currentStreak = streak?.current_streak || 0;
    const bestStreak = streak?.best_streak || 0;
    const totalSuccess = streak?.total_success_days || 0;
    const xp = streak?.xp || 0;
    const { level, xpIntoLevel, xpForNextLevel } = getLevelProgress(xp);

    const todayLimitMs = computeLimitForDate(settingsCache, getTodayDate());
    const limitLabel = Number.isFinite(todayLimitMs) ? `하루 ${Math.round(todayLimitMs / 60000)}분` : '무제한';
    document.getElementById('streakLimitLabel').textContent = limitLabel;

    document.getElementById('streakNumber').textContent = currentStreak;
    document.getElementById('bestStreak').textContent = bestStreak;
    document.getElementById('totalSuccess').textContent = totalSuccess;
    document.getElementById('perfectDays').textContent = streak?.perfect_days || 0;
    document.getElementById('levelNumber').textContent = level;
    document.getElementById('xpLabel').textContent = `${xpIntoLevel}/${xpForNextLevel} XP`;
    document.getElementById('xpBarFill').style.width = `${Math.min(100, (xpIntoLevel / xpForNextLevel) * 100)}%`;

    const tier = getLevelTier(level);
    document.getElementById('levelTierBadge').textContent = `${tier.emoji} ${tier.title}`;
    const streakSectionEl = document.getElementById('streakSection');
    streakSectionEl.className = streakSectionEl.className.replace(/\btier-\S+/g, '').trim();
    streakSectionEl.classList.add(`tier-${tier.key}`);

    await renderBadges(user.id);
  }

  await renderHeatmap(history, settingsCache, emergencyHistory);
  setupChartControls(history, settingsCache, hourlyHistory);
  renderChart(history, settingsCache);
  renderHourlyChart(hourlyHistory);
}

init();
