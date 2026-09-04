package com.tubelimiter.app.auth

const val MIN_PASSWORD_LENGTH = 6

enum class AuthMode { SIGN_IN, SIGN_UP }

sealed interface CredentialError {
    data object EmptyEmail : CredentialError
    data object MalformedEmail : CredentialError
    data object ShortPassword : CredentialError
}

/** Matches the extension's guard: an address plus a password of at least six characters. */
fun validateCredentials(email: String, password: String): CredentialError? = when {
    email.isBlank() -> CredentialError.EmptyEmail
    !isPlausibleEmail(email) -> CredentialError.MalformedEmail
    password.length < MIN_PASSWORD_LENGTH -> CredentialError.ShortPassword
    else -> null
}

private fun isPlausibleEmail(email: String): Boolean {
    val trimmed = email.trim()
    val at = trimmed.indexOf('@')
    if (at <= 0 || at != trimmed.lastIndexOf('@')) return false
    val domain = trimmed.substring(at + 1)
    return domain.length >= 3 &&
        domain.contains('.') &&
        !domain.startsWith('.') &&
        !domain.endsWith('.') &&
        trimmed.none { it.isWhitespace() }
}

fun CredentialError.message(): String = when (this) {
    CredentialError.EmptyEmail -> "이메일을 입력하세요."
    CredentialError.MalformedEmail -> "이메일 형식이 올바르지 않습니다."
    CredentialError.ShortPassword -> "비밀번호는 ${MIN_PASSWORD_LENGTH}자 이상이어야 합니다."
}

/**
 * Supabase returns English messages; map the ones a user actually hits so the
 * screen stays in Korean, and fall through to the original otherwise.
 */
fun translateAuthError(raw: String?): String {
    val message = raw?.trim().orEmpty()
    val lowered = message.lowercase()
    return when {
        message.isEmpty() -> "요청 중 오류가 발생했습니다."
        "invalid login credentials" in lowered -> "이메일 또는 비밀번호가 올바르지 않습니다."
        "email not confirmed" in lowered -> "가입 확인 메일의 링크를 먼저 눌러주세요."
        "user already registered" in lowered || "already been registered" in lowered ->
            "이미 가입된 이메일입니다. 로그인해주세요."

        "password should be at least" in lowered ->
            "비밀번호는 ${MIN_PASSWORD_LENGTH}자 이상이어야 합니다."

        "unable to validate email address" in lowered -> "이메일 형식이 올바르지 않습니다."
        "over_email_send_rate_limit" in lowered || "rate limit" in lowered ->
            "요청이 너무 잦습니다. 잠시 뒤 다시 시도해주세요."

        "failed to connect" in lowered || "unable to resolve host" in lowered || "timeout" in lowered ->
            "네트워크에 연결할 수 없습니다."

        else -> message
    }
}
