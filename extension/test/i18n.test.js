import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { pluralMessageKey } from '../src/lib/i18n.js';

// 확장을 브라우저에 올려 눈으로 보지 않고도 "문구가 비어 나오는" 사고를 잡기 위한 테스트.
// 세 가지를 못 박는다:
//   1) HTML의 data-i18n 키가 messages.json에 전부 있다 (없으면 그 자리가 통째로 빈다).
//   2) messages.json에 쓰이지 않는 키가 없다 (지운 화면의 잔해가 쌓이지 않게).
//   3) ko와 en의 키 집합과 치환 자리($1, $2 …)가 정확히 같다 (한쪽만 고치는 실수 방지).

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const HTML_FILES = [
  'public/popup/popup.html',
  'public/options/options.html',
  'public/dashboard/dashboard.html',
  'public/auth/auth.html'
];

/** lib/i18n.js의 applyI18n이 읽는 속성들. 새 속성을 늘리면 여기도 같이 늘려야 한다. */
const I18N_ATTRIBUTE = /data-i18n(?:-placeholder|-title|-empty)?="([A-Za-z0-9_]+)"/g;

/** manifest.json이 쓰는 자리(`__MSG_appName__` 등). */
const MANIFEST_MESSAGE = /__MSG_([A-Za-z0-9_]+)__/g;

/** 코드에 리터럴로 적힌 키. 키에는 이스케이프가 없으므로 이 정도 정규식으로 충분하다. */
const STRING_LITERAL = /'([^'\\\n]*)'|"([^"\\\n]*)"/g;

/** `t('키')` — 그 자리에 바로 적힌 키. 오타를 잡으려면 이쪽은 정확히 존재해야 한다. */
const DIRECT_MESSAGE_CALL = /\bt\(\s*'([A-Za-z0-9_]+)'/g;

/** `tCount('base', n)` / `pluralMessageKey('base', n)` — 접미사는 코드가 붙인다. */
const PLURAL_MESSAGE_CALL = /\b(?:tCount|pluralMessageKey)\(\s*'([A-Za-z0-9_]+)'/g;

const PLACEHOLDER = /\$([1-9])/g;

function read(relativePath) {
  return readFileSync(path.join(ROOT, relativePath), 'utf8');
}

function readLocale(lang) {
  return JSON.parse(read(`public/_locales/${lang}/messages.json`));
}

function matchAll(text, pattern) {
  const found = new Set();
  for (const match of text.matchAll(pattern)) {
    found.add(match[1] ?? match[2]);
  }
  return found;
}

function listJsFiles(dir) {
  const out = [];
  for (const entry of readdirSync(path.join(ROOT, dir))) {
    const relative = `${dir}/${entry}`;
    if (statSync(path.join(ROOT, relative)).isDirectory()) out.push(...listJsFiles(relative));
    else if (relative.endsWith('.js')) out.push(relative);
  }
  return out;
}

const ko = readLocale('ko');
const en = readLocale('en');

const htmlKeys = new Set();
for (const file of HTML_FILES) {
  for (const key of matchAll(read(file), I18N_ATTRIBUTE)) htmlKeys.add(key);
}

// 코드가 "쓰고 있다"고 볼 근거: HTML 속성, manifest의 __MSG_..__, 그리고 소스에 리터럴로 적힌 문자열.
const referencedStrings = new Set([
  ...htmlKeys,
  ...matchAll(read('public/manifest.json'), MANIFEST_MESSAGE)
]);
const directCallKeys = new Set();
const pluralCallKeys = new Set();
for (const file of listJsFiles('src')) {
  const source = read(file);
  for (const literal of matchAll(source, STRING_LITERAL)) referencedStrings.add(literal);
  for (const key of matchAll(source, DIRECT_MESSAGE_CALL)) directCallKeys.add(key);
  for (const key of matchAll(source, PLURAL_MESSAGE_CALL)) pluralCallKeys.add(key);
}

/**
 * 단수/복수 키는 코드에 `<base>_one` / `<base>_other`가 아니라 base만 적혀 있다
 * (tCount/pluralMessageKey가 접미사를 붙인다). 그래서 base가 보이면 둘 다 쓰인 것으로 친다.
 */
function isReferenced(key) {
  if (referencedStrings.has(key)) return true;
  const match = /^(.*)_(one|other)$/.exec(key);
  return !!match && referencedStrings.has(match[1]);
}

// --- 순수 함수: 단수/복수 고르기 ---

test('개수가 1이면 단수 키, 나머지는 복수 키를 고른다', () => {
  assert.equal(pluralMessageKey('minutes', 1), 'minutes_one');
  assert.equal(pluralMessageKey('minutes', 2), 'minutes_other');
  // 영어에서 0은 복수형이다 ("0 minutes").
  assert.equal(pluralMessageKey('minutes', 0), 'minutes_other');
});

test('음수는 절댓값으로 가른다 (-1분 남음도 "1 minute")', () => {
  assert.equal(pluralMessageKey('minutes', -1), 'minutes_one');
  assert.equal(pluralMessageKey('minutes', -3), 'minutes_other');
});

test('소수와 읽을 수 없는 값은 복수 쪽으로 접는다', () => {
  assert.equal(pluralMessageKey('minutes', 1.5), 'minutes_other');
  assert.equal(pluralMessageKey('minutes', Number.NaN), 'minutes_other');
  assert.equal(pluralMessageKey('minutes', undefined), 'minutes_other');
  // 문자열로 들어온 숫자도 개수로 읽는다.
  assert.equal(pluralMessageKey('minutes', '1'), 'minutes_one');
});

// --- 로케일 파일과 화면이 어긋나지 않는지 ---

test('HTML의 data-i18n 키가 두 로케일에 모두 있다', () => {
  assert.ok(htmlKeys.size > 0, 'data-i18n 키를 하나도 못 찾았다 — 정규식이 낡았을 수 있다');
  const missingKo = [...htmlKeys].filter((key) => !(key in ko));
  const missingEn = [...htmlKeys].filter((key) => !(key in en));
  assert.deepEqual(missingKo, [], 'ko에 없는 키');
  assert.deepEqual(missingEn, [], 'en에 없는 키');
});

test("코드가 t('키')로 직접 부르는 키가 두 로케일에 모두 있다", () => {
  assert.ok(directCallKeys.size > 0, "t('키') 호출을 하나도 못 찾았다 — 정규식이 낡았을 수 있다");
  for (const lang of [
    ['ko', ko],
    ['en', en]
  ]) {
    const missing = [...directCallKeys].filter((key) => !(key in lang[1]));
    assert.deepEqual(missing, [], `${lang[0]}에 없는 키`);
  }
});

test('tCount/pluralMessageKey가 쓰는 base마다 _one·_other가 두 로케일에 다 있다', () => {
  assert.ok(pluralCallKeys.size > 0);
  for (const lang of [
    ['ko', ko],
    ['en', en]
  ]) {
    const missing = [];
    for (const base of pluralCallKeys) {
      for (const suffix of ['one', 'other']) {
        if (!(`${base}_${suffix}` in lang[1])) missing.push(`${base}_${suffix}`);
      }
    }
    assert.deepEqual(missing.sort(), [], `${lang[0]}에 없는 키`);
  }
});

test('두 로케일의 키 집합이 정확히 같다', () => {
  assert.deepEqual(Object.keys(ko).sort(), Object.keys(en).sort());
});

test('messages.json에 아무 데서도 안 쓰이는 키가 없다', () => {
  const unused = Object.keys(ko).filter((key) => !isReferenced(key));
  assert.deepEqual(unused, []);
});

test('모든 메시지에 message가 있고, 치환 자리가 두 로케일에서 같다', () => {
  for (const key of Object.keys(ko)) {
    assert.equal(typeof ko[key].message, 'string', `${key}: ko에 message가 없다`);
    assert.equal(typeof en[key].message, 'string', `${key}: en에 message가 없다`);
    assert.deepEqual(
      [...matchAll(ko[key].message, PLACEHOLDER)].sort(),
      [...matchAll(en[key].message, PLACEHOLDER)].sort(),
      `${key}: 치환 자리가 두 로케일에서 다르다`
    );
  }
});

test('단수 키가 있으면 복수 키도 반드시 같이 있다', () => {
  const orphans = Object.keys(ko).filter((key) => {
    const match = /^(.*)_(one|other)$/.exec(key);
    if (!match) return false;
    const pair = `${match[1]}_${match[2] === 'one' ? 'other' : 'one'}`;
    return !(pair in ko);
  });
  assert.deepEqual(orphans, []);
});
