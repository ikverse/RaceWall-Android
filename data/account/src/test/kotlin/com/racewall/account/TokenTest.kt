package com.racewall.account

import java.net.URLEncoder
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ported from the desktop's app/main/account/token.test.ts. */
class TokenTest {
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun b64url(s: String) = b64url(s.toByteArray())

    /** A token shaped like F1's, signed with a key made for this test. */
    private fun makeToken(payload: String, kid: String = "test-key"): Pair<String, Jwk> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val head = b64url("""{"alg":"RS256","typ":"JWT","kid":"$kid"}""")
        val body = b64url(payload)
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update("$head.$body".toByteArray())
            sign()
        }
        val pub = pair.public as RSAPublicKey
        val jwk = Jwk(kid, b64url(pub.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()), b64url(pub.publicExponent.toByteArray()))
        return "$head.$body.${b64url(sig)}" to jwk
    }

    private val future = System.currentTimeMillis() / 1000 + 3600

    @Test
    fun `accepts a correctly signed, unexpired token and reads the subscription`() {
        val (token, jwk) = makeToken("""{"exp":$future,"SubscriptionStatus":"active","SubscribedProduct":"F1 TV Pro"}""")
        assertEquals(TokenClaims(future * 1000, "active", "F1 TV Pro"), Token.verify(token, listOf(jwk)))
        assertEquals("F1 TV Pro", Token.claims(token).product)
    }

    @Test
    fun `refuses a token whose payload was changed after signing`() {
        val (token, jwk) = makeToken("""{"exp":$future,"SubscribedProduct":"F1 TV Access"}""")
        val (h, _, s) = token.split('.')
        val forged = "$h.${b64url("""{"exp":$future,"SubscribedProduct":"F1 TV Premium"}""")}.$s"
        assertEquals("signature does not match", assertFailsWith<TokenException> { Token.verify(forged, listOf(jwk)) }.message)
    }

    @Test
    fun `refuses a token signed with a key F1 does not publish, or an expired one`() {
        val (a, _) = makeToken("""{"exp":$future}""", "a")
        val (_, b) = makeToken("""{"exp":$future}""", "b")
        val e = assertFailsWith<TokenException> { Token.verify(a, listOf(b, b.copy(kid = "c"))) }
        assertTrue(e.message!!.startsWith("signed with a key"))
        val (old, oldKey) = makeToken("""{"exp":1000}""")
        assertEquals("expired", assertFailsWith<TokenException> { Token.verify(old, listOf(oldKey)) }.message)
    }

    @Test
    fun `finds the token in the login-session cookie, URL-encoded or not`() {
        val (token, _) = makeToken("""{"exp":$future}""")
        val json = """{"data":{"subscriptionToken":"$token","subscriptionStatus":"active"}}"""
        assertEquals(token, Token.fromCookie(URLEncoder.encode(json, "UTF-8").replace("+", "%20")))
        assertEquals(token, Token.fromCookie(json))
        assertNull(Token.fromCookie("something-else"))
    }

    @Test
    fun `reads F1's key list`() {
        val keys = Token.keys("""{"keys":[{"kty":"RSA","kid":"k1","n":"abc","e":"AQAB","use":"sig"},{"kty":"oct"}]}""")
        assertEquals(listOf(Jwk("k1", "abc", "AQAB")), keys)
    }
}
