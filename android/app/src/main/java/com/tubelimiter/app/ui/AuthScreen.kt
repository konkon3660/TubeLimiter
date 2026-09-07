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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tubelimiter.app.R
import com.tubelimiter.app.auth.AuthError
import com.tubelimiter.app.auth.AuthMessage
import com.tubelimiter.app.auth.AuthMode
import com.tubelimiter.app.auth.asAuthMessage
import com.tubelimiter.app.auth.text
import com.tubelimiter.app.auth.validateCredentials

@Composable
fun AuthScreen(
    busy: Boolean,
    /** Server- or validation-side failure to show, or null. Turned into text here, not upstream. */
    error: AuthError?,
    /** String resource for the one-off notice under the fields, or null. */
    noticeRes: Int?,
    onSubmit: (AuthMode, String, String) -> Unit,
    onCancel: () -> Unit,
    onClearMessages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(AuthMode.SIGN_IN) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<AuthMessage?>(null) }

    fun submit() {
        val problem = validateCredentials(email, password)
        localError = problem?.asAuthMessage()
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
            text = stringResource(titleRes(mode)),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.auth_subtitle),
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
            label = { Text(stringResource(R.string.auth_field_email)) },
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
            label = { Text(stringResource(R.string.auth_field_password)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        val shownError = localError?.text(context) ?: error?.text(context)
        if (shownError != null) {
            Text(
                text = shownError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (noticeRes != null) {
            Text(text = stringResource(noticeRes), style = MaterialTheme.typography.bodyMedium)
        }

        Button(
            onClick = { submit() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(titleRes(mode)))
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
                stringResource(
                    if (mode == AuthMode.SIGN_IN) {
                        R.string.auth_switch_to_sign_up
                    } else {
                        R.string.auth_switch_to_sign_in
                    },
                ),
            )
        }

        TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.auth_later))
        }
    }
}

/** The screen's title doubles as its submit-button label, so both read from one place. */
private fun titleRes(mode: AuthMode): Int =
    if (mode == AuthMode.SIGN_IN) R.string.auth_title_sign_in else R.string.auth_title_sign_up
