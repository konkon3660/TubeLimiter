import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  EMERGENCY_GRANT_COOLDOWN_MS,
  EMERGENCY_DURATION_MS,
  emergencyOverlapMs
} from '../src/lib/emergency.js';

test('EMERGENCY_GRANT_COOLDOWN_MS is a 15 second cooldown', () => {
  assert.equal(EMERGENCY_GRANT_COOLDOWN_MS, 15 * 1000);
});

test('EMERGENCY_DURATION_MS is a 5 minute grant', () => {
  assert.equal(EMERGENCY_DURATION_MS, 5 * 60 * 1000);
});

const T0 = 1000000; // 임의의 기준 시각

test('emergencyOverlapMs counts a segment fully inside the grant window', () => {
  assert.equal(emergencyOverlapMs(T0 + 60000, T0 + 120000, T0), 60000);
});

test('emergencyOverlapMs clips a segment that starts before the grant', () => {
  // 차단 직전 30초 + 긴급 시청 30초짜리 구간이면 뒤쪽 30초만 긴급분이다.
  assert.equal(emergencyOverlapMs(T0 - 30000, T0 + 30000, T0), 30000);
});

test('emergencyOverlapMs clips a segment that runs past the grant window', () => {
  // 창이 끝난 뒤에 늦게 정산되는 구간 - 창 안쪽 1분만 긴급분으로 잡힌다.
  const end = T0 + EMERGENCY_DURATION_MS;
  assert.equal(emergencyOverlapMs(end - 60000, end + 120000, T0), 60000);
});

test('emergencyOverlapMs is zero outside the window or when no grant was ever issued', () => {
  assert.equal(
    emergencyOverlapMs(T0 + EMERGENCY_DURATION_MS + 1, T0 + EMERGENCY_DURATION_MS + 60000, T0),
    0
  );
  assert.equal(emergencyOverlapMs(T0 - 120000, T0 - 60000, T0), 0);
  // 긴급 시청을 한 번도 안 쓴 상태 - last_emergency_granted_at이 비어 있다.
  assert.equal(emergencyOverlapMs(T0, T0 + 60000, undefined), 0);
  assert.equal(emergencyOverlapMs(T0, T0 + 60000, null), 0);
});
