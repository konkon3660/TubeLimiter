import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  UNLIMITED_LIMIT_SENTINEL,
  LIMIT_HISTORY_RETENTION_DAYS,
  serializeLimitMs,
  resolveLimitForDate,
  planLimitHistoryUpdate,
  planRolloverLimits
} from '../src/lib/limitHistory.js';

// 히트맵이 과거 날짜를 "지금 설정된 한도"로 소급 판정하지 않게 하는 기록/조회 규칙을 고정한다.
// 한도를 30분에서 2시간으로 올려도 예전에 초과했던 날은 초과인 채로 남아야 한다.

const MIN = 60 * 1000;

// 2026-09-02는 수요일, 2026-09-06은 일요일 (limits.test.js와 같은 기준일).
const WEDNESDAY = '2026-09-02';
const SUNDAY = '2026-09-06';

const THIRTY_MIN_SETTINGS = { daily_limit_reset_frequency: 'daily', daily_limit_ms: 30 * MIN };
const TWO_HOUR_SETTINGS = { daily_limit_reset_frequency: 'daily', daily_limit_ms: 120 * MIN };

test('기록이 있는 날은 지금 설정을 바꿔도 그날 한도로 판정한다', () => {
  const history = { [WEDNESDAY]: 30 * MIN };
  const resolved = resolveLimitForDate(history, TWO_HOUR_SETTINGS, WEDNESDAY);
  assert.equal(resolved.limitMs, 30 * MIN);
  assert.equal(resolved.estimated, false);
});

test('기록이 없는 옛 날짜는 현재 설정으로 근사 판정하고 추정으로 표시된다', () => {
  const resolved = resolveLimitForDate({}, THIRTY_MIN_SETTINGS, WEDNESDAY);
  assert.equal(resolved.limitMs, 30 * MIN);
  assert.equal(resolved.estimated, true);
});

test('limit_history 자체가 없어도(이 기능 이전 저장소) 근사치로 떨어진다', () => {
  assert.deepEqual(resolveLimitForDate(undefined, THIRTY_MIN_SETTINGS, WEDNESDAY), {
    limitMs: 30 * MIN,
    estimated: true
  });
  assert.deepEqual(resolveLimitForDate(null, THIRTY_MIN_SETTINGS, WEDNESDAY), {
    limitMs: 30 * MIN,
    estimated: true
  });
});

test('무제한은 센티널로 저장되고 Infinity로 되읽힌다 (chrome.storage가 Infinity를 못 담는다)', () => {
  assert.equal(serializeLimitMs(Infinity), UNLIMITED_LIMIT_SENTINEL);

  const { history } = planLimitHistoryUpdate(
    {},
    [{ date: WEDNESDAY, limitMs: Infinity }],
    WEDNESDAY
  );
  assert.equal(history[WEDNESDAY], UNLIMITED_LIMIT_SENTINEL);

  const resolved = resolveLimitForDate(history, THIRTY_MIN_SETTINGS, WEDNESDAY);
  assert.equal(resolved.limitMs, Infinity);
  assert.equal(resolved.estimated, false);
});

test('한도 0은 무제한이 아니라 0으로 남는다 (요일별 0분 설정)', () => {
  const { history } = planLimitHistoryUpdate({}, [{ date: SUNDAY, limitMs: 0 }], SUNDAY);
  assert.equal(history[SUNDAY], 0);
  assert.equal(resolveLimitForDate(history, THIRTY_MIN_SETTINGS, SUNDAY).limitMs, 0);
});

test('요일별 한도 오버라이드도 그날 기록된 값이 우선한다', () => {
  const byDaySettings = {
    daily_limit_reset_frequency: 'by_day',
    daily_limit_by_day: [10, 20, 30, 40, 50, 60, 70]
  };
  // 일요일 기록이 남아 있으면 요일 테이블(10분)이 아니라 기록된 45분으로 판정한다.
  const history = { [SUNDAY]: 45 * MIN };
  assert.equal(resolveLimitForDate(history, byDaySettings, SUNDAY).limitMs, 45 * MIN);
  // 기록이 없는 수요일만 요일 테이블로 근사한다.
  assert.deepEqual(resolveLimitForDate(history, byDaySettings, WEDNESDAY), {
    limitMs: 40 * MIN,
    estimated: true
  });
});

test('손상된 기록은 무제한이 아니라 근사치로 떨어진다 (조용히 무조건 성공이 되면 안 된다)', () => {
  const history = { [WEDNESDAY]: 'broken' };
  const resolved = resolveLimitForDate(history, THIRTY_MIN_SETTINGS, WEDNESDAY);
  assert.equal(resolved.limitMs, 30 * MIN);
  assert.equal(resolved.estimated, true);
});

test('같은 값을 다시 기록하면 changed가 false다 (매 틱마다 storage에 쓰지 않게)', () => {
  const history = { [WEDNESDAY]: 30 * MIN };
  const result = planLimitHistoryUpdate(
    history,
    [{ date: WEDNESDAY, limitMs: 30 * MIN }],
    WEDNESDAY
  );
  assert.equal(result.changed, false);
  assert.deepEqual(result.history, history);
});

test('오늘 한도를 바꾸면 그날 기록이 마지막 값으로 갱신된다', () => {
  const result = planLimitHistoryUpdate(
    { [WEDNESDAY]: 30 * MIN },
    [{ date: WEDNESDAY, limitMs: 120 * MIN }],
    WEDNESDAY
  );
  assert.equal(result.changed, true);
  assert.equal(result.history[WEDNESDAY], 120 * MIN);
});

test('keepExisting은 지난 날짜의 기록을 덮어쓰지 않는다 (롤오버가 소급 적용하면 안 된다)', () => {
  const result = planLimitHistoryUpdate(
    { [WEDNESDAY]: 30 * MIN },
    [{ date: WEDNESDAY, limitMs: 120 * MIN, keepExisting: true }],
    SUNDAY
  );
  assert.equal(result.changed, false);
  assert.equal(result.history[WEDNESDAY], 30 * MIN);
});

test('keepExisting이어도 기록이 없는 날은 채워준다 (브라우저를 안 켠 날)', () => {
  const result = planLimitHistoryUpdate(
    {},
    [{ date: WEDNESDAY, limitMs: 30 * MIN, keepExisting: true }],
    SUNDAY
  );
  assert.equal(result.changed, true);
  assert.equal(result.history[WEDNESDAY], 30 * MIN);
});

test('원본 맵은 변형하지 않는다', () => {
  const history = { [WEDNESDAY]: 30 * MIN };
  const result = planLimitHistoryUpdate(history, [{ date: SUNDAY, limitMs: 45 * MIN }], SUNDAY);
  assert.deepEqual(history, { [WEDNESDAY]: 30 * MIN });
  assert.equal(result.history[SUNDAY], 45 * MIN);
});

test('보관 기간이 지난 날짜는 정리된다 (usage_history_hourly와 같은 패턴)', () => {
  const history = { '2026-01-01': 30 * MIN, [WEDNESDAY]: 30 * MIN };
  const result = planLimitHistoryUpdate(history, [], WEDNESDAY, 10);
  assert.equal(result.changed, true);
  assert.deepEqual(Object.keys(result.history), [WEDNESDAY]);
});

test('보관 기간은 히트맵 28일 + 막대그래프 30일보다 넉넉하다', () => {
  assert.ok(LIMIT_HISTORY_RETENTION_DAYS > 30);
});

test('기록도 정리도 없으면 changed가 false다', () => {
  const result = planLimitHistoryUpdate({ [WEDNESDAY]: 30 * MIN }, [], WEDNESDAY);
  assert.equal(result.changed, false);
});

// --- 롤오버 판정에 쓰는 한도 (planRolloverLimits) ---
//
// 자정 롤오버는 히트맵과 **같은 값**으로 판정해야 한다. 예전에는 기록만 keepExisting으로 지키고
// 판정은 지금 설정(computeLimitForDate)으로 해서, 브라우저를 며칠 안 켠 사이 한도를 바꾸면
// 히트맵은 그날 한도로, 스트릭은 새 한도로 같은 날을 반대로 판정했다. streaks 행은 안드로이드와
// 공유하므로 이 규칙이 갈라지면 기기마다 스트릭이 달라진다.

test('롤오버는 그날 기록된 한도로 판정한다 (그 사이 설정을 바꿔도)', () => {
  const history = { [WEDNESDAY]: 30 * MIN };
  const { limitByDate } = planRolloverLimits(history, TWO_HOUR_SETTINGS, [WEDNESDAY], SUNDAY);
  assert.equal(limitByDate[WEDNESDAY], 30 * MIN);
});

test('롤오버 판정값은 히트맵(resolveLimitForDate)이 읽는 값과 같다', () => {
  const history = { [WEDNESDAY]: 30 * MIN };
  const { limitByDate } = planRolloverLimits(history, TWO_HOUR_SETTINGS, [WEDNESDAY], SUNDAY);
  assert.equal(
    limitByDate[WEDNESDAY],
    resolveLimitForDate(history, TWO_HOUR_SETTINGS, WEDNESDAY).limitMs
  );
});

test('기록이 없는 날(브라우저를 안 켠 날)은 현재 설정 추정치로 판정하고, 그 값이 그대로 남는다', () => {
  const { updates, limitByDate } = planRolloverLimits({}, THIRTY_MIN_SETTINGS, [WEDNESDAY], SUNDAY);
  assert.equal(limitByDate[WEDNESDAY], 30 * MIN);

  const { history } = planLimitHistoryUpdate({}, updates, SUNDAY);
  assert.equal(history[WEDNESDAY], 30 * MIN);
});

test('판정에 쓴 값과 기록에 남는 값이 언제나 같다 (둘이 갈라지면 히트맵과 스트릭이 어긋난다)', () => {
  const history = { [WEDNESDAY]: 30 * MIN };
  const dates = [WEDNESDAY, '2026-09-03', '2026-09-04'];
  const { updates, limitByDate } = planRolloverLimits(history, TWO_HOUR_SETTINGS, dates, SUNDAY);
  const after = planLimitHistoryUpdate(history, updates, SUNDAY).history;
  dates.forEach((date) => {
    assert.equal(resolveLimitForDate(after, THIRTY_MIN_SETTINGS, date).limitMs, limitByDate[date]);
  });
});

test('오늘 한도는 목록에 없어도 항상 현재 설정으로 덮어쓴다 (하루 사이에도 바꿀 수 있다)', () => {
  const { updates, limitByDate } = planRolloverLimits(
    { [SUNDAY]: 30 * MIN },
    TWO_HOUR_SETTINGS,
    [],
    SUNDAY
  );
  assert.deepEqual(updates, [{ date: SUNDAY, limitMs: 120 * MIN }]);
  // 오늘은 아직 판정 대상이 아니다.
  assert.deepEqual(limitByDate, {});

  const { history } = planLimitHistoryUpdate({ [SUNDAY]: 30 * MIN }, updates, SUNDAY);
  assert.equal(history[SUNDAY], 120 * MIN);
});

test('무제한으로 기록된 날은 Infinity로 판정된다 (센티널이 0으로 새면 그날이 실패가 된다)', () => {
  const history = { [WEDNESDAY]: UNLIMITED_LIMIT_SENTINEL };
  const { limitByDate } = planRolloverLimits(history, THIRTY_MIN_SETTINGS, [WEDNESDAY], SUNDAY);
  assert.equal(limitByDate[WEDNESDAY], Infinity);
});
