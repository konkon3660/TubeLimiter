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
