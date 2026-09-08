package com.tubelimiter.app.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tubelimiter.app.R
import com.tubelimiter.app.data.Settings
import com.tubelimiter.app.diagnostics.DiagnosticEvent
import com.tubelimiter.app.diagnostics.diagnosticKindLabelRes
import com.tubelimiter.app.diagnostics.formatDiagnosticTime
import com.tubelimiter.app.limit.EmergencyResetFrequency
import com.tubelimiter.app.limit.LIMIT_PRESETS_MINUTES
import com.tubelimiter.app.limit.LimitFrequency
import com.tubelimiter.app.limit.ScheduleWindow
import com.tubelimiter.app.limit.UNLIMITED_MINUTES
import com.tubelimiter.app.usage.formatDuration

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
    deleteAccountInFlight: Boolean,
    onDeleteAccount: () -> Unit,
    onDailyLimitChange: (Int) -> Unit,
    onByDayChange: (Int, Int) -> Unit,
    onFrequencyChange: (LimitFrequency) -> Unit,
    onEmergencyAllowanceChange: (Int) -> Unit,
    onEmergencyResetChange: (EmergencyResetFrequency) -> Unit,
    onAlarmIntervalChange: (Int) -> Unit,
    onAlarmMilestonesChange: (Boolean) -> Unit,
    /** YouTube Music을 감시 대상에 넣을지 (기본 꺼짐 — [com.tubelimiter.app.usage.watchedPackages]). */
    onWatchMusicChange: (Boolean) -> Unit,
    onHardcoreEnable: () -> Unit,
    onHardcoreDisableRequest: () -> Unit,
    onHardcoreDisableCancel: () -> Unit,
    scheduleWindows: List<ScheduleWindow>,
    onScheduleWindowsChange: (List<ScheduleWindow>) -> Unit,
    /** 마지막으로 서버 왕복이 성공한 시각, 한 번도 없으면 null. */
    lastSyncSuccessAtMillis: Long?,
    /** 최근 동기화·인증 실패, 최신순 (diagnostics/SyncDiagnostics.kt). */
    diagnosticEvents: List<DiagnosticEvent>,
    onClearDiagnostics: () -> Unit,
    onCopyDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hardcore mode is what makes the streak mean anything, so it locks the limits.
    val limitsLocked = settings.hardcoreMode
    var confirmDeleteAccount by remember { mutableStateOf(false) }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val dayLabels = stringArrayResource(R.array.day_labels_short)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsCard(stringResource(R.string.settings_account)) {
            when {
                accountLoading -> Text(
                    stringResource(R.string.settings_account_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )

                accountEmail != null -> {
                    Text(accountEmail, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.settings_account_synced),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.settings_sign_out)) }
                }

                else -> {
                    Text(
                        stringResource(R.string.settings_account_signed_out),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = onSignInClick) { Text(stringResource(R.string.settings_sign_in)) }
                }
            }
        }

        SettingsCard(stringResource(R.string.settings_limit_mode)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.limit.frequency == LimitFrequency.DAILY,
                    onClick = { onFrequencyChange(LimitFrequency.DAILY) },
                    enabled = !limitsLocked,
                    label = { Text(stringResource(R.string.settings_limit_mode_daily)) },
                )
                FilterChip(
                    selected = settings.limit.frequency == LimitFrequency.BY_DAY,
                    onClick = { onFrequencyChange(LimitFrequency.BY_DAY) },
                    enabled = !limitsLocked,
                    label = { Text(stringResource(R.string.settings_limit_mode_by_day)) },
                )
            }
        }

        if (settings.limit.frequency == LimitFrequency.DAILY) {
            SettingsCard(stringResource(R.string.settings_daily_limit)) {
                ChipRow(
                    options = withCurrent(LIMIT_PRESETS_MINUTES, settings.limit.dailyLimitMinutes),
                    selected = settings.limit.dailyLimitMinutes,
                    enabled = !limitsLocked,
                    label = { stringResource(R.string.count_minutes, it) },
                    onSelect = onDailyLimitChange,
                )
                NumberEntryField(
                    label = stringResource(R.string.settings_minutes_entry_daily),
                    value = settings.limit.dailyLimitMinutes,
                    minValue = 0,
                    enabled = !limitsLocked,
                    onCommit = onDailyLimitChange,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            SettingsCard(stringResource(R.string.settings_by_day_limit)) {
                dayLabels.forEachIndexed { index, label ->
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
                                                Text(
                                                    if (option == UNLIMITED_MINUTES) {
                                                        stringResource(R.string.unlimited)
                                                    } else {
                                                        stringResource(R.string.count_minutes, option)
                                                    },
                                                )
                                            },
                                        )
                                    }
                            }
                        }
                        NumberEntryField(
                            label = stringResource(R.string.settings_minutes_entry_by_day),
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

        SettingsCard(stringResource(R.string.settings_emergency)) {
            Text(
                stringResource(R.string.settings_emergency_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ChipRow(
                options = EMERGENCY_ALLOWANCE_PRESETS,
                selected = settings.emergencyAllowance,
                label = { pluralStringResource(R.plurals.settings_emergency_allowance, it, it) },
                onSelect = onEmergencyAllowanceChange,
            )
            NumberEntryField(
                label = stringResource(R.string.settings_emergency_entry),
                value = settings.emergencyAllowance,
                minValue = 0,
                onCommit = onEmergencyAllowanceChange,
                modifier = Modifier.fillMaxWidth(),
            )
            ChipRow(
                options = EmergencyResetFrequency.entries.toList(),
                selected = settings.emergencyResetFrequency,
                label = {
                    stringResource(
                        when (it) {
                            EmergencyResetFrequency.DAILY -> R.string.settings_emergency_reset_daily
                            EmergencyResetFrequency.WEEKLY -> R.string.settings_emergency_reset_weekly
                            EmergencyResetFrequency.MONTHLY -> R.string.settings_emergency_reset_monthly
                        },
                    )
                },
                onSelect = onEmergencyResetChange,
            )
        }

        SettingsCard(stringResource(R.string.settings_watch_targets)) {
            Text(
                stringResource(R.string.settings_watch_targets_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_watch_music),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.settings_watch_music_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.watchYouTubeMusic,
                    onCheckedChange = onWatchMusicChange,
                )
            }
            Text(
                stringResource(R.string.settings_watch_targets_browser),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard(stringResource(R.string.settings_alarms)) {
            Text(
                stringResource(R.string.settings_alarm_interval_question),
                style = MaterialTheme.typography.bodySmall,
            )
            ChipRow(
                options = ALARM_INTERVAL_PRESETS,
                selected = settings.alarmIntervalMinutes,
                label = {
                    if (it == 0) {
                        stringResource(R.string.settings_alarm_interval_off)
                    } else {
                        stringResource(R.string.count_minutes, it)
                    }
                },
                onSelect = onAlarmIntervalChange,
            )
            NumberEntryField(
                label = stringResource(R.string.settings_alarm_interval_entry),
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
                Text(
                    stringResource(R.string.settings_alarm_milestones),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.alarmMilestonesEnabled,
                    onCheckedChange = onAlarmMilestonesChange,
                )
            }
        }

        SettingsCard(stringResource(R.string.settings_hardcore)) {
            Text(
                stringResource(R.string.settings_hardcore_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                !settings.hardcoreMode ->
                    OutlinedButton(onClick = onHardcoreEnable) {
                        Text(stringResource(R.string.settings_hardcore_enable))
                    }

                settings.hardcoreDisableRequestedAt != null -> {
                    Text(
                        stringResource(
                            R.string.settings_hardcore_cooldown,
                            formatDuration(context, hardcoreCooldownRemaining),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onHardcoreDisableCancel) {
                        Text(stringResource(R.string.settings_hardcore_cancel))
                    }
                }

                else -> OutlinedButton(onClick = onHardcoreDisableRequest) {
                    Text(stringResource(R.string.settings_hardcore_disable))
                }
            }
        }

        SettingsCard(stringResource(R.string.settings_schedule)) {
            Text(
                stringResource(R.string.settings_schedule_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            scheduleWindows.forEachIndexed { index, window ->
                ScheduleWindowEditor(
                    window = window,
                    dayLabels = dayLabels,
                    onChange = { updated ->
                        onScheduleWindowsChange(scheduleWindows.toMutableList().apply { this[index] = updated })
                    },
                    onRemove = {
                        onScheduleWindowsChange(scheduleWindows.toMutableList().apply { removeAt(index) })
                    },
                )
            }
            val seededLabel = stringResource(R.string.settings_schedule_default_label)
            OutlinedButton(
                onClick = {
                    val seeded = ScheduleWindow(
                        id = System.currentTimeMillis().toString(),
                        label = seededLabel,
                        days = List(7) { true },
                        startMinute = 22 * 60,
                        endMinute = 7 * 60,
                        enabled = true,
                    )
                    onScheduleWindowsChange(scheduleWindows + seeded)
                },
            ) { Text(stringResource(R.string.settings_schedule_add)) }
        }

        // 접힌 채로 두는 건 평소엔 볼 일이 없어서고, 접힌 헤더에 요약 한 줄을 남기는 건
        // 펼치지 않아도 "마지막 성공이 언제였나"는 보이게 하려는 것.
        DiagnosticsCard(
            expanded = diagnosticsExpanded,
            onToggle = { diagnosticsExpanded = !diagnosticsExpanded },
            lastSyncSuccessAtMillis = lastSyncSuccessAtMillis,
            events = diagnosticEvents,
            onClear = onClearDiagnostics,
            onCopy = onCopyDiagnostics,
        )

        // Google Play requires an in-app way to delete the account, so this only shows once
        // there is one to delete. Sits last, and in error colours, so it can't be hit in passing.
        if (accountEmail != null) {
            DangerCard(stringResource(R.string.settings_delete_account)) {
                Text(
                    stringResource(R.string.settings_delete_account_warning),
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { confirmDeleteAccount = true },
                    enabled = !deleteAccountInFlight,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(
                        stringResource(
                            if (deleteAccountInFlight) {
                                R.string.settings_delete_account_in_flight
                            } else {
                                R.string.settings_delete_account
                            },
                        ),
                    )
                }
            }
        }

        if (confirmDeleteAccount) {
            DeleteAccountDialog(
                email = accountEmail.orEmpty(),
                inFlight = deleteAccountInFlight,
                onDismiss = { confirmDeleteAccount = false },
                onConfirm = {
                    confirmDeleteAccount = false
                    onDeleteAccount()
                },
            )
        }
    }
}

/**
 * The one deliberate, irreversible action in the app, so it spells out what goes and keeps the
 * destructive button visually separate from the cancel it sits next to. [inFlight] disables both
 * buttons and blocks dismissal, so a second tap can't fire a second delete while one is running.
 */
@Composable
private fun DeleteAccountDialog(
    email: String,
    inFlight: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        title = { Text(stringResource(R.string.settings_delete_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (email.isNotBlank()) {
                    Text(email, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    stringResource(R.string.settings_delete_dialog_items),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.settings_delete_dialog_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (!inFlight) onConfirm() },
                enabled = !inFlight,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.settings_delete_dialog_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/**
 * 접히는 "동기화 상태 / 진단" 섹션.
 *
 * 동기화 실패는 지금까지 `Log`로만 남아 사용자에겐 아무 흔적도 없었다 — 외부 크래시 리포팅을
 * 쓰지 않기로 한 이상, 실기기에서 며칠째 실패하는 걸 알아챌 곳은 여기뿐이다. 그래서 접혀
 * 있어도 헤더에 최근 성공 시각과 실패 건수는 그대로 보인다.
 */
@Composable
private fun DiagnosticsCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    lastSyncSuccessAtMillis: Long?,
    events: List<DiagnosticEvent>,
    onClear: () -> Unit,
    onCopy: () -> Unit,
) {
    val lastSuccess = lastSyncSuccessAtMillis?.let { formatDiagnosticTime(it) }
        ?: stringResource(R.string.settings_diagnostics_never)
    val summary = if (events.isEmpty()) {
        stringResource(R.string.settings_diagnostics_summary_ok, lastSuccess)
    } else {
        pluralStringResource(
            R.plurals.settings_diagnostics_summary_failures,
            events.size,
            lastSuccess,
            events.size,
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.settings_diagnostics_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (expanded) {
                Text(
                    stringResource(R.string.settings_diagnostics_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (events.isEmpty()) {
                    Text(
                        stringResource(R.string.settings_diagnostics_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    events.forEach { DiagnosticRow(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCopy, enabled = events.isNotEmpty() || lastSyncSuccessAtMillis != null) {
                        Text(stringResource(R.string.settings_diagnostics_copy))
                    }
                    OutlinedButton(
                        onClick = onClear,
                        enabled = events.isNotEmpty() || lastSyncSuccessAtMillis != null,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.settings_diagnostics_clear)) }
                }
            }
        }
    }
}

/**
 * 실패 한 줄: 종류 + 짧은 코드, 오른쪽에 시각과 반복 횟수.
 *
 * 이름을 모르는 종류는 저장된 키를 그대로 보여준다 — 더 새 버전이 남긴 이벤트가 그렇다.
 */
@Composable
private fun DiagnosticRow(event: DiagnosticEvent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val kindLabel = diagnosticKindLabelRes(event.kind)?.let { stringResource(it) } ?: event.kind
            Text(kindLabel, style = MaterialTheme.typography.bodyMedium)
            Text(
                event.code,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                formatDiagnosticTime(event.atMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (event.count > 1) {
                Text(
                    stringResource(R.string.count_times, event.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
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

/** [SettingsCard] in the theme's error colours, for the one card holding a destructive action. */
@Composable
private fun DangerCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
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
    dayLabels: Array<String>,
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
                label = { Text(stringResource(R.string.settings_schedule_label)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = window.enabled, onCheckedChange = { onChange(window.copy(enabled = it)) })
        }

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            dayLabels.forEachIndexed { dayIndex, label ->
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
                label = stringResource(R.string.settings_schedule_start),
                minutes = window.startMinute,
                onCommit = { onChange(window.copy(startMinute = it)) },
            )
            MinuteOfDayField(
                label = stringResource(R.string.settings_schedule_end),
                minutes = window.endMinute,
                onCommit = { onChange(window.copy(endMinute = it)) },
            )
        }

        OutlinedButton(
            onClick = onRemove,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.settings_schedule_remove)) }
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

/** [label] is composable so a chip's text can come straight from a resource or a plural. */
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
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
