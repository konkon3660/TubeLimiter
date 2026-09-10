import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  MAX_ROLLOVER_DAYS,
  DEFAULT_EMERGENCY_USES,
  planDateRollover,
  emergencyResetDate,
  planEmergencyReset,
  normalizeEmergencyFrequency
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

// --- 주기 변경이 리필을 만들지 않는다 (QA_REVIEW §10.2) ---
// 안드로이드 BlockDecision.kt의 planEmergencyReset과 **같은 규칙**이라, 여기 시나리오는
// BlockDecisionTest.kt에도 같은 모양으로 박혀 있다. 한쪽만 고치면 두 기기가 다른 잔여를 보여준다.

test('주기를 모르는 값이나 누락으로 두면 daily로 접힌다(표식 비교가 어긋나지 않게)', () => {
  assert.equal(normalizeEmergencyFrequency(undefined), 'daily');
  assert.equal(normalizeEmergencyFrequency('yearly'), 'daily');
  assert.equal(normalizeEmergencyFrequency('weekly'), 'weekly');
  assert.equal(normalizeEmergencyFrequency('monthly'), 'monthly');
});

test('재현: 오늘 다 쓴 뒤 daily→weekly로 바꿔도 리셋되지 않고 쓴 횟수를 이어받는다', () => {
  // 하드코어 게이트는 "조이는 방향"이라 이 변경을 통과시킨다. 여기서 리셋이 돌면 하드코어를
  // 켜둔 채로 무료 리필이 된다.
  const plan = planEmergencyReset({
    lastResetDate: '2026-09-03',
    lastResetFrequency: 'daily',
    frequency: 'weekly',
    dailyUses: 3,
    ...RESET_DATES
  });
  assert.equal(plan.action, 'carryOver');
  assert.equal(plan.shouldReset, false);
  // 표식만 새 버킷으로 옮긴다 — 남은 횟수는 호출부가 건드리지 않는다.
  assert.equal(plan.resetDate, '2026-08-31');
  assert.equal(plan.resetFrequency, 'weekly');
});

test('재현: weekly→monthly로 한 번 더 바꿔도 마찬가지다(두 번째 무료 리필도 막힌다)', () => {
  const plan = planEmergencyReset({
    lastResetDate: '2026-08-31',
    lastResetFrequency: 'weekly',
    frequency: 'monthly',
    dailyUses: 3,
    ...RESET_DATES
  });
  assert.equal(plan.action, 'carryOver');
  assert.equal(plan.resetDate, '2026-09-01');
  assert.equal(plan.resetFrequency, 'monthly');
});

test('느슨해지는 방향(monthly→daily)도 리필하지 않는다 — 주기 변경 자체가 리셋 사유가 아니다', () => {
  const plan = planEmergencyReset({
    lastResetDate: '2026-09-01',
    lastResetFrequency: 'monthly',
    frequency: 'daily',
    dailyUses: 3,
    ...RESET_DATES
  });
  assert.equal(plan.action, 'carryOver');
  assert.equal(plan.resetDate, '2026-09-03');
});

test('주 시작일이 월 시작일보다 앞선 날에도 weekly→monthly가 리필로 새지 않는다', () => {
  // 9/1(화)에는 monthStart(09-01) > weekStart(08-31)이라 "키가 뒤로만 간다"는 어림짐작이 깨진다.
  // 저장된 주기로 다시 계산해 비교하기 때문에 여기서도 carryOver다.
  const plan = planEmergencyReset({
    lastResetDate: '2026-08-31',
    lastResetFrequency: 'weekly',
    frequency: 'monthly',
    dailyUses: 3,
    today: '2026-09-01',
    weekStart: '2026-08-31',
    monthStart: '2026-09-01'
  });
  assert.equal(plan.action, 'carryOver');
  assert.equal(plan.resetDate, '2026-09-01');
});

test('주기를 바꾼 뒤 실제로 새 버킷이 시작되면 그때는 정상적으로 리셋된다', () => {
  // 위 시나리오에서 weekly로 바꿔 표식이 2026-08-31/weekly가 된 상태. 다음 주 월요일이 오면
  // 저장된 주기(weekly)로 계산한 오늘의 키가 달라지므로 리셋이다.
  const plan = planEmergencyReset({
    lastResetDate: '2026-08-31',
    lastResetFrequency: 'weekly',
    frequency: 'weekly',
    dailyUses: 3,
    today: '2026-09-07',
    weekStart: '2026-09-07',
    monthStart: '2026-09-01'
  });
  assert.equal(plan.action, 'reset');
  assert.equal(plan.shouldReset, true);
  assert.equal(plan.resetDate, '2026-09-07');
  assert.equal(plan.uses, 3);
});

test('날짜가 흐른 뒤 주기까지 바꾸면 리셋이고, 키는 새 주기 기준이다', () => {
  // 어제 마지막으로 리셋된 daily 버킷은 오늘 이미 끝났다 — 주기를 같이 바꿨다고 해서
  // 정당한 리셋까지 막지는 않는다.
  const plan = planEmergencyReset({
    lastResetDate: '2026-09-02',
    lastResetFrequency: 'daily',
    frequency: 'weekly',
    dailyUses: 3,
    ...RESET_DATES
  });
  assert.equal(plan.action, 'reset');
  assert.equal(plan.resetDate, '2026-08-31');
  assert.equal(plan.resetFrequency, 'weekly');
});

test('표식이 없던 저장소는 예전과 똑같이 판정하되 표식을 남긴다', () => {
  // 이 규칙 이전 버전에서 올라온 저장소. 같은 버킷이면 횟수는 그대로 두고 주기만 적어둔다.
  const stamping = planEmergencyReset({
    lastResetDate: '2026-09-03',
    lastResetFrequency: undefined,
    frequency: 'daily',
    dailyUses: 3,
    ...RESET_DATES
  });
  assert.equal(stamping.action, 'carryOver');
  assert.equal(stamping.resetFrequency, 'daily');

  const rolled = planEmergencyReset({
    lastResetDate: '2026-09-02',
    lastResetFrequency: undefined,
    frequency: 'daily',
    dailyUses: 3,
    ...RESET_DATES
  });
  assert.equal(rolled.action, 'reset');
});

test('버킷도 주기도 그대로면 저장소를 건드리지 않는다', () => {
  const plan = planEmergencyReset({
    lastResetDate: '2026-08-31',
    lastResetFrequency: 'weekly',
    frequency: 'weekly',
    dailyUses: 3,
    ...RESET_DATES
  });
  assert.equal(plan.action, 'none');
  assert.equal(plan.shouldReset, false);
});
