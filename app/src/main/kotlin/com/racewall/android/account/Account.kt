package com.racewall.android.account

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.racewall.account.Jwk
import com.racewall.account.Token
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** formula1.com's own sign-in page, which sends you back to formula1.com once signed in. */
const val LOGIN_URL = "https://account.formula1.com/#/en/login?redirect=https%3A%2F%2Fwww.formula1.com%2F"

data class AccountStatus(
    val signedIn: Boolean = false,
    val product: String = "",
    /** UTC milliseconds. */
    val expires: Long? = null,
    /** Why the last sign-in or check failed, if it did. */
    val problem: String = "",
)

/** Encrypts the stored token. The app uses the Android Keystore; tests pass a plain one. */
interface TokenCrypto {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
}

/** AES-GCM with a key that never leaves the Android Keystore. */
class KeystoreCrypto(private val alias: String = "racewall-f1-token") : TokenCrypto {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        return byteArrayOf(iv.size.toByte()) + iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray {
        val n = sealed[0].toInt()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 1, n))
        }
        return cipher.doFinal(sealed, 1 + n, sealed.size - 1 - n)
    }
}

/**
 * Signing in with an F1 account, as the desktop's app/main/account/account.ts. You type your
 * details on formula1.com's own page, never into RaceWall. Once signed in, RaceWall reads the
 * subscription token from formula1.com's cookie, checks its signature, and keeps it encrypted.
 */
class Account(
    dir: File,
    private val crypto: TokenCrypto = KeystoreCrypto(),
    private val keys: suspend () -> List<Jwk> = { Token.fetchKeys() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val file = File(dir, "f1-token.bin")
    private var token: String? = null
    private var problem = ""
    private val _status = MutableStateFlow(AccountStatus())
    val status: StateFlow<AccountStatus> = _status.asStateFlow()

    /** Reads the stored token, dropping it if it has expired. */
    fun load() {
        if (!file.isFile) return
        try {
            val stored = String(crypto.decrypt(file.readBytes()))
            val expires = Token.claims(stored).expires
            if (expires != null && expires < now()) {
                problem = "Your F1 sign-in expired. Sign in again."
                file.delete()
            } else token = stored
        } catch (_: Exception) {
            file.delete()
        }
        publish()
    }

    /** For RaceWall's own requests only. */
    fun current(): String? {
        val t = token ?: return null
        val exp = runCatching { Token.claims(t).expires }.getOrNull()
        if (exp != null && exp < now()) {
            token = null
            problem = "Your F1 sign-in expired. Sign in again."
            file.delete()
            publish()
            return null
        }
        return t
    }

    /** Checks a token found in formula1.com's cookie and keeps it if F1 signed it. */
    suspend fun accept(found: String): AccountStatus {
        try {
            Token.verify(found, keys(), now())
            token = found
            problem = ""
            file.parentFile?.mkdirs()
            file.writeBytes(crypto.encrypt(found.toByteArray()))
        } catch (e: Exception) {
            problem = "Signed in, but the subscription token could not be checked: ${e.message}"
        }
        publish()
        return _status.value
    }

    /** Forgets the token; the caller also clears formula1.com's cookies. */
    fun signOut() {
        file.delete()
        token = null
        problem = ""
        publish()
    }

    private fun publish() {
        val c = token?.let { runCatching { Token.claims(it) }.getOrNull() }
        _status.value = AccountStatus(token != null, c?.product ?: "", c?.expires, problem)
    }
}
