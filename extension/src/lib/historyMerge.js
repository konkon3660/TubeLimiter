// 대시보드가 그리는 "날짜별 기록"을 로컬(chrome.storage)과 서버(daily_usage) 양쪽에서 합치는
// 순수 함수. chrome.* / supabase에 전혀 의존하지 않아 유닛 테스트로 검증된다.
//
// 왜 날짜별 Math.max인가:
//  - 서버 daily_usage는 계정 단위 행이라 보통 이 기기 몫보다 크다. 다른 기기(안드로이드 앱,
//    두 번째 브라우저)가 보낸 델타까지 서버가 누적해 두기 때문이다.
//  - 그렇다고 서버 값을 무조건 채택하면 안 된다. service-worker의 동기화는 30초 스로틀이고
//    오프라인이면 더 길게 벌어지므로, 방금 이 기기에서 늘어난 몫은 아직 서버에 없을 수 있다.
//  - 더할 수도 없다. 서버 합계에는 이 기기가 이미 보고한 몫이 들어 있어서 로컬 값을 더하면
//    같은 시간을 두 번 세게 된다 (lib/usageMerge.js가 오늘치에 대해 "이미 보고한 만큼을 빼고"
//    합치는 이유와 같다. 다만 지난 날짜는 기기별 보고분을 되짚을 근거가 남아 있지 않다).
// 그래서 둘 중 큰 쪽 = "지금까지 알려진 최대"를 택한다. 어느 쪽이 뒤처져 있어도 기록이 뒤로
// 가지 않는다는 게 이 규칙의 핵심이다.

const EMPTY = Object.freeze({});

/** 서버가 bigint를 문자열로 주거나 로컬에 쓰레기 값이 남아 있어도 0 이상 숫자로 정규화한다. */
function toNonNegativeNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : 0;
}

/**
 * 서버 행 배열을 날짜 -> 행 맵으로 바꾼다.
 * daily_usage.date는 각 클라이언트가 lib/time.js의 getTodayDate()(새벽 4시 컷오프)로 만든
 * 'YYYY-MM-DD' 문자열을 그대로 써 넣은 값이라, 로컬 기록의 키와 이미 같은 컨벤션이다.
 * 여기서 UTC 자정 기준 등으로 다시 계산하면 오히려 하루씩 어긋난다.
 */
function indexServerRowsByDate(serverRows) {
  const byDate = {};
  (Array.isArray(serverRows) ? serverRows : []).forEach((row) => {
    if (!row || !row.date) return;
    byDate[String(row.date)] = row;
  });
  return byDate;
}

/**
 * 날짜별 밀리초 맵 하나를 서버의 컬럼 하나와 합친다.
 * localMap: { 'YYYY-MM-DD': ms }, column: 'usage_ms' | 'shorts_ms' | 'emergency_ms'
 */
export function mergeMillisByDate(localMap, serverRows, column) {
  const local = localMap || EMPTY;
  const byDate = indexServerRowsByDate(serverRows);
  const merged = {};
  Object.keys(local).forEach((date) => {
    merged[date] = toNonNegativeNumber(local[date]);
  });
  Object.keys(byDate).forEach((date) => {
    merged[date] = Math.max(merged[date] || 0, toNonNegativeNumber(byDate[date][column]));
  });
  return merged;
}

/**
 * 대시보드가 쓰는 세 가지 기록을 한 번에 합친다.
 *
 * local: { usage: { date: ms }, shorts: { date: ms }, emergency: { date: { uses, ms } } }
 * serverRows: daily_usage에서 select 한 행 배열 ({ date, usage_ms, shorts_ms, emergency_ms })
 *
 * 반환 emergency 항목의 uses는 숫자 또는 null이다. 긴급 시청 "시간"은 daily_usage.emergency_ms로
 * 서버에 합산되지만 "횟수"는 서버로 올라가지 않고 각 기기 로컬에만 남기 때문(documents/BACKEND.md
 * daily_usage 절). 그래서 이 기기가 기록한 날은 로컬 횟수가 정답이고(긴급 기록이 없으면 0회),
 * 서버에만 있는 날은 횟수를 알 길이 없어 null로 둔다 — 호출부는 null이면 횟수를 표시하지 않는다.
 * 0회로 채워 넣으면 "긴급 시청 없이 넘긴 날"이라는 없는 사실을 화면에 적게 된다.
 */
export function mergeHistories(local, serverRows) {
  const localUsage = local?.usage || EMPTY;
  const localShorts = local?.shorts || EMPTY;
  const localEmergency = local?.emergency || EMPTY;
  const byDate = indexServerRowsByDate(serverRows);

  const usage = mergeMillisByDate(localUsage, serverRows, 'usage_ms');
  const shorts = mergeMillisByDate(localShorts, serverRows, 'shorts_ms');

  // 사용량 기록이 있는 날은 긴급 항목도 반드시 만들어 둔다. 그래야 호출부가 "항목이 없는 날"과
  // "횟수를 모르는 날"을 헷갈리지 않는다 (usage가 있는 날 = 이 기기 또는 서버가 아는 날).
  const emergency = {};
  const dates = new Set([
    ...Object.keys(localEmergency),
    ...Object.keys(localUsage),
    ...Object.keys(byDate)
  ]);
  dates.forEach((date) => {
    const localEntry = localEmergency[date];
    const ms = Math.max(
      toNonNegativeNumber(localEntry?.ms),
      toNonNegativeNumber(byDate[date]?.emergency_ms)
    );
    const knownLocally = localEntry !== undefined || localUsage[date] !== undefined;
    emergency[date] = { ms, uses: knownLocally ? toNonNegativeNumber(localEntry?.uses) : null };
  });

  return { usage, shorts, emergency };
}
