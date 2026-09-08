// 화이트리스트 검증·매칭 규칙 테스트.
//
// 이 파일이 지키는 건 하나다: **화이트리스트 항목 하나가 유튜브 전체를 여는 일이 다시는 없을 것.**
// 예전 규칙(url.includes(entry))에서는 `/` 한 글자가 정확히 그 일을 했다(documents/QA_REVIEW.md §1.2).
// 그래서 "넓은 값 거부" 케이스를 규칙별로 하나씩 박아둔다 - 매칭 규칙을 나중에 손보더라도
// 이 목록이 통과하는 한 마스터키는 못 만들어진다.

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  MIN_WHITELIST_ENTRY_LENGTH,
  WHITELIST_ERROR,
  normalizeWhitelistEntry,
  parseWhitelistEntry,
  whitelistEntryError
} from '../src/lib/whitelist.js';
import { isWhitelistedUrl } from '../src/lib/blockDecision.js';

const WATCH_URL = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ';
const SHORTS_URL = 'https://www.youtube.com/shorts/abc123';
const CHANNEL_URL = 'https://www.youtube.com/@lecture/videos';
const MUSIC_URL = 'https://music.youtube.com/watch?v=abcdefghijk';

test('전면 통과를 만들던 값들은 전부 거부된다', () => {
  // 옛 규칙에서 마스터키였던 값들. 하나라도 통과하면 화이트리스트가 다시 무력화 수단이 된다.
  for (const raw of [
    '/',
    '.',
    '..',
    'watch',
    '/watch',
    'youtube.com',
    'www.youtube.com',
    'https://youtube.com',
    'm.youtube.com',
    'youtu.be',
    '/shorts',
    'com'
  ]) {
    assert.notEqual(whitelistEntryError(raw), null, `"${raw}"는 거부돼야 한다`);
    assert.equal(isWhitelistedUrl(WATCH_URL, [raw]), false, `"${raw}"로 영상이 열리면 안 된다`);
    assert.equal(isWhitelistedUrl(SHORTS_URL, [raw]), false, `"${raw}"로 Shorts가 열리면 안 된다`);
  }
});

test('빈 값·공백·와일드카드는 형식 오류다', () => {
  for (const raw of ['', '   ', null, undefined, '*', 'youtube.com/*', 'a b', '"x"']) {
    assert.equal(whitelistEntryError(raw), WHITELIST_ERROR.format);
  }
});

test('너무 짧은 값은 길이 하한에 걸린다', () => {
  assert.equal(whitelistEntryError('@ab'), WHITELIST_ERROR.tooShort);
  assert.equal(MIN_WHITELIST_ENTRY_LENGTH, 4);
});

test('채널 핸들은 그 채널만 연다', () => {
  assert.equal(whitelistEntryError('@lecture'), null);
  assert.equal(isWhitelistedUrl(CHANNEL_URL, ['@lecture']), true);
  assert.equal(isWhitelistedUrl(WATCH_URL, ['@lecture']), false);
  // 접두사가 같은 다른 채널까지 열리면 안 된다.
  assert.equal(isWhitelistedUrl('https://www.youtube.com/@lecture2', ['@lecture']), false);
});

test('영상 ID는 그 영상만 연다', () => {
  assert.equal(isWhitelistedUrl(WATCH_URL, ['dQw4w9WgXcQ']), true);
  assert.equal(
    isWhitelistedUrl('https://www.youtube.com/watch?v=other123456', ['dQw4w9WgXcQ']),
    false
  );
  // 대소문자가 다르면 다른 영상이다 - 접어버리면 엉뚱한 영상이 통과한다.
  assert.equal(
    isWhitelistedUrl('https://www.youtube.com/watch?v=DQW4W9WGXCQ', ['dQw4w9WgXcQ']),
    false
  );
});

test('경로 항목은 구분자 경계에서만 접두사로 인정된다', () => {
  assert.equal(whitelistEntryError('youtube.com/@lecture'), null);
  assert.equal(isWhitelistedUrl(CHANNEL_URL, ['youtube.com/@lecture']), true);
  assert.equal(
    isWhitelistedUrl('https://www.youtube.com/@lecture', ['youtube.com/@lecture']),
    true
  );
  assert.equal(
    isWhitelistedUrl('https://www.youtube.com/@lecturehall', ['youtube.com/@lecture']),
    false
  );
});

test('쿼리까지 적은 항목은 그 영상만 열고 뒤에 붙는 파라미터는 허용한다', () => {
  const entry = 'youtube.com/watch?v=dQw4w9WgXcQ';
  assert.equal(whitelistEntryError(entry), null);
  assert.equal(isWhitelistedUrl(WATCH_URL, [entry]), true);
  assert.equal(isWhitelistedUrl(`${WATCH_URL}&t=10`, [entry]), true);
  assert.equal(isWhitelistedUrl('https://www.youtube.com/watch?v=zzzzzzzzzzz', [entry]), false);
});

test('호스트만 적은 항목은 정확 일치만, 유튜브 본체는 거부', () => {
  // music.youtube.com은 본체가 아니라서 허용된다 - 작업용 BGM을 따로 여는 정당한 용도다.
  assert.equal(whitelistEntryError('music.youtube.com'), null);
  assert.equal(isWhitelistedUrl(MUSIC_URL, ['music.youtube.com']), true);
  // 하위 도메인 하나가 본체를 열면 안 된다.
  assert.equal(isWhitelistedUrl(WATCH_URL, ['music.youtube.com']), false);
  assert.equal(whitelistEntryError('youtube.com'), WHITELIST_ERROR.tooBroad);
});

test('스킴과 www는 저장 전에 정규화된다', () => {
  assert.equal(normalizeWhitelistEntry('https://youtube.com/@lecture'), 'youtube.com/@lecture');
  assert.equal(normalizeWhitelistEntry('HTTPS://Music.YouTube.com'), 'music.youtube.com');
  assert.equal(normalizeWhitelistEntry('/'), null);
});

test('해석 못 하는 항목은 판정에서 조용히 무시된다', () => {
  // 검증이 없던 시절에 저장됐거나 다른 기기에서 동기화돼 들어온 값이 이미 들어 있을 수 있다.
  assert.equal(isWhitelistedUrl(WATCH_URL, ['/', '.', 'watch']), false);
  // 무효 항목이 섞여 있어도 유효 항목은 계속 동작해야 한다.
  assert.equal(isWhitelistedUrl(CHANNEL_URL, ['/', '@lecture']), true);
});

test('읽을 수 없는 URL이나 빈 목록은 통과시키지 않는다', () => {
  assert.equal(isWhitelistedUrl(WATCH_URL, []), false);
  assert.equal(isWhitelistedUrl(WATCH_URL, null), false);
  assert.equal(isWhitelistedUrl('', ['@lecture']), false);
  assert.equal(isWhitelistedUrl('not a url', ['@lecture']), false);
});

test('parseWhitelistEntry는 세 종류를 구분해 돌려준다', () => {
  assert.equal(parseWhitelistEntry('music.youtube.com').entry.kind, 'origin');
  assert.equal(parseWhitelistEntry('youtube.com/@lecture').entry.kind, 'path');
  assert.equal(parseWhitelistEntry('@lecture').entry.kind, 'token');
});
