// MV3 서비스워커가 "죽었다 깨어나는" 지점과 "확장이 업데이트되는" 지점의 판정 규칙.
//
// 이 두 사건은 자동 테스트가 하나도 없던 자리다(documents/QA_REVIEW.md §4.1·§4.2·§5). 여기 모아둔
// 이유는 다른 lib 파일들과 같다 — chrome.*를 직접 부르는 코드와 **판정**을 갈라놓아야 판정 쪽을
// 순수 함수로 테스트할 수 있다. chrome.*가 필요한 두 함수(reinjectContentScripts,
// probePlaybackState)도 API를 직접 부르지 않고 주입받은 함수로만 일해서, 테스트가 가짜 chrome을
// 넘겨 시나리오 전체를 돌릴 수 있게 했다.

/** 매니페스트의 content_scripts 항목과 같은 파일·같은 매치 패턴이어야 한다. */
export const CONTENT_SCRIPT_FILE = 'content/content.js';
export const CONTENT_SCRIPT_MATCHES = Object.freeze(['*://*.youtube.com/*']);

/**
 * content script에 상태를 물어보고 기다리는 한도.
 *
 * chrome.tabs.sendMessage는 받는 쪽이 없으면 대개 곧바로 reject하지만, "리스너는 있는데 답을
 * 안 하는" 상태(예: 무효화 직전의 유령 인스턴스가 return true만 하고 사라진 경우)에서는 영영
 * pending으로 남는다. 그 자리에서 서비스워커의 틱이 통째로 멈추면 안 되므로 시한을 둔다.
 */
export const PLAYBACK_PROBE_TIMEOUT_MS = 1000;

/** 시한 안에 안 끝나면 timeoutValue로 접는다. 성공/실패 모두 타이머를 반드시 정리한다. */
export async function raceTimeout(promise, timeoutMs, timeoutValue) {
  let timer = null;
  try {
    return await Promise.race([
      promise,
      new Promise((resolve) => {
        timer = setTimeout(() => resolve(timeoutValue), timeoutMs);
      })
    ]);
  } finally {
    if (timer !== null) clearTimeout(timer);
  }
}

/**
 * 이 URL에 content script를 다시 넣을 수 있는가.
 *
 * chrome.tabs.query가 매치 패턴으로 걸러주긴 하지만, 그 결과에는 주입이 불가능한 것들이 섞여
 * 들어올 수 있다(탭 권한이 없어 url이 아예 비어 오는 탭, view-source:/file: 스킴 등). 주입
 * 대상을 여기서 한 번 더 좁혀서 executeScript가 던지는 예외를 애초에 줄인다.
 */
export function isInjectableYoutubeUrl(url) {
  if (typeof url !== 'string' || !/^https?:\/\//i.test(url)) return false;
  try {
    const { hostname } = new URL(url);
    return hostname === 'youtube.com' || hostname.endsWith('.youtube.com');
  } catch {
    return false;
  }
}

/**
 * 어떤 탭에 content script를 주입할지 정한다.
 *
 * aliveTabIds는 "지금 살아있는 인스턴스가 답을 한 탭". 두 번 주입되면 onMessage 리스너와 3초
 * 폴링이 두 벌 돌아 재생 보고가 중복되므로, 살아있다고 확인된 탭은 반드시 건너뛴다. 같은 탭이
 * 목록에 두 번 들어오는 경우(창이 여러 개인 질의 결과가 합쳐지는 등)도 Set으로 접는다.
 */
export function planContentScriptInjection(tabs, aliveTabIds = []) {
  const alive = new Set(aliveTabIds);
  const planned = new Set();
  for (const tab of tabs ?? []) {
    const tabId = tab?.id;
    if (typeof tabId !== 'number' || tabId < 0) continue;
    if (alive.has(tabId)) continue;
    if (!isInjectableYoutubeUrl(tab.url)) continue;
    planned.add(tabId);
  }
  return [...planned];
}

/**
 * 열려 있는 유튜브 탭 중 content script가 죽은 탭에만 다시 주입한다.
 *
 * 크롬이 확장을 자동 업데이트하면 기존 탭의 content script는 남아 있어도 chrome.runtime이
 * 무효화되어 sendMessage가 동기 throw하고, content.js는 거기서 폴링을 접는다. 즉 사용자가
 * 아무것도 안 해도 그 탭은 재생 보고도 오버레이 수신도 끊긴 사각지대가 된다(§4.1).
 *
 * 중복 주입을 막는 기준으로 "예전에 주입했다"는 플래그가 아니라 **지금 답을 하는가**(ping)를
 * 쓴다. 유령 인스턴스가 페이지에 남긴 플래그는 업데이트 후에도 그대로 살아있어서, 플래그로
 * 판정하면 정작 되살려야 할 탭을 건너뛰게 된다. 반대로 ping에 답하는 인스턴스는 정의상
 * runtime이 유효한 살아있는 인스턴스다.
 *
 * @param {object} deps
 * @param {(query: object) => Promise<Array>} deps.queryTabs
 * @param {(tabId: number) => Promise<boolean>} deps.isContentScriptAlive
 * @param {(tabId: number) => Promise<unknown>} deps.injectContentScript
 */
export async function reinjectContentScripts({
  queryTabs,
  isContentScriptAlive,
  injectContentScript
}) {
  const result = { injected: [], skipped: [], failed: [] };
  let tabs;
  try {
    tabs = (await queryTabs({ url: [...CONTENT_SCRIPT_MATCHES] })) ?? [];
  } catch {
    return result; // 탭 조회조차 안 되면 할 수 있는 게 없다 - 다음 업데이트 때 다시 시도된다
  }

  for (const tabId of planContentScriptInjection(tabs)) {
    if (await isContentScriptAlive(tabId)) {
      result.skipped.push(tabId);
      continue;
    }
    try {
      await injectContentScript(tabId);
      result.injected.push(tabId);
    } catch {
      // 주입 실패(탭이 닫혔거나 오류 페이지거나 폐기된 탭)는 치명적이지 않다. 그런 탭은 다시
      // 보일 때 페이지가 로드되면서 매니페스트의 content script가 정상적으로 실행된다.
      result.failed.push(tabId);
    }
  }
  return result;
}

const PROBE_TIMEOUT = Symbol('playback-probe-timeout');

/**
 * 지금 이 탭이 재생 중인지 content script에 직접 물어본다.
 *
 * 돌려주는 값은 항상 {answered, playing, reason}이다 — "답이 없었다"와 "답이 '재생 아님'이었다"를
 * 호출부가 구별할 수 있어야 하기 때문이다(§4.2).
 */
export async function probePlaybackState(
  tabId,
  { sendMessage, timeoutMs = PLAYBACK_PROBE_TIMEOUT_MS } = {}
) {
  try {
    const response = await raceTimeout(
      sendMessage(tabId, { action: 'requestPlaybackState' }),
      timeoutMs,
      PROBE_TIMEOUT
    );
    if (response === PROBE_TIMEOUT) return { answered: false, playing: false, reason: 'timeout' };
    if (typeof response?.playing !== 'boolean') {
      return { answered: false, playing: false, reason: 'no-answer' };
    }
    return { answered: true, playing: response.playing, reason: 'answered' };
  } catch {
    // content script가 아직 없거나(문서 로드 전) 무효화됐거나 탭이 닫힌 경우.
    return { answered: false, playing: false, reason: 'error' };
  }
}

/**
 * 물어본 결과로 재생 상태를 정한다. **답이 없으면 "재생 아님"이다.**
 *
 * 서비스워커가 재시작하면 activeTabId도 재생 상태도 사라져서, 복구 경로는 일단 재생 중이라고
 * 낙관적으로 깔아둔 뒤 content script에 물어본다. 그런데 그 content script가 §4.1 상태로 죽어
 * 있으면 아무도 답하지 않고 낙관적 가정만 남아, **보고 있지도 않은 시간이 계속 깎인다.**
 *
 * 그래서 무응답은 "모르겠다"가 아니라 "재생 아님"으로 접는다. 근거:
 *  - 답이 없다는 건 그 탭에 살아있는 content script가 없다는 뜻이고, 그렇다면 우리는 차단
 *    오버레이도 못 띄운다. 집계만 계속하는 건 "막지도 못하면서 시간만 깎는" 최악의 조합이다.
 *  - 이 판정은 스스로 낫는다. content script가 (재주입되거나 페이지 로드로) 되살아나면 초기화
 *    직후 지금 상태를 한 번 보고하므로, 실제로 재생 중이었다면 즉시 true로 정정된다.
 *  - 반대 방향(무응답=재생 중)은 스스로 낫지 않는다. 아무도 정정해주지 않기 때문이다.
 */
export function resolvePlaybackAfterProbe(probe) {
  return probe?.answered === true && probe.playing === true;
}
