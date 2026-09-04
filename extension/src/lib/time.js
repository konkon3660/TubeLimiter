// 날짜/시간 관련 헬퍼 함수

// 하루 기준을 자정(00시)이 아닌 새벽 4시로 잡는다. 새벽 4시 이전 시청은 전날 사용량으로 취급.
const DAY_CUTOFF_HOUR = 4;

function getEffectiveNow() {
  const now = new Date();
  return new Date(now.getTime() - DAY_CUTOFF_HOUR * 60 * 60 * 1000);
}

export function getTodayDate() {
  return formatDate(getEffectiveNow());
}

export function getYesterdayDate() {
  const d = getEffectiveNow();
  d.setDate(d.getDate() - 1);
  return formatDate(d);
}

export function addDaysToDate(dateStr, days) {
  const [year, month, day] = dateStr.split('-').map(Number);
  const d = new Date(year, month - 1, day);
  d.setDate(d.getDate() + days);
  return formatDate(d);
}

export function formatDate(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function getWeekStartDate() {
  const now = getEffectiveNow();
  const dayOfWeek = now.getDay(); // Sunday - 0
  const diff = now.getDate() - dayOfWeek + (dayOfWeek === 0 ? -6 : 1); // Monday 기준
  const weekStart = new Date(now);
  weekStart.setDate(diff);
  return formatDate(weekStart);
}

export function getMonthStartDate() {
  const now = getEffectiveNow();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  return `${year}-${month}-01`;
}
