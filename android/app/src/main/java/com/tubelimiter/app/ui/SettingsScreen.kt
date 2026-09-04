package com.tubelimiter.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tubelimiter.app.data.Settings
import com.tubelimiter.app.limit.EmergencyResetFrequency
import com.tubelimiter.app.limit.LIMIT_PRESETS_MINUTES
import com.tubelimiter.app.limit.LimitFrequency
import com.tubelimiter.app.limit.ScheduleWindow
import com.tubelimiter.app.limit.UNLIMITED_MINUTES
import com.tubelimiter.app.usage.formatDuration

private val DAY_LABELS = listOf("일", "월", "화", "수", "목", "금", "토")
private val ALARM_INTERVAL_PRESETS = listOf(0, 10, 30, 60)
private val EMERGENCY_ALLOWANCE_PRESETS = listOf(0, 1, 3, 5)

/**
 * The extension lets any number be typed, so a synced account can arrive holding a value
 * no preset covers (20 minutes, say). Fold it in rather than showing nothing selected.
 */
private fun withCurrent(presets: List<Int>, current: Int): List<Int> =
    (presets + current).distinct().sorted()

@Composable
fun SettingsScreen(
    settings: Settings,
    hardcoreCooldownRemaining: Long,
    accountEmail: String?,
    accountLoading: Boolean,
    onSignInClick: () -> Unit,
    onSignOut: () -> Unit,
    onDailyLimitChange: (Int) -> Unit,
    onByDayChange: (Int, Int) -> Unit,
    onFrequencyChange: (LimitFrequency) -> Unit,
    onEmergencyAllowanceChange: (Int) -> Unit,
    onEmergencyResetChange: (EmergencyResetFrequency) -> Unit,
    onAlarmIntervalChange: (Int) -> Unit,
    onAlarmMilestonesChange: (Boolean) -> Unit,
    onHardcoreEnable: () -> Unit,
    onHardcoreDisableRequest: () -> Unit,
    onHardcoreDisableCancel: () -> Unit,
    scheduleWindows: List<ScheduleWindow>,
    onScheduleWindowsChange: (List<ScheduleWindow>) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hardcore mode is what makes the streak mean anything, so it locks the limits.
    val limitsLocked = settings.hardcoreMode

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsCard("계정") {
            when {
                accountLoading -> Text("확인 중…", style = MaterialTheme.typography.bodyMedium)

                accountEmail != null -> {
                    Text(accountEmail, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "크롬 확장과 기록이 이어집니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onSignOut) { Text("로그아웃") }
                }

                else -> {
                    Text(
                        "로그인하지 않아도 차단은 동작합니다. 로그인하면 확장과 계정을 공유합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onSignInClick) { Text("로그인 / 회원가입") }
                }
            }
        }

        SettingsCard("한도 방식") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.limit.frequency == LimitFrequency.DAILY,
                    onClick = { onFrequencyChange(LimitFrequency.DAILY) },
                    enabled = !limitsLocked,
                    label = { Text("매일 같음") },
                )
                FilterChip(
                    selected = settings.limit.frequency == LimitFrequency.BY_DAY,
                    onClick = { onFrequencyChange(LimitFrequency.BY_DAY) },
                    enabled = !limitsLocked,
                    label = { Text("요일별") },
                )
            }
        }

        if (settings.limit.frequency == LimitFrequency.DAILY) {
            SettingsCard("하루 한도") {
                ChipRow(
                    options = withCurrent(LIMIT_PRESETS_MINUTES, settings.limit.dailyLimitMinutes),
                    selected = settings.limit.dailyLimitMinutes,
                    enabled = !limitsLocked,
                    label = { "${it}분" },
                    onSelect = onDailyLimitChange,
                )
                NumberEntryField(
                    label = "직접 입력(분, 0=무제한)",
                    value = settings.limit.dailyLimitMinutes,
                    minValue = 0,
                    enabled = !limitsLocked,
                    onCommit = onDailyLimitChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            SettingsCard("요일별 한도") {
                DAY_LABELS.forEachIndexed { index, label ->
                    val minutes = settings.limit.byDayMinutes.getOrElse(index) { 0 }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(label, style = MaterialTheme.typography.titleSmall)
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                (withCurrent(LIMIT_PRESETS_MINUTES, minutes) + UNLIMITED_MINUTES)
                                    .distinct()
                                    .forEach { option ->
                                    FilterChip(
                                        selected = minutes == option,
                                        onClick = { onByDayChange(index, option) },
                                        enabled = !limitsLocked,
                                        label = {
                                            Text(if (option == UNLIMITED_MINUTES) "무제한" else "${option}분")
                                        },
                                    )
                                }
                            }
                        }
                        NumberEntryField(
                            label = "직접 입력(분, -1=무제한)",
                            value = minutes,
                            minValue = UNLIMITED_MINUTES,
                            enabled = !limitsLocked,
                            onCommit = { onByDayChange(index, it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        SettingsCard("긴급 시청") {
            Text(
                "차단된 동안 5분만 예외로 열어줍니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipRow(
                options = EMERGENCY_ALLOWANCE_PRESETS,
                selected = settings.emergencyAllowance,
                label = { "${it}회" },
                onSelect = onEmergencyAllowanceChange,
            )
            NumberEntryField(
                label = "직접 입력(회)",
                value = settings.emergencyAllowance,
                minValue = 0,
                onCommit = onEmergencyAllowanceChange,
                modifier = Modifier.fillMaxWidth(),
            )
            ChipRow(
                options = EmergencyResetFrequency.entries.toList(),
                selected = settings.emergencyResetFrequency,
                label = {
                    when (it) {
                        EmergencyResetFrequency.DAILY -> "매일 리셋"
                        EmergencyResetFrequency.WEEKLY -> "매주 리셋"
                        EmergencyResetFrequency.MONTHLY -> "매달 리셋"
                    }
                },
                onSelect = onEmergencyResetChange,
            )
        }

        SettingsCard("알림") {
            Text("몇 분마다 알려줄까요", style = MaterialTheme.typography.bodySmall)
            ChipRow(
                options = ALARM_INTERVAL_PRESETS,
                selected = settings.alarmIntervalMinutes,
                label = { if (it == 0) "끔" else "${it}분" },
                onSelect = onAlarmIntervalChange,
            )
            NumberEntryField(
                label = "직접 입력(분, 0=끔)",
                value = settings.alarmIntervalMinutes,
                minValue = 0,
                onCommit = onAlarmIntervalChange,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("남은 시간 30·10·5·1분 알림", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = settings.alarmMilestonesEnabled,
                    onCheckedChange = onAlarmMilestonesChange,
                )
            }
        }

        SettingsCard("하드코어 모드") {
            Text(
                "켜면 한도 설정이 잠기고, 켜져 있는 동안만 연속 기록과 XP가 쌓입니다. " +
                    "끄기는 1시간 뒤에 적용되고 연속 기록은 0으로 초기화됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                !settings.hardcoreMode ->
                    OutlinedButton(onClick = onHardcoreEnable) { Text("켜기") }

                settings.hardcoreDisableRequestedAt != null -> {
                    Text(
                        "${formatDuration(hardcoreCooldownRemaining)} 뒤 해제됩니다.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onHardcoreDisableCancel) { Text("해제 취소") }
                }

                else -> OutlinedButton(onClick = onHardcoreDisableRequest) { Text("끄기 요청") }
            }
        }

        SettingsCard("예약 차단") {
            Text(
                "정해진 요일·시간대엔 한도와 무관하게 자동으로 차단됩니다. 집중 모드처럼 긴급 시청으로 우회할 수 없어요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            scheduleWindows.forEachIndexed { index, window ->
                ScheduleWindowEditor(
                    window = window,
                    onChange = { updated ->
                        onScheduleWindowsChange(scheduleWindows.toMutableList().apply { this[index] = updated })
                    },
                    onRemove = {
                        onScheduleWindowsChange(scheduleWindows.toMutableList().apply { removeAt(index) })
                    },
                )
            }
            OutlinedButton(
                onClick = {
                    val seeded = ScheduleWindow(
                        id = System.currentTimeMillis().toString(),
                        label = "밤 시간",
                        days = List(7) { true },
                        startMinute = 22 * 60,
                        endMinute = 7 * 60,
                        enabled = true,
                    )
                    onScheduleWindowsChange(scheduleWindows + seeded)
                },
            ) { Text("+ 예약 추가") }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/** One editable row for a single [ScheduleWindow]: label, enabled switch, day chips, start/end time. */
@Composable
private fun ScheduleWindowEditor(
    window: ScheduleWindow,
    onChange: (ScheduleWindow) -> Unit,
    onRemove: () -> Unit,
) {
    val days = if (window.days.size >= 7) window.days else List(7) { window.days.getOrElse(it) { true } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = window.label,
                onValueChange = { onChange(window.copy(label = it)) },
                label = { Text("라벨") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = window.enabled, onCheckedChange = { onChange(window.copy(enabled = it)) })
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DAY_LABELS.forEachIndexed { dayIndex, label ->
                FilterChip(
                    selected = days[dayIndex],
                    onClick = {
                        val updatedDays = days.toMutableList().apply { this[dayIndex] = !this[dayIndex] }
                        onChange(window.copy(days = updatedDays))
                    },
                    label = { Text(label) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MinuteOfDayField(
                label = "시작",
                minutes = window.startMinute,
                onCommit = { onChange(window.copy(startMinute = it)) },
            )
            MinuteOfDayField(
                label = "종료",
                minutes = window.endMinute,
                onCommit = { onChange(window.copy(endMinute = it)) },
            )
        }

        OutlinedButton(
            onClick = onRemove,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("삭제") }
    }
}

/**
 * Hour/minute pair for a minute-of-day value (0-1439). Compose has no built-in time-text
 * field, so this keeps to the same typed-numeric-entry style as [NumberEntryField] rather than
 * introducing a new input pattern.
 */
@Composable
private fun MinuteOfDayField(
    label: String,
    minutes: Int,
    onCommit: (Int) -> Unit,
) {
    val normalized = ((minutes % 1440) + 1440) % 1440
    var hourText by remember(normalized) { mutableStateOf((normalized / 60).toString()) }
    var minuteText by remember(normalized) { mutableStateOf((normalized % 60).toString()) }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun commit() {
        val hour = (hourText.toIntOrNull() ?: 0).coerceIn(0, 23)
        val minute = (minuteText.toIntOrNull() ?: 0).coerceIn(0, 59)
        hourText = hour.toString()
        minuteText = minute.toString()
        onCommit(hour * 60 + minute)
        keyboardController?.hide()
    }

    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = hourText,
                onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) hourText = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { commit() }),
                modifier = Modifier.width(64.dp),
            )
            Text(":")
            OutlinedTextField(
                value = minuteText,
                onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) minuteText = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.width(64.dp),
            )
        }
    }
}

/**
 * Free numeric entry alongside the preset [ChipRow]s, matching the extension's options page
 * (`options.html`'s `<input type="number">` fields) where any positive integer can be typed
 * rather than being limited to a fixed preset list. Commits on IME "done" rather than on every
 * keystroke, same spirit as the extension only writing settings on its explicit Save action.
 */
@Composable
private fun NumberEntryField(
    label: String,
    value: Int,
    minValue: Int,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val keyboardController = LocalSoftwareKeyboardController.current

    fun commit() {
        val parsed = (text.toIntOrNull() ?: value).coerceAtLeast(minValue)
        text = parsed.toString()
        onCommit(parsed)
        keyboardController?.hide()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val allowsMinus = minValue < 0
            val isValid = input.isEmpty() ||
                input == "-".takeIf { allowsMinus } ||
                (input.length <= 6 && input.all(Char::isDigit)) ||
                (allowsMinus && input.startsWith("-") && input.length <= 7 && input.drop(1).all(Char::isDigit))
            if (isValid) text = input
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier,
    )
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                label = { Text(label(option)) },
            )
        }
    }
}
