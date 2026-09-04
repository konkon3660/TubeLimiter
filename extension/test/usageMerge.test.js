import { test } from 'node:test';
import assert from 'node:assert/strict';
import { usageDeltaSinceSync, combinedUsedMillis } from '../src/lib/usageMerge.js';

// Cross-checked against android/app/src/test/java/com/tubelimiter/app/sync/UsageMergeTest.kt —
// same algorithm, ported deliberately, so both sides are verified against the same scenarios.

test('nothing synced yet reports the full local figure as the delta', () => {
  assert.equal(usageDeltaSinceSync(20_000, null, 0, '2026-09-03'), 20_000);
});

test('only the usage since the last sync is a delta', () => {
  assert.equal(usageDeltaSinceSync(35_000, '2026-09-03', 20_000, '2026-09-03'), 15_000);
});

test('a stale baseline from yesterday does not carry over', () => {
  assert.equal(usageDeltaSinceSync(5_000, '2026-09-02', 20_000, '2026-09-03'), 5_000);
});

test('the delta never goes negative even if the local figure is behind the synced baseline', () => {
  assert.equal(usageDeltaSinceSync(10_000, '2026-09-03', 20_000, '2026-09-03'), 0);
});

test('a falsy syncedMillis (0) is treated as a zero baseline, not skipped', () => {
  assert.equal(usageDeltaSinceSync(12_000, '2026-09-03', 0, '2026-09-03'), 12_000);
});

test('combined usage adds only the other devices\' share on top of the local figure', () => {
  // This device already told the server about 20s; the server total is 50s, so the
  // other 30s came from elsewhere and should be added to whatever this device sees now.
  assert.equal(combinedUsedMillis(25_000, 20_000, 50_000), 55_000);
});

test('a single device sees its own usage unchanged', () => {
  assert.equal(combinedUsedMillis(25_000, 25_000, 25_000), 25_000);
});

test('a remote total behind the synced baseline contributes nothing negative', () => {
  assert.equal(combinedUsedMillis(10_000, 20_000, 15_000), 10_000);
});

test('combinedUsedMillis treats a falsy syncedMillis (0) as a zero baseline', () => {
  assert.equal(combinedUsedMillis(5_000, 0, 5_000), 10_000);
});
