import { test } from 'node:test';
import assert from 'node:assert/strict';
import { isScheduleActive, minutesUntilNextScheduleStart } from '../src/lib/schedule.js';

// Reference dates (same ones the Kotlin ScheduleRulesTest.kt and BlockDecisionTest.kt use, and
// the same chain limits.test.js anchors on): 2026-08-31 is a Monday, 2026-09-01 is a Tuesday,
// 2026-09-02 is a Wednesday, 2026-09-06 is a Sunday.
const AUG = 7; // JS Date month index (0-based) for August
const SEP = 8; // JS Date month index (0-based) for September

function at(monthIndex, day, hour, minute = 0) {
  return new Date(2026, monthIndex, day, hour, minute, 0);
}

const ALL_DAYS = [1, 1, 1, 1, 1, 1, 1]; // Sun..Sat, all on
const MON_ONLY = [0, 1, 0, 0, 0, 0, 0]; // Sunday=0 index convention, Monday=index 1
const WED_ONLY = [0, 0, 0, 1, 0, 0, 0];

test('non-wrapping window is active within its start/end minutes on an included day', () => {
  const windows = [
    {
      id: '1',
      label: '낮잠 방지',
      days: ALL_DAYS,
      startMinute: 13 * 60,
      endMinute: 14 * 60,
      enabled: true
    }
  ];
  assert.equal(isScheduleActive(at(SEP, 2, 13, 30), windows).active, true); // Wed 13:30
  assert.equal(isScheduleActive(at(SEP, 2, 12, 59), windows).active, false); // before start
  assert.equal(isScheduleActive(at(SEP, 2, 14, 0), windows).active, false); // end is exclusive
});

test('wrapping window (22:00-07:00) is active both before and after midnight', () => {
  const windows = [
    {
      id: 'w',
      label: '밤 시간',
      days: ALL_DAYS,
      startMinute: 22 * 60,
      endMinute: 7 * 60,
      enabled: true
    }
  ];
  assert.equal(isScheduleActive(at(SEP, 2, 23, 0), windows).active, true); // Wed 23:00, before midnight
  assert.equal(isScheduleActive(at(SEP, 3, 3, 0), windows).active, true); // Thu 03:00, after midnight
  assert.equal(isScheduleActive(at(SEP, 2, 12, 0), windows).active, false); // Wed noon, well outside
});

test('a wrapping window belongs to the day it starts on, not the day it ends on', () => {
  const windows = [
    {
      id: 'w',
      label: '월요일 밤',
      days: MON_ONLY,
      startMinute: 22 * 60,
      endMinute: 7 * 60,
      enabled: true
    }
  ];
  // Started Monday 22:00 (Aug 31), carries into Tuesday morning (Sep 1) before 07:00.
  assert.equal(isScheduleActive(at(AUG, 31, 23, 0), windows).active, true); // Monday night itself
  assert.equal(isScheduleActive(at(SEP, 1, 3, 0), windows).active, true); // Tuesday 03:00 - carried over from Monday
  // Tuesday 23:00 would need Tuesday itself in `days`, which this window does not have.
  assert.equal(isScheduleActive(at(SEP, 1, 23, 0), windows).active, false);
});

test('boundary: active exactly at startMinute, inactive exactly at endMinute', () => {
  const nonWrap = [
    { id: 'w', label: 'x', days: ALL_DAYS, startMinute: 13 * 60, endMinute: 14 * 60, enabled: true }
  ];
  assert.equal(isScheduleActive(at(SEP, 2, 13, 0), nonWrap).active, true);
  assert.equal(isScheduleActive(at(SEP, 2, 14, 0), nonWrap).active, false);

  const wrap = [
    { id: 'w', label: 'x', days: ALL_DAYS, startMinute: 22 * 60, endMinute: 7 * 60, enabled: true }
  ];
  assert.equal(isScheduleActive(at(SEP, 2, 22, 0), wrap).active, true);
  assert.equal(isScheduleActive(at(SEP, 3, 7, 0), wrap).active, false);
});

test('a disabled window is always ignored regardless of time', () => {
  const windows = [
    { id: 'w', label: 'x', days: ALL_DAYS, startMinute: 0, endMinute: 1439, enabled: false }
  ];
  assert.equal(isScheduleActive(at(SEP, 2, 12, 0), windows).active, false);
});

test('multiple overlapping windows: any active one wins', () => {
  const windows = [
    { id: 'a', label: 'a', days: MON_ONLY, startMinute: 0, endMinute: 60, enabled: true }, // not active on Wed
    { id: 'b', label: 'b', days: ALL_DAYS, startMinute: 13 * 60, endMinute: 14 * 60, enabled: true } // active Wed 13:30
  ];
  const result = isScheduleActive(at(SEP, 2, 13, 30), windows);
  assert.equal(result.active, true);
  assert.equal(result.window.id, 'b');
});

test('empty or missing scheduled_blocks is never active', () => {
  assert.equal(isScheduleActive(at(SEP, 2, 13, 30), []).active, false);
  assert.equal(isScheduleActive(at(SEP, 2, 13, 30), undefined).active, false);
  assert.equal(isScheduleActive(at(SEP, 2, 13, 30), null).active, false);
});

test('schedule evaluation uses the real wall-clock day, not the 4am usage-day cutoff', () => {
  // A Monday-only window from 00:00-03:00 (non-wrapping). Under the 4am cutoff used for
  // usage/streak accounting (extension/src/lib/time.js's DAY_CUTOFF_HOUR + getTodayDate()),
  // Tuesday 02:00 would be folded into "Monday" (it's before the 4am boundary, so
  // getEffectiveNow() shifts it back onto the previous calendar day). Schedule evaluation must
  // ignore that entirely and use the real wall-clock day (Date#getDay()), which is Tuesday - so
  // a Monday-only window must NOT be active at real Tuesday 02:00.
  const windows = [
    { id: 'w', label: 'x', days: MON_ONLY, startMinute: 0, endMinute: 180, enabled: true }
  ];
  assert.equal(isScheduleActive(at(SEP, 1, 2, 0), windows).active, false); // Tue 02:00, wall-clock Tuesday
  assert.equal(isScheduleActive(at(AUG, 31, 2, 0), windows).active, true); // sanity: real Monday 02:00 IS active
});

test('minutesUntilNextScheduleStart counts minutes to a start later today', () => {
  const windows = [
    { id: 'w', label: 'x', days: ALL_DAYS, startMinute: 22 * 60, endMinute: 7 * 60, enabled: true }
  ];
  assert.equal(minutesUntilNextScheduleStart(at(SEP, 2, 21, 50), windows), 10);
});

test('minutesUntilNextScheduleStart rolls over to the next matching day when today has passed', () => {
  const windows = [
    { id: 'w', label: 'x', days: MON_ONLY, startMinute: 22 * 60, endMinute: 7 * 60, enabled: true }
  ];
  // From Wednesday 10:00, the next Monday is 5 days away.
  const minutes = minutesUntilNextScheduleStart(at(SEP, 2, 10, 0), windows);
  assert.equal(minutes, 5 * 24 * 60 + (22 * 60 - 10 * 60));
});

test('minutesUntilNextScheduleStart wraps to next week when the only matching day already passed today', () => {
  const windows = [
    { id: 'w', label: 'x', days: WED_ONLY, startMinute: 8 * 60, endMinute: 9 * 60, enabled: true }
  ];
  // At Wed 10:00 today's 08:00-09:00 window is long over; the next occurrence is next Wednesday.
  const minutes = minutesUntilNextScheduleStart(at(SEP, 2, 10, 0), windows);
  assert.equal(minutes, 7 * 24 * 60 - 10 * 60 + 8 * 60);
});

test('minutesUntilNextScheduleStart is null while a window is already active', () => {
  const windows = [
    { id: 'w', label: 'x', days: ALL_DAYS, startMinute: 22 * 60, endMinute: 7 * 60, enabled: true }
  ];
  assert.equal(minutesUntilNextScheduleStart(at(SEP, 2, 23, 0), windows), null);
});

test('minutesUntilNextScheduleStart is null with no windows', () => {
  assert.equal(minutesUntilNextScheduleStart(at(SEP, 2, 12, 0), []), null);
  assert.equal(minutesUntilNextScheduleStart(at(SEP, 2, 12, 0), undefined), null);
});

test('minutesUntilNextScheduleStart ignores disabled windows', () => {
  const windows = [
    {
      id: 'w',
      label: 'x',
      days: ALL_DAYS,
      startMinute: 13 * 60,
      endMinute: 14 * 60,
      enabled: false
    }
  ];
  assert.equal(minutesUntilNextScheduleStart(at(SEP, 2, 10, 0), windows), null);
});
