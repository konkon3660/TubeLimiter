import { test } from 'node:test';
import assert from 'node:assert/strict';
import { focusStateBeforeTransition } from '../src/lib/focusTransition.js';

// chrome.windows.WINDOW_ID_NONE의 실제 값. lib는 chrome.*을 참조하지 않고 이 값을 넘겨받는다.
const NONE = -1;

test('leaving Chrome settles the elapsed interval as focused', () => {
  // 크롬 창 7 -> 크롬 밖: 방금까지 실제로 보고 있던 시간이므로 버리면 안 된다.
  assert.equal(focusStateBeforeTransition(7, NONE, NONE), true);
});

test('returning to Chrome settles the elapsed interval as unfocused', () => {
  // 크롬 밖 -> 크롬 창 7: 자리를 비웠던 시간이므로 시청 시간으로 잡히면 안 된다.
  assert.equal(focusStateBeforeTransition(NONE, 7, NONE), false);
});

test('switching between two Chrome windows stays focused', () => {
  assert.equal(focusStateBeforeTransition(7, 9, NONE), true);
});

test('with an unknown prior window the transition direction decides', () => {
  // 서비스워커가 막 재시작해 직전 창을 모르는 상태.
  assert.equal(focusStateBeforeTransition(null, NONE, NONE), true); // 크롬 밖으로 나갔다면 직전엔 있었고
  assert.equal(focusStateBeforeTransition(null, 7, NONE), false); // 크롬 창으로 들어왔다면 직전엔 없었다
});

test('window id 0 is a real window, not treated as unknown', () => {
  assert.equal(focusStateBeforeTransition(0, NONE, NONE), true);
});
