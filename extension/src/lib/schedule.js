// 예약 차단(요일별 반복 시간대 자동 차단) 순수 판정 로직.
// settings.scheduled_blocks: [{ id, label, days[7](일=0), startMinute, endMinute, enabled }]
//
// startMinute/endMinute는 자정 기준 분(0~1439). endMinute <= startMinute면 자정을 넘기는
// 구간이고, 그 구간은 days가 가리키는 "시작 요일"에 속한다 (종료 요일 쪽 days는 무관).
//
// 중요: 이 파일은 usage/streak 집계용 4시 컷오프(DAY_CUTOFF_HOUR, lib/time.js)와 완전히
// 무관하다. 예약 차단은 실제 벽시계 기준(Date#getDay(), Date#getHours())으로만 판정한다 -
// getTodayDate()/DAY_CUTOFF_HOUR을 여기서 쓰지 않는다 (의도적).

function minuteOfDay(date) {
  return date.getHours() * 60 + date.getMinutes();
}

function isWindowValid(w) {
  return !!w && Array.isArray(w.days) && w.days.length >= 7 &&
    typeof w.startMinute === 'number' && typeof w.endMinute === 'number';
}

/**
 * @param {Date} nowDate
 * @param {Array} windows settings.scheduled_blocks
 * @returns {{active: boolean, window: object|null}}
 */
export function isScheduleActive(nowDate, windows) {
  if (!Array.isArray(windows) || windows.length === 0) return { active: false, window: null };

  const dayIdx = nowDate.getDay();
  const yesterdayIdx = (dayIdx + 6) % 7;
  const nowMinute = minuteOfDay(nowDate);

  for (const w of windows) {
    if (!w || w.enabled === false) continue;
    if (!isWindowValid(w)) continue;

    const wraps = w.endMinute <= w.startMinute;
    if (!wraps) {
      if (w.days[dayIdx] && nowMinute >= w.startMinute && nowMinute < w.endMinute) {
        return { active: true, window: w };
      }
    } else {
      // 오늘이 시작 요일인 구간(자정까지) 또는 어제 시작해 오늘 새벽까지 이어지는 구간.
      if (w.days[dayIdx] && nowMinute >= w.startMinute) {
        return { active: true, window: w };
      }
      if (w.days[yesterdayIdx] && nowMinute < w.endMinute) {
        return { active: true, window: w };
      }
    }
  }
  return { active: false, window: null };
}

const MINUTES_PER_DAY = 24 * 60;

/**
 * 다음 예약 차단 시작까지 남은 분. 이미 활성 중이거나 예약이 없으면 null.
 * (마일스톤 알림 "10분 후 예약된 차단이 시작됩니다." 용)
 *
 * @param {Date} nowDate
 * @param {Array} windows
 * @returns {number|null}
 */
export function minutesUntilNextScheduleStart(nowDate, windows) {
  if (!Array.isArray(windows) || windows.length === 0) return null;
  if (isScheduleActive(nowDate, windows).active) return null;

  const nowDayIdx = nowDate.getDay();
  const nowMinute = minuteOfDay(nowDate);

  let best = null;
  for (const w of windows) {
    if (!w || w.enabled === false) continue;
    if (!isWindowValid(w)) continue;

    // offset 0..6은 이번 주 내 각 요일, offset 7은 "오늘 같은 요일의 다음 주" -
    // 오늘 시작 시각이 이미 지났고 이 요일만 해당하는 경우(예: 매주 수요일 한 번) 대비.
    for (let offset = 0; offset <= 7; offset++) {
      const dayIdx = (nowDayIdx + offset) % 7;
      if (!w.days[dayIdx]) continue;
      if (offset === 0 && w.startMinute <= nowMinute) continue;

      const minutesUntil = offset * MINUTES_PER_DAY - nowMinute + w.startMinute;
      if (best === null || minutesUntil < best) best = minutesUntil;
    }
  }
  return best;
}
