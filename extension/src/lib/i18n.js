// chrome.i18n 얇은 래퍼 + HTML 정적 문구 치환.
//
// 문구는 전부 public/_locales/<lang>/messages.json에 있고, 코드에는 **키만** 남는다.
// 확장 페이지들의 CSP가 `script-src 'self'`라 인라인 <script>를 못 쓰므로, HTML 쪽 치환도
// 반드시 이 모듈을 import한 외부 스크립트에서 applyI18n()을 불러 처리한다.
//
// 판정 로직(lib/blockDecision.js, lib/alarmRules.js, lib/gamification.js …)에서는 이 모듈을
// import하지 않는다 — 그쪽은 chrome.* 없이 node:test로 돌아가야 하므로 키/enum만 돌려주고
// 문구는 호출자(UI)가 여기서 붙인다.

/**
 * 개수에 따라 단수/복수 메시지 키를 고른다. **순수 함수** (chrome.* 의존 없음).
 *
 * chrome.i18n에는 복수형 기능이 아예 없다. 한국어는 수 일치가 없어 문제가 안 되지만 영어는
 * "1 minute / 2 minutes"로 갈리므로, `<base>_one` / `<base>_other` 두 키를 두고 개수로 고른다.
 * 0은 영어에서 복수형("0 minutes")이라 other로 간다. 개수를 못 읽으면(NaN 등) other가 안전한
 * 기본값이다 — 영어에서 복수형이 더 일반적인 형태이기 때문.
 */
export function pluralMessageKey(baseKey, count) {
  const n = Number(count);
  const isOne = Number.isFinite(n) && Math.abs(n) === 1;
  return `${baseKey}_${isOne ? 'one' : 'other'}`;
}

/** messages.json의 한 줄을 가져온다. 치환값은 문자열(또는 문자열 배열, 최대 9개). */
export function t(key, substitutions) {
  return chrome.i18n.getMessage(key, substitutions);
}

/**
 * 개수가 들어가는 문구. `$1`이 개수로 채워지고, 뒤에 추가 치환값을 더 붙일 수 있다($2, $3 …).
 * 문자열을 `+`로 잇지 않는 이유는 어순이 언어마다 다르기 때문 — 순서는 messages.json이 정한다.
 */
export function tCount(baseKey, count, extraSubstitutions = []) {
  return t(pluralMessageKey(baseKey, count), [String(count), ...extraSubstitutions]);
}

// 텍스트 말고 속성에 넣어야 하는 자리들. `data-i18n-empty`는 CSS의 `content: attr(data-empty)`
// 로 흘러간다 — CSS에서는 chrome.i18n을 부를 수 없어서 비어 있는 목록의 안내 문구를 이렇게 넣는다.
const ATTRIBUTE_TARGETS = [
  ['data-i18n-placeholder', 'placeholder'],
  ['data-i18n-title', 'title'],
  ['data-i18n-empty', 'data-empty']
];

/**
 * 페이지의 고정 문구를 한 번에 치환한다. 각 페이지 스크립트가 맨 처음에 한 번 부른다.
 *
 * - `data-i18n="키"` → textContent
 * - `data-i18n-placeholder="키"` → placeholder 속성
 * - `data-i18n-title="키"` → title 속성 (툴팁)
 * - `data-i18n-empty="키"` → data-empty 속성 (CSS `content: attr(data-empty)`용)
 *
 * 키가 messages.json에 없으면 getMessage가 빈 문자열을 돌려준다. 그때는 HTML에 적혀 있는
 * 원문을 그대로 두는 게 낫다 — 화면이 통째로 비는 것보다 낫고, 누락은 테스트가 잡는다
 * (test/i18n.test.js가 HTML의 키와 messages.json을 대조한다).
 */
export function applyI18n(root = document) {
  root.querySelectorAll('[data-i18n]').forEach((el) => {
    const message = t(el.getAttribute('data-i18n'));
    if (message) el.textContent = message;
  });

  for (const [sourceAttribute, targetAttribute] of ATTRIBUTE_TARGETS) {
    root.querySelectorAll(`[${sourceAttribute}]`).forEach((el) => {
      const message = t(el.getAttribute(sourceAttribute));
      if (message) el.setAttribute(targetAttribute, message);
    });
  }
}
