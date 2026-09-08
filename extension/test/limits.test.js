import { test } from 'node:test';
import assert from 'node:assert/strict';
import { computeLimitForDate, computeShortsLimit } from '../src/lib/limits.js';

// Cross-checked against android/app/src/test/java/com/tubelimiter/app/limit/LimitRulesTest.kt
// and AlarmRulesTest.kt for equivalent scenarios. Note the extension's settings shape stores
// the daily limit directly in milliseconds (daily_limit_ms) rather than minutes, and the
// per-day table (daily_limit_by_day) is keyed by JS Date#getDay() (Sunday = 0), matching the
// Kotlin fixture's "Sunday first" per-day table.

const MIN = 60 * 1000;

// 2026-09-02 is a Wednesday; 2026-09-06 is a Sunday (same reference dates as the Kotlin suite).
const WEDNESDAY = '2026-09-02';
const SUNDAY = '2026-09-06';

test('missing settings falls back to a 30 minute default', () => {
  assert.equal(computeLimitForDate(null, WEDNESDAY), 30 * MIN);
  assert.equal(computeLimitForDate(undefined, WEDNESDAY), 30 * MIN);
});

test('daily frequency uses daily_limit_ms regardless of the date', () => {
  const settings = { daily_limit_reset_frequency: 'daily', daily_limit_ms: 45 * MIN };
  assert.equal(computeLimitForDate(settings, WEDNESDAY), 45 * MIN);
  assert.equal(computeLimitForDate(settings, SUNDAY), 45 * MIN);
});

test('daily frequency ignores a populated per-day table', () => {
  const settings = {
    daily_limit_reset_frequency: 'daily',
    daily_limit_ms: 45 * MIN,
    daily_limit_by_day: [5, 5, 5, 5, 5, 5, 5]
  };
  assert.equal(computeLimitForDate(settings, WEDNESDAY), 45 * MIN);
});

test('a zero daily_limit_ms means unlimited, not blocked-always', () => {
  const settings = { daily_limit_reset_frequency: 'daily', daily_limit_ms: 0 };
  assert.equal(computeLimitForDate(settings, WEDNESDAY), Infinity);
});

test('an undefined or null daily_limit_ms means unlimited', () => {
  assert.equal(computeLimitForDate({ daily_limit_reset_frequency: 'daily' }, WEDNESDAY), Infinity);
  assert.equal(
    computeLimitForDate({ daily_limit_reset_frequency: 'daily', daily_limit_ms: null }, WEDNESDAY),
    Infinity
  );
});

test('an unrecognized frequency falls back to the daily_limit_ms branch', () => {
  const settings = { daily_limit_reset_frequency: undefined, daily_limit_ms: 10 * MIN };
  assert.equal(computeLimitForDate(settings, WEDNESDAY), 10 * MIN);
});

test('per-day limits are indexed with Sunday first', () => {
  const byDay = [10, 20, 30, 40, 50, 60, 70];
  const settings = { daily_limit_reset_frequency: 'by_day', daily_limit_by_day: byDay };
  assert.equal(computeLimitForDate(settings, SUNDAY), 10 * MIN);
  assert.equal(computeLimitForDate(settings, WEDNESDAY), 40 * MIN);
});

test('the per-day sentinel (-1) means unlimited', () => {
  const settings = {
    daily_limit_reset_frequency: 'by_day',
    daily_limit_by_day: [-1, -1, -1, -1, -1, -1, -1]
  };
  assert.equal(computeLimitForDate(settings, WEDNESDAY), Infinity);
});

test('a missing entry in the per-day table means unlimited', () => {
  const settings = { daily_limit_reset_frequency: 'by_day', daily_limit_by_day: undefined };
  assert.equal(computeLimitForDate(settings, WEDNESDAY), Infinity);
});

test('a zero minute per-day entry is treated as zero, not unlimited (only -1 is the sentinel)', () => {
  const byDay = [0, 0, 0, 0, 0, 0, 0];
  const settings = { daily_limit_reset_frequency: 'by_day', daily_limit_by_day: byDay };
  assert.equal(computeLimitForDate(settings, WEDNESDAY), 0);
});

// --- Shorts 전용 한도 (settings.shorts_limit_ms, 브라우저 전용 컬럼) ---
// 안드로이드에는 대응 로직이 없다 — 앱 단위 감지라 화면이 Shorts인지 알 수 없기 때문
// (documents/BACKEND.md의 whitelist/always_block_shorts와 같은 부류).

test('shorts_limit_ms가 설정돼 있으면 그 값이 그대로 Shorts 한도다', () => {
  assert.equal(computeShortsLimit({ shorts_limit_ms: 10 * MIN }), 10 * MIN);
});

test('shorts_limit_ms가 0·null·미설정이면 Shorts 한도 없음(Infinity)이다', () => {
  assert.equal(computeShortsLimit({ shorts_limit_ms: 0 }), Infinity);
  assert.equal(computeShortsLimit({ shorts_limit_ms: null }), Infinity);
  assert.equal(computeShortsLimit({}), Infinity);
  assert.equal(computeShortsLimit(null), Infinity);
  assert.equal(computeShortsLimit(undefined), Infinity);
});

test('음수 shorts_limit_ms도 한도 없음으로 접는다 (영구 차단 사고 방지)', () => {
  assert.equal(computeShortsLimit({ shorts_limit_ms: -1 }), Infinity);
});

test('Shorts 한도는 전체 한도와 완전히 독립이다', () => {
  const settings = {
    daily_limit_reset_frequency: 'daily',
    daily_limit_ms: 120 * MIN,
    shorts_limit_ms: 10 * MIN
  };
  assert.equal(computeLimitForDate(settings, WEDNESDAY), 120 * MIN);
  assert.equal(computeShortsLimit(settings), 10 * MIN);

  // 전체가 무제한이어도 Shorts 한도는 그대로 살아 있다.
  const unlimitedOverall = {
    daily_limit_reset_frequency: 'daily',
    daily_limit_ms: 0,
    shorts_limit_ms: 10 * MIN
  };
  assert.equal(computeLimitForDate(unlimitedOverall, WEDNESDAY), Infinity);
  assert.equal(computeShortsLimit(unlimitedOverall), 10 * MIN);
});

test('요일별 한도 설정을 써도 Shorts 한도는 요일과 무관하게 같은 값이다', () => {
  const settings = {
    daily_limit_reset_frequency: 'by_day',
    daily_limit_by_day: [10, 20, 30, 40, 50, 60, 70],
    shorts_limit_ms: 10 * MIN
  };
  assert.equal(computeShortsLimit(settings), 10 * MIN);
  assert.notEqual(computeLimitForDate(settings, SUNDAY), computeLimitForDate(settings, WEDNESDAY));
});
