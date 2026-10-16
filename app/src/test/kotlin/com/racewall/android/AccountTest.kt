package com.racewall.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.racewall.account.Jwk
import com.racewall.android.account.Account
import com.racewall.android.account.AccountStatus
import com.racewall.android.account.TokenCrypto
import com.racewall.android.ui.AccountSection
import com.racewall.android.ui.RaceWallTheme
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stored sign-in: kept encrypted, dropped when expired, refused when F1 did not sign it. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w1280dp-h800dp-land-xhdpi")
class AccountTest {
    @get:Rule
    val compose = createComposeRule()

    /** Reverses the bytes: enough to show the file never holds the token as written. */
    private val crypto = object : TokenCrypto {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    private fun token(exp: Long): Pair<String, Jwk> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val head = b64("""{"alg":"RS256","kid":"k"}""".toByteArray())
        val body = b64("""{"exp":$exp,"SubscribedProduct":"F1 TV Pro"}""".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(pair.private); update("$head.$body".toByteArray()); sign() }
        val pub = pair.public as RSAPublicKey
        return "$head.$body.${b64(sig)}" to Jwk("k", b64(pub.modulus.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()), b64(pub.publicExponent.toByteArray()))
    }

    private val dir = Files.createTempDirectory("racewall-account").toFile()
    private val soon = System.currentTimeMillis() / 1000 + 3600

    @Test
    fun `keeps a token F1 signed, encrypted, and reads it back on the next start`() = runTest {
        val (t, key) = token(soon)
        val a = Account(dir, crypto, keys = { listOf(key) })
        assertTrue(a.accept(t).signedIn)
        assertEquals("F1 TV Pro", a.status.value.product)
        assertFalse(String(dir.resolve("f1-token.bin").readBytes()).contains(t))
        val again = Account(dir, crypto, keys = { listOf(key) }).apply { load() }
        assertEquals(t, again.current())
    }

    @Test
    fun `refuses a token signed with another key`() = runTest {
        val (t, _) = token(soon)
        val (_, other) = token(soon)
        val s = Account(dir, crypto, keys = { listOf(other, other.copy(kid = "x")) }).accept(t)
        assertFalse(s.signedIn)
        assertTrue(s.problem.startsWith("Signed in, but the subscription token could not be checked"))
    }

    @Test
    fun `drops an expired token and says to sign in again`() = runTest {
        val (t, key) = token(soon)
        Account(dir, crypto, keys = { listOf(key) }).accept(t)
        val later = Account(dir, crypto, keys = { listOf(key) }, now = { (soon + 60) * 1000 }).apply { load() }
        assertNull(later.current())
        assertEquals("Your F1 sign-in expired. Sign in again.", later.status.value.problem)
        assertFalse(dir.resolve("f1-token.bin").exists())
    }

    @Test
    fun `sign out forgets the token`() = runTest {
        val (t, key) = token(soon)
        val a = Account(dir, crypto, keys = { listOf(key) })
        a.accept(t)
        a.signOut()
        assertNull(a.current())
        assertFalse(dir.resolve("f1-token.bin").exists())
    }

    @Test
    fun `the settings section offers sign in, and sign out once signed in`() {
        var signIn = 0
        var signOut = 0
        var status by androidx.compose.runtime.mutableStateOf(AccountStatus())
        compose.setContent { RaceWallTheme { androidx.compose.foundation.layout.Column { AccountSection(status, false, { signIn++ }, { signOut++ }) } } }
        compose.onNodeWithText("Not signed in").assertExists()
        compose.onNodeWithContentDescription("Sign in").performClick()
        assertEquals(1, signIn)
        status = AccountStatus(signedIn = true, product = "F1 TV Pro", expires = soon * 1000)
        compose.onNodeWithText("Signed in · F1 TV Pro").assertExists()
        compose.onNodeWithContentDescription("Sign out").performClick()
        assertEquals(1, signOut)
    }
}

private operator fun <T> androidx.compose.runtime.MutableState<T>.getValue(thisObj: Any?, property: kotlin.reflect.KProperty<*>): T = value
private operator fun <T> androidx.compose.runtime.MutableState<T>.setValue(thisObj: Any?, property: kotlin.reflect.KProperty<*>, v: T) {
    value = v
}
