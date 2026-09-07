package com.tubelimiter.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthMessagesTest {

    @Test
    fun `a well-formed pair passes`() {
        assertNull(validateCredentials("me@example.com", "hunter2!"))
    }

    @Test
    fun `an empty email is rejected before the format check`() {
        assertEquals(CredentialError.EmptyEmail, validateCredentials("   ", "hunter2!"))
    }

    @Test
    fun `addresses without a usable domain are rejected`() {
        assertEquals(CredentialError.MalformedEmail, validateCredentials("me", "hunter2!"))
        assertEquals(CredentialError.MalformedEmail, validateCredentials("@example.com", "hunter2!"))
        assertEquals(CredentialError.MalformedEmail, validateCredentials("me@example", "hunter2!"))
        assertEquals(CredentialError.MalformedEmail, validateCredentials("me@@example.com", "hunter2!"))
        assertEquals(CredentialError.MalformedEmail, validateCredentials("me@.com", "hunter2!"))
        assertEquals(CredentialError.MalformedEmail, validateCredentials("me @example.com", "hunter2!"))
    }

    @Test
    fun `passwords shorter than six characters are rejected`() {
        assertEquals(CredentialError.ShortPassword, validateCredentials("me@example.com", "12345"))
        assertNull(validateCredentials("me@example.com", "123456"))
    }

    @Test
    fun `the email check runs before the password check`() {
        assertEquals(CredentialError.MalformedEmail, validateCredentials("nope", "1"))
    }

    @Test
    fun `known Supabase errors are translated`() {
        assertEquals(
            "이메일 또는 비밀번호가 올바르지 않습니다.",
            translateAuthError("Invalid login credentials"),
        )
        assertEquals(
            "이미 가입된 이메일입니다. 로그인해주세요.",
            translateAuthError("User already registered"),
        )
        assertEquals(
            "가입 확인 메일의 링크를 먼저 눌러주세요.",
            translateAuthError("Email not confirmed"),
        )
    }

    @Test
    fun `an unknown message is passed through untouched`() {
        assertEquals("Something odd happened", translateAuthError("Something odd happened"))
    }

    @Test
    fun `a missing message falls back to a generic line`() {
        assertEquals("요청 중 오류가 발생했습니다.", translateAuthError(null))
        assertEquals("요청 중 오류가 발생했습니다.", translateAuthError("  "))
    }

    @Test
    fun `a rejected session while deleting reads as an expired login`() {
        assertEquals(
            "로그인이 만료되었습니다. 다시 로그인한 뒤 시도해주세요.",
            translateDeleteAccountError("invalid JWT"),
        )
        assertEquals(
            "로그인이 만료되었습니다. 다시 로그인한 뒤 시도해주세요.",
            translateDeleteAccountError("JWT expired"),
        )
        assertEquals(
            "로그인이 만료되었습니다. 다시 로그인한 뒤 시도해주세요.",
            translateDeleteAccountError("Missing authorization header"),
        )
    }

    @Test
    fun `a missing Edge Function reads as try again later`() {
        assertEquals(
            "지금은 탈퇴를 처리할 수 없습니다. 잠시 뒤 다시 시도해주세요.",
            translateDeleteAccountError("Requested function was not found"),
        )
    }

    @Test
    fun `RestException noise around the body still matches`() {
        // RestException.message appends the URL, headers and method to the server's body.
        val raw = """
            {"error":"invalid JWT"}
            URL: https://example.supabase.co/functions/v1/delete-account
            Headers: []
            Http Method: POST
        """.trimIndent()
        assertEquals("로그인이 만료되었습니다. 다시 로그인한 뒤 시도해주세요.", translateDeleteAccountError(raw))
    }

    @Test
    fun `delete failures fall through to the shared auth mapping`() {
        assertEquals("네트워크에 연결할 수 없습니다.", translateDeleteAccountError("Unable to resolve host"))
        assertEquals(
            "요청이 너무 잦습니다. 잠시 뒤 다시 시도해주세요.",
            translateDeleteAccountError("Request rate limit reached"),
        )
        assertEquals("Something odd happened", translateDeleteAccountError("Something odd happened"))
        assertEquals("요청 중 오류가 발생했습니다.", translateDeleteAccountError(null))
    }
}
