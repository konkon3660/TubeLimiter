import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  BLOCK_REASON,
  isUsageLimitExceeded,
  isWhitelistedUrl,
  isShortsUrl,
  resolveBlockDecision,
  resolveTabBlock
} from '../src/lib/blockDecision.js';

// android/app/src/main/java/com/tubelimiter/app/limit/BlockDecision.kt의 BlockInputs.blockReason()과
// 같은 우선순위를 고정하는 테스트다 (안드로이드 쪽 BlockDecisionTest.kt와 짝). 화이트리스트/Shorts는
// 브라우저 전용이라 확장에만 있다 (documents/BACKEND.md의 scheduled_blocks 항목 참고).

const MIN = 60 * 1000;

/** 아무것도 안 걸린 평범한 상태. 각 테스트에서 필요한 것만 덮어쓴다. */
function inputs(overrides = {}) {
  return {
    usedMs: 0,
    limitMs: 30 * MIN,
    focusModeActive: false,
    scheduleBlockActive: false,
    emergencyModeActive: false,
    manuallyBlocked: false,
    ...overrides
  };
}

test('아무 조건도 안 걸리면 차단하지 않는다', () => {
  const decision = resolveBlockDecision(inputs({ usedMs: 10 * MIN }));
  assert.equal(decision.shouldBlock, false);
  assert.equal(decision.reason, '');
  assert.equal(decision.displayBlocked, false);
});

test('한도에 정확히 도달한 순간 차단된다 (경계는 >=)', () => {
  assert.equal(isUsageLimitExceeded(30 * MIN, 30 * MIN), true);
  assert.equal(isUsageLimitExceeded(30 * MIN - 1, 30 * MIN), false);

  const atLimit = resolveBlockDecision(inputs({ usedMs: 30 * MIN }));
  assert.equal(atLimit.shouldBlock, true);
  assert.equal(atLimit.reason, BLOCK_REASON.usageLimit);

  const justUnder = resolveBlockDecision(inputs({ usedMs: 30 * MIN - 1 }));
  assert.equal(justUnder.shouldBlock, false);
});

test('무제한 한도(Infinity)는 아무리 봐도 한도 차단이 안 걸린다', () => {
  const decision = resolveBlockDecision(inputs({ usedMs: 24 * 60 * MIN, limitMs: Infinity }));
  assert.equal(decision.shouldBlock, false);
  assert.equal(decision.displayBlocked, false);
});

test('집중 모드가 예약 차단·긴급 시청·수동 차단·한도보다 우선한다', () => {
  const decision = resolveBlockDecision(inputs({
    usedMs: 100 * MIN,
    focusModeActive: true,
    scheduleBlockActive: true,
    emergencyModeActive: true,
    manuallyBlocked: true
  }));
  assert.equal(decision.shouldBlock, true);
  assert.equal(decision.reason, BLOCK_REASON.focusMode);
});

test('예약 차단은 긴급 시청으로 우회되지 않는다', () => {
  const decision = resolveBlockDecision(inputs({ scheduleBlockActive: true, emergencyModeActive: true }));
  assert.equal(decision.shouldBlock, true);
  assert.equal(decision.reason, BLOCK_REASON.scheduledBlock);
});

test('집중 모드도 긴급 시청으로 우회되지 않는다', () => {
  const decision = resolveBlockDecision(inputs({ focusModeActive: true, emergencyModeActive: true }));
  assert.equal(decision.shouldBlock, true);
  assert.equal(decision.reason, BLOCK_REASON.focusMode);
});

test('긴급 시청은 한도 차단과 수동 차단을 뚫는다', () => {
  const overLimit = resolveBlockDecision(inputs({ usedMs: 60 * MIN, emergencyModeActive: true }));
  assert.equal(overLimit.shouldBlock, false);

  const manual = resolveBlockDecision(inputs({ manuallyBlocked: true, emergencyModeActive: true }));
  assert.equal(manual.shouldBlock, false);
});

test('긴급 시청이 만료된 직후엔 다시 한도 차단으로 돌아온다', () => {
  const during = resolveBlockDecision(inputs({ usedMs: 60 * MIN, emergencyModeActive: true }));
  assert.equal(during.shouldBlock, false);

  // 서비스워커가 emergencyModeActive를 내린 다음 틱 — 사용량은 그대로인데 차단이 되살아난다.
  const after = resolveBlockDecision(inputs({ usedMs: 60 * MIN, emergencyModeActive: false }));
  assert.equal(after.shouldBlock, true);
  assert.equal(after.reason, BLOCK_REASON.usageLimit);
});

test('수동 차단이 한도보다 먼저 사유로 잡힌다', () => {
  const decision = resolveBlockDecision(inputs({ usedMs: 60 * MIN, manuallyBlocked: true }));
  assert.equal(decision.reason, BLOCK_REASON.manualBlock);
});

// displayBlocked는 storage의 isYoutubeBlocked로 저장돼 팝업 표시/긴급 시청 요청 가능 여부와
// 사용시간 집계 중단 판정에 쓰인다. shouldBlock과 갈라지는 지점을 고정해둔다.
test('긴급 시청 중 한도 초과는 표시 상태에서도 풀려 사용시간이 계속 집계된다', () => {
  const decision = resolveBlockDecision(inputs({ usedMs: 60 * MIN, emergencyModeActive: true }));
  assert.equal(decision.shouldBlock, false);
  assert.equal(decision.displayBlocked, false);
});

test('긴급 시청 중이어도 수동 차단은 표시 상태로 남는다', () => {
  const decision = resolveBlockDecision(inputs({ manuallyBlocked: true, emergencyModeActive: true }));
  assert.equal(decision.shouldBlock, false); // 지금 이 순간은 뚫려 있지만
  assert.equal(decision.displayBlocked, true); // "차단해둔 상태"라는 사실은 유지된다
});

test('isWhitelistedUrl은 부분 문자열 매칭이고 URL/목록이 없으면 false다', () => {
  assert.equal(isWhitelistedUrl('https://www.youtube.com/@lecture/videos', ['youtube.com/@lecture']), true);
  assert.equal(isWhitelistedUrl('https://www.youtube.com/watch?v=abc', ['youtube.com/@lecture']), false);
  assert.equal(isWhitelistedUrl(undefined, ['youtube.com/@lecture']), false);
  assert.equal(isWhitelistedUrl('https://www.youtube.com/', []), false);
  assert.equal(isWhitelistedUrl('https://www.youtube.com/', undefined), false);
});

test('isShortsUrl은 Shorts 시청 페이지만 잡는다', () => {
  assert.equal(isShortsUrl('https://www.youtube.com/shorts/abcd'), true);
  assert.equal(isShortsUrl('https://www.youtube.com/watch?v=abcd'), false);
  assert.equal(isShortsUrl(null), false);
});

const WHITELISTED_TAB = { isWhitelisted: true, isShortsTab: false, alwaysBlockShorts: false };

test('화이트리스트는 집중 모드를 뚫지 못한다', () => {
  const tabDecision = resolveTabBlock(inputs({ focusModeActive: true }), WHITELISTED_TAB);
  assert.equal(tabDecision.shouldBlock, true);
  assert.equal(tabDecision.reason, BLOCK_REASON.focusMode);
});

test('화이트리스트는 예약 차단도 뚫지 못한다', () => {
  const tabDecision = resolveTabBlock(inputs({ scheduleBlockActive: true }), WHITELISTED_TAB);
  assert.equal(tabDecision.shouldBlock, true);
  assert.equal(tabDecision.reason, BLOCK_REASON.scheduledBlock);
});

test('화이트리스트는 한도 차단과 수동 차단은 뚫는다', () => {
  assert.equal(resolveTabBlock(inputs({ usedMs: 60 * MIN }), WHITELISTED_TAB).shouldBlock, false);
  assert.equal(resolveTabBlock(inputs({ manuallyBlocked: true }), WHITELISTED_TAB).shouldBlock, false);
});

test('Shorts 항상 차단은 한도가 남아 있어도 Shorts 탭만 막는다', () => {
  const shortsTab = { isWhitelisted: false, isShortsTab: true, alwaysBlockShorts: true };
  const watchTab = { isWhitelisted: false, isShortsTab: false, alwaysBlockShorts: true };

  const blocked = resolveTabBlock(inputs(), shortsTab);
  assert.equal(blocked.shouldBlock, true);
  assert.equal(blocked.reason, BLOCK_REASON.alwaysBlockShorts);
  assert.equal(resolveTabBlock(inputs(), watchTab).shouldBlock, false);
});

test('화이트리스트가 Shorts 항상 차단보다 먼저 적용된다', () => {
  const tab = { isWhitelisted: true, isShortsTab: true, alwaysBlockShorts: true };
  assert.equal(resolveTabBlock(inputs(), tab).shouldBlock, false);
});

test('긴급 시청 중에는 Shorts 항상 차단도 잠시 풀린다', () => {
  const tab = { isWhitelisted: false, isShortsTab: true, alwaysBlockShorts: true };
  assert.equal(resolveTabBlock(inputs({ emergencyModeActive: true }), tab).shouldBlock, false);
});

test('탭별 예외가 없으면 전역 판정을 그대로 따른다', () => {
  const tab = { isWhitelisted: false, isShortsTab: false, alwaysBlockShorts: false };
  const tabDecision = resolveTabBlock(inputs({ usedMs: 60 * MIN }), tab);
  assert.equal(tabDecision.shouldBlock, true);
  assert.equal(tabDecision.reason, BLOCK_REASON.usageLimit);
});
