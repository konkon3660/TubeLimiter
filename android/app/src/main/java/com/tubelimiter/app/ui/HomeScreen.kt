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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tubelimiter.app.gamification.StreakRecord
import com.tubelimiter.app.gamification.levelProgress
import com.tubelimiter.app.gamification.levelTier
import com.tubelimiter.app.limit.BlockReason
import com.tubelimiter.app.limit.ScheduleWindow
import com.tubelimiter.app.limit.isUnlimited
import com.tubelimiter.app.limit.message
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
            title = "직접 차단",
            subtitle = if (manuallyBlocked) "한도와 무관하게 차단 중" else "꺼짐",
            checked = manuallyBlocked,
            onChange = onManualBlockChange,
        )
        ToggleCard(
            title = "백그라운드 감시",
            subtitle = if (monitoringEnabled) "켜짐 — 앱을 닫아도 계속 잽니다" else "꺼짐 — 차단되지 않습니다",
            checked = monitoringEnabled,
            onChange = onMonitoringChange,
        )
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
                text = "일 연속 성공 · 최고 ${streak.bestStreak}일",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = {
                    if (progress.xpForNextLevel == 0) 0f
                    else (progress.xpIntoLevel.toFloat() / progress.xpForNextLevel).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Text(
                text = "${tier.emoji} Lv.${progress.level} · ${progress.xpIntoLevel}/${progress.xpForNextLevel} XP",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Ring + quick stats — the app's take on the popup's `.progress-circle-small` + `.quick-stats-grid`. */
@Composable
private fun UsageRingCard(usedMillis: Long, limitMillis: Long, emergencyRemaining: Int) {
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
                centerValue = if (unlimited) "무제한" else formatCountdown(remaining),
                centerLabel = "차단까지",
            )

            StatRow("오늘 사용", formatDuration(usedMillis))
            StatRow("긴급 시청 남음", "${emergencyRemaining}회")
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
    val modeLabel = when {
        // Focus mode keeps message priority over a scheduled block when both happen to be true
        // (same tier by design - see BlockDecision.kt's blockReason()).
        stopPending -> {
            val stopAt = resolveFocusStopTime(focusEndMillis, focusStopRequestedAtMillis) ?: nowMillis
            "집중 모드 종료 대기 · ${formatDuration((stopAt - nowMillis).coerceAtLeast(0L))} 후 종료"
        }
        active -> "집중 모드 · ${formatDuration((focusEndMillis!! - nowMillis).coerceAtLeast(0L))} 남음"
        scheduled -> "집중 모드 대기 중 · ${formatDuration((focusDelayEndMillis!! - nowMillis).coerceAtLeast(0L))} 뒤 시작"
        scheduleWindow != null -> "${scheduleWindow.label} · ${formatMinuteOfDay(scheduleWindow.endMinute)}까지"
        blockReason != null -> blockReason.message()
        else -> "비활성"
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
            BadgeRow("감시", if (monitoringEnabled) "감시 중" else "꺼짐", if (monitoringEnabled) BadgeTone.ACTIVE else BadgeTone.INACTIVE)
            BadgeRow("모드", modeLabel, modeTone)
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
        StatusBadge(value, tone)
    }
}

@Composable
private fun StatusBadge(text: String, tone: BadgeTone) {
    val (bg, fg) = when (tone) {
        BadgeTone.ACTIVE -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f) to MaterialTheme.colorScheme.secondary
        BadgeTone.WARNING -> Color(0xFFF59E0B).copy(alpha = 0.15f) to Color(0xFFB45309)
        BadgeTone.INACTIVE -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** Mirrors the popup's 집중 모드 control: a delay(분) + 지속(분) pair, not just presets. */
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

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("집중 모드", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            when {
                // Checked before the plain `active` branch since a pending stop is also active.
                // Mirrors the extension popup's "종료 대기 중" state: the request already fired
                // and only a cancel is offered, not another stop (re-clicking must not push the
                // cooldown back out further).
                stopPending -> {
                    val stopAt = resolveFocusStopTime(focusEndMillis, focusStopRequestedAtMillis) ?: nowMillis
                    Text(
                        text = "${formatDuration((stopAt - nowMillis).coerceAtLeast(0L))} 후 종료",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "종료를 요청했어요. 충동적으로 끄지 못하도록 잠시 기다립니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onCancelFocusStop) { Text("종료 예약 취소") }
                }

                active -> {
                    Text(
                        text = "${formatDuration((focusEndMillis!! - nowMillis).coerceAtLeast(0L))} 남음",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    OutlinedButton(
                        onClick = onStopFocus,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("집중 모드 종료") }
                }

                scheduled -> {
                    Text("${formatDuration((focusDelayEndMillis!! - nowMillis).coerceAtLeast(0L))} 뒤 ${focusDelayDurationMinutes}분간 시작")
                    OutlinedButton(onClick = onStopFocus) { Text("예약 취소") }
                }

                else -> {
                    Text(
                        text = "한도와 무관하게 정해진 시간 동안 즉시 차단합니다.",
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
                            label = { Text("지연(분)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = durationText,
                            onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) durationText = it },
                            label = { Text("지속(분)") },
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
                    ) { Text("집중 모드 시작") }
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
