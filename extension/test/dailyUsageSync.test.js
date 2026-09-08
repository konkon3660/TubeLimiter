import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { usageDeltaSinceSync, combinedUsedMillis } from '../src/lib/usageMerge.js';

// increment_daily_usage는 서버에서 usage_ms를 덮어쓰지 않고 델타만큼 더한다. 그래서 이 델타를
// 계산하는 쪽(service-worker.js의 syncUsageToSupabase)이 겹쳐 호출되면, 두 호출이 아직 갱신되지
// 않은 같은 "마지막 동기화" 기준점을 읽고 각자 델타를 만들어 서버에 두 번 더해버린다.
// 로컬 저장소라면 나중 쓰기가 앞 값을 덮어써서 끝나지만, 서버는 누적이라 영구히 부풀어 오른다.
// (실제로 팝업 91분 / 대시보드 46분으로 벌어진 사고의 원인.)
//
// syncUsageToSupabase에는 락이 걸려 있어 겹침 자체가 막히지만, 이 알고리즘은
// android의 sync/UsageMerge.kt와 공유하므로 어느 쪽에서든 직렬화가 깨지면 같은 사고가 난다.
// 아래 테스트가 그 계약을 고정한다.

const TODAY = '2026-09-03';

/** increment_daily_usage RPC를 흉내낸다: 델타를 더하고 누적 합계를 돌려준다. */
function makeServer() {
  let usageMs = 0;
  return {
    increment(deltaMs) {
      usageMs += Math.max(0, deltaMs);
      return usageMs;
    },
    get total() {
      return usageMs;
    }
  };
}

test('직렬화된 동기화는 서버 합계와 로컬 합계를 일치시킨다', () => {
  const server = makeServer();
  let syncedDate = null;
  let syncedMs = 0;
  let combinedMs = 0;

  function sync(localUsage) {
    const delta = usageDeltaSinceSync(localUsage, syncedDate, syncedMs, TODAY);
    const total = server.increment(delta);
    syncedDate = TODAY;
    syncedMs = localUsage;
    combinedMs = total;
  }

  sync(500);
  sync(700);

  assert.equal(server.total, 700);
  // 다른 기기가 없으므로 팝업이 보여주는 값도 로컬(대시보드) 값과 같아야 한다.
  assert.equal(combinedUsedMillis(700, syncedMs, combinedMs), 700);
});

test('겹친 동기화는 같은 구간을 두 번 더해 팝업 값을 부풀린다 (락이 막아야 하는 상황)', () => {
  const server = makeServer();
  let syncedDate = null;
  let syncedMs = 0;
  let combinedMs = 0;

  // 호출 A: 기준점을 읽고 델타를 계산하지만 아직 결과를 쓰지 않았다 (RPC 대기 중).
  const aLocal = 500;
  const aDelta = usageDeltaSinceSync(aLocal, syncedDate, syncedMs, TODAY);

  // 그 사이 로컬 사용량이 더 쌓이고, 호출 B가 A와 같은(아직 갱신 안 된) 기준점을 읽는다.
  const bLocal = 700;
  const bDelta = usageDeltaSinceSync(bLocal, syncedDate, syncedMs, TODAY);

  assert.equal(aDelta, 500);
  assert.equal(bDelta, 700); // A가 이미 500을 밀어넣은 걸 모른다

  server.increment(aDelta);
  const afterB = server.increment(bDelta);
  syncedDate = TODAY;
  syncedMs = bLocal;
  combinedMs = afterB;

  // 실제 시청은 700인데 서버에는 1200이 쌓였고, 그 차이가 "다른 기기 몫"으로 오인돼
  // 팝업 값에 그대로 얹힌다 - 대시보드(로컬 700)와 벌어지는 지점.
  assert.equal(server.total, 1200);
  assert.equal(combinedUsedMillis(bLocal, syncedMs, combinedMs), 1200);
});

// --- 죽은 저장소 키 ---
//
// dailyUsageCombinedEmergencyUses는 매 동기화마다 쓰이기만 하고 아무도 읽지 않았다. 잔여 긴급
// 시청 횟수는 하루가 아니라 리셋 버킷(일/주/월) 단위라 오늘 행 하나로는 주간/월간에서 틀리고,
// 실제 판정은 refreshEmergencyUsesBucket이 캐시하는 emergencyUsesBucketRemote가 한다.
// 다시 살아나면 같은 사실을 말하는 값이 둘이 되어 언젠가 갈라진다.

test('오늘치 긴급 시청 횟수 합계를 따로 저장하지 않는다 (버킷 캐시가 유일한 출처)', () => {
  const source = readFileSync(
    path.join(
      path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'),
      'src/background/service-worker.js'
    ),
    'utf8'
  );
  assert.ok(!/dailyUsageCombinedEmergencyUses\s*:/.test(source));
});
