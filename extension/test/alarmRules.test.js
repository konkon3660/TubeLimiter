import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  ALARM_KIND,
  ALARM_MILESTONE_MINUTES,
  SCHEDULE_SOON_LEAD_MINUTES,
  createAlarmState,
  evaluateAlarms
} from '../src/lib/alarmRules.js';

// android/app/src/main/java/com/tubelimiter/app/limit/AlarmRules.kt의 evaluateAlarms 대응.
// 안드로이드는 예약 차단 10분 전 알림만 별도 함수로 빼놨지만(dedupe 키도 따로 저장),
// "하루 한 번"이라는 규칙은 같다.

const MIN = 60 * 1000;
const TODAY = '2026-09-03';
const YESTERDAY = '2026-09-02';

// 기본 한도는 넉넉히 잡는다 — 마일스톤을 다루지 않는 테스트가 30분 마일스톤에 걸리지 않게.
function evaluate(previous, overrides = {}) {
  return evaluateAlarms(previous, {
    date: TODAY,
    usedMs: 0,
    limitMs: 120 * MIN,
    intervalMinutes: 0,
    milestonesEnabled: true,
    minutesUntilScheduleStart: null,
    ...overrides
  });
}

function kinds(result) {
  return result.notifications.map((n) => n.kind);
}

test('마일스톤 목록은 30/10/5/1분이다', () => {
  assert.deepEqual(ALARM_MILESTONE_MINUTES, [30, 10, 5, 1]);
});

test('아무 조건도 안 걸리면 알림도 없고 장부도 안 바뀐다', () => {
  const result = evaluate(null, { usedMs: 5 * MIN });
  assert.equal(result.changed, false);
  assert.deepEqual(result.notifications, []);
});

test('N분 주기 알림은 간격에 정확히 도달할 때 발화한다', () => {
  const justUnder = evaluate(createAlarmState(TODAY), {
    usedMs: 10 * MIN - 1,
    intervalMinutes: 10,
    limitMs: Infinity
  });
  assert.deepEqual(kinds(justUnder), []);

  const atInterval = evaluate(createAlarmState(TODAY), {
    usedMs: 10 * MIN,
    intervalMinutes: 10,
    limitMs: Infinity
  });
  assert.deepEqual(kinds(atInterval), [ALARM_KIND.interval]);
  // 문구가 아니라 "몇 분짜리 알림인가"만 돌려준다 — 문구는 service-worker.js가 chrome.i18n으로 붙인다.
  assert.equal(atInterval.notifications[0].minutes, 10);
  assert.equal(atInterval.state.lastIntervalNotifyMs, 10 * MIN);
});

test('오래 못 돌았어도 밀린 주기 알림이 한꺼번에 쏟아지지 않고 경계로 스냅한다', () => {
  // 10분 주기인데 35분치가 한 번에 들어온 상황 (서비스워커가 잠들어 있었던 경우).
  const result = evaluate(createAlarmState(TODAY), {
    usedMs: 35 * MIN,
    intervalMinutes: 10,
    limitMs: Infinity
  });
  assert.equal(result.notifications.length, 1);
  assert.equal(result.state.lastIntervalNotifyMs, 30 * MIN);

  // 다음 발화는 40분째부터 — 스냅한 기준점 덕분에 곧바로 또 울리지 않는다.
  assert.deepEqual(
    kinds(evaluate(result.state, { usedMs: 39 * MIN, intervalMinutes: 10, limitMs: Infinity })),
    []
  );
  assert.deepEqual(
    kinds(evaluate(result.state, { usedMs: 40 * MIN, intervalMinutes: 10, limitMs: Infinity })),
    ['interval']
  );
});

test('주기 알림이 0분이면(끔) 아무리 봐도 조용하다', () => {
  assert.deepEqual(
    kinds(evaluate(null, { usedMs: 200 * MIN, intervalMinutes: 0, limitMs: Infinity })),
    []
  );
});

test('남은 시간이 정확히 마일스톤에 닿으면 발화한다', () => {
  // 한도 60분, 30분 사용 → 남은 30분 정확히 도달.
  const result = evaluate(null, { usedMs: 30 * MIN, limitMs: 60 * MIN });
  assert.deepEqual(kinds(result), ['milestone']);
  assert.equal(result.notifications[0].minutes, 30);
  assert.equal(result.notifications[0].kind, ALARM_KIND.milestone);
  assert.equal(result.notifications[0].minutes, 30);
  assert.deepEqual(result.state.notifiedMilestones, [30]);
});

test('같은 마일스톤은 하루에 한 번만 발화한다', () => {
  const first = evaluate(null, { usedMs: 30 * MIN, limitMs: 60 * MIN });
  const second = evaluate(first.state, { usedMs: 31 * MIN, limitMs: 60 * MIN });
  assert.deepEqual(kinds(second), []);
  assert.equal(second.changed, false);
});

test('여러 마일스톤을 한 번에 넘기면 넘긴 것들이 모두 발화한다', () => {
  // 한도 60분에 55분 사용 → 남은 5분: 30/10/5가 한꺼번에 걸린다.
  const result = evaluate(null, { usedMs: 55 * MIN, limitMs: 60 * MIN });
  assert.deepEqual(
    result.notifications.map((n) => n.minutes),
    [30, 10, 5]
  );
  assert.deepEqual(result.state.notifiedMilestones, [30, 10, 5]);
});

test('한도를 다 쓴 뒤엔 마일스톤이 조용하다 (그땐 차단 화면이 뜬다)', () => {
  assert.deepEqual(kinds(evaluate(null, { usedMs: 60 * MIN, limitMs: 60 * MIN })), []);
  assert.deepEqual(kinds(evaluate(null, { usedMs: 90 * MIN, limitMs: 60 * MIN })), []);
});

test('마일스톤을 끄거나 한도가 무제한이면 조용하다', () => {
  assert.deepEqual(
    kinds(evaluate(null, { usedMs: 59 * MIN, limitMs: 60 * MIN, milestonesEnabled: false })),
    []
  );
  assert.deepEqual(kinds(evaluate(null, { usedMs: 59 * MIN, limitMs: Infinity })), []);
});

test('어제 장부는 버리고 오늘치로 새로 시작한다', () => {
  const yesterday = {
    date: YESTERDAY,
    lastIntervalNotifyMs: 120 * MIN,
    notifiedMilestones: [30, 10, 5, 1],
    scheduleStartNotified: true
  };
  const result = evaluate(yesterday, { usedMs: 30 * MIN, limitMs: 60 * MIN, intervalMinutes: 10 });
  assert.equal(result.state.date, TODAY);
  // 어제 이미 알린 30분 마일스톤이 오늘 다시 울려야 한다.
  assert.deepEqual(
    result.notifications.map((n) => n.kind),
    ['interval', 'milestone']
  );
});

test('예약 차단 10분 전 알림은 10분 이내일 때만, 하루 한 번만 뜬다', () => {
  assert.deepEqual(kinds(evaluate(null, { minutesUntilScheduleStart: 11 })), []);
  assert.deepEqual(kinds(evaluate(null, { minutesUntilScheduleStart: null })), []);
  assert.deepEqual(kinds(evaluate(null, { minutesUntilScheduleStart: 0 })), []);

  const first = evaluate(null, { minutesUntilScheduleStart: 10 });
  assert.deepEqual(kinds(first), [ALARM_KIND.scheduleSoon]);
  // 실제 남은 분이 아니라 예고 기준값을 담는다 (문구가 "10분 후"로 고정이므로).
  assert.equal(first.notifications[0].minutes, SCHEDULE_SOON_LEAD_MINUTES);
  assert.equal(first.state.scheduleStartNotified, true);

  assert.deepEqual(kinds(evaluate(first.state, { minutesUntilScheduleStart: 3 })), []);
});

test('입력으로 받은 장부를 그 자리에서 뜯어고치지 않는다', () => {
  // 서비스워커는 changed일 때만 저장하므로, 판정이 원본을 건드리면 저장 안 된 변경이
  // 인메모리에만 남아 다음 틱 판정이 어긋난다.
  const previous = createAlarmState(TODAY);
  evaluate(previous, { usedMs: 30 * MIN, limitMs: 60 * MIN, intervalMinutes: 10 });
  assert.deepEqual(previous, createAlarmState(TODAY));
});

test('알림이 하나라도 있으면 changed가 true라 장부를 저장한다', () => {
  const result = evaluate(null, { usedMs: 30 * MIN, limitMs: 60 * MIN });
  assert.equal(result.changed, true);
  assert.equal(result.notifications.length > 0, true);
});
