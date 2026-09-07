package com.tubelimiter.app.auth

import android.content.Context
import com.tubelimiter.app.R

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

/**
 * Every line the auth screen can show, as an identity rather than as text.
 *
 * Mapping a Supabase failure onto one of these is pure logic worth unit-testing, but unit tests
 * cannot resolve Android resources — so the translation stops here and the screen finishes the
 * job with [text].
 */
enum class AuthMessage {
    EMPTY_EMAIL,
    MALFORMED_EMAIL,
    SHORT_PASSWORD,
    GENERIC_FAILURE,
    INVALID_CREDENTIALS,
    EMAIL_NOT_CONFIRMED,
    ALREADY_REGISTERED,
    RATE_LIMITED,
    NETWORK_UNREACHABLE,
    SESSION_EXPIRED,
    DELETE_UNAVAILABLE,
    NOT_SIGNED_IN,
}

/** Either one of ours, or a server message we have nothing better to offer than. */
sealed interface AuthError {
    data class Known(val message: AuthMessage) : AuthError

    /** Shown verbatim: we did not recognise it, and hiding it would leave the user with nothing. */
    data class Unknown(val raw: String) : AuthError
}

fun CredentialError.asAuthMessage(): AuthMessage = when (this) {
    CredentialError.EmptyEmail -> AuthMessage.EMPTY_EMAIL
    CredentialError.MalformedEmail -> AuthMessage.MALFORMED_EMAIL
    CredentialError.ShortPassword -> AuthMessage.SHORT_PASSWORD
}

fun AuthMessage.text(context: Context): String = when (this) {
    AuthMessage.EMPTY_EMAIL -> context.getString(R.string.auth_error_empty_email)
    AuthMessage.MALFORMED_EMAIL -> context.getString(R.string.auth_error_malformed_email)
    AuthMessage.SHORT_PASSWORD ->
        context.getString(R.string.auth_error_short_password, MIN_PASSWORD_LENGTH)

    AuthMessage.GENERIC_FAILURE -> context.getString(R.string.auth_error_generic)
    AuthMessage.INVALID_CREDENTIALS -> context.getString(R.string.auth_error_invalid_credentials)
    AuthMessage.EMAIL_NOT_CONFIRMED -> context.getString(R.string.auth_error_email_not_confirmed)
    AuthMessage.ALREADY_REGISTERED -> context.getString(R.string.auth_error_already_registered)
    AuthMessage.RATE_LIMITED -> context.getString(R.string.auth_error_rate_limited)
    AuthMessage.NETWORK_UNREACHABLE -> context.getString(R.string.auth_error_network)
    AuthMessage.SESSION_EXPIRED -> context.getString(R.string.auth_error_session_expired)
    AuthMessage.DELETE_UNAVAILABLE -> context.getString(R.string.auth_error_delete_unavailable)
    AuthMessage.NOT_SIGNED_IN -> context.getString(R.string.auth_error_not_signed_in)
}

fun AuthError.text(context: Context): String = when (this) {
    is AuthError.Known -> message.text(context)
    is AuthError.Unknown -> raw
}

/**
 * Supabase returns raw English messages; map the ones a user actually hits onto our own
 * wording, and fall through to the server's own text otherwise.
 */
fun translateAuthError(raw: String?): AuthError {
    val message = raw?.trim().orEmpty()
    val lowered = message.lowercase()
    return when {
        message.isEmpty() -> known(AuthMessage.GENERIC_FAILURE)
        "invalid login credentials" in lowered -> known(AuthMessage.INVALID_CREDENTIALS)
        "email not confirmed" in lowered -> known(AuthMessage.EMAIL_NOT_CONFIRMED)
        "user already registered" in lowered || "already been registered" in lowered ->
            known(AuthMessage.ALREADY_REGISTERED)

        "password should be at least" in lowered -> known(AuthMessage.SHORT_PASSWORD)
        "unable to validate email address" in lowered -> known(AuthMessage.MALFORMED_EMAIL)
        "over_email_send_rate_limit" in lowered || "rate limit" in lowered ->
            known(AuthMessage.RATE_LIMITED)

        "failed to connect" in lowered || "unable to resolve host" in lowered || "timeout" in lowered ->
            known(AuthMessage.NETWORK_UNREACHABLE)

        else -> AuthError.Unknown(message)
    }
}

private fun known(message: AuthMessage): AuthError = AuthError.Known(message)

/**
 * Account deletion goes through the `delete-account` Edge Function rather than GoTrue, so the
 * failures a user can actually hit are different ones: a session the server no longer accepts,
 * or the function itself being unavailable. Anything else — network trouble above all — reads
 * the same as everywhere else, so fall through to [translateAuthError].
 */
fun translateDeleteAccountError(raw: String?): AuthError {
    val message = raw?.trim().orEmpty()
    val lowered = message.lowercase()
    return when {
        "invalid jwt" in lowered ||
            "jwt expired" in lowered ||
            "missing authorization" in lowered ||
            "not authenticated" in lowered ->
            known(AuthMessage.SESSION_EXPIRED)

        "function not found" in lowered || "requested function was not found" in lowered ->
            known(AuthMessage.DELETE_UNAVAILABLE)

        else -> translateAuthError(message)
    }
}
