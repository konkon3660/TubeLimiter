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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tubelimiter.app.permission.AppPermission

private data class PermissionCopy(val title: String, val rationale: String, val action: String)

private fun copyFor(permission: AppPermission): PermissionCopy = when (permission) {
    AppPermission.USAGE_ACCESS -> PermissionCopy(
        title = "사용 현황 접근",
        rationale = "유튜브 앱을 얼마나 봤는지 재려면 필요합니다. 설정 화면에서 TubeLimiter를 직접 켜야 합니다.",
        action = "설정 열기",
    )

    AppPermission.OVERLAY -> PermissionCopy(
        title = "다른 앱 위에 표시",
        rationale = "한도를 넘겼을 때 차단 화면을 띄우려면 필요합니다.",
        action = "설정 열기",
    )

    AppPermission.BATTERY_UNRESTRICTED -> PermissionCopy(
        title = "배터리 최적화 제외",
        rationale = "절전 모드에서 감지가 멈추지 않게 하려면 필요합니다.",
        action = "허용 요청",
    )

    AppPermission.NOTIFICATIONS -> PermissionCopy(
        title = "알림",
        rationale = "감지 서비스가 백그라운드에 상주하려면 알림 하나를 띄워야 합니다.",
        action = "허용 요청",
    )
}

@Composable
fun OnboardingScreen(
    required: List<AppPermission>,
    granted: Map<AppPermission, Boolean>,
    onRequest: (AppPermission) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "권한 설정",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "아래 네 가지를 순서대로 허용해야 유튜브 사용시간 감지와 차단이 동작합니다.",
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
                Text(text = if (isGranted) "완료" else "미허용", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = copy.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(text = copy.rationale, style = MaterialTheme.typography.bodySmall)
            if (!isGranted) {
                Button(onClick = onRequest, enabled = isNext) {
                    Text(copy.action)
                }
            }
        }
    }
}
