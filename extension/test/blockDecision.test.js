import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  BLOCK_REASON,
  isUsageLimitExceeded,
  isShortsLimitExceeded,
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
    // Shorts 한도는 기본이 "미설정 = 무제한"이다 (computeShortsLimit이 0/null을 Infinity로 접는다).
    shortsUsedMs: 0,
    shortsLimitMs: Infinity,
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

// trackingBlocked는 storage의 trackingBlocked로 저장돼 trackUsage의 집계 중단 판정에 쓰인다.
// 예전엔 이 판정에도 displayBlocked를 써서, 수동 차단 위에서 쓴 긴급 시청은 탭만 풀리고 시간은
// 집계되지 않는 비대칭이 있었다 (안드로이드엔 이 게이트 자체가 없어 늘 집계된다).
test('수동 차단 위에서 쓴 긴급 시청 시간도 집계된다 (표시는 차단인 채로)', () => {
  const decision = resolveBlockDecision(inputs({ manuallyBlocked: true, emergencyModeActive: true }));
  assert.equal(decision.displayBlocked, true);
  assert.equal(decision.trackingBlocked, false);
});

test('긴급 시청 집계는 차단 사유(수동/한도)와 무관하게 같다', () => {
  const overManual = resolveBlockDecision(inputs({ manuallyBlocked: true, emergencyModeActive: true }));
  const overLimit = resolveBlockDecision(inputs({ usedMs: 60 * MIN, emergencyModeActive: true }));
  assert.equal(overManual.trackingBlocked, overLimit.trackingBlocked);
  assert.equal(overLimit.trackingBlocked, false);
});

test('긴급 시청이 없으면 수동 차단·한도 초과 둘 다 집계가 멈춘다', () => {
  assert.equal(resolveBlockDecision(inputs({ manuallyBlocked: true })).trackingBlocked, true);
  assert.equal(resolveBlockDecision(inputs({ usedMs: 60 * MIN })).trackingBlocked, true);
});

test('집중 모드·예약 차단은 긴급 시청 중에도 집계가 멈춘다 (애초에 뚫리지 않는 차단)', () => {
  const focus = resolveBlockDecision(inputs({ focusModeActive: true, emergencyModeActive: true }));
  const schedule = resolveBlockDecision(inputs({ scheduleBlockActive: true, emergencyModeActive: true }));
  assert.equal(focus.trackingBlocked, true);
  assert.equal(schedule.trackingBlocked, true);
});

test('아무 차단도 없으면 당연히 집계된다', () => {
  assert.equal(resolveBlockDecision(inputs({ usedMs: 10 * MIN })).trackingBlocked, false);
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

// --- Shorts 전용 일일 한도 ---
// 전체 한도와 독립이고, 넘겨도 Shorts 탭만 막힌다. 우선순위상 "한도" 급이라 긴급 시청으로는
// 뚫리고 집중 모드/예약 차단은 못 뚫는다 (lib/blockDecision.js 상단 우선순위 주석 참고).

const SHORTS_TAB = { isWhitelisted: false, isShortsTab: true, alwaysBlockShorts: false };
const WATCH_TAB = { isWhitelisted: false, isShortsTab: false, alwaysBlockShorts: false };

test('Shorts 한도에 정확히 도달한 순간 차단된다 (경계는 >=)', () => {
  assert.equal(isShortsLimitExceeded(10 * MIN, 10 * MIN), true);
  assert.equal(isShortsLimitExceeded(10 * MIN - 1, 10 * MIN), false);

  const atLimit = resolveTabBlock(inputs({ shortsUsedMs: 10 * MIN, shortsLimitMs: 10 * MIN }), SHORTS_TAB);
  assert.equal(atLimit.shouldBlock, true);
  assert.equal(atLimit.reason, BLOCK_REASON.shortsLimit);

  const justUnder = resolveTabBlock(inputs({ shortsUsedMs: 10 * MIN - 1, shortsLimitMs: 10 * MIN }), SHORTS_TAB);
  assert.equal(justUnder.shouldBlock, false);
});

test('Shorts 한도가 미설정(무제한)이면 아무리 봐도 안 막힌다', () => {
  const decision = resolveTabBlock(inputs({ shortsUsedMs: 5 * 60 * MIN, shortsLimitMs: Infinity }), SHORTS_TAB);
  assert.equal(decision.shouldBlock, false);
  // 인자 자체가 없어도(옛 저장소에서 올라온 판정) 무제한으로 떨어져야 한다.
  assert.equal(isShortsLimitExceeded(5 * 60 * MIN), false);
  assert.equal(isShortsLimitExceeded(), false);
});

test('전체 한도가 남아 있어도 Shorts 한도를 넘기면 Shorts 탭만 막힌다', () => {
  const overShorts = inputs({ usedMs: 5 * MIN, limitMs: 120 * MIN, shortsUsedMs: 12 * MIN, shortsLimitMs: 10 * MIN });

  const shorts = resolveTabBlock(overShorts, SHORTS_TAB);
  assert.equal(shorts.shouldBlock, true);
  assert.equal(shorts.reason, BLOCK_REASON.shortsLimit);

  // 일반 영상은 그대로 볼 수 있어야 한다 — 이게 안 되면 그냥 전체 한도를 앞당긴 것과 다를 게 없다.
  assert.equal(resolveTabBlock(overShorts, WATCH_TAB).shouldBlock, false);

  // 전역 판정(= 표시용 isYoutubeBlocked, 집계 게이트)에도 영향을 주지 않는다.
  const global = resolveBlockDecision(overShorts);
  assert.equal(global.shouldBlock, false);
  assert.equal(global.displayBlocked, false);
  assert.equal(global.trackingBlocked, false);
});

test('Shorts 항상 차단이 켜져 있으면 Shorts 한도가 남아 있어도 그 사유로 막힌다', () => {
  const tab = { isWhitelisted: false, isShortsTab: true, alwaysBlockShorts: true };
  const decision = resolveTabBlock(inputs({ shortsUsedMs: 0, shortsLimitMs: 10 * MIN }), tab);
  assert.equal(decision.shouldBlock, true);
  assert.equal(decision.reason, BLOCK_REASON.alwaysBlockShorts);

  // 둘 다 걸린 상태에서도 "항상 차단"이 먼저다 (한도와 무관한 on/off라 사유가 더 정확하다).
  const both = resolveTabBlock(inputs({ shortsUsedMs: 12 * MIN, shortsLimitMs: 10 * MIN }), tab);
  assert.equal(both.reason, BLOCK_REASON.alwaysBlockShorts);
});

test('Shorts 한도는 긴급 시청으로 뚫린다 (전체 한도와 같은 급)', () => {
  const decision = resolveTabBlock(
    inputs({ shortsUsedMs: 12 * MIN, shortsLimitMs: 10 * MIN, emergencyModeActive: true }),
    SHORTS_TAB
  );
  assert.equal(decision.shouldBlock, false);
});

test('집중 모드·예약 차단 중에는 Shorts 한도가 남아 있어도 긴급 시청으로 못 뚫는다', () => {
  const focus = resolveTabBlock(
    inputs({ shortsUsedMs: 12 * MIN, shortsLimitMs: 10 * MIN, focusModeActive: true, emergencyModeActive: true }),
    SHORTS_TAB
  );
  assert.equal(focus.shouldBlock, true);
  assert.equal(focus.reason, BLOCK_REASON.focusMode);

  const schedule = resolveTabBlock(
    inputs({ shortsUsedMs: 12 * MIN, shortsLimitMs: 10 * MIN, scheduleBlockActive: true, emergencyModeActive: true }),
    SHORTS_TAB
  );
  assert.equal(schedule.shouldBlock, true);
  assert.equal(schedule.reason, BLOCK_REASON.scheduledBlock);
});

test('화이트리스트는 Shorts 한도도 뚫는다 (한도 급 차단이라)', () => {
  const tab = { isWhitelisted: true, isShortsTab: true, alwaysBlockShorts: false };
  assert.equal(resolveTabBlock(inputs({ shortsUsedMs: 12 * MIN, shortsLimitMs: 10 * MIN }), tab).shouldBlock, false);
});

test('전체 한도도 같이 넘겼으면 전역 사유(전체 한도)가 그대로 쓰인다', () => {
  const decision = resolveTabBlock(
    inputs({ usedMs: 60 * MIN, limitMs: 30 * MIN, shortsUsedMs: 12 * MIN, shortsLimitMs: 10 * MIN }),
    SHORTS_TAB
  );
  assert.equal(decision.shouldBlock, true);
  assert.equal(decision.reason, BLOCK_REASON.usageLimit);
});

test('수동 차단은 Shorts 한도보다 먼저 사유로 잡힌다', () => {
  const decision = resolveTabBlock(
    inputs({ manuallyBlocked: true, shortsUsedMs: 12 * MIN, shortsLimitMs: 10 * MIN }),
    SHORTS_TAB
  );
  assert.equal(decision.shouldBlock, true);
  assert.equal(decision.reason, BLOCK_REASON.manualBlock);
});
