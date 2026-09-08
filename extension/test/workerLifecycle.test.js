// 서비스워커가 죽었다 깨는 지점과 확장이 업데이트되는 지점의 판정 테스트.
//
// 이 두 자리는 자동 테스트가 하나도 없던 곳이다(documents/QA_REVIEW.md §4.1·§4.2·§5). 실제로
// 깨지는 방식이 "조용히 아무 일도 안 일어남"이라 눈으로는 못 잡는다 - 재주입이 안 되면 그 탭은
// 그냥 보고를 멈추고, 무응답을 재생 중으로 접으면 보지도 않는 시간이 계속 깎인다.

import test from 'node:test';
import assert from 'node:assert/strict';

import {
  isInjectableYoutubeUrl,
  planContentScriptInjection,
  probePlaybackState,
  raceTimeout,
  reinjectContentScripts,
  resolvePlaybackAfterProbe
} from '../src/lib/workerLifecycle.js';

test('주입 대상은 http(s) 유튜브 탭뿐이다', () => {
  assert.equal(isInjectableYoutubeUrl('https://www.youtube.com/watch?v=a'), true);
  assert.equal(isInjectableYoutubeUrl('http://m.youtube.com/'), true);
  assert.equal(isInjectableYoutubeUrl('https://youtube.com'), true);
  // 탭 권한이 없어 url이 비어 오는 탭, 다른 스킴, 유사 도메인은 제외.
  assert.equal(isInjectableYoutubeUrl(undefined), false);
  assert.equal(isInjectableYoutubeUrl(''), false);
  assert.equal(isInjectableYoutubeUrl('view-source:https://www.youtube.com/'), false);
  assert.equal(isInjectableYoutubeUrl('file:///tmp/youtube.com.html'), false);
  assert.equal(isInjectableYoutubeUrl('https://notyoutube.com/watch'), false);
});

test('살아있다고 확인된 탭과 중복 항목은 주입 계획에서 빠진다', () => {
  const tabs = [
    { id: 1, url: 'https://www.youtube.com/watch?v=a' },
    { id: 2, url: 'https://www.youtube.com/shorts/b' },
    { id: 2, url: 'https://www.youtube.com/shorts/b' },
    { id: 3, url: 'https://example.com' },
    { url: 'https://www.youtube.com/' }
  ];
  assert.deepEqual(planContentScriptInjection(tabs, [1]), [2]);
  assert.deepEqual(planContentScriptInjection(tabs), [1, 2]);
  assert.deepEqual(planContentScriptInjection(undefined), []);
});

test('재주입은 답이 없는 탭에만 하고, 실패한 탭은 따로 센다', async () => {
  const injected = [];
  const result = await reinjectContentScripts({
    queryTabs: async () => [
      { id: 1, url: 'https://www.youtube.com/watch?v=a' }, // 살아있음 -> 건너뜀
      { id: 2, url: 'https://www.youtube.com/watch?v=b' }, // 죽음 -> 주입
      { id: 3, url: 'https://www.youtube.com/watch?v=c' } // 죽음 -> 주입 실패
    ],
    isContentScriptAlive: async (tabId) => tabId === 1,
    injectContentScript: async (tabId) => {
      if (tabId === 3) throw new Error('탭이 닫힘');
      injected.push(tabId);
    }
  });

  assert.deepEqual(result.injected, [2]);
  assert.deepEqual(result.skipped, [1]);
  assert.deepEqual(result.failed, [3]);
  assert.deepEqual(injected, [2], '살아있는 탭에는 두 번째 인스턴스를 넣지 않는다');
});

test('탭 조회 자체가 실패하면 아무것도 주입하지 않고 조용히 끝난다', async () => {
  const result = await reinjectContentScripts({
    queryTabs: async () => {
      throw new Error('권한 없음');
    },
    isContentScriptAlive: async () => false,
    injectContentScript: async () => {
      throw new Error('여기까지 오면 안 된다');
    }
  });
  assert.deepEqual(result, { injected: [], skipped: [], failed: [] });
});

test('응답이 없으면 answered=false로 접고, 답이 오면 그 값을 쓴다', async () => {
  const answered = await probePlaybackState(7, {
    sendMessage: async () => ({ playing: true })
  });
  assert.deepEqual(answered, { answered: true, playing: true, reason: 'answered' });

  const paused = await probePlaybackState(7, { sendMessage: async () => ({ playing: false }) });
  assert.deepEqual(paused, { answered: true, playing: false, reason: 'answered' });

  const thrown = await probePlaybackState(7, {
    sendMessage: async () => {
      throw new Error('Receiving end does not exist');
    }
  });
  assert.equal(thrown.answered, false);
  assert.equal(thrown.reason, 'error');

  const garbage = await probePlaybackState(7, { sendMessage: async () => ({}) });
  assert.equal(garbage.answered, false);
  assert.equal(garbage.reason, 'no-answer');
});

test('답을 영영 안 주는 탭은 시한 안에 무응답으로 접는다', async () => {
  const probe = await probePlaybackState(7, {
    sendMessage: () => new Promise(() => {}),
    timeoutMs: 20
  });
  assert.deepEqual(probe, { answered: false, playing: false, reason: 'timeout' });
});

test('무응답은 "재생 아님"이다', () => {
  // 이 방향이어야 스스로 낫는다. 반대로 접으면(무응답=재생 중) 아무도 정정해주지 않아
  // 보지도 않는 시간이 계속 깎인다.
  assert.equal(resolvePlaybackAfterProbe({ answered: false, playing: false }), false);
  assert.equal(resolvePlaybackAfterProbe({ answered: false, playing: true }), false);
  assert.equal(resolvePlaybackAfterProbe({ answered: true, playing: true }), true);
  assert.equal(resolvePlaybackAfterProbe({ answered: true, playing: false }), false);
  assert.equal(resolvePlaybackAfterProbe(null), false);
});

test('raceTimeout은 먼저 끝나는 쪽을 돌려준다', async () => {
  assert.equal(await raceTimeout(Promise.resolve('ok'), 50, 'late'), 'ok');
  assert.equal(await raceTimeout(new Promise(() => {}), 10, 'late'), 'late');
});
