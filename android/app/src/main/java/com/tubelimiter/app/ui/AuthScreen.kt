package com.tubelimiter.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tubelimiter.app.auth.AuthMode
import com.tubelimiter.app.auth.message
import com.tubelimiter.app.auth.validateCredentials

@Composable
fun AuthScreen(
    busy: Boolean,
    errorMessage: String?,
    noticeMessage: String?,
    onSubmit: (AuthMode, String, String) -> Unit,
    onCancel: () -> Unit,
    onClearMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val problem = validateCredentials(email, password)
        localError = problem?.message()
        if (problem == null) onSubmit(mode, email, password)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (mode == AuthMode.SIGN_IN) "로그인" else "회원가입",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "크롬 확장과 같은 계정을 쓰면 설정과 기록이 이어집니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = email,
            onValueChange = {
                email = it
                localError = null
                onClearMessages()
            },
            label = { Text("이메일") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                localError = null
                onClearMessages()
            },
            label = { Text("비밀번호") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        val shownError = localError ?: errorMessage
        if (shownError != null) {
            Text(
                text = shownError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (noticeMessage != null) {
            Text(text = noticeMessage, style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { submit() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
            } else {
                Text(if (mode == AuthMode.SIGN_IN) "로그인" else "회원가입")
            }
        }

        TextButton(
            onClick = {
                mode = if (mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN
                localError = null
                onClearMessages()
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (mode == AuthMode.SIGN_IN) {
                    "계정이 없으신가요? 회원가입"
                } else {
                    "이미 계정이 있으신가요? 로그인"
                },
            )
        }

        TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("나중에 하기")
        }
    }
}
