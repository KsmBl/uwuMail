package de.uwumail.mail.oauth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class OAuthClientTest {

    private val client = OAuthClient()

    @Test
    fun `code challenge is the base64url sha256 of the verifier`() {
        val pkce = client.createPkce()
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(pkce.verifier.toByteArray(Charsets.US_ASCII))
        )
        assertEquals(expected, pkce.challenge)
    }

    @Test
    fun `pkce values are url safe and unpadded`() {
        val pkce = client.createPkce()
        // RFC 7636 restricts the verifier to unreserved characters.
        val allowed = Regex("^[A-Za-z0-9._~-]+$")
        assertTrue("verifier not url-safe: ${pkce.verifier}", allowed.matches(pkce.verifier))
        assertTrue("challenge not url-safe: ${pkce.challenge}", allowed.matches(pkce.challenge))
        assertTrue(!pkce.verifier.contains('='))
        assertTrue(!pkce.challenge.contains('='))
    }

    @Test
    fun `verifier length is within the spec range`() {
        val verifier = client.createPkce().verifier
        assertTrue("length ${verifier.length}", verifier.length in 43..128)
    }

    @Test
    fun `each sign-in gets fresh pkce and state`() {
        assertNotEquals(client.createPkce().verifier, client.createPkce().verifier)
        assertNotEquals(client.randomState(), client.randomState())
    }

    @Test
    fun `parses a token response and sets an expiry in the future`() {
        val before = System.currentTimeMillis()
        val tokens = client.parseTokens(
            JSONObject()
                .put("access_token", "ya29.token")
                .put("refresh_token", "1//refresh")
                .put("expires_in", 3599)
                .put("id_token", "header.payload.sig")
        )
        assertEquals("ya29.token", tokens.accessToken)
        assertEquals("1//refresh", tokens.refreshToken)
        assertTrue(tokens.expiresAtMillis > before)
        // Refreshed a minute early so a request cannot race the expiry.
        assertTrue(tokens.expiresAtMillis <= before + 3599_000L - 59_000L)
    }

    @Test
    fun `a response without a refresh token keeps the rest`() {
        val tokens = client.parseTokens(
            JSONObject().put("access_token", "ya29.token").put("expires_in", 3600)
        )
        assertEquals("ya29.token", tokens.accessToken)
        assertNull(tokens.refreshToken)
    }

    @Test(expected = OAuthException::class)
    fun `a response without an access token is rejected`() {
        client.parseTokens(JSONObject().put("expires_in", 3600))
    }

    @Test
    fun `reads the address out of an id token`() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"email":"someone@gmail.com","email_verified":true}""".toByteArray()
        )
        assertEquals("someone@gmail.com", client.emailFromIdToken("header.$payload.signature"))
    }

    @Test
    fun `malformed id tokens do not throw`() {
        assertNull(client.emailFromIdToken(null))
        assertNull(client.emailFromIdToken("not-a-jwt"))
        assertNull(client.emailFromIdToken("header.!!!notbase64!!!.sig"))
    }

    @Test
    fun `expiry check reflects the stored deadline`() {
        val now = System.currentTimeMillis()
        assertTrue(TokenSet("a", null, now - 1).isExpired(now))
        assertTrue(!TokenSet("a", null, now + 60_000).isExpired(now))
    }

    @Test
    fun `google provider carries the full mail scope`() {
        assertTrue(OAuthProvider.GOOGLE.scopes.contains("https://mail.google.com/"))
        // Without offline access Google never issues a refresh token.
        assertEquals("offline", OAuthProvider.GOOGLE.extraAuthParams["access_type"])
    }

    @Test
    fun `provider is suggested from the address domain`() {
        assertEquals(OAuthProvider.GOOGLE, OAuthProvider.forEmail("someone@gmail.com"))
        assertEquals(OAuthProvider.GOOGLE, OAuthProvider.forEmail("someone@googlemail.com"))
        assertNull(OAuthProvider.forEmail("me@synthelicz.de"))
    }
}
