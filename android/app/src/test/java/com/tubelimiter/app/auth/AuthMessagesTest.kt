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
}
