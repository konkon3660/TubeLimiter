import { test } from 'node:test';
import assert from 'node:assert/strict';
import { computeLimitForDate } from '../src/lib/limits.js';

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
