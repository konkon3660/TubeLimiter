import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  usageDeltaSinceSync,
  sumEmergencyUsesInBucket,
  reportedEmergencyUsesInBucket,
  remainingEmergencyUses
} from '../src/lib/usageMerge.js';
import { emergencyResetDate } from '../src/lib/dateRollover.js';

// 긴급 시청 "횟수"가 로컬에만 남으면 PC에서 3회를 다 써도 폰에서 다시 3회를 쓸 수 있다 —
// 커밋먼트 장치가 기기 하나 바꾸는 것으로 무력화되는 구멍이다. 그래서 횟수도 daily_usage에
// 델타로 올리고, 남은 횟수는 "로컬 카운터 - 다른 기기가 이 버킷에서 쓴 몫"으로 판정한다.
// 아래가 그 계약(안드로이드 sync/UsageMerge.kt도 같은 규칙으로 맞춰야 한다).

// --- 델타: 시간과 같은 함수를 단위만 바꿔 재사용한다 ---

test('한 번도 안 올렸으면 오늘 횟수 전부가 델타다', () => {
  assert.equal(usageDeltaSinceSync(2, null, 0, '2026-09-03'), 2);
});

test('이미 올린 횟수만큼은 다시 안 올린다', () => {
  assert.equal(usageDeltaSinceSync(3, '2026-09-03', 2, '2026-09-03'), 1);
});

test('날짜가 바뀌면 어제 기준점은 안 끌고 온다 (서버는 날짜별 행이다)', () => {
  assert.equal(usageDeltaSinceSync(1, '2026-09-02', 3, '2026-09-03'), 1);
});

// --- 버킷 합산 ---

const HISTORY = {
  '2026-08-30': { uses: 5, ms: 1000 }, // 버킷 시작일 직전
  '2026-08-31': { uses: 1, ms: 1000 }, // 버킷 시작일 (월요일)
  '2026-09-02': { uses: 2, ms: 2000 },
  '2026-09-03': { uses: 1, ms: 3000 } // 오늘
};

test('버킷 시작일 직전 날짜는 합계에서 빠진다', () => {
  // 8/30의 5회가 새 주로 새어 들어오면 새 주가 시작하자마자 횟수가 0이 된다.
  assert.equal(sumEmergencyUsesInBucket(HISTORY, '2026-08-31', '2026-09-03'), 4);
});

test('버킷 시작일과 오늘은 양끝 모두 합계에 들어간다', () => {
  assert.equal(sumEmergencyUsesInBucket(HISTORY, '2026-08-31', '2026-08-31'), 1);
  assert.equal(sumEmergencyUsesInBucket(HISTORY, '2026-09-03', '2026-09-03'), 1);
});

test('시계가 앞선 기기가 남긴 미래 날짜는 세지 않는다', () => {
  const withFuture = { ...HISTORY, '2026-09-10': { uses: 9, ms: 0 } };
  assert.equal(sumEmergencyUsesInBucket(withFuture, '2026-08-31', '2026-09-03'), 4);
});

test('기록이 없거나(설치 직후) uses 필드가 비어도 0으로 센다', () => {
  assert.equal(sumEmergencyUsesInBucket(null, '2026-09-01', '2026-09-03'), 0);
  assert.equal(sumEmergencyUsesInBucket({}, '2026-09-01', '2026-09-03'), 0);
  assert.equal(sumEmergencyUsesInBucket({ '2026-09-03': { ms: 500 } }, '2026-09-01', '2026-09-03'), 0);
});

test('daily 설정이면 버킷은 오늘 하루라 오늘 것만 센다', () => {
  const start = emergencyResetDate('daily', { today: '2026-09-03', weekStart: '2026-08-31', monthStart: '2026-09-01' });
  assert.equal(start, '2026-09-03');
  assert.equal(sumEmergencyUsesInBucket(HISTORY, start, '2026-09-03'), 1);
});

test('weekly 설정이면 버킷은 그 주 월요일부터다', () => {
  const start = emergencyResetDate('weekly', { today: '2026-09-03', weekStart: '2026-08-31', monthStart: '2026-09-01' });
  assert.equal(start, '2026-08-31');
  assert.equal(sumEmergencyUsesInBucket(HISTORY, start, '2026-09-03'), 4);
});

test('monthly 설정이면 버킷은 그 달 1일부터다 (지난달 것은 빠진다)', () => {
  const start = emergencyResetDate('monthly', { today: '2026-09-03', weekStart: '2026-08-31', monthStart: '2026-09-01' });
  assert.equal(start, '2026-09-01');
  assert.equal(sumEmergencyUsesInBucket(HISTORY, start, '2026-09-03'), 3);
});

// --- 이 기기가 서버에 기여한 몫 ---

test('아직 안 올린 오늘분은 내 몫으로 치지 않는다', () => {
  // 버킷에서 4회를 썼고 그중 오늘이 1회인데 오늘분은 아직 서버에 못 올렸다면,
  // 서버 합계에 들어있는 내 몫은 지난 날들의 3회뿐이다.
  assert.equal(reportedEmergencyUsesInBucket(4, 1, 0), 3);
});

test('오늘분까지 올렸으면 버킷 전체가 내 몫이다', () => {
  assert.equal(reportedEmergencyUsesInBucket(4, 1, 1), 4);
});

test('로컬 기록이 뒤엉켜도(오늘치가 버킷 합보다 큼) 음수로 새지 않는다', () => {
  assert.equal(reportedEmergencyUsesInBucket(1, 3, 0), 0);
});

// --- 남은 횟수 ---

test('다른 기기가 쓴 만큼 남은 횟수가 깎인다', () => {
  // 내가 1회를 써서 로컬엔 2회가 남았지만, 서버 버킷 합계는 3회 — 나머지 2회는 다른 기기 몫이다.
  assert.equal(remainingEmergencyUses(2, 1, 3), 0);
});

test('서버 합계가 내 보고분과 같으면(나 혼자 쓴 기기) 로컬 값 그대로다', () => {
  assert.equal(remainingEmergencyUses(2, 1, 1), 2);
});

test('서버 합계가 내 보고분보다 작아도(전파 지연) 남은 횟수를 늘려주지는 않는다', () => {
  assert.equal(remainingEmergencyUses(2, 3, 1), 2);
});

test('오프라인/로그아웃이라 서버 합계가 0이면 로컬 값만으로 동작한다', () => {
  // 네트워크가 죽었다고 긴급 시청이 막히면 안 된다 — 서버 합계는 얻어지면 반영되는 보너스다.
  assert.equal(remainingEmergencyUses(3, 0, 0), 3);
  assert.equal(remainingEmergencyUses(3, 2, 0), 3);
});

test('남은 횟수는 음수로 내려가지 않는다', () => {
  assert.equal(remainingEmergencyUses(1, 0, 10), 0);
});

test('로컬 카운터가 이미 0이면 서버 합계와 무관하게 0이다', () => {
  assert.equal(remainingEmergencyUses(0, 0, 0), 0);
});

// --- 기기 두 대 시나리오 (구멍이 막혔는지) ---

test('PC에서 3회를 다 쓰면 폰은 새 기기여도 0회를 본다', () => {
  const allowance = 3;
  const bucketStart = '2026-09-03'; // daily
  const today = '2026-09-03';

  // PC: 3회를 쓰고 전부 서버에 올렸다.
  const pcHistory = { '2026-09-03': { uses: 3, ms: 0 } };
  const pcBucketUses = sumEmergencyUsesInBucket(pcHistory, bucketStart, today);
  const serverBucketUses = pcBucketUses; // RPC가 델타를 누적한 결과

  // 폰: 로컬 기록이 없어 자기 카운터로는 3회가 남아 보인다.
  const phoneLocalRemaining = allowance;
  const phoneReported = reportedEmergencyUsesInBucket(0, 0, 0);
  assert.equal(remainingEmergencyUses(phoneLocalRemaining, phoneReported, serverBucketUses), 0);

  // PC 자신은 이중으로 깎이지 않는다 (자기 몫은 서버 합계에서 빼고 세므로).
  const pcReported = reportedEmergencyUsesInBucket(pcBucketUses, 3, 3);
  assert.equal(remainingEmergencyUses(allowance - 3, pcReported, serverBucketUses), 0);
});

test('주간 버킷에서 다른 기기가 이번 주 초에 쓴 몫도 오늘 남은 횟수에 반영된다', () => {
  const bucketStart = emergencyResetDate('weekly', {
    today: '2026-09-03', weekStart: '2026-08-31', monthStart: '2026-09-01'
  });

  // 이 기기는 이번 주에 아무것도 안 썼고(로컬 5회 그대로), 서버 주간 합계는 4회.
  const localHistory = { '2026-08-30': { uses: 5, ms: 0 } }; // 지난 주 것이라 이번 주엔 안 들어간다
  const localBucketUses = sumEmergencyUsesInBucket(localHistory, bucketStart, '2026-09-03');
  assert.equal(localBucketUses, 0);

  const reported = reportedEmergencyUsesInBucket(localBucketUses, 0, 0);
  assert.equal(remainingEmergencyUses(5, reported, 4), 1);
});
