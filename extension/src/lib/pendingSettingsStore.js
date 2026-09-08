// 오프라인 편집분(대기분)의 chrome.storage.local 쪽 절반. 판정은 전부 순수 함수
// (lib/offlineSettings.js)에 있고 여기선 읽고 쓰는 일만 한다 — diagnosticsStore.js와 같은 경계다.
//
// 옵션 화면과 서비스워커가 **같은 키**를 본다. 화면은 대기분을 쌓고, 서비스워커는 온라인이 되면
// 그걸 올리고 지운다. 어느 쪽이 먼저 올려도 upsert라 결과가 같으므로 잠금은 두지 않는다.

import { getStorage, setStorage } from './storage.js';
import { PENDING_SETTINGS_KEY, createPendingSettings, readPendingFor } from './offlineSettings.js';

/** 저장돼 있는 대기분 원본. 계정 확인은 호출부가 readPendingFor로 한다. */
export async function readPendingSettings() {
  const stored = await getStorage([PENDING_SETTINGS_KEY]);
  return stored[PENDING_SETTINGS_KEY] ?? null;
}

/** 지금 로그인(또는 로컬 세션)한 계정의 대기분만. 없으면 null. */
export async function readPendingSettingsFor(userId) {
  return readPendingFor(await readPendingSettings(), userId);
}

/**
 * 이번 오프라인 저장분을 대기분에 **누적**한다.
 *
 * @param {object} patch 바뀐 설정 필드들
 * @param {{userId: string, atMillis?: number, resetStreak?: boolean}} meta
 * @returns {Promise<object>} 저장된 대기분(화면이 "동기화 대기 중" 표시에 쓴다)
 */
export async function queuePendingSettings(patch, meta) {
  const pending = createPendingSettings(await readPendingSettings(), patch, {
    atMillis: Date.now(),
    ...meta
  });
  await setStorage({ [PENDING_SETTINGS_KEY]: pending });
  return pending;
}

/** 서버에 올리는 데 성공했을 때만 부른다. */
export async function clearPendingSettings() {
  await chrome.storage.local.remove(PENDING_SETTINGS_KEY);
}
