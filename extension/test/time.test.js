import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  getTodayDate,
  getYesterdayDate,
  addDaysToDate,
  formatDate,
  getWeekStartDate,
  getMonthStartDate
} from '../src/lib/time.js';

// The module treats the day as starting at 04:00 local time, not midnight — usage before
// 4am counts toward the previous day. All "now" values below are constructed with the
// local Date constructor (new Date(y, m, d, h, m, s)), so these tests are timezone-agnostic:
// they pass in whatever local timezone the test runner executes in.

test('formatDate pads single-digit month and day', () => {
  assert.equal(formatDate(new Date(2026, 0, 5)), '2026-01-05');
  assert.equal(formatDate(new Date(2026, 8, 30)), '2026-09-30');
});

test('getTodayDate before the 4am cutoff reports the previous calendar day', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: new Date(2026, 8, 3, 3, 59, 59).getTime() });
  assert.equal(getTodayDate(), '2026-09-02');
});

test('getTodayDate at exactly the 4am cutoff reports the new day', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: new Date(2026, 8, 3, 4, 0, 0).getTime() });
  assert.equal(getTodayDate(), '2026-09-03');
});

test('getTodayDate just after the 4am cutoff reports the new day', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: new Date(2026, 8, 3, 4, 0, 1).getTime() });
  assert.equal(getTodayDate(), '2026-09-03');
});

test('getTodayDate mid-afternoon is unaffected by the cutoff', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: new Date(2026, 8, 3, 15, 30, 0).getTime() });
  assert.equal(getTodayDate(), '2026-09-03');
});

test('getYesterdayDate is one day behind the effective today, including before the cutoff', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: new Date(2026, 8, 3, 3, 0, 0).getTime() });
  // Effective "today" here is 2026-09-02 (still before the 4am cutoff), so yesterday is 09-01.
  assert.equal(getTodayDate(), '2026-09-02');
  assert.equal(getYesterdayDate(), '2026-09-01');
});

test('getYesterdayDate crosses a month boundary correctly', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: new Date(2026, 9, 1, 12, 0, 0).getTime() });
  assert.equal(getYesterdayDate(), '2026-09-30');
});

// NOTE: getYesterdayDate is exported but currently has zero callers anywhere under
// extension/src (verified via repo-wide search). Still tested here since it is public
// API of this module, but flagging it as unused per the audit.

test('addDaysToDate adds positive days across a month boundary', () => {
  assert.equal(addDaysToDate('2026-09-28', 5), '2026-10-03');
});

test('addDaysToDate subtracts (negative days) across a year boundary', () => {
  assert.equal(addDaysToDate('2026-01-02', -5), '2025-12-28');
});

test('addDaysToDate handles a non-leap-year February correctly', () => {
  // 2026 is not a leap year.
  assert.equal(addDaysToDate('2026-02-28', 1), '2026-03-01');
});

test('addDaysToDate with zero days returns the same date', () => {
  assert.equal(addDaysToDate('2026-05-15', 0), '2026-05-15');
});

test('getWeekStartDate anchors mid-week days to the preceding Monday', (t) => {
  // 2026-09-02 is a Wednesday; effective "now" at noon is unaffected by the 4am cutoff.
  t.mock.timers.enable({ apis: ['Date'], now: new Date(2026, 8, 2, 12, 0, 0).getTime() });
  assert.equal(getWeekStartDate(), '2026-08-31');
});

test('getWeekStartDate treats Sunday as the end of the week, not the start', (t) => {
  // 2026-09-06 is a Sunday; the Monday-based week it belongs to starts 2026-08-31.
  t.mock.timers.enable({ apis: ['Date'], now: new Date(2026, 8, 6, 12, 0, 0).getTime() });
  assert.equal(getWeekStartDate(), '2026-08-31');
});

test('getWeekStartDate on a Monday returns that same day', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: new Date(2026, 7, 31, 12, 0, 0).getTime() });
  assert.equal(getWeekStartDate(), '2026-08-31');
});

test('getMonthStartDate returns the first of the effective month', (t) => {
  t.mock.timers.enable({ apis: ['Date'], now: new Date(2026, 8, 17, 12, 0, 0).getTime() });
  assert.equal(getMonthStartDate(), '2026-09-01');
});

test('getMonthStartDate before the cutoff on the 1st rolls back to the previous month', (t) => {
  // 2026-09-01 at 2am is, per the 4am cutoff, still "2026-08-31".
  t.mock.timers.enable({ apis: ['Date'], now: new Date(2026, 8, 1, 2, 0, 0).getTime() });
  assert.equal(getTodayDate(), '2026-08-31');
  assert.equal(getMonthStartDate(), '2026-08-01');
});
