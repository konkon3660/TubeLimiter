// 하드코어 모드가 막아야 하는 변경 목록을 고정한다.
//
// 이 목록이 이 파일의 존재 이유다. 설정 항목이 하나 늘 때마다 "잠가야 하는가"를 묻지 않으면
// 예전처럼 조용히 우회로가 생긴다 - 실제로 화이트리스트·긴급 횟수·예약 차단 삭제·Shorts 항상
// 차단 넷이 그렇게 열려 있었다(documents/QA_REVIEW.md §1.2).

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  HARDCORE_VIOLATION,
  findHardcoreViolations,
  isHardcoreChangeAllowed
} from '../src/lib/hardcoreLock.js';

const BASE = Object.freeze({
  hardcore_mode: true,
  daily_limit_ms: 1800000,
  daily_limit_by_day: { 0: 3600000 },
  shorts_limit_ms: 600000,
  whitelist: ['youtube.com/@lecture'],
  always_block_shorts: true,
  emergency_config: { dailyUses: 3, resetFrequency: 'weekly' },
  scheduled_blocks: [
    { id: 'a', days: [1, 1, 1, 1, 1, 1, 1], startMinute: 1320, endMinute: 420, enabled: true }
  ]
});

const withChange = (patch) => ({ ...BASE, ...patch });
const keysOf = (violations) => violations.map((v) => v.messageKey);

test('하드코어가 꺼져 있으면 무엇이든 허용된다', () => {
  const off = { ...BASE, hardcore_mode: false };
  const result = isHardcoreChangeAllowed(off, {
    ...off,
    daily_limit_ms: 99999999,
    whitelist: ['/']
  });
  assert.equal(result.allowed, true);
  assert.deepEqual(result.violations, []);
});

test('아무것도 안 바꾸면 위반이 없다', () => {
  assert.deepEqual(findHardcoreViolations(BASE, { ...BASE }), []);
});

test('한도를 늘리는 건 막고 줄이는 건 허용한다', () => {
  assert.deepEqual(keysOf(findHardcoreViolations(BASE, withChange({ daily_limit_ms: 3600000 }))), [
    HARDCORE_VIOLATION.dailyLimit
  ]);
  assert.deepEqual(findHardcoreViolations(BASE, withChange({ daily_limit_ms: 600000 })), []);
  // 무제한(0)은 가장 느슨한 값이라 막힌다.
  assert.deepEqual(keysOf(findHardcoreViolations(BASE, withChange({ daily_limit_ms: 0 }))), [
    HARDCORE_VIOLATION.dailyLimit
  ]);
});

test('요일별 한도는 요일 하나만 늘어나도 막는다', () => {
  const loosened = withChange({ daily_limit_by_day: { 0: 7200000 } });
  assert.ok(keysOf(findHardcoreViolations(BASE, loosened)).includes(HARDCORE_VIOLATION.byDayLimit));
  // 요일 오버라이드를 지우면 기본 한도(30분)로 돌아가므로 오히려 세진다 - 허용.
  assert.deepEqual(findHardcoreViolations(BASE, withChange({ daily_limit_by_day: {} })), []);
});

test('Shorts 한도를 늘리거나 푸는 건 막는다', () => {
  assert.deepEqual(keysOf(findHardcoreViolations(BASE, withChange({ shorts_limit_ms: 900000 }))), [
    HARDCORE_VIOLATION.shortsLimit
  ]);
  assert.deepEqual(keysOf(findHardcoreViolations(BASE, withChange({ shorts_limit_ms: 0 }))), [
    HARDCORE_VIOLATION.shortsLimit
  ]);
});

test('화이트리스트는 추가만 막고 삭제는 허용한다', () => {
  const added = withChange({ whitelist: ['youtube.com/@lecture', '@other'] });
  assert.deepEqual(keysOf(findHardcoreViolations(BASE, added)), [HARDCORE_VIOLATION.whitelist]);
  assert.deepEqual(findHardcoreViolations(BASE, withChange({ whitelist: [] })), []);
});

test('긴급 시청 횟수 상향과 리셋 주기 단축을 막는다', () => {
  assert.ok(
    keysOf(
      findHardcoreViolations(
        BASE,
        withChange({ emergency_config: { dailyUses: 5, resetFrequency: 'weekly' } })
      )
    ).includes(HARDCORE_VIOLATION.emergencyUses)
  );
  // weekly -> daily는 "더 자주 채워짐"이라 약화다.
  assert.ok(
    keysOf(
      findHardcoreViolations(
        BASE,
        withChange({ emergency_config: { dailyUses: 3, resetFrequency: 'daily' } })
      )
    ).includes(HARDCORE_VIOLATION.emergencyUses)
  );
  // 줄이는 방향은 허용.
  assert.deepEqual(
    findHardcoreViolations(
      BASE,
      withChange({ emergency_config: { dailyUses: 1, resetFrequency: 'monthly' } })
    ),
    []
  );
});

test('Shorts 항상 차단은 끄는 것만 막는다', () => {
  assert.deepEqual(
    keysOf(findHardcoreViolations(BASE, withChange({ always_block_shorts: false }))),
    [HARDCORE_VIOLATION.alwaysBlockShorts]
  );
  const off = { ...BASE, always_block_shorts: false };
  assert.deepEqual(findHardcoreViolations(off, { ...off, always_block_shorts: true }), []);
});

test('예약 차단은 삭제·비활성화·축소를 막고 추가·확대는 허용한다', () => {
  assert.deepEqual(keysOf(findHardcoreViolations(BASE, withChange({ scheduled_blocks: [] }))), [
    HARDCORE_VIOLATION.scheduledBlocks
  ]);

  const disabled = withChange({
    scheduled_blocks: [{ ...BASE.scheduled_blocks[0], enabled: false }]
  });
  assert.deepEqual(keysOf(findHardcoreViolations(BASE, disabled)), [
    HARDCORE_VIOLATION.scheduledBlocks
  ]);

  const shrunk = withChange({
    scheduled_blocks: [{ ...BASE.scheduled_blocks[0], startMinute: 1380 }]
  });
  assert.deepEqual(keysOf(findHardcoreViolations(BASE, shrunk)), [
    HARDCORE_VIOLATION.scheduledBlocks
  ]);

  const addedBlock = withChange({
    scheduled_blocks: [
      BASE.scheduled_blocks[0],
      { id: 'b', days: [1, 0, 0, 0, 0, 0, 0], startMinute: 600, endMinute: 700, enabled: true }
    ]
  });
  assert.deepEqual(findHardcoreViolations(BASE, addedBlock), []);
});

test('하드코어 해제 요청 자체는 막지 않는다', () => {
  // 해제는 1시간 쿨다운(lib/hardcore.js)이 담당하는 별개 관문이다. 여기서 또 막으면 해제 요청을
  // 저장할 수조차 없게 된다.
  const requested = withChange({ hardcore_disable_requested_at: new Date().toISOString() });
  assert.equal(isHardcoreChangeAllowed(BASE, requested).allowed, true);
});

test('여러 항목을 한 번에 약화시키면 전부 보고한다', () => {
  const all = withChange({
    daily_limit_ms: 3600000,
    whitelist: ['youtube.com/@lecture', '@other'],
    always_block_shorts: false,
    scheduled_blocks: []
  });
  const keys = keysOf(findHardcoreViolations(BASE, all));
  assert.ok(keys.includes(HARDCORE_VIOLATION.dailyLimit));
  assert.ok(keys.includes(HARDCORE_VIOLATION.whitelist));
  assert.ok(keys.includes(HARDCORE_VIOLATION.alwaysBlockShorts));
  assert.ok(keys.includes(HARDCORE_VIOLATION.scheduledBlocks));
});
