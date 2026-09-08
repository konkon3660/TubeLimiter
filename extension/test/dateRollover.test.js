import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  MAX_ROLLOVER_DAYS,
  DEFAULT_EMERGENCY_USES,
  planDateRollover,
  emergencyResetDate,
  planEmergencyReset
} from '../src/lib/dateRollover.js';

// android UsageMonitorService.settleFinishedDays / resetEmergencyAllowanceIfDue와
// BlockDecision.kt의 emergencyResetKey 대응.

test('기준점이 없으면(설치 직후) 정산할 날 없이 오늘로 기준만 잡는다', () => {
  const plan = planDateRollover(null, '2026-09-03');
  assert.equal(plan.action, 'init');
  assert.deepEqual(plan.dates, []);
  assert.equal(plan.nextStoredDate, '2026-09-03');
});

test('오늘과 같으면 아무것도 하지 않는다 (storage도 안 건드린다)', () => {
  const plan = planDateRollover('2026-09-03', '2026-09-03');
  assert.equal(plan.action, 'upToDate');
  assert.deepEqual(plan.dates, []);
  assert.equal(plan.nextStoredDate, null);
});

test('시계가 되돌아가 기준점이 미래면 정산 없이 오늘로 재동기화한다', () => {
  // 앞으로만 증가하는 루프라 이 경우를 안 걸러내면 today를 영영 못 만나 무한루프에 빠진다.
  const plan = planDateRollover('2026-09-10', '2026-09-03');
  assert.equal(plan.action, 'clockWentBackwards');
  assert.deepEqual(plan.dates, []);
  assert.equal(plan.nextStoredDate, '2026-09-03');
});

test('하루 넘어가면 어제 하루만 정산한다 (오늘은 아직 안 끝났다)', () => {
  const plan = planDateRollover('2026-09-02', '2026-09-03');
  assert.equal(plan.action, 'rollover');
  assert.deepEqual(plan.dates, ['2026-09-02']);
  assert.equal(plan.nextStoredDate, '2026-09-03');
});

test('며칠 안 켰어도 그 사이 날짜를 하루씩 모두 정산한다', () => {
  const plan = planDateRollover('2026-08-30', '2026-09-03');
  assert.deepEqual(plan.dates, ['2026-08-30', '2026-08-31', '2026-09-01', '2026-09-02']);
  assert.equal(plan.nextStoredDate, '2026-09-03');
});

test('월/연 경계도 그냥 하루씩 넘어간다', () => {
  assert.deepEqual(planDateRollover('2026-02-27', '2026-03-01').dates, [
    '2026-02-27',
    '2026-02-28'
  ]);
  assert.deepEqual(planDateRollover('2025-12-31', '2026-01-02').dates, [
    '2025-12-31',
    '2026-01-01'
  ]);
});

test('저장소가 손상돼 기준점이 아주 옛날이어도 상한에서 멈추고 기준점은 오늘로 옮긴다', () => {
  // 상한에서 멈춘 뒤 기준점을 그대로 두면 매 틱마다 같은 400일을 다시 도는 꼴이 된다.
  const plan = planDateRollover('2000-01-01', '2026-09-03');
  assert.equal(plan.dates.length, MAX_ROLLOVER_DAYS);
  assert.equal(plan.dates[0], '2000-01-01');
  assert.equal(plan.nextStoredDate, '2026-09-03');
});

test('상한은 인자로 낮출 수 있다', () => {
  const plan = planDateRollover('2026-08-30', '2026-09-03', 2);
  assert.deepEqual(plan.dates, ['2026-08-30', '2026-08-31']);
});

const RESET_DATES = { today: '2026-09-03', weekStart: '2026-08-31', monthStart: '2026-09-01' };

test('긴급 시청 리셋 버킷은 주기에 따라 오늘/주 시작/월 시작이 된다', () => {
  assert.equal(emergencyResetDate('daily', RESET_DATES), '2026-09-03');
  assert.equal(emergencyResetDate('weekly', RESET_DATES), '2026-08-31');
  assert.equal(emergencyResetDate('monthly', RESET_DATES), '2026-09-01');
});

test('주기 설정이 없거나 모르는 값이면 일간으로 본다', () => {
  assert.equal(emergencyResetDate(undefined, RESET_DATES), '2026-09-03');
  assert.equal(emergencyResetDate('yearly', RESET_DATES), '2026-09-03');
});

test('버킷이 그대로면 남은 횟수를 건드리지 않는다', () => {
  const plan = planEmergencyReset({
    lastResetDate: '2026-09-03',
    frequency: 'daily',
    dailyUses: 3,
    ...RESET_DATES
  });
  assert.equal(plan.shouldReset, false);
});

test('날짜가 바뀌면 설정된 횟수로 되돌린다', () => {
  const plan = planEmergencyReset({
    lastResetDate: '2026-09-02',
    frequency: 'daily',
    dailyUses: 5,
    ...RESET_DATES
  });
  assert.equal(plan.shouldReset, true);
  assert.equal(plan.resetDate, '2026-09-03');
  assert.equal(plan.uses, 5);
});

test('주간 주기는 같은 주 안에서는 날짜가 바뀌어도 리셋하지 않는다', () => {
  const plan = planEmergencyReset({
    lastResetDate: '2026-08-31',
    frequency: 'weekly',
    dailyUses: 3,
    ...RESET_DATES
  });
  assert.equal(plan.shouldReset, false);
});

test('기록이 아예 없으면(설치 직후) 리셋해서 초기 횟수를 깔아준다', () => {
  const plan = planEmergencyReset({
    lastResetDate: undefined,
    frequency: 'daily',
    dailyUses: 3,
    ...RESET_DATES
  });
  assert.equal(plan.shouldReset, true);
  assert.equal(plan.uses, 3);
});

test('횟수 설정이 비어 있으면 기본값 3, 0은 0 그대로다', () => {
  const missing = planEmergencyReset({
    lastResetDate: null,
    frequency: 'daily',
    dailyUses: undefined,
    ...RESET_DATES
  });
  assert.equal(missing.uses, DEFAULT_EMERGENCY_USES);
  assert.equal(missing.uses, 3);

  // "긴급 시청 금지"로 0을 설정한 사용자를 기본값 3으로 되살려주면 안 된다.
  const zero = planEmergencyReset({
    lastResetDate: null,
    frequency: 'daily',
    dailyUses: 0,
    ...RESET_DATES
  });
  assert.equal(zero.uses, 0);
});
