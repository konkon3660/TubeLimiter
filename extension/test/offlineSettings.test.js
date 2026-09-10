// 백엔드가 죽어 있을 때의 판정을 고정한다.
//
// 여기서 지키는 세 가지가 QA_REVIEW §3.3 대응의 전부다:
//   1) "로그아웃"과 "서버에 못 닿음"이 갈린다 (안 갈리면 옵션 화면이 통째로 잠긴다).
//   2) 서버가 살아난 뒤 **대기분이 이긴다** (아니면 방금 한 저장이 조용히 되돌아간다).
//   3) 오프라인 저장도 하드코어 관문을 탄다 (빠지면 "인터넷 끊고 한도 늘리기"가 새 우회로가 된다).

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  ACCESS_STATE,
  PENDING_SETTINGS_KEY,
  applyPendingSettings,
  createPendingSettings,
  isOfflineFailure,
  planSettingsSave,
  planPendingSettingsPush,
  readPendingFor,
  resolveAccessState,
  resolveSettingsSyncPlan
} from '../src/lib/offlineSettings.js';
import {
  ACCOUNT_DELETE_REMOVED_KEYS,
  ACCOUNT_SWITCH_REMOVED_KEYS
} from '../src/lib/accountReset.js';

const USER = 'user-a';
const SESSION = { user: { id: USER, email: 'a@example.com' } };

// --- 1. 로그아웃 / 오프라인 구분 ---

test('사용자를 얻었으면 온라인이다', () => {
  const result = resolveAccessState({ user: { id: USER }, session: SESSION });
  assert.deepEqual(result, { state: ACCESS_STATE.online, userId: USER });
});

test('세션 자체가 없으면 로그아웃이다', () => {
  const result = resolveAccessState({
    user: null,
    userError: { name: 'AuthRetryableFetchError' },
    session: null
  });
  assert.deepEqual(result, { state: ACCESS_STATE.signedOut, userId: null });
});

test('세션은 있는데 서버에 못 닿으면 오프라인이다', () => {
  // pause된 Supabase 프로젝트: fetch가 통째로 실패해 상태 코드가 없다.
  const result = resolveAccessState({
    user: null,
    userError: new TypeError('Failed to fetch'),
    session: SESSION
  });
  assert.deepEqual(result, { state: ACCESS_STATE.offline, userId: USER });
});

test('5xx·타임아웃도 오프라인이다 (게이트웨이가 답하는 pause 모양)', () => {
  for (const status of [500, 502, 503, 504, 408, 429]) {
    const result = resolveAccessState({ user: null, userError: { status }, session: SESSION });
    assert.equal(result.state, ACCESS_STATE.offline, `status ${status}`);
  }
});

test('서버가 401·403으로 거절하면 오프라인이 아니라 로그아웃이다', () => {
  for (const status of [400, 401, 403, 422]) {
    const result = resolveAccessState({ user: null, userError: { status }, session: SESSION });
    assert.deepEqual(result, { state: ACCESS_STATE.signedOut, userId: null }, `status ${status}`);
  }
});

test('세션 저장소를 못 읽으면 오프라인 편집을 열지 않는다', () => {
  const result = resolveAccessState({
    user: null,
    userError: new TypeError('Failed to fetch'),
    session: SESSION,
    sessionError: new Error('storage unavailable')
  });
  assert.equal(result.state, ACCESS_STATE.signedOut);
});

test('PostgREST 오류 코드가 붙어 있으면 서버가 답한 것이므로 오프라인이 아니다', () => {
  assert.equal(isOfflineFailure({ code: 'PGRST301', message: 'JWT expired' }), false);
  assert.equal(isOfflineFailure({ code: '', message: 'TypeError: Failed to fetch' }), true);
});

test('오류가 없으면 오프라인 판정도 없다', () => {
  assert.equal(isOfflineFailure(null), false);
  assert.equal(isOfflineFailure(undefined), false);
});

// --- 2. 대기분 쌓기 ---

test('오프라인 저장은 누적된다 (앞선 저장이 사라지면 안 된다)', () => {
  const first = createPendingSettings(
    null,
    { daily_limit_ms: 600000 },
    { userId: USER, atMillis: 1 }
  );
  const second = createPendingSettings(
    first,
    { shorts_limit_ms: 0 },
    { userId: USER, atMillis: 2 }
  );

  assert.deepEqual(second.settings, { daily_limit_ms: 600000, shorts_limit_ms: 0 });
  assert.equal(second.updatedAtMillis, 2);
  assert.equal(second.userId, USER);
});

test('계정이 바뀌면 앞의 대기분은 버린다', () => {
  const mine = createPendingSettings(
    null,
    { daily_limit_ms: 600000 },
    { userId: USER, atMillis: 1 }
  );
  const theirs = createPendingSettings(mine, { whitelist: [] }, { userId: 'user-b', atMillis: 2 });
  assert.deepEqual(theirs.settings, { whitelist: [] });
});

test('스트릭 리셋 예약은 한 번 켜지면 올릴 때까지 유지된다', () => {
  const disable = createPendingSettings(
    null,
    { hardcore_mode: false },
    { userId: USER, atMillis: 1, resetStreak: true }
  );
  const later = createPendingSettings(
    disable,
    { alarm_interval_minutes: 10 },
    { userId: USER, atMillis: 2 }
  );
  assert.equal(later.resetStreak, true);
});

test('남의 대기분과 깨진 값은 없는 셈 친다', () => {
  const mine = createPendingSettings(null, { daily_limit_ms: 1 }, { userId: USER, atMillis: 1 });
  assert.equal(readPendingFor(mine, USER), mine);
  assert.equal(readPendingFor(mine, 'user-b'), null);
  assert.equal(readPendingFor(mine, null), null);
  assert.equal(readPendingFor(null, USER), null);
  assert.equal(readPendingFor({ userId: USER }, USER), null);
});

test('대기분은 계정 삭제·계정 전환에서 함께 지워진다', () => {
  assert.ok(ACCOUNT_DELETE_REMOVED_KEYS.includes(PENDING_SETTINGS_KEY));
  assert.ok(ACCOUNT_SWITCH_REMOVED_KEYS.includes(PENDING_SETTINGS_KEY));
});

// --- 3. 서버가 살아난 뒤: 누가 이기는가 ---

test('대기분이 있으면 서버를 pull 하지 않고 push 한다', () => {
  const pending = createPendingSettings(
    null,
    { daily_limit_ms: 600000 },
    { userId: USER, atMillis: 5 }
  );
  const plan = resolveSettingsSyncPlan({
    pending,
    cached: { daily_limit_ms: 1800000, whitelist: ['youtube.com/@lecture'] }
  });

  assert.equal(plan.action, 'push');
  // 올리는 건 대기분에 든 필드뿐이다 — 캐시를 통째로 올리면 그 사이 다른 기기가 바꾼 값까지 되돌린다.
  assert.deepEqual(plan.patch, { daily_limit_ms: 600000 });
  // 화면·판정에는 캐시 위에 대기분을 얹은 값이 간다.
  assert.deepEqual(plan.settings, {
    daily_limit_ms: 600000,
    whitelist: ['youtube.com/@lecture']
  });
});

test('대기분이 없으면 기존 계약대로 pull 이다 (합치기는 호출부가 한다)', () => {
  const plan = resolveSettingsSyncPlan({ pending: null, cached: { daily_limit_ms: 1800000 } });
  assert.equal(plan.action, 'pull');
  assert.equal(plan.patch, null);
  assert.equal(plan.settings, null);
});

test('밀린 스트릭 리셋은 push 계획에 실려 나간다', () => {
  const pending = createPendingSettings(
    null,
    { hardcore_mode: false, hardcore_disable_requested_at: null },
    { userId: USER, atMillis: 5, resetStreak: true }
  );
  assert.equal(resolveSettingsSyncPlan({ pending, cached: {} }).resetStreak, true);
});

test('화면에 띄우는 값은 캐시 위에 대기분을 얹은 것이다', () => {
  assert.deepEqual(applyPendingSettings({ a: 1, b: 2 }, { settings: { b: 3 } }), { a: 1, b: 3 });
  assert.deepEqual(applyPendingSettings({ a: 1 }, null), { a: 1 });
  assert.deepEqual(applyPendingSettings(null, null), {});
});

// --- 4. 하드코어 관문은 오프라인에서도 그대로 ---

const HARDCORE_ON = Object.freeze({
  hardcore_mode: true,
  daily_limit_ms: 1800000,
  shorts_limit_ms: 600000,
  whitelist: [],
  always_block_shorts: true,
  emergency_config: { dailyUses: 3, resetFrequency: 'weekly' },
  scheduled_blocks: []
});

test('오프라인이어도 한도를 늘리는 저장은 막힌다 (인터넷을 끊는 게 우회로가 되면 안 된다)', () => {
  const plan = planSettingsSave({
    accessState: ACCESS_STATE.offline,
    current: HARDCORE_ON,
    patch: { daily_limit_ms: 7200000 }
  });
  assert.equal(plan.action, 'blocked');
  assert.ok(plan.violations.length > 0);
});

test('오프라인에서 화이트리스트 추가·Shorts 해금도 똑같이 막힌다', () => {
  for (const patch of [
    { whitelist: ['youtube.com/'] },
    { always_block_shorts: false },
    { shorts_limit_ms: 0 },
    { emergency_config: { dailyUses: 9, resetFrequency: 'weekly' } }
  ]) {
    const plan = planSettingsSave({
      accessState: ACCESS_STATE.offline,
      current: HARDCORE_ON,
      patch
    });
    assert.equal(plan.action, 'blocked', JSON.stringify(patch));
  }
});

test('차단을 세게 만드는 변경은 오프라인에서도 로컬 저장으로 통과한다', () => {
  const plan = planSettingsSave({
    accessState: ACCESS_STATE.offline,
    current: HARDCORE_ON,
    patch: { daily_limit_ms: 600000 }
  });
  assert.equal(plan.action, 'local');
  assert.equal(plan.settings.daily_limit_ms, 600000);
});

test('하드코어 해제 요청은 오프라인에서도 성립한다 (쿨다운이 별도 관문이다)', () => {
  const plan = planSettingsSave({
    accessState: ACCESS_STATE.offline,
    current: HARDCORE_ON,
    patch: { hardcore_disable_requested_at: '2026-09-08T00:00:00.000Z' }
  });
  assert.equal(plan.action, 'local');
  assert.equal(plan.settings.hardcore_disable_requested_at, '2026-09-08T00:00:00.000Z');
});

test('온라인이면 같은 판정을 통과한 뒤 서버로 간다', () => {
  const plan = planSettingsSave({
    accessState: ACCESS_STATE.online,
    current: { hardcore_mode: false },
    patch: { daily_limit_ms: 7200000 }
  });
  assert.equal(plan.action, 'server');
});

test('로그아웃 상태의 계획은 서버로 가지 않는다', () => {
  const plan = planSettingsSave({
    accessState: ACCESS_STATE.signedOut,
    current: null,
    patch: { daily_limit_ms: 600000 }
  });
  assert.equal(plan.action, 'local');
});

// --- push 직전 재검증 (QA_REVIEW §10.4) ---
// 대기분은 오프라인 시점의 로컬 캐시로 게이트를 통과한 값이라, 그 사이 다른 기기가 규칙을
// 조였으면 그대로 올라가 잠금을 되돌린다. 안드로이드 SyncRepository.pushPendingSettings /
// planPendingSettingsPush(sync/OfflineSettings.kt)와 같은 규칙이다.

const pendingOf = (settings) => ({
  userId: 'u1',
  settings,
  updatedAtMillis: 1,
  resetStreak: false
});

test('오프라인 사이 폰이 한도를 조였으면 대기분의 한도 상향은 올라가지 않는다', () => {
  // PC 오프라인에서 60분 저장 → 그동안 폰이 10분으로 조임 → PC 복귀.
  const push = planPendingSettingsPush({
    pending: pendingOf({ daily_limit_ms: 3600000 }),
    remote: { hardcore_mode: true, daily_limit_ms: 600000 },
    cached: { hardcore_mode: true, daily_limit_ms: 1800000 }
  });
  assert.deepEqual(push.patch, {});
  assert.deepEqual(
    push.dropped.map((violation) => violation.field),
    ['daily_limit_ms']
  );
  // 캐시도 서버 값으로 되돌린다 — 화면에만 60분이 남으면 사용자는 규칙이 약해진 줄 안다.
  assert.equal(push.settings.daily_limit_ms, 600000);
});

test('거부되지 않은 필드는 그대로 올라간다 (하나 때문에 전부 버리지 않는다)', () => {
  const push = planPendingSettingsPush({
    pending: pendingOf({
      daily_limit_ms: 3600000,
      hardcore_disable_requested_at: '2026-09-08T00:00:00.000Z'
    }),
    remote: { hardcore_mode: true, daily_limit_ms: 600000, hardcore_disable_requested_at: null },
    cached: { hardcore_mode: true, daily_limit_ms: 1800000 }
  });
  // 하드코어 해제 요청은 게이트가 막는 값이 아니다(쿨다운이 별도 관문).
  assert.deepEqual(push.patch, { hardcore_disable_requested_at: '2026-09-08T00:00:00.000Z' });
  assert.equal(push.dropped.length, 1);
});

test('서버 기준으로도 통과하는 대기분은 손대지 않는다', () => {
  const push = planPendingSettingsPush({
    pending: pendingOf({ daily_limit_ms: 300000 }),
    remote: { hardcore_mode: true, daily_limit_ms: 600000 },
    cached: { hardcore_mode: true, daily_limit_ms: 600000 }
  });
  assert.deepEqual(push.patch, { daily_limit_ms: 300000 });
  assert.deepEqual(push.dropped, []);
  assert.equal(push.settings.daily_limit_ms, 300000);
});

test('하드코어가 꺼진 계정은 예전처럼 그대로 올라간다', () => {
  const push = planPendingSettingsPush({
    pending: pendingOf({ daily_limit_ms: 3600000 }),
    remote: { hardcore_mode: false, daily_limit_ms: 600000 },
    cached: { hardcore_mode: false, daily_limit_ms: 600000 }
  });
  assert.deepEqual(push.patch, { daily_limit_ms: 3600000 });
  assert.deepEqual(push.dropped, []);
});

test('서버에 행이 아직 없으면 비교 기준이 없으므로 그대로 올린다', () => {
  const push = planPendingSettingsPush({
    pending: pendingOf({ daily_limit_ms: 3600000 }),
    remote: null,
    cached: { hardcore_mode: true, daily_limit_ms: 600000 }
  });
  assert.deepEqual(push.patch, { daily_limit_ms: 3600000 });
  assert.deepEqual(push.dropped, []);
});

test('한 필드를 빼면 다른 규칙의 기준이 바뀌는 경우도 통과할 때까지 걸러낸다', () => {
  // 요일별 한도는 빠진 요일을 기본 한도로 채워 비교하므로, 기본 한도만 빼면 요일별 한도가
  // 서버의 기본 한도(10분)와 비교돼 그때 걸린다.
  const push = planPendingSettingsPush({
    pending: pendingOf({ daily_limit_ms: 3600000, daily_limit_by_day: { 3: 5400000 } }),
    remote: { hardcore_mode: true, daily_limit_ms: 600000, daily_limit_by_day: {} },
    cached: { hardcore_mode: true, daily_limit_ms: 600000, daily_limit_by_day: {} }
  });
  assert.deepEqual(push.patch, {});
  assert.deepEqual(push.dropped.map((violation) => violation.field).sort(), [
    'daily_limit_by_day',
    'daily_limit_ms'
  ]);
});
