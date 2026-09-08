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
import { resolveLimitForDate } from '../lib/limitHistory.js';
import { mergeHistories } from '../lib/historyMerge.js';
import { applyI18n, pluralMessageKey, t, tCount } from '../lib/i18n.js';

applyI18n();

const signedOutView = document.getElementById('signedOutView');
const signedInView = document.getElementById('signedInView');

const CHART_RANGE_OPTIONS = [7, 14, 30];
let chartRangeDays = 14;
let chartMode = 'minutes'; // 'minutes' | 'percent' — 세션 중에만 유지, 기본값은 항상 '분'
let usageChartInstance = null;
let hourlyChartInstance = null;

// 레벨 티어의 칭호. gamification.js는 key만 돌려준다(그쪽은 chrome.* 없이 도는 순수 로직) —
// 키를 문자열로 조립하지 않고 표로 적어두면 이 파일만 읽어도 어떤 문구가 쓰이는지 보인다.
const TIER_MESSAGE_KEYS = {
  seed: 'tier_seed',
  trainee: 'tier_trainee',
  skilled: 'tier_skilled',
  master: 'tier_master',
  legend: 'tier_legend'
};

// 히트맵(28일)과 막대그래프(최대 30일)를 다 채우려면 30일이면 충분하다. 더 길게 읽어봐야
// 화면에 그릴 곳이 없고, select 응답만 커진다.
const HISTORY_LOOKBACK_DAYS = 30;

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

/**
 * 서버(daily_usage)에서 최근 N일치 행을 한 번에 읽는다. 실패하면 null — 호출부는 로컬 기록만으로
 * 기존과 똑같이 그린다(대시보드가 통째로 비는 것보다 이 기기 기록이라도 보이는 게 낫다).
 *
 * 날짜 경계: daily_usage.date는 확장/안드로이드가 각자 lib/time.js의 getTodayDate()
 * (새벽 4시 컷오프)로 만든 'YYYY-MM-DD'를 그대로 써 넣은 값이다. 그래서 여기서도 같은
 * 컨벤션으로 만든 lastNDates()의 시작일을 gte 경계로 쓰면 로컬 기록 키와 어긋나지 않는다.
 * 서버 쪽에서 UTC 자정 기준으로 다시 계산하면 오히려 하루씩 밀린다.
 */
async function fetchServerHistory(userId, days) {
  const since = addDaysToDate(getTodayDate(), -(days - 1));
  try {
    const { data, error } = await supabase
      .from('daily_usage')
      // emergency_uses까지 읽어야 다른 기기에서만 긴급 시청을 쓴 날이 히트맵에서 걸러진다.
      // 발급만 받고 안 본 날은 emergency_ms가 0이라 시간 컬럼만으로는 알 수 없다
      // (병합 규칙은 lib/historyMerge.js, 안드로이드 sync/HistoryMerge.kt와 같은 계약).
      .select('date, usage_ms, shorts_ms, emergency_ms, emergency_uses')
      .eq('user_id', userId)
      .gte('date', since);
    if (error) {
      console.error('[TubeLimiter] daily_usage 기록 조회 실패:', error);
      return null;
    }
    return data || [];
  } catch (e) {
    // 오프라인이면 fetch 자체가 throw 한다 — 여기서 막지 않으면 init()이 통째로 죽는다.
    console.error('[TubeLimiter] daily_usage 기록 조회 실패:', e);
    return null;
  }
}

function setHistoryNote(message) {
  const el = document.getElementById('historySourceNote');
  if (el) el.textContent = message;
}

function formatMinutes(ms) {
  if (!Number.isFinite(ms)) return t('common_unlimited');
  return tCount('minutes', Math.round(ms / 60000));
}

/**
 * 히트맵 툴팁에 들어갈 판정 근거 한 줄. 긴급 시청분은 스트릭 판정에서 빠지므로(gamification.js의
 * isDaySuccess) 총 사용시간만 보여주면 초과로 뜬 이유를 읽을 수 없다 - 뺀 뒤의 값과 한도를 같이 적는다.
 *
 * emergencyUses가 null이면 "로컬에도 서버에도 그날 횟수가 없다"는 뜻이다 - 이 기기에 기록이 없고,
 * 서버 행의 emergency_uses도 비어 있는(그 컬럼이 생기기 전에 쓰인) 날. 그때 0회로 적으면 없는
 * 사실을 지어내는 셈이라 횟수만 빼고 시간만 보여준다.
 *
 * limitEstimated도 같은 이유로 밝힌다: 한도 기록(limit_history)이 없는 옛 날짜는 지금 설정으로
 * 근사 판정할 수밖에 없는데, 그걸 실제 한도인 것처럼 적으면 "설정을 바꾸면 과거 판정이 바뀐다"는
 * 사실이 화면에서 안 보인다.
 */
function formatDayBreakdown(usageMs, limitMs, emergencyMs, emergencyUses, limitEstimated = false) {
  const ownMs = Math.max(0, usageMs - Math.max(0, emergencyMs));
  const parts = [t('dashboard_breakdown_used', [formatMinutes(usageMs)])];
  if (emergencyMs > 0 || emergencyUses > 0) {
    parts.push(
      emergencyUses === null
        ? t('dashboard_breakdown_emergency', [formatMinutes(emergencyMs)])
        : t(pluralMessageKey('dashboard_breakdown_emergency_with_uses', emergencyUses), [
            formatMinutes(emergencyMs),
            String(emergencyUses)
          ])
    );
    parts.push(t('dashboard_breakdown_counted', [formatMinutes(ownMs)]));
  }
  parts.push(
    limitEstimated
      ? t('dashboard_breakdown_limit_estimated', [formatMinutes(limitMs)])
      : t('dashboard_breakdown_limit', [formatMinutes(limitMs)])
  );
  return parts.join(' · ');
}

/** 그날의 판정을 한 마디로. 툴팁 첫 줄에 날짜와 나란히 들어간다. */
function dayStatusLabel(usage, limit, emergencyMs, emergencyUses) {
  if (isPerfectDay(usage, limit, emergencyMs, emergencyUses ?? 0))
    return t('dashboard_status_perfect');
  if (!isDaySuccess(usage, limit, emergencyMs)) return t('dashboard_status_over');
  if (emergencyUses > 0) {
    return t(pluralMessageKey('dashboard_status_success_with_emergency', emergencyUses), [
      String(emergencyUses)
    ]);
  }
  return t('dashboard_status_success');
}

async function renderHeatmap(usageHistory, settings, emergencyHistory = {}, limitHistory = {}) {
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
      // 그날 실제로 적용됐던 한도가 남아 있으면 그 값으로 판정한다. 기록이 없는 옛 날짜만
      // 지금 설정으로 근사 판정하고(estimated), 그런 날은 툴팁에 추정이라고 밝힌다.
      const { limitMs: limit, estimated: limitEstimated } = resolveLimitForDate(
        limitHistory,
        settings,
        date
      );
      // 스트릭과 같은 규칙으로 판정한다: 긴급 시청 시간은 빼고 보되(실패 아님),
      // 긴급 시청을 쓴 날은 완벽한 날이 아니라 한 단계 옅게 표시된다.
      const emergency = emergencyHistory[date] || {};
      const emergencyMs = emergency.ms || 0;
      // null = 로컬에도 서버에도 횟수가 없는 날(emergency_uses 컬럼 이전에 쓰인 서버 행).
      // 판정할 때는 0회로 보되 툴팁에는 적지 않는다. 다른 기기에서 쓴 횟수는 이제 서버
      // emergency_uses로 병합되므로(lib/historyMerge.js) 여기서 null이 되는 날은 거의 없다.
      const emergencyUses = emergency.uses ?? null;
      // 판정 근거를 툴팁에 그대로 적는다. 초과로 뜬 날이 "긴급 시청분을 빼고도 넘긴" 건지
      // "긴급 기록이 없는" 건지 화면에서 바로 구분되지 않으면, 규칙을 아는 사람만 읽을 수 있는
      // 히트맵이 된다 (실제로 긴급 시청을 쓴 날이 왜 실패인지 묻는 일이 있었다).
      const breakdown = formatDayBreakdown(
        usage,
        limit,
        emergencyMs,
        emergencyUses,
        limitEstimated
      );
      if (isPerfectDay(usage, limit, emergencyMs, emergencyUses ?? 0)) {
        cell.classList.add('perfect');
      } else if (isDaySuccess(usage, limit, emergencyMs)) {
        cell.classList.add('success');
      } else {
        cell.classList.add('fail');
      }
      // "날짜 · 판정" 다음 줄에 근거. 세 조각의 순서가 언어마다 다를 수 있어 통째로 한 문구로 만든다.
      cell.title = t('dashboard_day_tooltip', [
        date,
        dayStatusLabel(usage, limit, emergencyMs, emergencyUses),
        breakdown
      ]);
    }
    if (date === today) cell.classList.add('today');
    container.appendChild(cell);
  });
}

// 뱃지는 이모지 + 짧은 설명 한 줄. 문구가 번역돼 들어오므로 innerHTML 대신 DOM으로 짓는다.
function createBadge(emoji, className, caption, title) {
  const item = document.createElement('div');
  item.className = className;
  if (title) item.title = title;
  const captionEl = document.createElement('span');
  captionEl.className = 'badge-caption';
  captionEl.textContent = caption;
  item.append(emoji, captionEl);
  return item;
}

async function renderBadges(userId) {
  const { data: unlocked } = await supabase
    .from('achievements')
    .select('key')
    .eq('user_id', userId);
  const unlockedKeys = new Set((unlocked || []).map((r) => r.key));
  const grid = document.getElementById('badgeGrid');
  grid.innerHTML = '';
  STREAK_MILESTONES.forEach((days) => {
    const key = milestoneAchievementKey(days);
    grid.appendChild(
      createBadge(
        '🏅',
        'badge-item' + (unlockedKeys.has(key) ? ' unlocked' : ''),
        tCount('days', days)
      )
    );
  });
  // 완벽한 날(긴급 시청 0회) 연속 기록 뱃지 — 스트릭 뱃지와 같은 그리드에 이어서 붙인다.
  PERFECT_MILESTONES.forEach((days) => {
    const key = perfectAchievementKey(days);
    grid.appendChild(
      createBadge(
        '💎',
        'badge-item perfect-badge' + (unlockedKeys.has(key) ? ' unlocked' : ''),
        tCount('dashboard_perfect_badge_caption', days),
        tCount('dashboard_perfect_badge_title', days)
      )
    );
  });
}

function renderChart(usageHistory, settings, limitHistory = {}) {
  const dates = lastNDates(chartRangeDays);
  const ctx = document.getElementById('usageChart');
  const axisColor = '#475569';
  const gridColor = 'rgba(100, 116, 139, 0.15)';
  const isPercent = chartMode === 'percent';

  const values = dates.map((d) => {
    const usedMs = usageHistory[d] || 0;
    if (!isPercent) return Math.round(usedMs / 60000);
    // 히트맵과 같은 기준: 그날 기록된 한도가 있으면 그것으로, 없으면 지금 설정으로 근사한다.
    const { limitMs } = resolveLimitForDate(limitHistory, settings, d);
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
          label: isPercent
            ? t('dashboard_chart_label_percent')
            : t('dashboard_chart_label_minutes'),
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
      labels: Array.from({ length: 24 }, (_, hour) => t('dashboard_hour_label', [String(hour)])),
      datasets: [
        {
          label: t('dashboard_chart_label_hourly_avg'),
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

function setupChartControls(history, settings, hourlyHistory, limitHistory) {
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
    renderChart(history, settings, limitHistory);
    renderHourlyChart(hourlyHistory);
  });

  modeGroup.addEventListener('click', (e) => {
    const btn = e.target.closest('.chip');
    if (!btn) return;
    const mode = btn.dataset.mode;
    if ((mode !== 'minutes' && mode !== 'percent') || mode === chartMode) return;
    chartMode = mode;
    updateChipGroupUI(modeGroup, 'mode', chartMode);
    renderChart(history, settings, limitHistory);
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
    { limit_history },
    { dashboardChartRangeDays }
  ] = await Promise.all([
    getStorage(['usage_history']),
    getStorage(['settingsCache']),
    getStorage(['usage_history_hourly']),
    getStorage(['emergency_history']),
    getStorage(['limit_history']),
    getStorage(['dashboardChartRangeDays'])
  ]);

  if (CHART_RANGE_OPTIONS.includes(dashboardChartRangeDays)) {
    chartRangeDays = dashboardChartRangeDays;
  }

  // 히트맵과 사용 시간 그래프는 서버 기록까지 합쳐서 그린다. 로컬 usage_history만 보면
  // 재설치하거나 새 기기에서 로그인한 직후에는 히트맵이 통째로 비고, 기기 두 대를 번갈아 쓰면
  // 기기마다 다른 그래프가 나온다 — 계정 기록은 daily_usage에 이미 다 쌓여 있는데도.
  const serverRows = await fetchServerHistory(user.id, HISTORY_LOOKBACK_DAYS);
  const { usage: history, emergency: emergencyHistory } = mergeHistories(
    { usage: usage_history || {}, emergency: emergency_history || {} },
    serverRows || []
  );
  setHistoryNote(
    serverRows ? t('dashboard_history_note_merged') : t('dashboard_history_note_local_only')
  );

  const hourlyHistory = usage_history_hourly || {};
  // 서버(daily_usage)에는 한도가 올라가지 않으므로 limit_history는 로컬 기록뿐이다 —
  // 다른 기기에서만 본 날은 기록이 없어 근사 판정으로 떨어진다(툴팁에 그렇게 표시된다).
  const limitHistory = limit_history || {};
  const hardcoreMode = !!settingsCache?.hardcore_mode;
  document.getElementById('streakSection').style.display = hardcoreMode ? '' : 'none';
  document.getElementById('streakLockedHint').style.display = hardcoreMode ? 'none' : '';

  if (hardcoreMode) {
    const { data: streak } = await supabase
      .from('streaks')
      .select('*')
      .eq('user_id', user.id)
      .maybeSingle();
    const currentStreak = streak?.current_streak || 0;
    const bestStreak = streak?.best_streak || 0;
    const totalSuccess = streak?.total_success_days || 0;
    const xp = streak?.xp || 0;
    const { level, xpIntoLevel, xpForNextLevel } = getLevelProgress(xp);

    const todayLimitMs = computeLimitForDate(settingsCache, getTodayDate());
    const limitLabel = Number.isFinite(todayLimitMs)
      ? t('dashboard_daily_limit_value', [String(Math.round(todayLimitMs / 60000))])
      : t('common_unlimited');
    // 단복수는 연속 일수로 가르고, $1에는 위 한도 문구가 들어간다.
    document.getElementById('streakCaption').textContent = t(
      pluralMessageKey('dashboard_streak_caption', currentStreak),
      [limitLabel]
    );

    document.getElementById('streakNumber').textContent = currentStreak;
    document.getElementById('bestStreak').textContent = bestStreak;
    document.getElementById('totalSuccess').textContent = totalSuccess;
    document.getElementById('perfectDays').textContent = streak?.perfect_days || 0;
    document.getElementById('levelNumber').textContent = level;
    document.getElementById('xpLabel').textContent = `${xpIntoLevel}/${xpForNextLevel} XP`;
    document.getElementById('xpBarFill').style.width =
      `${Math.min(100, (xpIntoLevel / xpForNextLevel) * 100)}%`;

    const tier = getLevelTier(level);
    document.getElementById('levelTierBadge').textContent =
      `${tier.emoji} ${t(TIER_MESSAGE_KEYS[tier.key])}`;
    const streakSectionEl = document.getElementById('streakSection');
    streakSectionEl.className = streakSectionEl.className.replace(/\btier-\S+/g, '').trim();
    streakSectionEl.classList.add(`tier-${tier.key}`);

    await renderBadges(user.id);
  }

  await renderHeatmap(history, settingsCache, emergencyHistory, limitHistory);
  setupChartControls(history, settingsCache, hourlyHistory, limitHistory);
  renderChart(history, settingsCache, limitHistory);
  renderHourlyChart(hourlyHistory);
}

init();
