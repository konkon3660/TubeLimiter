import { test } from 'node:test';
import assert from 'node:assert/strict';
import { FOCUS_STOP_COOLDOWN_MS, resolveFocusStopTime } from '../src/lib/focusMode.js';

test('FOCUS_STOP_COOLDOWN_MS is a ten minute cooldown', () => {
  assert.equal(FOCUS_STOP_COOLDOWN_MS, 10 * 60 * 1000);
});

test('no stop request means the natural end time wins unchanged', () => {
  assert.equal(resolveFocusStopTime(5000, null), 5000);
  assert.equal(resolveFocusStopTime(null, null), null);
});

test('a stop request with no natural end resolves to the cooldown end', () => {
  assert.equal(resolveFocusStopTime(null, 1000, FOCUS_STOP_COOLDOWN_MS), 1000 + FOCUS_STOP_COOLDOWN_MS);
});

test('cooldown end wins when the natural end is further away', () => {
  const requestedAt = 1_000_000;
  const naturalEnd = requestedAt + FOCUS_STOP_COOLDOWN_MS + 60_000; // ends well after cooldown
  assert.equal(
    resolveFocusStopTime(naturalEnd, requestedAt, FOCUS_STOP_COOLDOWN_MS),
    requestedAt + FOCUS_STOP_COOLDOWN_MS
  );
});

test('natural end wins when it arrives before the cooldown would', () => {
  const requestedAt = 1_000_000;
  const naturalEnd = requestedAt + 60_000; // ends soon, well before the 10-minute cooldown
  assert.equal(resolveFocusStopTime(naturalEnd, requestedAt, FOCUS_STOP_COOLDOWN_MS), naturalEnd);
});

test('does not extend a session past its natural end just because a stop is pending', () => {
  const requestedAt = 1_000_000;
  const naturalEnd = requestedAt + 1_000; // about to end naturally anyway
  const resolved = resolveFocusStopTime(naturalEnd, requestedAt, FOCUS_STOP_COOLDOWN_MS);
  assert.ok(resolved <= naturalEnd);
});
