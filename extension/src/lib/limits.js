export function computeLimitForDate(settings, dateStr) {
  if (!settings) return 30 * 60 * 1000;
  if (settings.daily_limit_reset_frequency === 'by_day') {
    const dayOfWeek = new Date(dateStr + 'T00:00:00').getDay();
    const limit = settings.daily_limit_by_day?.[dayOfWeek];
    if (limit === undefined || limit === -1) return Infinity;
    return limit * 60 * 1000;
  }
  const ms = settings.daily_limit_ms;
  if (ms === undefined || ms === null || ms === 0) return Infinity;
  return ms;
}

/**
 * Shorts 전용 일일 한도. 전체 한도(computeLimitForDate)와 완전히 독립이라, 전체 한도가 넉넉해도
 * Shorts만 따로 막을 수 있다 (서버 컬럼: settings.shorts_limit_ms — 브라우저 전용).
 *
 * 0 · null · 미설정은 전부 "한도 없음"(Infinity)이다. daily_limit_ms의 0 컨벤션과 같게 맞춰서
 * 스키마 기본값(0)으로 새로 붙은 컬럼이 기존 사용자를 갑자기 차단하지 않게 한다.
 * 음수는 저장될 일이 없지만(옵션 입력 min=0) 들어와도 "한도 없음"으로 접는다 — 음수 한도는
 * "즉시 항상 차단"이 되어버려 설정 실수 하나로 Shorts가 영구히 막힌다.
 *
 * 날짜를 받지 않는 이유: 요일별 오버라이드가 없다. 요일별로 다르게 하고 싶어지면 그때
 * daily_limit_by_day와 같은 모양의 컬럼을 따로 추가해야 한다.
 */
export function computeShortsLimit(settings) {
  const ms = settings?.shorts_limit_ms;
  if (ms === undefined || ms === null || ms <= 0) return Infinity;
  return ms;
}
