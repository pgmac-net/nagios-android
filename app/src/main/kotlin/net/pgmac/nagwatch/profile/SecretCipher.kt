// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Where the key that protects stored secrets comes from. */
fun interface SecretKeySource {
    /** The key, created on first use. Throws [GeneralSecurityException] if it cannot be had. */
    fun key(): SecretKey
}

/**
 * A 256-bit AES key held in the Android Keystore. It never leaves secure
 * hardware (or the Keystore process), cannot be exported, and is gone when the
 * app is uninstalled or the device is restored from backup. Losing it loses
 * the stored secrets, which is the intended failure: the user re-enters them.
 */
@Singleton
class KeystoreKeySource @Inject constructor() : SecretKeySource {
    override fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "nagwatch.profile-secrets.v1"
        const val KEY_BITS = 256
    }
}

/**
 * Encrypts the secrets stored with a profile: password, Cloudflare Access
 * client secret, custom header values.
 *
 * AES-256-GCM, a fresh random nonce per value, authenticated: a stored value
 * that has been altered, or that was written under a key that no longer
 * exists, fails to decrypt rather than yielding garbage.
 *
 * Stored form: `v1:` + Base64(nonce || ciphertext || tag).
 */
@Singleton
class SecretCipher @Inject constructor(private val keySource: SecretKeySource) {
    /** @throws GeneralSecurityException if the key cannot be obtained; nothing is stored in that case. */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The provider picks the nonce; the Keystore refuses caller-supplied ones.
        cipher.init(Cipher.ENCRYPT_MODE, keySource.key())
        val nonce = cipher.iv
        check(nonce.size == NONCE_BYTES) { "unexpected GCM nonce length ${nonce.size}" }
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(nonce + sealed)
    }

    /**
     * @return the secret, or null if it cannot be recovered: the key is gone,
     *   the value was tampered with, or it is not something this class wrote.
     *   Callers treat null as "the user must enter this again".
     */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return try {
            val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            require(bytes.size > NONCE_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keySource.key(), GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
            String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
