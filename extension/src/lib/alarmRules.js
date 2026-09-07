// 알림 발화 판정 순수 로직. service-worker.js의 checkAlarms에서 "지금 무슨 알림을 띄워야
// 하는가 / 오늘 이미 띄웠는가"만 떼어냈다 — 실제 chrome.notifications 호출과 alarm_state
// 저장은 호출자 몫이다. 안드로이드 limit/AlarmRules.kt의 evaluateAlarms 대응이며,
// 한쪽 규칙을 고치면 다른 쪽도 맞춰야 한다.

/** 남은 시간이 이 값들을 밑돌 때마다 "얼마 안 남았다" 알림을 한 번씩 띄운다. */
export const ALARM_MILESTONE_MINUTES = [30, 10, 5, 1];

/** 예약 차단 시작 몇 분 전에 알릴지. 알림 문구에도 이 값이 그대로 들어간다. */
export const SCHEDULE_SOON_LEAD_MINUTES = 10;

/**
 * 알림 종류. 문구는 여기서 만들지 않는다 — 이 파일은 chrome.*를 모르는 순수 판정이고, 실제
 * 문구는 호출자(service-worker.js)가 chrome.i18n으로 붙인다(언어마다 어순이 달라 문자열을
 * 이어붙이는 대신 messages.json의 `$1` 자리에 분을 꽂아야 한다).
 */
export const ALARM_KIND = Object.freeze({
  interval: 'interval',
  milestone: 'milestone',
  scheduleSoon: 'scheduleSoon'
});

/** 오늘치 알림 장부의 초기값. 날짜가 바뀌면 이걸로 새로 시작한다. */
export function createAlarmState(date) {
  return { date, lastIntervalNotifyMs: 0, notifiedMilestones: [], scheduleStartNotified: false };
}

// 어제 장부를 그대로 쓰면 "이미 알렸음" 표시가 남아 오늘 알림이 통째로 죽는다.
// 날짜가 다르면 버리고, 같으면 모르는 필드까지 보존한 채 복사해서 쓴다.
function restoreState(previous, date) {
  if (!previous || previous.date !== date) return createAlarmState(date);
  return {
    ...previous,
    lastIntervalNotifyMs: previous.lastIntervalNotifyMs || 0,
    notifiedMilestones: Array.isArray(previous.notifiedMilestones) ? [...previous.notifiedMilestones] : [],
    scheduleStartNotified: !!previous.scheduleStartNotified
  };
}

/**
 * 이번 틱에 띄워야 할 알림과 갱신된 장부를 계산한다.
 *
 * @param {object|null} previous 저장돼 있던 alarm_state
 * @param {object} inputs
 * @param {string} inputs.date 오늘 날짜(4시 컷오프 기준, getTodayDate())
 * @param {number} inputs.usedMs 오늘 사용량(다른 기기 몫 합산 후)
 * @param {number} inputs.limitMs 오늘 한도 (무제한이면 Infinity — 마일스톤은 조용해진다)
 * @param {number} inputs.intervalMinutes N분 주기 알림 간격 (0이면 끔)
 * @param {boolean} inputs.milestonesEnabled 남은 시간 마일스톤 알림 사용 여부
 * @param {number|null} inputs.minutesUntilScheduleStart 다음 예약 차단까지 남은 분
 *   (minutesUntilNextScheduleStart의 반환값 — 이미 활성 중이거나 예약이 없으면 null)
 * @returns {{state: object, changed: boolean, notifications: Array<{kind: string, minutes: number}>}}
 *   notifications는 발화 순서대로이고, 각 항목은 종류(ALARM_KIND)와 문구에 들어갈 분(minutes)만
 *   담는다. 문구도 알림 id도 호출자가 붙인다 — 주기/예약 알림은 매번 고유한 id여야 하는데
 *   (같은 id면 크롬이 배너를 다시 안 띄우고 조용히 업데이트만 한다) 그건 Date.now()가 필요해
 *   순수 함수 밖의 일이다.
 */
export function evaluateAlarms(previous, {
  date,
  usedMs = 0,
  limitMs = Infinity,
  intervalMinutes = 0,
  milestonesEnabled = true,
  minutesUntilScheduleStart = null
} = {}) {
  const state = restoreState(previous, date);
  const notifications = [];
  let changed = false;

  if (intervalMinutes > 0) {
    const intervalMs = intervalMinutes * 60 * 1000;
    if (usedMs - state.lastIntervalNotifyMs >= intervalMs) {
      // 다음 기준점을 지난 경계에 스냅시킨다. 오랫동안 못 돌았어도 밀린 알림이
      // 한꺼번에 쏟아지지 않고 이번 한 번으로 끝난다.
      state.lastIntervalNotifyMs = Math.floor(usedMs / intervalMs) * intervalMs;
      changed = true;
      notifications.push({ kind: ALARM_KIND.interval, minutes: Math.floor(usedMs / 60000) });
    }
  }

  // 한도가 무제한(Infinity)이면 "남은 시간"이라는 개념 자체가 없으므로 건너뛴다.
  if (milestonesEnabled && Number.isFinite(limitMs)) {
    const remaining = limitMs - usedMs;
    for (const minutes of ALARM_MILESTONE_MINUTES) {
      // remaining > 0 조건 때문에 한도를 이미 넘긴 뒤엔 조용하다 (그땐 차단 화면이 뜬다).
      if (remaining > 0 && remaining <= minutes * 60 * 1000 && !state.notifiedMilestones.includes(minutes)) {
        state.notifiedMilestones.push(minutes);
        changed = true;
        notifications.push({ kind: ALARM_KIND.milestone, minutes });
      }
    }
  }

  // 예약 차단 시작 10분 전 알림. 하루 한 번만 알리면 충분하므로 날짜별로 한 번만 dedupe한다.
  // minutes에는 실제 남은 분이 아니라 예고 기준값(10)을 담는다 — 문구가 "10분 후"로 고정이라
  // 7분 남았을 때 "7분 후"로 바뀌면 하루 한 번만 알린다는 규칙과 어긋나 보인다.
  if (minutesUntilScheduleStart !== null && minutesUntilScheduleStart > 0 &&
      minutesUntilScheduleStart <= SCHEDULE_SOON_LEAD_MINUTES && !state.scheduleStartNotified) {
    state.scheduleStartNotified = true;
    changed = true;
    notifications.push({ kind: ALARM_KIND.scheduleSoon, minutes: SCHEDULE_SOON_LEAD_MINUTES });
  }

  return { state, changed, notifications };
}
