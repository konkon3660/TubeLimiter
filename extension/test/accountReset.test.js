import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { DIAGNOSTIC_STORAGE_KEYS } from '../src/lib/diagnosticsStore.js';
import {
  SIGN_OUT_REMOVED_KEYS,
  ACCOUNT_DELETE_REMOVED_KEYS,
  ACCOUNT_DELETE_PRESERVED_KEYS
} from '../src/lib/accountReset.js';

// 로그아웃/계정 삭제가 "무엇을 지우는가"는 그 자체로 우회 경로다. 여기서 못 박는 두 가지:
//
//  1. 로그아웃은 긴급 시청 버킷 캐시를 **남긴다.** 지우면 getEffectiveEmergencyUses가 로컬 값으로
//     폴백해서 다른 기기가 이미 쓴 횟수가 되살아난다 — PC에서 로그아웃 한 번이면 폰이 쓴 횟수가
//     리필된다. 안드로이드가 로그아웃 때 캐시를 남기는 것과 같은 이유(의도된 비대칭).
//  2. 계정 삭제는 storage.local.clear()가 아니라 명시 목록으로 지운다. 통째로 비우면 이 기기의
//     남은 횟수 카운트다운(emergency_uses_today)까지 날아가 "탈퇴 후 재가입 = 횟수 full 복구"가
//     된다. 안드로이드 AppState.clearAccountData가 같은 키를 일부러 남긴다.

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OPTIONS_SOURCE = readFileSync(path.join(ROOT, 'src/options/options.js'), 'utf8');

const EMERGENCY_BUCKET_KEYS = [
  'emergencyUsesBucketDate',
  'emergencyUsesBucketRemote',
  'emergencyUsesBucketReported'
];

// --- 로그아웃 ---

test('로그아웃이 지우는 건 진단 기록뿐이다', () => {
  assert.deepEqual([...SIGN_OUT_REMOVED_KEYS], [...DIAGNOSTIC_STORAGE_KEYS]);
});

test('로그아웃은 긴급 시청 버킷 캐시를 남긴다 (지우면 다른 기기가 쓴 횟수가 되살아난다)', () => {
  EMERGENCY_BUCKET_KEYS.forEach((key) => {
    assert.ok(!SIGN_OUT_REMOVED_KEYS.includes(key), `로그아웃이 ${key}를 지우면 안 된다`);
  });
});

test('로그아웃은 이 기기의 남은 횟수 카운트다운도 건드리지 않는다', () => {
  ['emergency_uses_today', 'last_emergency_date'].forEach((key) => {
    assert.ok(!SIGN_OUT_REMOVED_KEYS.includes(key));
  });
});

test('로그아웃은 사용 기록·한도 기록을 지우지 않는다 (같은 계정으로 다시 들어올 수 있다)', () => {
  ['usage_history', 'usage_history_shorts', 'emergency_history', 'limit_history'].forEach((key) => {
    assert.ok(!SIGN_OUT_REMOVED_KEYS.includes(key));
  });
});

// --- 계정 삭제 ---

test('계정 삭제는 남은 긴급 시청 횟수를 남긴다 (재가입이 횟수 리필이 되면 안 된다)', () => {
  ['emergency_uses_today', 'last_emergency_date'].forEach((key) => {
    assert.ok(!ACCOUNT_DELETE_REMOVED_KEYS.includes(key), `계정 삭제가 ${key}를 지우면 안 된다`);
    assert.ok(ACCOUNT_DELETE_PRESERVED_KEYS.includes(key));
  });
});

test('계정 삭제는 진행 중인 차단·집중 세션을 남긴다 (삭제가 탈출구가 되면 안 된다)', () => {
  ['isManuallyBlocked', 'focusModeActive', 'focusModeEndTime', 'emergencyEndTime'].forEach(
    (key) => {
      assert.ok(!ACCOUNT_DELETE_REMOVED_KEYS.includes(key));
      assert.ok(ACCOUNT_DELETE_PRESERVED_KEYS.includes(key));
    }
  );
});

test('계정 삭제는 버킷 캐시를 지운다 (계정 행이 사라진 기기 몫으로 횟수가 깎이면 안 된다)', () => {
  EMERGENCY_BUCKET_KEYS.forEach((key) => {
    assert.ok(ACCOUNT_DELETE_REMOVED_KEYS.includes(key));
  });
});

test('계정 삭제는 계정에서 온 값을 빠짐없이 지운다', () => {
  [
    'settingsCache',
    'usage_history',
    'usage_history_shorts',
    'usage_history_hourly',
    'emergency_history',
    'limit_history',
    'local_current_date',
    'dailyUsageSyncDate',
    'dailyUsageSyncedMillis',
    'dailyUsageShortsSyncedMillis',
    'dailyUsageEmergencySyncedMillis',
    'dailyUsageEmergencyUsesSyncedCount',
    'dailyUsageCombinedMillis',
    'dailyUsageCombinedShortsMillis',
    ...DIAGNOSTIC_STORAGE_KEYS
  ].forEach((key) => {
    assert.ok(ACCOUNT_DELETE_REMOVED_KEYS.includes(key), `계정 삭제가 ${key}를 지워야 한다`);
  });
});

test('지우는 목록과 남기는 목록은 겹치지 않고, 각각 중복도 없다', () => {
  const removed = new Set(ACCOUNT_DELETE_REMOVED_KEYS);
  const preserved = new Set(ACCOUNT_DELETE_PRESERVED_KEYS);
  assert.equal(removed.size, ACCOUNT_DELETE_REMOVED_KEYS.length);
  assert.equal(preserved.size, ACCOUNT_DELETE_PRESERVED_KEYS.length);
  const overlap = [...removed].filter((key) => preserved.has(key));
  assert.deepEqual(overlap, []);
});

// --- 화면이 실제로 이 목록을 쓰는지 ---

test('옵션 화면은 storage.local.clear()를 쓰지 않는다', () => {
  // clear()로 되돌아가면 위 "남긴다" 규칙이 전부 무효가 되는데, 목록 테스트만으로는 안 잡힌다.
  assert.ok(!/storage\.local\.clear\s*\(/.test(OPTIONS_SOURCE));
});

test('옵션 화면이 로그아웃·계정 삭제에서 이 목록을 쓴다', () => {
  assert.match(OPTIONS_SOURCE, /storage\.local\.remove\(\[\.\.\.SIGN_OUT_REMOVED_KEYS\]\)/);
  assert.match(OPTIONS_SOURCE, /storage\.local\.remove\(\[\.\.\.ACCOUNT_DELETE_REMOVED_KEYS\]\)/);
});
