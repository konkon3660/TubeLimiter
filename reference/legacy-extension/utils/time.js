// utils/time.js
// 시간 관련 유틸리티 함수

export function getTodayDate() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function getWeekStartDate() {
  const now = new Date();
  const dayOfWeek = now.getDay(); // Sunday - 0, Monday - 1, ..., Saturday - 6
  const diff = now.getDate() - dayOfWeek + (dayOfWeek === 0 ? -6 : 1); // Adjust to Monday
  const weekStartDate = new Date(now.setDate(diff));
  const year = weekStartDate.getFullYear();
  const month = String(weekStartDate.getMonth() + 1).padStart(2, '0');
  const day = String(weekStartDate.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function getMonthStartDate() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  return `${year}-${month}-01`;
}