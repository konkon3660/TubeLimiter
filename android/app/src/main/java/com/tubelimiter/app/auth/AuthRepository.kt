package com.tubelimiter.app.auth

import android.content.Context
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "AuthRepository"

data class AccountState(
    val loading: Boolean = true,
    val email: String? = null,
    val userId: String? = null,
) {
    val signedIn: Boolean get() = userId != null
}

sealed interface AuthResult {
    data object Success : AuthResult
    /** Sign-up succeeded but the address still needs confirming before a session exists. */
    data object ConfirmationRequired : AuthResult
    data class Failed(val message: String) : AuthResult
}

class AuthRepository(context: Context) {

    private val client: SupabaseClient = createSupabaseClient(
        supabaseUrl = SUPABASE_URL,
        supabaseKey = SUPABASE_ANON_KEY,
    ) {
        // auth-kt persists the session on Android by itself, so a restart stays signed in.
        install(Auth)
        install(Postgrest)
    }

    val postgrest get() = client.postgrest

    fun currentUserIdOrNull(): String? = client.auth.currentSessionOrNull()?.user?.id

    val account: Flow<AccountState> = client.auth.sessionStatus.map { status ->
        when (status) {
            is SessionStatus.Authenticated -> AccountState(
                loading = false,
                email = status.session.user?.email,
                userId = status.session.user?.id,
            )

            is SessionStatus.NotAuthenticated -> AccountState(loading = false)
            // Still loading a stored session, or refreshing an expired one.
            else -> AccountState(loading = true)
        }
    }

    suspend fun signIn(email: String, password: String): AuthResult = runCatching {
        client.auth.signInWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        AuthResult.Success
    }.getOrElse { failure(it) }

    suspend fun signUp(email: String, password: String): AuthResult = runCatching {
        val user = client.auth.signUpWith(Email) {
            this.email = email.trim()
            this.password = password
        }
        // A non-null user with no active session means Supabase sent a confirmation mail.
        if (user != null && client.auth.currentSessionOrNull() == null) {
            AuthResult.ConfirmationRequired
        } else {
            AuthResult.Success
        }
    }.getOrElse { failure(it) }

    suspend fun signOut(): AuthResult = runCatching {
        client.auth.signOut()
        AuthResult.Success
    }.getOrElse { failure(it) }

    private fun failure(error: Throwable): AuthResult.Failed {
        Log.e(TAG, "Auth request failed", error)
        return AuthResult.Failed(translateAuthError(error.message))
    }
}
