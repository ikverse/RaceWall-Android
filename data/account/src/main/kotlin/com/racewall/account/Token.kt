package com.racewall.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigInteger
import java.net.URLDecoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * The F1 TV subscription token: a signed JWT that formula1.com keeps in its login-session cookie
 * after you sign in. RaceWall checks its signature against F1's published keys before trusting it,
 * as the desktop app does (app/main/account/token.ts).
 */
const val JWKS_URL = "https://api.formula1.com/static/jwks.json"

data class TokenClaims(
    /** Expiry, UTC milliseconds. */
    val expires: Long?,
    /** "Active", for example. */
    val status: String,
    /** "F1 TV Pro", for example. */
    val product: String,
)

/** One of F1's published RSA keys, as a JSON Web Key. */
data class Jwk(val kid: String?, val n: String, val e: String)

class TokenException(message: String) : Exception(message)

object Token {
    private val json = Json { ignoreUnknownKeys = true }

    private fun b64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))

    fun decode(token: String): Pair<JsonObject, JsonObject> {
        val parts = token.split('.')
        if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) throw TokenException("not a JWT")
        return try {
            json.parseToJsonElement(String(b64url(parts[0]))).jsonObject to json.parseToJsonElement(String(b64url(parts[1]))).jsonObject
        } catch (e: Exception) {
            throw TokenException("not a JWT")
        }
    }

    fun claims(token: String): TokenClaims {
        val (_, payload) = decode(token)
        val exp = (payload["exp"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        return TokenClaims(
            expires = exp?.let { (it * 1000).toLong() },
            status = text(payload["SubscriptionStatus"]),
            product = text(payload["SubscribedProduct"]),
        )
    }

    private fun text(e: JsonElement?): String = (e as? JsonPrimitive)?.contentOrNull ?: ""

    /** Throws unless the token is signed by one of `keys` (RS256) and has not expired. */
    fun verify(token: String, keys: List<Jwk>, now: Long = System.currentTimeMillis()): TokenClaims {
        val parts = token.split('.')
        if (parts.size != 3) throw TokenException("not a JWT")
        val (header, _) = decode(token)
        val alg = text(header["alg"])
        if (alg != "RS256") throw TokenException("unexpected signature algorithm $alg")
        val kid = text(header["kid"])
        val jwk = keys.firstOrNull { it.kid == kid } ?: keys.singleOrNull() ?: throw TokenException("signed with a key F1 does not publish")
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(BigInteger(1, b64url(jwk.n)), BigInteger(1, b64url(jwk.e))))
        val ok = Signature.getInstance("SHA256withRSA").run {
            initVerify(key)
            update("${parts[0]}.${parts[1]}".toByteArray())
            try {
                verify(b64url(parts[2]))
            } catch (_: Exception) {
                false
            }
        }
        if (!ok) throw TokenException("signature does not match")
        val c = claims(token)
        if (c.expires != null && c.expires < now) throw TokenException("expired")
        return c
    }

    /** The subscription token inside a formula1.com cookie value, if there is one. */
    fun fromCookie(value: String): String? {
        val decoded = try {
            URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            value
        }
        for (candidate in listOf(value, decoded)) {
            try {
                val data = json.parseToJsonElement(candidate).jsonObject["data"]?.jsonObject ?: continue
                val token = text(data["subscriptionToken"])
                if (token.split('.').size == 3) return token
            } catch (_: Exception) {
                // not JSON
            }
        }
        return null
    }

    /** Parses F1's key list. */
    fun keys(text: String): List<Jwk> = json.parseToJsonElement(text).jsonObject["keys"]?.jsonArray.orEmpty().mapNotNull { k ->
        val o = k.jsonObject
        val n = (o["n"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        val e = (o["e"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        Jwk((o["kid"] as? JsonPrimitive)?.contentOrNull, n, e)
    }

    /** Downloads F1's published keys. */
    suspend fun fetchKeys(client: OkHttpClient = OkHttpClient()): List<Jwk> = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(JWKS_URL).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("F1's key list answered ${res.code}")
            keys(res.body.string())
        }
    }
}
