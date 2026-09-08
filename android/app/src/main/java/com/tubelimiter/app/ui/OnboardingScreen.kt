package com.tubelimiter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tubelimiter.app.R
import com.tubelimiter.app.permission.AppPermission

/** Resource ids rather than text, so a card can be described without a Context in hand. */
private data class PermissionCopy(val titleRes: Int, val rationaleRes: Int, val actionRes: Int)

private fun copyFor(permission: AppPermission): PermissionCopy = when (permission) {
    AppPermission.USAGE_ACCESS -> PermissionCopy(
        titleRes = R.string.permission_usage_access_title,
        rationaleRes = R.string.permission_usage_access_rationale,
        actionRes = R.string.onboarding_action_open_settings,
    )

    AppPermission.OVERLAY -> PermissionCopy(
        titleRes = R.string.permission_overlay_title,
        rationaleRes = R.string.permission_overlay_rationale,
        actionRes = R.string.onboarding_action_open_settings,
    )

    AppPermission.BATTERY_UNRESTRICTED -> PermissionCopy(
        titleRes = R.string.permission_battery_title,
        rationaleRes = R.string.permission_battery_rationale,
        actionRes = R.string.onboarding_action_request,
    )

    AppPermission.NOTIFICATIONS -> PermissionCopy(
        titleRes = R.string.permission_notifications_title,
        rationaleRes = R.string.permission_notifications_rationale,
        actionRes = R.string.onboarding_action_request,
    )
}

@Composable
fun OnboardingScreen(
    required: List<AppPermission>,
    granted: Map<AppPermission, Boolean>,
    onRequest: (AppPermission) -> Unit,
    modifier: Modifier = Modifier,
    /** 권한이 다 채워진 채로 이 화면에 들어온 경우에만 주는 "나가기". 최초 설치 흐름에서는
     * 돌아갈 곳이 없으므로 null이다. */
    onDone: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.onboarding_intro),
            style = MaterialTheme.typography.bodyMedium,
        )

        val firstMissing = required.firstOrNull { granted[it] != true }
        required.forEach { permission ->
            PermissionCard(
                permission = permission,
                isGranted = granted[permission] == true,
                isNext = permission == firstMissing,
                onRequest = { onRequest(permission) },
            )
        }

        CoverageNoteCard()

        if (onDone != null) {
            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onboarding_action_done))
            }
        }
    }
}

/**
 * 이 앱이 **못 막는 것**을 권한 카드와 같은 화면에 적는다 (documents/QA_REVIEW.md §1.8).
 *
 * 폰 브라우저로 `m.youtube.com`을 여는 건 원천적으로 막을 수 없다 — 이유는
 * [com.tubelimiter.app.usage.watchedPackages] 주석에 있다. 이걸 안 적으면 사용자는 "네 개나
 * 허용했으니 다 막히겠지"라고 읽고, 어느 날 브라우저로 유튜브를 보다가 앱 전체를 불신하게 된다.
 * 겁주지 않고 사실만 적는 건 신뢰를 잃는 게 아니라 얻는 쪽이다(§1.1과 같은 논지).
 */
@Composable
private fun CoverageNoteCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_coverage_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.onboarding_coverage_apps),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.onboarding_coverage_browser),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun PermissionCard(
    permission: AppPermission,
    isGranted: Boolean,
    isNext: Boolean,
    onRequest: () -> Unit,
) {
    val copy = copyFor(permission)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (isGranted) R.string.onboarding_status_granted else R.string.onboarding_status_missing,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(copy.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(text = stringResource(copy.rationaleRes), style = MaterialTheme.typography.bodySmall)
            if (!isGranted) {
                Button(onClick = onRequest, enabled = isNext) {
                    Text(stringResource(copy.actionRes))
                }
            }
        }
    }
}
