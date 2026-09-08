// 진단 링버퍼의 chrome.storage.local 쪽 절반. 판정/자르기/합산은 전부 순수 함수(syncDiagnostics.js)에
// 있고 여기선 읽고 쓰는 일만 한다 — 안드로이드가 SyncDiagnostics.kt(순수)와 AppState.kt(DataStore)로
// 나눠둔 것과 같은 경계다.
//
// 저장 키 이름도 안드로이드 DataStore 키와 맞춰뒀다(diagnostic_events / last_sync_success_at).
// 사용자가 두 기기의 기록을 나란히 놓고 볼 일이 있어서, 이름까지 같은 편이 헷갈리지 않는다.

import { getStorage, setStorage } from './storage.js';
import {
  DIAGNOSTIC_CAPACITY,
  appendDiagnosticEvent,
  normalizeDiagnosticEvents
} from './syncDiagnostics.js';

export const DIAGNOSTIC_EVENTS_KEY = 'diagnostic_events';
export const LAST_SYNC_SUCCESS_KEY = 'last_sync_success_at';

/** 로그아웃·계정 삭제에서 한꺼번에 지울 때 쓰는 키 목록. */
export const DIAGNOSTIC_STORAGE_KEYS = [DIAGNOSTIC_EVENTS_KEY, LAST_SYNC_SUCCESS_KEY];

/**
 * 기록 쓰기를 한 줄로 세우는 꼬리 프라미스.
 *
 * 한 틱 안에서 설정 동기화·사용시간 RPC·긴급 횟수 조회가 **동시에** 실패할 수 있는데,
 * chrome.storage에는 트랜잭션이 없어서 읽기-수정-쓰기가 겹치면 나중 쓰기가 먼저 쓴 실패를
 * 통째로 덮어쓴다(안드로이드는 DataStore edit 하나로 묶어 해결한 지점이다). 실패 기록이
 * 유실되면 이 기능의 존재 이유가 없어지므로 직렬화한다.
 */
let writeQueue = Promise.resolve();

function enqueue(task) {
  const next = writeQueue.then(task, task);
  // 큐가 한 번의 실패로 끊기면 이후 기록이 영영 안 쌓인다 — 에러는 여기서 흡수한다.
  writeQueue = next.catch(() => {});
  return next;
}

/** 화면/팝업이 쓰는 읽기. 저장소가 깨져 있어도 빈 목록으로 접힌다. */
export async function readDiagnostics() {
  const stored = await getStorage(DIAGNOSTIC_STORAGE_KEYS);
  const lastSuccess = Number(stored[LAST_SYNC_SUCCESS_KEY]);
  return {
    events: normalizeDiagnosticEvents(stored[DIAGNOSTIC_EVENTS_KEY], DIAGNOSTIC_CAPACITY),
    lastSuccessAtMillis: Number.isFinite(lastSuccess) ? lastSuccess : null
  };
}

/**
 * 실패 한 건을 링버퍼에 넣는다.
 *
 * `code`에는 서버 에러 메시지 원문이 아니라 짧은 분류만 넘길 것(syncDiagnostics.js의
 * summarizeFailure 참고) — 토큰·이메일·user_id가 이 버퍼에 들어가면 사용자가 "복사" 버튼으로
 * 그대로 퍼가게 된다. appendDiagnosticEvent가 마지막 방어선으로 한 번 더 지우긴 하지만,
 * 그건 보험이지 통로가 아니다.
 */
export async function recordDiagnosticFailure(kind, code, atMillis = Date.now()) {
  return enqueue(async () => {
    const stored = await getStorage([DIAGNOSTIC_EVENTS_KEY]);
    const events = appendDiagnosticEvent(stored[DIAGNOSTIC_EVENTS_KEY], { atMillis, kind, code });
    await setStorage({ [DIAGNOSTIC_EVENTS_KEY]: events });
  });
}

/**
 * 서버 왕복이 성공했을 때. **이벤트를 쌓지 않고 시각만 덮어쓴다** — 성공은 세는 게 아니라
 * "마지막이 언제였나"만 알면 되고, 30초마다 한 줄씩 남기면 링버퍼가 성공 기록으로만 차서
 * 정작 봐야 할 실패가 밀려난다. (안드로이드 recordSyncSuccess와 같은 규칙)
 */
export async function recordSyncSuccess(atMillis = Date.now()) {
  return enqueue(() => setStorage({ [LAST_SYNC_SUCCESS_KEY]: atMillis }));
}

/** 옵션 화면의 "지우기"와 로그아웃이 같이 쓴다. 마지막 성공 시각도 함께 비운다. */
export async function clearDiagnostics() {
  return enqueue(() => chrome.storage.local.remove(DIAGNOSTIC_STORAGE_KEYS));
}
