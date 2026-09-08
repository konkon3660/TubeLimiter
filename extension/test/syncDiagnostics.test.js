import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  DIAGNOSTIC_CAPACITY,
  SYNC_STALE_THRESHOLD_MS,
  DiagnosticKind,
  StaleSyncReason,
  diagnosticKindMessageKey,
  sanitizeDiagnosticCode,
  summarizeFailure,
  normalizeDiagnosticEvents,
  appendDiagnosticEvent,
  formatDiagnosticTime,
  buildDiagnosticsReport,
  staleSyncWarning
} from '../src/lib/syncDiagnostics.js';

// 안드로이드 diagnostics/SyncDiagnostics.kt(및 SyncDiagnosticsTest.kt) 대응.
// 두 클라이언트가 같은 규칙(50건 링버퍼 · 같은 종류+코드 합산 · 24시간 임계값)을 쓰는지까지
// 여기서 못 박는다.

const HOUR = 60 * 60 * 1000;

test('안드로이드와 같은 상수를 쓴다 (링버퍼 50건 · 임계값 24시간)', () => {
  assert.equal(DIAGNOSTIC_CAPACITY, 50);
  assert.equal(SYNC_STALE_THRESHOLD_MS, 24 * HOUR);
});

test('이벤트 종류 이름이 안드로이드 DiagnosticKind와 문자열까지 같다', () => {
  assert.deepEqual(DiagnosticKind, {
    SYNC_SETTINGS: 'sync_settings',
    SYNC_STREAK: 'sync_streak',
    SYNC_USAGE: 'sync_usage',
    EMERGENCY_FETCH: 'emergency_fetch',
    AUTH: 'auth',
    MONITOR: 'monitor'
  });
});

test('종류마다 메시지 키를 주고, 모르는 종류는 null이다 (호출자가 원래 값을 그대로 쓴다)', () => {
  // 문구가 아니라 키를 돌려주는 게 핵심이다 — 이 파일은 chrome.i18n 없이 돌아야 한다.
  assert.equal(diagnosticKindMessageKey(DiagnosticKind.SYNC_USAGE), 'diag_kind_sync_usage');
  assert.equal(diagnosticKindMessageKey('sync_future_thing'), null);
});

// --- sanitize: 마지막 방어선 ---

test('이메일은 [redacted]로 지운다', () => {
  assert.equal(
    sanitizeDiagnosticCode('duplicate key user@example.com'),
    'duplicate key [redacted]'
  );
});

test('user_id 같은 UUID는 [redacted]로 지운다', () => {
  assert.equal(
    sanitizeDiagnosticCode('row 3f2504e0-4f89-11d3-9a0c-0305e82c3301 denied'),
    'row [redacted] denied'
  );
});

test('JWT(access token · anon key)는 [redacted]로 지운다', () => {
  const jwt = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.abc';
  assert.equal(sanitizeDiagnosticCode(`bad token ${jwt}`), 'bad token [redacted]');
});

test('제어문자와 개행은 공백 하나로 접힌다 (한 줄로 읽혀야 한다)', () => {
  assert.equal(sanitizeDiagnosticCode('a\nb\t\tc'), 'a b c');
});

test('코드가 48자를 넘으면 잘라낸다 (길수록 원문이 섞여 들어올 여지가 커진다)', () => {
  const cleaned = sanitizeDiagnosticCode('x'.repeat(200));
  assert.equal(cleaned.length, 48);
});

test('비어 있거나 문자열이 아니면 unknown으로 접는다', () => {
  assert.equal(sanitizeDiagnosticCode(''), 'unknown');
  assert.equal(sanitizeDiagnosticCode('   '), 'unknown');
  assert.equal(sanitizeDiagnosticCode(null), 'unknown');
  assert.equal(sanitizeDiagnosticCode(undefined), 'unknown');
  assert.equal(sanitizeDiagnosticCode(42), 'unknown');
});

// --- summarizeFailure: 허용 목록 ---

test('예외는 클래스 이름만 남는다 (메시지 본문은 한 글자도 옮기지 않는다)', () => {
  assert.equal(summarizeFailure(new TypeError('Failed to fetch')), 'TypeError');
});

test('메시지에 섞인 HTTP 상태 코드는 뽑아낸다', () => {
  assert.equal(summarizeFailure(new Error('request failed with status 503')), 'Error/http_503');
});

test('AuthError처럼 status 필드로 오는 상태 코드도 뽑아낸다', () => {
  const authError = { name: 'AuthApiError', message: 'Invalid Refresh Token', status: 401 };
  assert.equal(summarizeFailure(authError), 'AuthApiError/http_401');
});

test('PostgrestError는 이름이 없어 고정 이름으로 접고 code만 남긴다', () => {
  const postgrestError = {
    message: 'JSON object requested, multiple (or no) rows returned',
    details: null,
    hint: null,
    code: 'PGRST116'
  };
  assert.equal(summarizeFailure(postgrestError), 'PostgrestError/PGRST116');
});

test('메시지 본문에 이메일이 실려 와도 요약에는 남지 않는다', () => {
  // PostgREST/GoTrue는 조건에 걸린 값을 문구에 그대로 실어 보낸다 — 이게 걸러지지 않으면
  // 사용자가 진단 기록을 복사하는 순간 그대로 유출이다.
  const error = new Error('duplicate key (email)=(victim@example.com) already exists');
  assert.equal(summarizeFailure(error), 'Error');
});

test('user_id가 담긴 메시지에서도 uuid 조각을 상태 코드로 오해하지 않는다', () => {
  // 안드로이드 HTTP_STATUS_PATTERN과 같은 규칙: 앞뒤가 영숫자면 상태 코드로 안 본다.
  assert.equal(summarizeFailure(new Error('id ab401cd failed')), 'Error');
});

test('상태 코드는 4xx/5xx만 인정한다 (200번대 숫자를 오탐하지 않는다)', () => {
  assert.equal(summarizeFailure(new Error('took 204 ms')), 'Error');
  assert.equal(summarizeFailure({ name: 'X', message: '', status: 200 }), 'X');
});

test('오류가 없으면 unknown', () => {
  assert.equal(summarizeFailure(null), 'unknown');
  assert.equal(summarizeFailure(undefined), 'unknown');
});

// --- 링버퍼: 자르기 · 중복 합산 ---

test('새 실패는 맨 앞(최신)에 들어간다', () => {
  const first = appendDiagnosticEvent([], { atMillis: 1000, kind: DiagnosticKind.AUTH, code: 'a' });
  const second = appendDiagnosticEvent(first, {
    atMillis: 2000,
    kind: DiagnosticKind.SYNC_USAGE,
    code: 'b'
  });
  assert.deepEqual(
    second.map((e) => e.code),
    ['b', 'a']
  );
});

test('같은 (종류, 코드)는 새로 쌓지 않고 count만 올리고 시각을 갱신한다', () => {
  let events = [];
  for (let i = 1; i <= 143; i += 1) {
    events = appendDiagnosticEvent(events, {
      atMillis: i * 1000,
      kind: DiagnosticKind.SYNC_SETTINGS,
      code: 'pull/http_500'
    });
  }
  assert.equal(events.length, 1);
  assert.equal(events[0].count, 143);
  assert.equal(events[0].atMillis, 143000);
});

test('같은 종류라도 코드가 다르면 별개 항목이다', () => {
  const events = appendDiagnosticEvent(
    appendDiagnosticEvent([], {
      atMillis: 1,
      kind: DiagnosticKind.SYNC_USAGE,
      code: 'rpc/http_500'
    }),
    { atMillis: 2, kind: DiagnosticKind.SYNC_USAGE, code: 'rpc/TypeError' }
  );
  assert.equal(events.length, 2);
});

test('합산된 항목은 다시 맨 앞으로 올라온다 (뒤에 묻히지 않는다)', () => {
  let events = appendDiagnosticEvent([], { atMillis: 1, kind: DiagnosticKind.AUTH, code: 'a' });
  events = appendDiagnosticEvent(events, {
    atMillis: 2,
    kind: DiagnosticKind.SYNC_USAGE,
    code: 'b'
  });
  events = appendDiagnosticEvent(events, { atMillis: 3, kind: DiagnosticKind.AUTH, code: 'a' });
  assert.deepEqual(
    events.map((e) => e.code),
    ['a', 'b']
  );
  assert.equal(events[0].count, 2);
});

test('50건을 넘으면 오래된 것부터 버린다', () => {
  let events = [];
  for (let i = 0; i < 60; i += 1) {
    events = appendDiagnosticEvent(events, {
      atMillis: i,
      kind: DiagnosticKind.SYNC_USAGE,
      code: `code-${i}`
    });
  }
  assert.equal(events.length, DIAGNOSTIC_CAPACITY);
  assert.equal(events[0].code, 'code-59');
  assert.equal(events.at(-1).code, 'code-10');
});

test('버퍼에 넣을 때도 sanitize를 거친다 (민감정보가 저장까지 가지 않는다)', () => {
  const events = appendDiagnosticEvent([], {
    atMillis: 1,
    kind: DiagnosticKind.AUTH,
    code: 'refresh failed for user@example.com'
  });
  assert.equal(events[0].code, 'refresh failed for [redacted]');
});

test('capacity가 0 이하면 아무것도 남기지 않는다', () => {
  assert.deepEqual(appendDiagnosticEvent([], { atMillis: 1, kind: 'a', code: 'b' }, 0), []);
});

// --- 손상 데이터 방어 ---

test('저장소 값이 배열이 아니면 빈 목록으로 접는다', () => {
  assert.deepEqual(normalizeDiagnosticEvents(undefined), []);
  assert.deepEqual(normalizeDiagnosticEvents(null), []);
  assert.deepEqual(normalizeDiagnosticEvents('{"broken":true}'), []);
  assert.deepEqual(normalizeDiagnosticEvents({ atMillis: 1 }), []);
});

test('망가진 항목만 버리고 멀쩡한 항목은 남긴다', () => {
  const events = normalizeDiagnosticEvents([
    null,
    'nope',
    { kind: 'auth', code: 'a' }, // 시각 없음
    { atMillis: 'later', kind: 'auth', code: 'a' }, // 시각이 숫자가 아님
    { atMillis: 5, kind: '  ', code: 'a' }, // 종류가 빈 문자열
    { atMillis: 5, kind: 'auth', code: '' }, // 코드가 빈 문자열
    { atMillis: 5, kind: 'auth', code: 'ok' }
  ]);
  assert.equal(events.length, 1);
  assert.deepEqual(events[0], { atMillis: 5, kind: 'auth', code: 'ok', count: 1 });
});

test('count가 이상하면 1로 접는다 (0 · 음수 · 문자열 · 소수)', () => {
  const events = normalizeDiagnosticEvents([
    { atMillis: 1, kind: 'auth', code: 'a', count: 0 },
    { atMillis: 2, kind: 'auth', code: 'b', count: -5 },
    { atMillis: 3, kind: 'auth', code: 'c', count: 'many' },
    { atMillis: 4, kind: 'auth', code: 'd', count: 2.9 }
  ]);
  assert.deepEqual(
    events.map((e) => e.count),
    [1, 1, 1, 2]
  );
});

test('저장소에 50건을 넘게 들어 있어도 읽을 때 잘라낸다', () => {
  const raw = Array.from({ length: 80 }, (_, i) => ({
    atMillis: i,
    kind: 'auth',
    code: `c${i}`
  }));
  assert.equal(normalizeDiagnosticEvents(raw).length, DIAGNOSTIC_CAPACITY);
});

test('깨진 목록 위에 새 실패를 얹어도 터지지 않는다', () => {
  const events = appendDiagnosticEvent('완전히 깨진 값', {
    atMillis: 1,
    kind: DiagnosticKind.AUTH,
    code: 'a'
  });
  assert.equal(events.length, 1);
});

// --- 복사용 텍스트 ---

test('시각은 MM-DD HH:mm으로 보여준다', () => {
  const at = new Date(2026, 8, 7, 4, 5).getTime();
  assert.equal(formatDiagnosticTime(at), '09-07 04:05');
});

test('읽을 수 없는 시각은 null이다 (뭐라고 적을지는 화면이 정한다)', () => {
  assert.equal(formatDiagnosticTime(Number.NaN), null);
});

// 머리말 문구는 화면(options.js)이 chrome.i18n으로 넣는다. 여기서는 그 자리에 무엇이 오든
// 줄 순서와 각 줄의 모양이 그대로인지만 본다.
const LABELS = {
  title: 'REPORT',
  lastSuccess: 'LAST: 09-07 04:05',
  noFailures: 'NO FAILURES',
  failureCount: 'FAILURES 2',
  unknownTime: 'UNKNOWN'
};

test('실패가 없으면 머리말 두 줄 + 없음 한 줄만 나온다', () => {
  assert.equal(buildDiagnosticsReport([], LABELS), 'REPORT\nLAST: 09-07 04:05\nNO FAILURES');
});

test('복사 텍스트에는 시각·종류·코드·횟수만 들어간다', () => {
  const at = new Date(2026, 8, 7, 4, 5).getTime();
  const report = buildDiagnosticsReport(
    [
      { atMillis: at, kind: DiagnosticKind.SYNC_USAGE, code: 'rpc/http_500', count: 3 },
      { atMillis: at, kind: DiagnosticKind.AUTH, code: 'session_refresh', count: 1 }
    ],
    LABELS
  );
  assert.equal(
    report,
    [
      'REPORT',
      'LAST: 09-07 04:05',
      'FAILURES 2',
      '09-07 04:05 sync_usage rpc/http_500 x3',
      '09-07 04:05 auth session_refresh'
    ].join('\n')
  );
});

test('읽을 수 없는 시각 자리에는 문자 그대로 "null"이 아니라 폴백 문구가 들어간다', () => {
  // normalizeDiagnosticEvents는 Number.isFinite만 보므로 1e300 같은 값이 여기까지 온다
  // (Date가 소화하지 못해 formatDiagnosticTime이 null을 준다). 그대로 템플릿에 넣으면
  // 사용자가 붙여넣는 텍스트에 "null 8:00 sync_usage rpc"가 찍힌다.
  const report = buildDiagnosticsReport(
    [{ atMillis: 1e300, kind: DiagnosticKind.SYNC_USAGE, code: 'rpc', count: 1 }],
    LABELS
  );
  assert.equal(report.split('\n').at(-1), 'UNKNOWN sync_usage rpc');
  assert.ok(!report.includes('null'));
});

test('복사 텍스트도 sanitize를 거친다 (저장소가 예전 버전 값이어도 새지 않는다)', () => {
  const report = buildDiagnosticsReport(
    [{ atMillis: 0, kind: 'auth', code: 'denied for victim@example.com', count: 1 }],
    LABELS
  );
  assert.ok(!report.includes('victim@example.com'));
  assert.ok(report.includes('[redacted]'));
});

// --- 24시간 임계값 ---

test('로그아웃 상태에서는 경고하지 않는다 (동기화할 게 없다)', () => {
  assert.equal(staleSyncWarning(false, 0, true, 100 * HOUR), null);
});

test('마지막 성공이 24시간 이내면 경고하지 않는다', () => {
  const now = 100 * HOUR;
  assert.equal(staleSyncWarning(true, now - 23 * HOUR, true, now), null);
});

test('마지막 성공이 24시간을 넘기면 경과 시간을 사실대로 알린다', () => {
  const now = 100 * HOUR;
  const warning = staleSyncWarning(true, now - 30 * HOUR, true, now);
  // 문구가 아니라 사유 + 경과 시간을 돌려준다 — 문구는 popup.js가 chrome.i18n으로 붙인다.
  assert.deepEqual(warning, { reason: StaleSyncReason.STALE, hours: 30 });
});

test('임계값 경계는 안드로이드와 같다: 1ms 모자라면 조용하고, 정각부터 경고한다', () => {
  // 안드로이드 staleSyncWarning의 `elapsed < thresholdMillis` 와 같은 부등호를 쓴다.
  const now = 100 * HOUR;
  assert.equal(staleSyncWarning(true, now - 24 * HOUR + 1, true, now), null);
  assert.ok(staleSyncWarning(true, now - 24 * HOUR, true, now));
});

test('한 번도 성공한 적 없고 실패도 없으면 (막 로그인한 직후) 조용히 넘어간다', () => {
  assert.equal(staleSyncWarning(true, null, false, 100 * HOUR), null);
});

test('한 번도 성공한 적 없는데 실패만 쌓였으면 시간 대신 사실만 말한다', () => {
  const warning = staleSyncWarning(true, null, true, 100 * HOUR);
  // 경과 시간을 셀 기준점이 없으므로 hours 없이 사유만 돌려준다.
  assert.deepEqual(warning, { reason: StaleSyncReason.NEVER_SUCCEEDED });
});
