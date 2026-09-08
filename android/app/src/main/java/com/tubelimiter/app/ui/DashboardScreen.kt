package com.tubelimiter.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tubelimiter.app.R
import com.tubelimiter.app.data.CHART_RANGE_PRESETS_DAYS
import com.tubelimiter.app.gamification.PERFECT_MILESTONES
import com.tubelimiter.app.gamification.STREAK_MILESTONES
import com.tubelimiter.app.gamification.StreakRecord
import com.tubelimiter.app.gamification.isDaySuccess
import com.tubelimiter.app.gamification.isPerfectDay
import com.tubelimiter.app.gamification.levelProgress
import com.tubelimiter.app.gamification.levelTier
import com.tubelimiter.app.gamification.milestoneAchievementKey
import com.tubelimiter.app.gamification.perfectAchievementKey
import com.tubelimiter.app.limit.LimitConfig
import com.tubelimiter.app.limit.isUnlimited
import com.tubelimiter.app.limit.resolveLimitForDate
import com.tubelimiter.app.sync.MergedEmergencyDay
import com.tubelimiter.app.usage.formatDuration
import com.tubelimiter.app.usage.lastNDates
import java.time.LocalDate

private const val HEATMAP_DAYS = 28

/**
 * 히트맵/차트가 어떤 기록으로 그려졌는지. 서버 조회가 조용히 실패해도 화면은 그대로 그려지므로
 * ([com.tubelimiter.app.sync.SyncRepository.fetchDailyUsageSince]), 이 한 줄이 없으면 "다른
 * 기기 기록이 빠진 그래프"를 전부인 것처럼 보게 된다.
 */
enum class HistorySource { MERGED, LOCAL_ONLY, SIGNED_OUT }

@Composable
fun DashboardScreen(
    today: LocalDate,
    /** 로컬 기록과 서버 `daily_usage`를 날짜별 max로 합친 값 ([com.tubelimiter.app.sync.mergeHistories]). */
    usageHistory: Map<String, Long>,
    usageHistoryHourly: Map<String, Map<Int, Long>>,
    /** Per-day emergency-pass time and use count, for telling perfect days from merely successful ones. */
    emergencyHistory: Map<String, MergedEmergencyDay>,
    /** 그날 실제 적용됐던 한도의 스냅샷. 없는 날은 [limitConfig]로 추정하고 화면에 그렇게 표시한다. */
    limitHistory: Map<String, Long>,
    limitConfig: LimitConfig,
    historySource: HistorySource,
    streak: StreakRecord,
    achievements: Set<String>,
    hardcoreMode: Boolean,
    chartRangeDays: Int,
    onChartRangeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (hardcoreMode) {
            StreakCard(streak)
            BadgeCard(achievements)
        } else {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.dashboard_hardcore_hint),
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        HeatmapCard(today, usageHistory, emergencyHistory, limitHistory, limitConfig, historySource)
        ChartCard(today, usageHistory, limitHistory, limitConfig, chartRangeDays, onChartRangeChange)
        HourlyPatternCard(today, usageHistoryHourly, chartRangeDays)
    }
}

@Composable
private fun StreakCard(streak: StreakRecord) {
    val progress = levelProgress(streak.xp)
    val tier = levelTier(progress.level)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.dashboard_level_line,
                    tier.emoji,
                    stringResource(tier.titleRes),
                    progress.level,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            LinearProgressIndicator(
                progress = {
                    if (progress.xpForNextLevel == 0) {
                        0f
                    } else {
                        (progress.xpIntoLevel.toFloat() / progress.xpForNextLevel).coerceIn(0f, 1f)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(
                    R.string.dashboard_xp_progress,
                    progress.xpIntoLevel,
                    progress.xpForNextLevel,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Four to a row, and "365 days" is a good deal wider than "365일" — an even
                // share each, so a long value wraps inside its column instead of shoving the
                // last stat off the card.
                Stat(
                    stringResource(R.string.dashboard_stat_streak),
                    dayCount(streak.currentStreak),
                    Modifier.weight(1f),
                )
                Stat(
                    stringResource(R.string.dashboard_stat_best),
                    dayCount(streak.bestStreak),
                    Modifier.weight(1f),
                )
                Stat(
                    stringResource(R.string.dashboard_stat_success),
                    dayCount(streak.totalSuccessDays),
                    Modifier.weight(1f),
                )
                Stat(
                    stringResource(R.string.dashboard_stat_perfect),
                    dayCount(streak.perfectDays),
                    Modifier.weight(1f),
                )
            }
        }
    }
}

/** "N일" / "N days" — a plural, since English splits at one and Korean doesn't. */
@Composable
private fun dayCount(days: Int): String = pluralStringResource(R.plurals.count_days, days, days)

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun BadgeCard(achievements: Set<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.dashboard_badges), style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                STREAK_MILESTONES.forEach { days ->
                    val unlocked = milestoneAchievementKey(days) in achievements
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (unlocked) "🏅" else "🔒",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            // 일곱 개가 한 줄에 들어가야 하므로 짧은 쪽을 쓴다.
                            text = stringResource(R.string.count_days_compact, days),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 긴급 시청을 한 번도 안 쓴 날만 세는 별도 뱃지 줄.
            Text(stringResource(R.string.dashboard_perfect_badges), style = MaterialTheme.typography.titleSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PERFECT_MILESTONES.forEach { days ->
                    val unlocked = perfectAchievementKey(days) in achievements
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (unlocked) "💎" else "🔒",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.count_days_compact, days),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCard(
    today: LocalDate,
    usageHistory: Map<String, Long>,
    emergencyHistory: Map<String, MergedEmergencyDay>,
    limitHistory: Map<String, Long>,
    limitConfig: LimitConfig,
    historySource: HistorySource,
) {
    // Same three grades the extension dashboard uses: perfect (deep), success (light), over.
    val perfectColor = Color(0xFF16A34A)
    val successColor = Color(0xFF86EFAC)
    val failColor = Color(0xFFEF4444)
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    val estimatedOutline = MaterialTheme.colorScheme.onSurfaceVariant

    val dates = lastNDates(today, HEATMAP_DAYS)
    // 한도 스냅샷이 없어 현재 설정으로 근사 판정한 칸이 하나라도 있으면 범례를 붙인다. 확장은
    // 툴팁 한 줄로 밝히지만 안드로이드 히트맵 칸에는 붙일 툴팁이 없어서, 테두리 + 아래 한 줄로
    // 같은 사실을 전한다 — 근사치를 사실처럼 보여주면 "설정을 바꾸면 과거 판정이 바뀐다"는
    // 문제를 그대로 두면서 티만 안 나게 하는 셈이 된다.
    val hasEstimatedDay = dates.any { date ->
        usageHistory[date.toString()] != null &&
            resolveLimitForDate(limitHistory, limitConfig, date).estimated
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.dashboard_heatmap_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(
                    when (historySource) {
                        HistorySource.MERGED -> R.string.dashboard_history_note_merged
                        HistorySource.LOCAL_ONLY -> R.string.dashboard_history_note_local_only
                        HistorySource.SIGNED_OUT -> R.string.dashboard_history_note_signed_out
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            dates.chunked(7).forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    week.forEach { date ->
                        val key = date.toString()
                        val used = usageHistory[key]
                        // 그날 실제로 적용됐던 한도로 판정한다. 판정 규칙 자체
                        // (isPerfectDay/isDaySuccess)는 스트릭과 같은 것을 그대로 쓰고, 바뀐 건
                        // 넘기는 한도값뿐이다.
                        val resolved = resolveLimitForDate(limitHistory, limitConfig, date)
                        val emergency = emergencyHistory[key]
                        val emergencyMillis = emergency?.millis ?: 0L
                        val emergencyUses = emergency?.uses ?: 0
                        val color = when {
                            used == null -> emptyColor
                            isPerfectDay(used, resolved.limitMillis, emergencyMillis, emergencyUses) -> perfectColor
                            isDaySuccess(used, resolved.limitMillis, emergencyMillis) -> successColor
                            else -> failColor
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(color)
                                .then(
                                    if (used != null && resolved.estimated) {
                                        Modifier.border(1.dp, estimatedOutline, RoundedCornerShape(4.dp))
                                    } else {
                                        Modifier
                                    },
                                ),
                        )
                    }
                }
            }
            if (hasEstimatedDay) {
                Text(
                    text = stringResource(R.string.dashboard_heatmap_estimated_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChartCard(
    today: LocalDate,
    usageHistory: Map<String, Long>,
    limitHistory: Map<String, Long>,
    limitConfig: LimitConfig,
    rangeDays: Int,
    onRangeChange: (Int) -> Unit,
) {
    var showPercent by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val dates = lastNDates(today, rangeDays)
    val barColor = MaterialTheme.colorScheme.primary
    val overLimitColor = Color(0xFFEF4444)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.dashboard_chart_title), style = MaterialTheme.typography.titleSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CHART_RANGE_PRESETS_DAYS.forEach { days ->
                    FilterChip(
                        selected = rangeDays == days,
                        onClick = { onRangeChange(days) },
                        label = { Text(stringResource(R.string.count_days_compact, days)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showPercent,
                    onClick = { showPercent = false },
                    label = { Text(stringResource(R.string.dashboard_chart_mode_time)) },
                )
                FilterChip(
                    selected = showPercent,
                    onClick = { showPercent = true },
                    label = { Text(stringResource(R.string.dashboard_chart_mode_percent)) },
                )
            }

            if (showPercent) {
                // Days with no limit (isUnlimited) can't sensibly be expressed as a percentage,
                // so they render as an empty bar rather than dividing by a near-infinite limit.
                val percentValues = dates.map { date ->
                    val used = usageHistory[date.toString()] ?: 0L
                    // 히트맵과 같은 값으로 나눈다. 여기만 현재 설정을 쓰면 같은 날이 히트맵에선
                    // 초과, 막대에선 한도 이내로 보인다.
                    val limit = resolveLimitForDate(limitHistory, limitConfig, date).limitMillis
                    if (isUnlimited(limit) || limit <= 0L) 0f else (used.toFloat() / limit) * 100f
                }
                // A day can blow past 100%; that's meaningful, so the scale grows to fit it
                // instead of clamping every bar to a shared 100% ceiling.
                val peak = percentValues.maxOrNull()?.coerceAtLeast(1f) ?: 1f
                val displayMax = maxOf(peak, 100f)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    percentValues.forEach { value ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(fraction = (value / displayMax).coerceIn(0.02f, 1f))
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(if (value > 100f) overLimitColor else barColor),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = dates.first().toString().substring(5),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.dashboard_chart_peak_percent, peak.toInt()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            } else {
                val values = dates.map { usageHistory[it.toString()] ?: 0L }
                val peak = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    values.forEach { value ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(fraction = (value.toFloat() / peak).coerceIn(0.02f, 1f))
                                .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                                .background(barColor),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = dates.first().toString().substring(5),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(
                            R.string.dashboard_chart_peak_duration,
                            formatDuration(context, peak),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

/**
 * 24 hourly bars aggregated (summed) over the currently selected [rangeDays] window. Sum rather
 * than average: this feature ships with no back-fill, so older days simply have no entry in
 * [usageHistoryHourly] and averaging by rangeDays would silently dilute the pattern for anyone
 * without a full window of hourly history yet. A sum also reads naturally as "total time spent
 * at this hour across the selected days," which is the more direct answer to "when do I watch."
 *
 * 히트맵/차트와 달리 **서버 기록과 합치지 않는다** — `daily_usage`에 시간대별 대응 데이터가
 * 없어서 합칠 것이 없다(확장도 같다). 그래서 카드에 "이 기기 기록만"이라고 밝힌다. 밝히지
 * 않으면 바로 위 두 카드가 계정 전체를 보여주는 상황에서 이 카드만 조용히 다른 범위가 된다.
 */
@Composable
private fun HourlyPatternCard(
    today: LocalDate,
    usageHistoryHourly: Map<String, Map<Int, Long>>,
    rangeDays: Int,
) {
    val dates = lastNDates(today, rangeDays)
    val hourTotals = LongArray(24)
    dates.forEach { date ->
        val dayHours = usageHistoryHourly[date.toString()] ?: emptyMap()
        dayHours.forEach { (hour, millis) ->
            if (hour in 0..23) hourTotals[hour] += millis
        }
    }
    val peak = hourTotals.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val barColor = MaterialTheme.colorScheme.secondary
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.dashboard_hourly_title), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.dashboard_hourly_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                hourTotals.forEach { value ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(fraction = (value.toFloat() / peak).coerceIn(0.02f, 1f))
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(barColor),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.dashboard_hour_start),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(
                        R.string.dashboard_chart_peak_duration,
                        formatDuration(context, peak),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.dashboard_hour_end),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
