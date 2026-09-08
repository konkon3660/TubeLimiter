package com.tubelimiter.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tubelimiter.app.R
import com.tubelimiter.app.diagnostics.MonitorWarning
import com.tubelimiter.app.diagnostics.SyncWarning
import com.tubelimiter.app.gamification.StreakRecord
import com.tubelimiter.app.gamification.levelProgress
import com.tubelimiter.app.gamification.levelTier
import com.tubelimiter.app.limit.BlockReason
import com.tubelimiter.app.limit.ScheduleWindow
import com.tubelimiter.app.limit.isUnlimited
import com.tubelimiter.app.limit.messageRes
import com.tubelimiter.app.limit.resolveFocusStopTime
import com.tubelimiter.app.usage.formatCountdown
import com.tubelimiter.app.usage.formatDuration

@Composable
fun HomeScreen(
    usedMillis: Long,
    limitMillis: Long,
    blockReason: BlockReason?,
    focusEndMillis: Long?,
    focusDelayEndMillis: Long?,
    focusDelayDurationMinutes: Int,
    focusStopRequestedAtMillis: Long?,
    manuallyBlocked: Boolean,
    emergencyRemaining: Int,
    monitoringEnabled: Boolean,
    hardcoreMode: Boolean,
    streak: StreakRecord,
    /** Currently-active scheduled-block window, if any (read-only here - only the settings
     * screen can add/edit/remove windows, by design, so this stays a real commitment device). */
    scheduleWindow: ScheduleWindow?,
    /** 동기화가 오래 실패했을 때 띄울 경고, 아니면 null
     * ([com.tubelimiter.app.diagnostics.staleSyncWarning]가 판정하고, 문구는 여기서 붙인다). */
    syncWarning: SyncWarning?,
    /** 감시가 멈췄거나 권한이 빠졌을 때 띄울 경고, 아니면 null
     * ([com.tubelimiter.app.diagnostics.monitorWarning]가 판정한다). */
    monitorWarning: MonitorWarning?,
    /** 경고 줄에서 온보딩(권한) 화면으로 돌려보내는 복구 경로. */
    onFixPermissions: () -> Unit,
    nowMillis: Long,
    onStartFocus: (delayMinutes: Int, durationMinutes: Int) -> Unit,
    onStopFocus: () -> Unit,
    onCancelFocusStop: () -> Unit,
    onManualBlockChange: (Boolean) -> Unit,
    onMonitoringChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = focusEndMillis != null && nowMillis < focusEndMillis
    val scheduled = !active && focusDelayEndMillis != null && nowMillis < focusDelayEndMillis
    val stopPending = active && focusStopRequestedAtMillis != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 감시 경고가 동기화 경고보다 위다 — "기록이 안 올라간다"보다 "지금 안 막힌다"가 급하다.
        if (monitorWarning != null) MonitorWarningBanner(monitorWarning, onFixPermissions)
        if (syncWarning != null) SyncWarningBanner(syncWarning)
        if (hardcoreMode) StreakHeroCard(streak)
        UsageRingCard(usedMillis, limitMillis, emergencyRemaining)
        ModeBadgesCard(
            monitoringEnabled = monitoringEnabled,
            blockReason = blockReason,
            active = active,
            scheduled = scheduled,
            stopPending = stopPending,
            focusEndMillis = focusEndMillis,
            focusDelayEndMillis = focusDelayEndMillis,
            focusStopRequestedAtMillis = focusStopRequestedAtMillis,
            scheduleWindow = scheduleWindow,
            nowMillis = nowMillis,
        )
        FocusCard(
            active = active,
            scheduled = scheduled,
            stopPending = stopPending,
            focusEndMillis = focusEndMillis,
            focusDelayEndMillis = focusDelayEndMillis,
            focusDelayDurationMinutes = focusDelayDurationMinutes,
            focusStopRequestedAtMillis = focusStopRequestedAtMillis,
            nowMillis = nowMillis,
            onStartFocus = onStartFocus,
            onStopFocus = onStopFocus,
            onCancelFocusStop = onCancelFocusStop,
        )
        ToggleCard(
            title = stringResource(R.string.home_manual_block_title),
            subtitle = stringResource(
                if (manuallyBlocked) R.string.home_manual_block_on else R.string.home_badge_off,
            ),
            checked = manuallyBlocked,
            onChange = onManualBlockChange,
        )
        ToggleCard(
            title = stringResource(R.string.home_monitoring_title),
            subtitle = stringResource(
                if (monitoringEnabled) R.string.home_monitoring_on else R.string.home_monitoring_off,
            ),
            checked = monitoringEnabled,
            onChange = onMonitoringChange,
        )
    }
}

/**
 * 동기화가 오래 실패했을 때만 뜨는 한 줄.
 *
 * 카드가 아니라 얇은 띠 하나인 건 의도적이다 — 차단이나 스트릭과 달리 이건 사용자가 지금
 * 당장 뭘 해야 하는 일이 아니고, 그냥 오프라인이었을 수도 있다. [StatusBadge]의 경고 톤과
 * 같은 색을 써서 이 화면 안에서 새로운 시각 언어를 만들지 않는다.
 */
@Composable
private fun SyncWarningBanner(warning: SyncWarning) {
    val message = when (warning) {
        SyncWarning.NeverSucceeded -> stringResource(R.string.home_sync_warning_never)
        is SyncWarning.StaleFor -> pluralStringResource(
            R.plurals.home_sync_warning_stale,
            warning.hours.toInt(),
            warning.hours,
        )
    }
    WarningBanner(message)
}

/**
 * 감시가 실제로는 안 돌고 있을 때 뜨는 한 줄 (documents/QA_REVIEW.md §1.7).
 *
 * 동기화 경고와 같은 급의 얇은 띠지만 **행동 버튼이 하나 붙는다** — 동기화 지연은 사용자가 할
 * 수 있는 게 없는 반면, 권한 회수는 설정 한 번으로 되돌릴 수 있고 그 사이 유튜브는 전혀 막히지
 * 않기 때문이다. 버튼은 새 화면을 만들지 않고 온보딩 흐름을 그대로 다시 쓴다.
 */
@Composable
private fun MonitorWarningBanner(warning: MonitorWarning, onFixPermissions: () -> Unit) {
    when (warning) {
        is MonitorWarning.PermissionsRevoked -> WarningBanner(
            message = stringResource(R.string.home_monitor_warning_permissions),
            actionLabel = stringResource(R.string.home_monitor_warning_action),
            onAction = onFixPermissions,
        )

        is MonitorWarning.StaleFor -> WarningBanner(
            message = pluralStringResource(
                R.plurals.home_monitor_warning_stale,
                warning.hours.toInt(),
                warning.hours,
            ),
        )
    }
}

/** 두 경고가 같은 시각 언어를 쓰도록 띠 자체는 한 곳에만 둔다. */
@Composable
private fun WarningBanner(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFF59E0B).copy(alpha = 0.15f))
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⚠️", style = MaterialTheme.typography.labelSmall)
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFB45309),
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFB45309),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Compact streak/XP summary — the app's take on the popup's `.streak-hero` + `.xp-bar-track`. */
@Composable
private fun StreakHeroCard(streak: StreakRecord) {
    val progress = levelProgress(streak.xp)
    val tier = levelTier(progress.level)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "${streak.currentStreak}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                // 큰 숫자는 위에 따로 서 있고, 이 줄은 그 뒤를 잇는 꼬리다. 영어는 하루/여러 날에서
                // 갈리므로 plurals의 quantity는 현재 연속 일수, 인자는 최고 기록.
                text = pluralStringResource(
                    R.plurals.home_streak_subtitle,
                    streak.currentStreak,
                    streak.bestStreak,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = {
                    if (progress.xpForNextLevel == 0) {
                        0f
                    } else {
                        (progress.xpIntoLevel.toFloat() / progress.xpForNextLevel).coerceIn(0f, 1f)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Text(
                text = stringResource(
                    R.string.home_level_line,
                    tier.emoji,
                    progress.level,
                    progress.xpIntoLevel,
                    progress.xpForNextLevel,
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Ring + quick stats — the app's take on the popup's `.progress-circle-small` + `.quick-stats-grid`. */
@Composable
private fun UsageRingCard(usedMillis: Long, limitMillis: Long, emergencyRemaining: Int) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val unlimited = isUnlimited(limitMillis)
            val remaining = (limitMillis - usedMillis).coerceAtLeast(0L)
            val fraction = if (unlimited || limitMillis <= 0L) 0f else (usedMillis.toFloat() / limitMillis).coerceIn(0f, 1f)

            UsageRing(
                fraction = fraction,
                centerValue = if (unlimited) {
                    stringResource(R.string.unlimited)
                } else {
                    formatCountdown(remaining)
                },
                centerLabel = stringResource(R.string.home_ring_label),
            )

            StatRow(
                label = stringResource(R.string.home_stat_used_today),
                value = formatDuration(context, usedMillis),
            )
            StatRow(
                label = stringResource(R.string.home_stat_emergency_left),
                value = stringResource(R.string.count_times, emergencyRemaining),
            )
        }
    }
}

@Composable
private fun UsageRing(fraction: Float, centerValue: String, centerLabel: String) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val ringColor = MaterialTheme.colorScheme.primary
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(120.dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 10.dp.toPx())
            val inset = stroke.width / 2
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke.width, size.height - stroke.width),
            )
            drawArc(
                color = ringColor,
                startAngle = -90f,
                sweepAngle = 360f * fraction,
                useCenter = false,
                style = stroke,
                topLeft = Offset(inset, inset),
                size = Size(size.width - stroke.width, size.height - stroke.width),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerValue,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 16.sp,
            )
            Text(
                text = centerLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** The app's take on the popup's status badges (`지금 소모 중` / `모드`). */
@Composable
private fun ModeBadgesCard(
    monitoringEnabled: Boolean,
    blockReason: BlockReason?,
    active: Boolean,
    scheduled: Boolean,
    stopPending: Boolean,
    focusEndMillis: Long?,
    focusDelayEndMillis: Long?,
    focusStopRequestedAtMillis: Long?,
    scheduleWindow: ScheduleWindow?,
    nowMillis: Long,
) {
    val context = LocalContext.current
    val modeLabel = when {
        // Focus mode keeps message priority over a scheduled block when both happen to be true
        // (same tier by design - see BlockDecision.kt's blockReason()).
        stopPending -> {
            val stopAt = resolveFocusStopTime(focusEndMillis, focusStopRequestedAtMillis) ?: nowMillis
            stringResource(
                R.string.home_mode_focus_stop_pending,
                formatDuration(context, (stopAt - nowMillis).coerceAtLeast(0L)),
            )
        }

        active -> stringResource(
            R.string.home_mode_focus_active,
            formatDuration(context, (focusEndMillis!! - nowMillis).coerceAtLeast(0L)),
        )

        scheduled -> stringResource(
            R.string.home_mode_focus_scheduled,
            formatDuration(context, (focusDelayEndMillis!! - nowMillis).coerceAtLeast(0L)),
        )

        scheduleWindow != null -> stringResource(
            R.string.home_mode_schedule_window,
            scheduleWindow.label,
            formatMinuteOfDay(scheduleWindow.endMinute),
        )

        blockReason != null -> stringResource(blockReason.messageRes())
        else -> stringResource(R.string.home_mode_inactive)
    }
    val modeTone = when {
        stopPending -> BadgeTone.WARNING
        active -> BadgeTone.ACTIVE
        scheduled -> BadgeTone.WARNING
        scheduleWindow != null -> BadgeTone.ACTIVE
        blockReason != null -> BadgeTone.WARNING
        else -> BadgeTone.INACTIVE
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BadgeRow(
                label = stringResource(R.string.home_badge_monitoring),
                value = stringResource(
                    if (monitoringEnabled) R.string.home_badge_monitoring_on else R.string.home_badge_off,
                ),
                tone = if (monitoringEnabled) BadgeTone.ACTIVE else BadgeTone.INACTIVE,
            )
            BadgeRow(stringResource(R.string.home_badge_mode), modeLabel, modeTone)
        }
    }
}

private enum class BadgeTone { ACTIVE, INACTIVE, WARNING }

/** Wall-clock "HH:mm" for a minute-of-day value, matching the extension popup's own formatter. */
private fun formatMinuteOfDay(minutes: Int): String {
    val normalized = ((minutes % 1440) + 1440) % 1440
    val hour = normalized / 60
    val minute = normalized % 60
    return "%02d:%02d".format(hour, minute)
}

@Composable
private fun BadgeRow(label: String, value: String, tone: BadgeTone) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
        // The mode badge carries a whole sentence ("Focus mode queued · starts in 25m"), which
        // runs longer in some languages than in others; bound it to what's left of the row so it
        // wraps rather than running off the card. fill = false keeps short badges hugging.
        StatusBadge(value, tone, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun StatusBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        BadgeTone.ACTIVE -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f) to MaterialTheme.colorScheme.secondary
        BadgeTone.WARNING -> Color(0xFFF59E0B).copy(alpha = 0.15f) to Color(0xFFB45309)
        BadgeTone.INACTIVE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** Mirrors the popup's 집중 모드 control: a delay + duration pair in minutes, not just presets. */
@Composable
private fun FocusCard(
    active: Boolean,
    scheduled: Boolean,
    stopPending: Boolean,
    focusEndMillis: Long?,
    focusDelayEndMillis: Long?,
    focusDelayDurationMinutes: Int,
    focusStopRequestedAtMillis: Long?,
    nowMillis: Long,
    onStartFocus: (delayMinutes: Int, durationMinutes: Int) -> Unit,
    onStopFocus: () -> Unit,
    onCancelFocusStop: () -> Unit,
) {
    var delayText by rememberSaveable { mutableStateOf("0") }
    var durationText by rememberSaveable { mutableStateOf("30") }
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.home_focus_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            when {
                // Checked before the plain `active` branch since a pending stop is also active.
                // Mirrors the extension popup's "종료 대기 중" state: the request already fired
                // and only a cancel is offered, not another stop (re-clicking must not push the
                // cooldown back out further).
                stopPending -> {
                    val stopAt = resolveFocusStopTime(focusEndMillis, focusStopRequestedAtMillis) ?: nowMillis
                    Text(
                        text = stringResource(
                            R.string.home_focus_stops_in,
                            formatDuration(context, (stopAt - nowMillis).coerceAtLeast(0L)),
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.home_focus_stop_pending_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onCancelFocusStop) {
                        Text(stringResource(R.string.home_focus_cancel_stop))
                    }
                }

                active -> {
                    Text(
                        text = stringResource(
                            R.string.home_focus_remaining,
                            formatDuration(context, (focusEndMillis!! - nowMillis).coerceAtLeast(0L)),
                        ),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(
                        onClick = onStopFocus,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.home_focus_stop)) }
                }

                scheduled -> {
                    Text(
                        stringResource(
                            R.string.home_focus_scheduled_note,
                            formatDuration(context, (focusDelayEndMillis!! - nowMillis).coerceAtLeast(0L)),
                            focusDelayDurationMinutes,
                        ),
                    )
                    OutlinedButton(onClick = onStopFocus) {
                        Text(stringResource(R.string.home_focus_cancel_schedule))
                    }
                }

                else -> {
                    Text(
                        text = stringResource(R.string.home_focus_intro),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = delayText,
                            onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) delayText = it },
                            label = { Text(stringResource(R.string.home_focus_delay_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = durationText,
                            onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) durationText = it },
                            label = { Text(stringResource(R.string.home_focus_duration_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            val delay = delayText.toIntOrNull() ?: 0
                            val duration = durationText.toIntOrNull()?.takeIf { it > 0 } ?: 30
                            onStartFocus(delay, duration)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.home_focus_start)) }
                }
            }
        }
    }
}

@Composable
private fun ToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
