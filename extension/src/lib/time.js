// 날짜/시간 관련 헬퍼 함수

export function getTodayDate() {
  return formatDate(new Date());
}

export function getYesterdayDate() {
  const d = new Date();
  d.setDate(d.getDate() - 1);
  return formatDate(d);
}

export function formatDate(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function getWeekStartDate() {
  const now = new Date();
  const dayOfWeek = now.getDay(); // Sunday - 0
  const diff = now.getDate() - dayOfWeek + (dayOfWeek === 0 ? -6 : 1); // Monday 기준
  const weekStart = new Date(now);
  weekStart.setDate(diff);
  return formatDate(weekStart);
}

export function getMonthStartDate() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  return `${year}-${month}-01`;
}
