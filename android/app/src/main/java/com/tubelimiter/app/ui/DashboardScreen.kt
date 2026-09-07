package com.tubelimiter.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.tubelimiter.app.limit.computeLimitMillis
import com.tubelimiter.app.limit.isUnlimited
import com.tubelimiter.app.usage.formatDuration
import com.tubelimiter.app.usage.lastNDates
import java.time.LocalDate

private const val HEATMAP_DAYS = 28

@Composable
fun DashboardScreen(
    today: LocalDate,
    usageHistory: Map<String, Long>,
    usageHistoryHourly: Map<String, Map<Int, Long>>,
    /** Per-day emergency-pass time and use count, for telling perfect days from merely successful ones. */
    emergencyMillisHistory: Map<String, Long>,
    emergencyUseHistory: Map<String, Long>,
    limitConfig: LimitConfig,
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
                    text = "연속 기록과 XP는 하드코어 모드가 켜져 있을 때만 쌓입니다.",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        HeatmapCard(today, usageHistory, emergencyMillisHistory, emergencyUseHistory, limitConfig)
        ChartCard(today, usageHistory, limitConfig, chartRangeDays, onChartRangeChange)
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
            Text("${tier.emoji} ${tier.title} · Lv.${progress.level}", style = MaterialTheme.typography.titleMedium)
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
                text = "${progress.xpIntoLevel} / ${progress.xpForNextLevel} XP",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Stat("연속", "${streak.currentStreak}일")
                Stat("최고", "${streak.bestStreak}일")
                Stat("성공", "${streak.totalSuccessDays}일")
                Stat("완벽", "${streak.perfectDays}일")
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text("뱃지", style = MaterialTheme.typography.titleSmall)
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
                            text = "${days}일",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // 긴급 시청을 한 번도 안 쓴 날만 세는 별도 뱃지 줄.
            Text("완벽한 날 (긴급 시청 0회)", style = MaterialTheme.typography.titleSmall)
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
                            text = "${days}일",
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
    emergencyMillisHistory: Map<String, Long>,
    emergencyUseHistory: Map<String, Long>,
    limitConfig: LimitConfig,
) {
    // Same three grades the extension dashboard uses: perfect (deep), success (light), over.
    val perfectColor = Color(0xFF16A34A)
    val successColor = Color(0xFF86EFAC)
    val failColor = Color(0xFFEF4444)
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("최근 28일", style = MaterialTheme.typography.titleSmall)
            lastNDates(today, HEATMAP_DAYS).chunked(7).forEach { week ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    week.forEach { date ->
                        val key = date.toString()
                        val used = usageHistory[key]
                        val limit = computeLimitMillis(limitConfig, date)
                        val emergencyMillis = emergencyMillisHistory[key] ?: 0L
                        val emergencyUses = (emergencyUseHistory[key] ?: 0L).toInt()
                        val color = when {
                            used == null -> emptyColor
                            isPerfectDay(used, limit, emergencyMillis, emergencyUses) -> perfectColor
                            isDaySuccess(used, limit, emergencyMillis) -> successColor
                            else -> failColor
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(4.dp))
                                .background(color),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartCard(
    today: LocalDate,
    usageHistory: Map<String, Long>,
    limitConfig: LimitConfig,
    rangeDays: Int,
    onRangeChange: (Int) -> Unit,
) {
    var showPercent by remember { mutableStateOf(false) }

    val dates = lastNDates(today, rangeDays)
    val barColor = MaterialTheme.colorScheme.primary
    val overLimitColor = Color(0xFFEF4444)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("사용시간 추이", style = MaterialTheme.typography.titleSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CHART_RANGE_PRESETS_DAYS.forEach { days ->
                    FilterChip(
                        selected = rangeDays == days,
                        onClick = { onRangeChange(days) },
                        label = { Text("${days}일") },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !showPercent,
                    onClick = { showPercent = false },
                    label = { Text("사용 시간") },
                )
                FilterChip(
                    selected = showPercent,
                    onClick = { showPercent = true },
                    label = { Text("한도 대비 %") },
                )
            }

            if (showPercent) {
                // Days with no limit (isUnlimited) can't sensibly be expressed as a percentage,
                // so they render as an empty bar rather than dividing by a near-infinite limit.
                val percentValues = dates.map { date ->
                    val used = usageHistory[date.toString()] ?: 0L
                    val limit = computeLimitMillis(limitConfig, date)
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
                        text = "최대 ${peak.toInt()}%",
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
                        text = "최대 ${formatDuration(peak)}",
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("시간대별 사용 패턴", style = MaterialTheme.typography.titleSmall)
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
                    text = "0시",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "최대 ${formatDuration(peak)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "23시",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}
