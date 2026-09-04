// 브라우저 창 포커스 전환(chrome.windows.onFocusChanged)이 "이미 끝난 뒤"에 도착하는 이벤트를
// 정산하기 위한 순수 판정 로직. 이 이벤트 시점에 포커스를 새로 조회하면 방향이 정반대로
// 뒤집힌다 - 크롬을 떠나면 방금까지 실제로 본 시간이 통째로 버려지고, 크롬으로 돌아오면
// 자리를 비웠던 시간이 시청 시간으로 잡힌다(최대 1분씩). 그래서 직전 구간은 "전환 전"
// 포커스 값으로 정리한 뒤에 새 상태를 적용해야 한다.
//
// chrome.* 를 직접 참조하지 않는다 - WINDOW_ID_NONE 센티널은 호출부에서 넘겨받아
// 순수 함수로 테스트할 수 있게 한다.

/**
 * 전환 직전 구간이 "크롬에 포커스가 있던" 구간이었는지 판정한다.
 *
 * @param {number|null} lastFocusedWindowId 직전에 포커스를 가졌던 창 id.
 *   null이면 모르는 상태(서비스워커가 막 재시작한 경우).
 * @param {number} newWindowId 이번 전환으로 포커스를 가지게 된 창 id (없으면 noneWindowId).
 * @param {number} noneWindowId chrome.windows.WINDOW_ID_NONE 센티널 값.
 * @returns {boolean} 직전 구간에 크롬이 포커스를 가지고 있었으면 true.
 */
export function focusStateBeforeTransition(lastFocusedWindowId, newWindowId, noneWindowId) {
  // 직전 창을 모를 땐 전환 방향으로 추론한다: 포커스가 크롬 밖으로 나갔다면 직전엔 있었고,
  // 크롬 창으로 들어왔다면 직전엔 없었다(크롬 창끼리의 전환은 기억한 값으로 잡힌다).
  if (lastFocusedWindowId === null) return newWindowId === noneWindowId;

  return lastFocusedWindowId !== noneWindowId;
}
