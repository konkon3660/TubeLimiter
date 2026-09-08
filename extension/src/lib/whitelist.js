// 화이트리스트 항목의 **형식 검증**과 **매칭 규칙**을 한곳에 모은 파일.
// chrome.*를 참조하지 않는 순수 모듈이라 node:test에서 그대로 돌린다 (blockDecision.js와 같은 원칙).
//
// ## 왜 따로 떼어냈나
//
// 예전 규칙은 `url.includes(entry)` 한 줄이 전부였고 저장할 때 아무 검증도 없었다. 그래서 `/`
// 한 글자만 넣으면 모든 유튜브 URL이 화이트리스트에 걸렸다 — 한도·수동 차단·Shorts 차단이
// 통째로 무력화되는 마스터키였다 (documents/QA_REVIEW.md §1.2).
//
// 그래서 **두 겹**으로 막는다.
//   1. 저장할 때: 형식·길이·"전면 통과 값" 검증 (options.js가 whitelistEntryError를 쓴다).
//   2. 판정할 때: 같은 검증을 한 번 더 돌려서 통과 못 한 항목은 아예 무시한다 (isWhitelistedUrl).
//
// 2번이 없으면 안 되는 이유: 검증이 없던 시절에 저장된 값이나 **다른 기기에서 동기화돼 들어온
// 값**이 이미 settings.whitelist 안에 들어 있을 수 있다. 저장 쪽만 막으면 그 값들은 계속 산다.
// 판정에서 거르면 "위험한 옛 항목은 조용히 효력을 잃고, 옵션 화면이 그 이유를 보여준다"가 된다.
//
// ## 매칭을 어떻게 좁혔나 (부분 문자열 → 호스트 + 경로 접두사)
//
// 항목은 세 모양 중 하나로 해석된다.
//   - origin  : 호스트만 (`music.youtube.com`)         → 호스트 **정확 일치**
//   - path    : 호스트(선택) + 경로 (`youtube.com/@ch`) → 호스트 일치(하위 도메인 허용)
//                                                        + 경로 **접두사**(구분자 경계) 일치
//   - token   : 슬래시·점·물음표가 없는 낱말 (`@ch`, `dQw4w9WgXcQ`)
//                                                      → 경로 첫 조각이 같거나 ?v= 영상 ID가 같음
//
// 기존 사용자 호환(마이그레이션): 실제로 쓸 법한 옛 항목은 그대로 산다.
//   `youtube.com/@lecture` → path 규칙으로 계속 매칭, `@lecture`·영상 ID → token 규칙으로 계속 매칭.
// 반대로 옛 규칙에서만 통하던 넓은 값(`/`, `.`, `youtube.com`, `watch`)은 여기서 전부 죽는다.
// 그게 이 변경의 목적이다. 값을 잃는 대신 옵션 화면이 무효 항목과 이유를 그대로 보여준다.
//
// 대소문자: 호스트만 소문자로 접고 경로·토큰은 있는 그대로 비교한다. 영상 ID는 대소문자가
// 다르면 다른 영상이라, 여기서 접으면 엉뚱한 영상이 통과할 수 있다.

/** 이보다 짧은 값은 오타이거나 너무 넓다 (채널 핸들 최소 길이가 `@`+3자다). */
export const MIN_WHITELIST_ENTRY_LENGTH = 4;

/** 화면에 띄울 거부 사유. 값은 _locales의 메시지 키다 (문구는 UI가 붙인다 — lib/i18n.js 주석 참고). */
export const WHITELIST_ERROR = Object.freeze({
  format: 'options_whitelist_error_format',
  tooShort: 'options_whitelist_error_short',
  tooBroad: 'options_whitelist_error_broad'
});

/**
 * 호스트만 적었을 때 거부하는 호스트. 전부 "유튜브 본체"라, 통째로 허용하면 화이트리스트가
 * 곧 전면 통과가 된다. music.youtube.com처럼 본체가 아닌 하위 도메인은 일부러 뺐다 —
 * 작업용 BGM을 따로 허용하는 게 정당한 용도이기 때문이다 (QA_REVIEW.md §2.4).
 */
const FULL_YOUTUBE_HOSTS = Object.freeze([
  'youtube.com',
  'm.youtube.com',
  'youtu.be',
  'youtube-nocookie.com'
]);

/**
 * 뒤에 아무것도 없이 이것만 적으면 "그 종류의 페이지 전부"가 열리는 경로 첫 조각.
 * `/watch` 하나면 모든 영상이, `/shorts` 하나면 모든 Shorts가 통과한다.
 * (`/watch?v=...`처럼 뒤가 더 붙으면 특정 영상이므로 허용된다.)
 */
const BROAD_FIRST_SEGMENTS = Object.freeze([
  '',
  'watch',
  'shorts',
  'live',
  'embed',
  'v',
  'feed',
  'results',
  'playlist'
]);

/** 스킴(https://)만 떼어낸다. 화이트리스트는 http/https를 구분하지 않는다. */
function stripScheme(value) {
  return value.replace(/^[a-z][a-z0-9+.-]*:\/\//i, '');
}

function stripWww(host) {
  return host.replace(/^www\./, '');
}

function isHostShaped(value) {
  return /^[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,}$/.test(value);
}

/** 경로 첫 조각(쿼리 제외). `/@ch/videos` → `@ch`, `/` → `''`. */
function firstSegmentOf(pathname) {
  return (pathname.split('?')[0].split('/')[1] || '').toLowerCase();
}

/**
 * 항목 한 줄을 해석한다. 성공하면 `{ ok: true, entry }`, 실패하면 `{ ok: false, errorKey }`.
 * 저장 검증(whitelistEntryError)과 판정(isWhitelistedUrl)이 **같은 함수**를 쓴다 — 규칙이
 * 두 벌이 되면 "저장은 되는데 안 먹는" 항목이 생긴다.
 */
export function parseWhitelistEntry(raw) {
  const trimmed = String(raw ?? '').trim();
  if (!trimmed) return { ok: false, errorKey: WHITELIST_ERROR.format };
  // 공백·와일드카드·인용부호는 지원하지 않는다. 조용히 안 맞는 것보다 거부하고 알려주는 게 낫다.
  if (/[\s*"'<>\\^|`{}]/.test(trimmed)) return { ok: false, errorKey: WHITELIST_ERROR.format };

  const rest = stripScheme(trimmed);
  if (!rest) return { ok: false, errorKey: WHITELIST_ERROR.format };

  let host = '';
  let pathname;

  if (rest.startsWith('/')) {
    pathname = rest;
  } else {
    const slash = rest.indexOf('/');
    const head = slash === -1 ? rest : rest.slice(0, slash);
    const tail = slash === -1 ? '' : rest.slice(slash);
    if (head.includes('.')) {
      // 점이 있으면 호스트로 본다. 호스트 모양이 아니면(`.`, `..`) 거부 — 옛 규칙에서 전면
      // 통과를 만들던 값들이 대부분 여기서 걸린다.
      if (!isHostShaped(head.toLowerCase())) return { ok: false, errorKey: WHITELIST_ERROR.format };
      host = head.toLowerCase();
      pathname = tail;
    } else if (slash === -1 && !rest.includes('?')) {
      // 슬래시도 점도 없는 낱말 = 채널 핸들 또는 영상 ID.
      return finishToken(trimmed, rest);
    } else {
      pathname = `/${rest}`;
    }
  }

  const value = `${host}${pathname}`;
  if (value.length < MIN_WHITELIST_ENTRY_LENGTH) {
    return { ok: false, errorKey: WHITELIST_ERROR.tooShort };
  }

  if (!pathname || pathname === '/') {
    // 호스트만 적은 항목. 유튜브 본체면 전면 통과라 거부한다.
    if (!host) return { ok: false, errorKey: WHITELIST_ERROR.format };
    if (FULL_YOUTUBE_HOSTS.includes(stripWww(host))) {
      return { ok: false, errorKey: WHITELIST_ERROR.tooBroad };
    }
    return { ok: true, entry: { kind: 'origin', host, path: '', token: '', value: host } };
  }

  const [pathOnly, query = ''] = splitQuery(pathname);
  // 경로 첫 조각만 적힌 넓은 값(`/watch`, `/shorts`)은 거부. 뒤에 조각이나 쿼리가 더 붙으면 통과.
  const segments = pathOnly.split('/').filter(Boolean);
  if (segments.length <= 1 && !query && BROAD_FIRST_SEGMENTS.includes(firstSegmentOf(pathOnly))) {
    return { ok: false, errorKey: WHITELIST_ERROR.tooBroad };
  }

  return { ok: true, entry: { kind: 'path', host, path: pathname, token: '', value } };
}

function finishToken(original, token) {
  if (!/^[@A-Za-z0-9_-]+$/.test(token)) return { ok: false, errorKey: WHITELIST_ERROR.format };
  if (token.length < MIN_WHITELIST_ENTRY_LENGTH) {
    return { ok: false, errorKey: WHITELIST_ERROR.tooShort };
  }
  if (BROAD_FIRST_SEGMENTS.includes(token.toLowerCase())) {
    return { ok: false, errorKey: WHITELIST_ERROR.tooBroad };
  }
  return { ok: true, entry: { kind: 'token', host: '', path: '', token, value: original } };
}

function splitQuery(pathname) {
  const idx = pathname.indexOf('?');
  return idx === -1 ? [pathname, ''] : [pathname.slice(0, idx), pathname.slice(idx)];
}

/** 저장 전 검증. 통과하면 null, 아니면 메시지 키. */
export function whitelistEntryError(raw) {
  const parsed = parseWhitelistEntry(raw);
  return parsed.ok ? null : parsed.errorKey;
}

/**
 * 저장할 값으로 다듬는다(스킴 제거, 호스트 소문자화). 무효면 null.
 * 저장 값을 통일해두면 "https://youtube.com/@a"와 "youtube.com/@a"가 중복으로 쌓이지 않고,
 * 하드코어 잠금의 "화이트리스트는 부분집합이어야 한다" 비교도 흔들리지 않는다.
 */
export function normalizeWhitelistEntry(raw) {
  const parsed = parseWhitelistEntry(raw);
  return parsed.ok ? parsed.entry.value : null;
}

/** 판정 대상 URL을 호스트/경로/영상 ID로 쪼갠다. 읽을 수 없는 값이면 null. */
export function parseWhitelistTarget(url) {
  if (typeof url !== 'string' || !url) return null;
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    try {
      // 스킴 없이 들어온 값(테스트·구버전 저장값)도 읽어준다.
      parsed = new URL(`https://${url}`);
    } catch {
      return null;
    }
  }
  const host = parsed.hostname.toLowerCase();
  return {
    host,
    baseHost: stripWww(host),
    path: parsed.pathname,
    search: parsed.search,
    firstSegment: (parsed.pathname.split('/')[1] || '').toLowerCase(),
    videoId: parsed.searchParams.get('v') || ''
  };
}

function hostMatches(entryHost, target) {
  const base = stripWww(entryHost);
  return target.baseHost === base || target.baseHost.endsWith(`.${base}`);
}

/** 해석이 끝난 항목 하나와 대상 URL을 맞춰본다. */
export function whitelistEntryMatches(entry, target) {
  if (!entry || !target) return false;

  if (entry.kind === 'origin') {
    // 호스트만 적은 항목은 **정확 일치**만 인정한다. 하위 도메인까지 열어주면
    // `music.youtube.com` 하나가 `www.youtube.com`을 여는 식의 사고가 난다.
    return target.baseHost === stripWww(entry.host);
  }

  if (entry.kind === 'token') {
    // 채널 핸들(경로 첫 조각) 또는 영상 ID(?v=). 둘 다 "정확히 그것"만 연다.
    return target.firstSegment === entry.token.toLowerCase() || target.videoId === entry.token;
  }

  if (entry.host && !hostMatches(entry.host, target)) return false;

  const [entryPath, entryQuery] = splitQuery(entry.path);
  if (entryQuery) {
    // 쿼리까지 적었으면 경로+쿼리 앞부분이 그대로 일치해야 한다 (`/watch?v=abc` → `...&t=10` 허용).
    return `${target.path}${target.search}`.startsWith(`${entryPath}${entryQuery}`);
  }
  // 경로는 구분자 경계에서만 접두사로 인정한다 — `/@lecture`가 `/@lecture2`를 열면 안 된다.
  return target.path === entryPath || target.path.startsWith(`${entryPath}/`);
}
