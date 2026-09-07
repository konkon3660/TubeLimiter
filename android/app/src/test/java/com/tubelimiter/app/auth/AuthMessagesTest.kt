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
            AuthError.Known(AuthMessage.INVALID_CREDENTIALS),
            translateAuthError("Invalid login credentials"),
        )
        assertEquals(
            AuthError.Known(AuthMessage.ALREADY_REGISTERED),
            translateAuthError("User already registered"),
        )
        assertEquals(
            AuthError.Known(AuthMessage.EMAIL_NOT_CONFIRMED),
            translateAuthError("Email not confirmed"),
        )
    }

    @Test
    fun `an unknown message is passed through untouched`() {
        assertEquals(
            AuthError.Unknown("Something odd happened"),
            translateAuthError("Something odd happened"),
        )
    }

    @Test
    fun `a missing message falls back to a generic line`() {
        assertEquals(AuthError.Known(AuthMessage.GENERIC_FAILURE), translateAuthError(null))
        assertEquals(AuthError.Known(AuthMessage.GENERIC_FAILURE), translateAuthError("  "))
    }

    @Test
    fun `a rejected session while deleting reads as an expired login`() {
        assertEquals(
            AuthError.Known(AuthMessage.SESSION_EXPIRED),
            translateDeleteAccountError("invalid JWT"),
        )
        assertEquals(
            AuthError.Known(AuthMessage.SESSION_EXPIRED),
            translateDeleteAccountError("JWT expired"),
        )
        assertEquals(
            AuthError.Known(AuthMessage.SESSION_EXPIRED),
            translateDeleteAccountError("Missing authorization header"),
        )
    }

    @Test
    fun `a missing Edge Function reads as try again later`() {
        assertEquals(
            AuthError.Known(AuthMessage.DELETE_UNAVAILABLE),
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
        assertEquals(AuthError.Known(AuthMessage.SESSION_EXPIRED), translateDeleteAccountError(raw))
    }

    @Test
    fun `delete failures fall through to the shared auth mapping`() {
        assertEquals(
            AuthError.Known(AuthMessage.NETWORK_UNREACHABLE),
            translateDeleteAccountError("Unable to resolve host"),
        )
        assertEquals(
            AuthError.Known(AuthMessage.RATE_LIMITED),
            translateDeleteAccountError("Request rate limit reached"),
        )
        assertEquals(
            AuthError.Unknown("Something odd happened"),
            translateDeleteAccountError("Something odd happened"),
        )
        assertEquals(AuthError.Known(AuthMessage.GENERIC_FAILURE), translateDeleteAccountError(null))
    }
}
