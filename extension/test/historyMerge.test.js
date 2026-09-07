import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mergeMillisByDate, mergeHistories } from '../src/lib/historyMerge.js';

// The dashboard used to draw only this device's chrome.storage history, so a reinstall or a
// second device showed an empty heatmap. These cover the merge rule that fills that hole:
// per-date Math.max(local, server), because either side can be the one that is behind.

const row = (date, usage_ms, shorts_ms = 0, emergency_ms = 0) => ({ date, usage_ms, shorts_ms, emergency_ms });

test('a day only the server knows about shows up in the merged history', () => {
  const merged = mergeMillisByDate({}, [row('2026-09-01', 1_800_000)], 'usage_ms');
  assert.deepEqual(merged, { '2026-09-01': 1_800_000 });
});

test('a day only the local device knows about survives the merge', () => {
  const merged = mergeMillisByDate({ '2026-09-01': 600_000 }, [], 'usage_ms');
  assert.deepEqual(merged, { '2026-09-01': 600_000 });
});

test('the bigger of the two wins when the local figure is ahead', () => {
  // Not yet synced: the service worker throttles daily_usage pushes to 30s.
  const merged = mergeMillisByDate({ '2026-09-01': 900_000 }, [row('2026-09-01', 600_000)], 'usage_ms');
  assert.deepEqual(merged, { '2026-09-01': 900_000 });
});

test('the bigger of the two wins when the server figure is ahead', () => {
  // The usual case: the server row also carries the other devices' share.
  const merged = mergeMillisByDate({ '2026-09-01': 600_000 }, [row('2026-09-01', 900_000)], 'usage_ms');
  assert.deepEqual(merged, { '2026-09-01': 900_000 });
});

test('the two sides are never summed, which would double count this device', () => {
  const merged = mergeMillisByDate({ '2026-09-01': 600_000 }, [row('2026-09-01', 600_000)], 'usage_ms');
  assert.deepEqual(merged, { '2026-09-01': 600_000 });
});

test('empty input on both sides merges to an empty map', () => {
  assert.deepEqual(mergeMillisByDate({}, [], 'usage_ms'), {});
  assert.deepEqual(mergeMillisByDate(undefined, undefined, 'usage_ms'), {});
  assert.deepEqual(mergeMillisByDate(null, null, 'usage_ms'), {});
});

test('shorts use the same rule against their own column', () => {
  const rows = [row('2026-09-01', 900_000, 300_000)];
  assert.deepEqual(mergeMillisByDate({ '2026-09-01': 120_000 }, rows, 'shorts_ms'), { '2026-09-01': 300_000 });
});

test('bigint columns arriving as strings are still compared as numbers', () => {
  // PostgREST can hand back bigint as a string; '900000' < '600000' lexicographically.
  const merged = mergeMillisByDate({ '2026-09-01': 600_000 }, [row('2026-09-01', '900000')], 'usage_ms');
  assert.deepEqual(merged, { '2026-09-01': 900_000 });
});

test('missing or junk values are normalised to zero instead of poisoning the max', () => {
  const rows = [row('2026-09-01', null), { date: '2026-09-02' }, null, { usage_ms: 5 }];
  const merged = mergeMillisByDate({ '2026-09-01': -5, '2026-09-03': 'x' }, rows, 'usage_ms');
  assert.deepEqual(merged, { '2026-09-01': 0, '2026-09-02': 0, '2026-09-03': 0 });
});

test('all three histories merge together in one pass', () => {
  const local = {
    usage: { '2026-09-01': 900_000 },
    shorts: { '2026-09-01': 120_000 },
    emergency: { '2026-09-01': { uses: 2, ms: 300_000 } }
  };
  const rows = [row('2026-09-01', 600_000, 400_000, 200_000), row('2026-09-02', 1_200_000, 0, 60_000)];
  const merged = mergeHistories(local, rows);

  assert.deepEqual(merged.usage, { '2026-09-01': 900_000, '2026-09-02': 1_200_000 });
  assert.deepEqual(merged.shorts, { '2026-09-01': 400_000, '2026-09-02': 0 });
  assert.equal(merged.emergency['2026-09-01'].ms, 300_000);
  assert.equal(merged.emergency['2026-09-02'].ms, 60_000);
});

test('emergency uses come from the local record, never from the server', () => {
  const local = { usage: { '2026-09-01': 900_000 }, emergency: { '2026-09-01': { uses: 3, ms: 200_000 } } };
  const merged = mergeHistories(local, [row('2026-09-01', 900_000, 0, 500_000)]);
  // The server knows more emergency *time* (another device watched too) but no count exists there.
  assert.deepEqual(merged.emergency['2026-09-01'], { ms: 500_000, uses: 3 });
});

test('a day only the server knows about has an unknown (null) emergency count', () => {
  const merged = mergeHistories({ usage: {}, emergency: {} }, [row('2026-09-05', 900_000, 0, 120_000)]);
  assert.deepEqual(merged.emergency['2026-09-05'], { ms: 120_000, uses: null });
});

test('a locally recorded day with no emergency entry counts as zero uses, not unknown', () => {
  const merged = mergeHistories({ usage: { '2026-09-01': 600_000 }, emergency: {} }, []);
  assert.deepEqual(merged.emergency['2026-09-01'], { ms: 0, uses: 0 });
});

test('every day present in the merged usage also has an emergency entry', () => {
  const local = { usage: { '2026-09-01': 600_000 }, emergency: { '2026-08-30': { uses: 1, ms: 10 } } };
  const merged = mergeHistories(local, [row('2026-09-02', 600_000)]);
  Object.keys(merged.usage).forEach((date) => {
    assert.ok(merged.emergency[date], `missing emergency entry for ${date}`);
  });
});

test('merging nothing at all yields three empty maps', () => {
  assert.deepEqual(mergeHistories({}, []), { usage: {}, shorts: {}, emergency: {} });
  assert.deepEqual(mergeHistories(undefined, undefined), { usage: {}, shorts: {}, emergency: {} });
});
