// 동기화·인증 실패를 **이 브라우저 안에만** 쌓아두는 링버퍼의 순수 로직.
//
// MV3 서비스워커는 유휴 상태가 되면 죽고, 그때 devtools 콘솔도 같이 날아간다. 지금까지
// console.error로만 남기던 실패는 사실상 아무 흔적도 남기지 않은 것과 같아서, 며칠째 Supabase
// 동기화가 막혀 있어도 사용자는 물론 우리도 알 방법이 없었다. 외부 크래시 리포팅 서비스를
// 붙이지 않기로 했으므로(계정도 비용도 개인정보 처리도 필요 없다) 그 자리를 이 링버퍼가 메운다.
//
// 규칙은 안드로이드 diagnostics/SyncDiagnostics.kt와 **의도적으로 동일하다** — 같은 이벤트 종류
// 이름, 같은 50건 링버퍼, 같은 (종류+코드) 합산, 같은 24시간 임계값. 두 클라이언트가 서로 다른
// 기준으로 갈라지면 "폰에선 멀쩡한데 크롬만 이상하다"는 판단 자체를 할 수 없게 된다.
// 저장 인코딩만 다르다: 안드로이드는 DataStore라 손으로 만든 문자열을 쓰지만 여기는
// chrome.storage.local이 구조화된 값을 그대로 담으므로 배열/객체로 둔다.
//
// ## 민감정보 금지 — 이 파일의 존재 이유의 절반
//
// access token, 이메일, `user_id`, anon/service key 같은 값은 **절대** 이벤트에 들어가면 안 된다.
// 이 버퍼는 사용자가 옵션 화면에서 읽고 "복사" 버튼으로 클립보드에 담아 남에게 붙여넣는 것을
// 전제로 하므로, 한 번 새면 그대로 유출이다. 그래서 두 겹으로 막는다:
//
// 1. 서버 에러 메시지를 **그대로 넣지 말 것.** summarizeFailure()가 허용 목록 방식으로
//    오류 이름 + HTTP 상태 코드 + PostgREST 오류 코드(PGRSTxxx)만 뽑아낸다. PostgREST/GoTrue의
//    오류 문구에는 조건에 걸린 값(이메일, uuid 등)이 그대로 실려 오는 경우가 있어서, 메시지
//    본문은 한 글자도 옮기지 않는다.
// 2. 그래도 새는 경우를 대비해 sanitizeDiagnosticCode()가 이메일·UUID·JWT 모양을 한 번 더
//    지운다. **마지막 방어선이지 1번의 대체재가 아니다.**
//
// 이벤트를 만드는 모든 경로는 반드시 이 두 함수를 거쳐야 한다.

/** 링버퍼 크기. 넘치면 오래된 것부터 버린다. (안드로이드 DIAGNOSTIC_CAPACITY와 같은 값) */
export const DIAGNOSTIC_CAPACITY = 50;

/** 마지막 동기화 성공이 이만큼 지나면 팝업에 조용한 한 줄 경고를 띄운다. */
export const SYNC_STALE_THRESHOLD_MS = 24 * 60 * 60 * 1000;

/** 코드 한 줄의 최대 길이. 길수록 원문(=민감정보)이 섞여 들어올 여지가 커진다. */
const MAX_CODE_LENGTH = 48;

/** 저장된 코드에서 지워야 할 것들. 순서대로 훑고 전부 `[redacted]`로 바꾼다. */
const REDACTION_PATTERNS = [
  // 이메일
  /[\w.+-]+@[\w.-]+\.\w+/g,
  // user_id 같은 UUID
  /[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/g,
  // JWT (access token / anon key / service key 전부 이 모양이다)
  /eyJ[A-Za-z0-9_.-]{8,}/g
];

/**
 * 메시지에서 뽑아낼 **유일한** 숫자. 앞뒤가 영숫자면 잡지 않아 uuid 조각에서 세 자리를
 * 잘라오는 일이 없다. (안드로이드 HTTP_STATUS_PATTERN과 같은 규칙)
 */
const HTTP_STATUS_PATTERN = /(?<![0-9A-Za-z])[45][0-9]{2}(?![0-9A-Za-z])/;

/** PostgREST가 돌려주는 오류 코드(`PGRST116` 등). 값이 아니라 분류라 안전하다. */
const POSTGREST_CODE_PATTERN = /PGRST[0-9]{3}/;

/**
 * 제어문자(개행 포함)를 공백으로 바꾼다. 코드 문자열은 오류 메시지에서 나온 값이라 개행이
 * 얼마든지 섞여 올 수 있는데, 그대로 두면 진단 화면의 한 줄과 클립보드 텍스트의 줄 경계가 무너진다.
 *
 * 정규식 대신 코드포인트로 훑는 이유는 두 가지다: 제어문자를 소스에 직접 박지 않아도 되고,
 * 안드로이드가 쓰는 `Char.isISOControl()`(U+0000..U+001F, U+007F..U+009F)과 범위를 정확히 맞출 수 있다.
 */
function collapseControlChars(text) {
  let out = '';
  for (const char of text) {
    const code = char.codePointAt(0);
    out += code <= 0x1f || (code >= 0x7f && code <= 0x9f) ? ' ' : char;
  }
  return out;
}

/**
 * 어느 경로가 실패했는지. 안드로이드 DiagnosticKind와 **문자열까지 같아야** 두 기기에서 모은
 * 기록을 나란히 놓고 읽을 수 있다.
 */
export const DiagnosticKind = {
  SYNC_SETTINGS: 'sync_settings',
  SYNC_STREAK: 'sync_streak',
  SYNC_USAGE: 'sync_usage',
  EMERGENCY_FETCH: 'emergency_fetch',
  AUTH: 'auth',
  MONITOR: 'monitor'
};

const KIND_LABELS = {
  [DiagnosticKind.SYNC_SETTINGS]: '설정 동기화',
  [DiagnosticKind.SYNC_STREAK]: '연속 기록 / 뱃지',
  [DiagnosticKind.SYNC_USAGE]: '사용시간 보고',
  [DiagnosticKind.EMERGENCY_FETCH]: '긴급 횟수 조회',
  [DiagnosticKind.AUTH]: '로그인 세션',
  [DiagnosticKind.MONITOR]: '감시 루프'
};

/** 화면 표시용 한글 이름. 모르는 종류(예전 버전이 남긴 값)는 그대로 보여준다. */
export function diagnosticKindLabel(kind) {
  return KIND_LABELS[kind] || kind;
}

/**
 * 코드/종류 문자열에서 제어문자를 없애고, 민감해 보이는 값을 지우고, 길이를 자른다.
 * 이벤트를 넣는 유일한 통로인 appendDiagnosticEvent()가 항상 이걸 거치므로 저장된 문자열엔
 * 위 REDACTION_PATTERNS에 걸리는 값이 남을 수 없다.
 */
export function sanitizeDiagnosticCode(raw) {
  if (typeof raw !== 'string' || !raw.trim()) return 'unknown';
  let cleaned = raw;
  for (const pattern of REDACTION_PATTERNS) {
    cleaned = cleaned.replace(pattern, '[redacted]');
  }
  // 제어문자는 공백으로 접고 연속 공백은 하나로 — 화면과 클립보드 양쪽에서 한 줄로 읽혀야 한다.
  const collapsed = collapseControlChars(cleaned).trim().replace(/\s+/g, ' ');
  return collapsed === '' ? 'unknown' : collapsed.slice(0, MAX_CODE_LENGTH);
}

/**
 * 오류를 **허용 목록 방식**으로 짧은 코드로 줄인다. 남기는 건 오류 이름과, 필드/메시지에서
 * 뽑아낸 HTTP 상태 · PostgREST 코드뿐 — 메시지 본문은 한 글자도 옮기지 않는다. 원문에 무엇이
 * 들어 있을지 우리가 통제할 수 없기 때문이다(파일 맨 위 "민감정보 금지" 참고).
 *
 * supabase-js는 던지는 예외(Error)와 돌려주는 오류 객체(PostgrestError: `{message, code, ...}`,
 * AuthError: `status` 보유)가 섞여 있어 둘 다 같은 방식으로 받는다.
 */
export function summarizeFailure(error) {
  if (error === null || error === undefined) return 'unknown';
  if (typeof error === 'string') {
    // 문자열만 온 경우엔 이름을 알 수 없다. 문자열 자체는 서버 문구일 수 있어 버린다.
    return sanitizeDiagnosticCode(['Error', extractStatus(error), extractPostgrestCode(error)].filter(Boolean).join('/'));
  }
  if (typeof error !== 'object') return 'unknown';

  // PostgrestError에는 name이 없다 — 그때는 어떤 모양인지가 곧 분류라서 고정 이름으로 접는다.
  const name = typeof error.name === 'string' && error.name ? error.name : 'PostgrestError';
  const message = typeof error.message === 'string' ? error.message : '';
  // 상태 코드는 필드로 오는 경우(AuthError.status)와 메시지에 섞여 오는 경우가 둘 다 있다.
  const status = statusFromField(error.status) || extractStatus(message);
  // PostgrestError.code는 `PGRST116`처럼 분류값이거나 Postgres SQLSTATE다. 값이 아니라 분류만
  // 담기는 자리지만, 그래도 우리가 아는 모양(PGRSTxxx)만 통과시킨다.
  const postgrest = extractPostgrestCode(typeof error.code === 'string' ? error.code : '') || extractPostgrestCode(message);
  return sanitizeDiagnosticCode([name, status, postgrest].filter(Boolean).join('/'));
}

function statusFromField(value) {
  return Number.isInteger(value) && value >= 400 && value <= 599 ? `http_${value}` : null;
}

function extractStatus(message) {
  const match = HTTP_STATUS_PATTERN.exec(message);
  return match ? `http_${match[0]}` : null;
}

function extractPostgrestCode(text) {
  const match = POSTGREST_CODE_PATTERN.exec(text);
  return match ? match[0] : null;
}

/**
 * chrome.storage.local에서 읽어온 값을 믿을 수 있는 이벤트 배열로 바꾼다.
 *
 * 저장소 값은 예전 버전이 쓴 모양이거나(필드 추가 전) 사용자가 devtools로 건드려 통째로 깨져
 * 있을 수 있다. 항목 하나가 이상하다고 진단 화면이나 팝업 렌더가 통째로 터지면 안 되므로,
 * 이상한 항목은 조용히 버리고 나머지만 돌려준다(안드로이드 decodeDiagnosticEvents와 같은 태도).
 */
export function normalizeDiagnosticEvents(raw, capacity = DIAGNOSTIC_CAPACITY) {
  if (!Array.isArray(raw) || capacity <= 0) return [];
  const events = [];
  for (const entry of raw) {
    if (!entry || typeof entry !== 'object') continue;
    const atMillis = Number(entry.atMillis);
    if (!Number.isFinite(atMillis)) continue;
    // 종류/코드가 비어 있으면 무엇이 실패했는지 알 수 없는 줄이라 남길 가치가 없다.
    if (typeof entry.kind !== 'string' || !entry.kind.trim()) continue;
    if (typeof entry.code !== 'string' || !entry.code.trim()) continue;
    const count = Number(entry.count);
    events.push({
      atMillis,
      kind: sanitizeDiagnosticCode(entry.kind),
      code: sanitizeDiagnosticCode(entry.code),
      count: Number.isFinite(count) ? Math.max(1, Math.trunc(count)) : 1
    });
  }
  return events.slice(0, capacity);
}

/**
 * 새 실패를 버퍼 맨 앞(=최신)에 넣는다. 목록은 항상 최신순이고 capacity를 넘으면 뒤쪽
 * (=오래된 것)부터 버린다.
 *
 * 같은 (종류, 코드)가 이미 있으면 새로 쌓지 않고 그 항목의 시각을 갱신하며 count만 올린다.
 * 30초마다 도는 동기화가 실패하면 같은 실패가 하루에 2880번 쌓이는데, 그대로 넣으면
 * "설정 동기화 http_500" 한 종류가 버퍼 50칸을 다 차지해서 정작 다른 실패가 밀려난다.
 * "설정 동기화 http_500 ×143, 마지막 3분 전" 한 줄이 같은 줄 143개보다 진단에도 쓸모 있다.
 */
export function appendDiagnosticEvent(events, event, capacity = DIAGNOSTIC_CAPACITY) {
  if (capacity <= 0) return [];
  const existing = normalizeDiagnosticEvents(events, capacity);
  const kind = sanitizeDiagnosticCode(event?.kind);
  const code = sanitizeDiagnosticCode(event?.code);
  const at = Number(event?.atMillis);
  const atMillis = Number.isFinite(at) ? at : 0;
  const increment = Number.isFinite(Number(event?.count)) ? Math.max(1, Math.trunc(Number(event.count))) : 1;

  const previous = existing.find((e) => e.kind === kind && e.code === code);
  const merged = { atMillis, kind, code, count: (previous?.count || 0) + increment };
  const rest = existing.filter((e) => !(e.kind === kind && e.code === code));
  return [merged, ...rest].slice(0, capacity);
}

/**
 * "성공 기록 없음"(null/undefined)과 "1970년"(0)을 구별한다. Number(null)이 0이라 그냥
 * Number.isFinite로 거르면 한 번도 성공한 적 없는 상태가 "1970-01-01에 성공"으로 둔갑한다.
 */
function toMillisOrNull(value) {
  if (value === null || value === undefined || value === '') return null;
  const millis = Number(value);
  return Number.isFinite(millis) ? millis : null;
}

/** 화면과 클립보드가 같이 쓰는 시각 표기. 초 단위까지는 진단에 필요 없다. */
export function formatDiagnosticTime(millis) {
  const date = new Date(millis);
  if (Number.isNaN(date.getTime())) return '알 수 없음';
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * 클립보드로 나가는 텍스트. 여기 들어가는 값은 시각·종류·코드·횟수뿐이라, 어디에 붙여넣어도
 * 계정을 특정할 수 있는 정보가 따라나가지 않는다. (안드로이드 buildDiagnosticsReport와 같은 형식)
 */
export function buildDiagnosticsReport(lastSuccessAtMillis, events) {
  const lastSuccessMillis = toMillisOrNull(lastSuccessAtMillis);
  const lastSuccess = lastSuccessMillis === null ? '없음' : formatDiagnosticTime(lastSuccessMillis);
  const header = `TubeLimiter 동기화 진단\n최근 성공: ${lastSuccess}`;
  const list = normalizeDiagnosticEvents(events);
  if (list.length === 0) return `${header}\n최근 실패: 없음`;
  const lines = list
    .map((event) => {
      const repeat = event.count > 1 ? ` x${event.count}` : '';
      return `${formatDiagnosticTime(event.atMillis)} ${event.kind} ${event.code}${repeat}`;
    })
    .join('\n');
  return `${header}\n최근 실패 ${list.length}건\n${lines}`;
}

/**
 * 팝업에 띄울 한 줄 경고, 띄울 게 없으면 null.
 *
 * 로그아웃 상태에서는 애초에 동기화할 게 없으니 조용히 넘어간다. 성공 기록이 아예 없는데
 * 실패는 쌓인 경우는 "며칠째 실패"를 셀 기준점이 없으므로 시간 대신 사실만 말한다. 문구는
 * 겁주지 않는 선에서 사실만 — 사용자가 지금 당장 뭘 잘못한 게 아니고, 실제로 그냥 오프라인일
 * 수도 있다. (안드로이드 staleSyncWarning과 같은 판정, 안내하는 화면 이름만 다르다)
 */
export function staleSyncWarning(
  signedIn,
  lastSuccessAtMillis,
  hasRecordedFailure,
  nowMillis,
  thresholdMillis = SYNC_STALE_THRESHOLD_MS
) {
  if (!signedIn) return null;
  const lastSuccess = toMillisOrNull(lastSuccessAtMillis);
  if (lastSuccess === null) {
    return hasRecordedFailure
      ? '아직 한 번도 동기화에 성공하지 못했어요. 옵션 > 동기화 상태에서 확인할 수 있어요.'
      : null;
  }
  const elapsed = nowMillis - lastSuccess;
  if (elapsed < thresholdMillis) return null;
  const hours = Math.floor(elapsed / (60 * 60 * 1000));
  return `동기화가 ${hours}시간째 되지 않고 있어요. 옵션 > 동기화 상태에서 확인할 수 있어요.`;
}
