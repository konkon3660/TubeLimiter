package com.tubelimiter.app.auth

import android.content.Context
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val TAG = "AuthRepository"

/**
 * Edge Function that deletes the caller's `auth.users` row, which cascades to `daily_usage`,
 * `settings`, `streaks` and `achievements`. It takes no body: the caller is identified purely
 * from the JWT supabase-kt attaches, so no user id ever travels in the request.
 */
private const val DELETE_ACCOUNT_FUNCTION = "delete-account"

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
        // Only used by deleteAccount(); the plugin signs its requests with the current session.
        install(Functions)
    }

    val postgrest get() = client.postgrest

    fun currentUserIdOrNull(): String? = client.auth.currentSessionOrNull()?.user?.id

    /**
     * 저장된 세션의 토큰 갱신이 실패한 상태인지. `sessionStatus`는 StateFlow라 구독 없이
     * 지금 값만 볼 수 있다.
     *
     * 갱신 실패는 [account]에서 `loading = true`로 접히기 때문에 화면상으로는 그냥 "확인 중"과
     * 구별되지 않고, 동기화 쪽에서는 [currentUserIdOrNull]이 null이라 조용한 로그아웃과도
     * 구별되지 않는다. 진단 기록에서 이 둘을 갈라놓으려고 노출한다
     * ([com.tubelimiter.app.sync.SyncRepository] 참고).
     */
    fun sessionRefreshFailed(): Boolean = client.auth.sessionStatus.value is SessionStatus.RefreshFailure

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

    /**
     * Erases the account server-side, then drops the session. Google Play requires this path to
     * exist in-app for any app that has accounts.
     *
     * Deliberately fails closed: unless the Edge Function returns success the session is left
     * alone, so a network blip cannot sign someone out of an account that still exists. The
     * local DataStore wipe is the caller's job — see MainActivity's `onDeleteAccount` — and it
     * only runs on [AuthResult.Success].
     */
    suspend fun deleteAccount(): AuthResult {
        if (client.auth.currentSessionOrNull() == null) {
            return AuthResult.Failed("로그인 상태가 아닙니다.")
        }
        return runCatching {
            // No arguments: the function reads the caller from the JWT this call carries.
            client.functions.invoke(DELETE_ACCOUNT_FUNCTION)
            // The row is gone, so signing out can only fail on the way to a session that is
            // already dead; auth-kt clears the stored session either way, so don't let a
            // throw here report a deletion that actually succeeded as a failure.
            runCatching { client.auth.signOut() }
                .onFailure { Log.w(TAG, "Sign-out after account deletion failed", it) }
            AuthResult.Success
        }.getOrElse { error ->
            Log.e(TAG, "Account deletion failed", error)
            AuthResult.Failed(translateDeleteAccountError(error.message))
        }
    }

    private fun failure(error: Throwable): AuthResult.Failed {
        Log.e(TAG, "Auth request failed", error)
        return AuthResult.Failed(translateAuthError(error.message))
    }
}
