// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cipher itself, with a software key. The Android Keystore key source is a
 * thin wrapper that cannot run on the JVM; what is tested here is everything
 * that could be got wrong in our own code.
 */
class SecretCipherTest {
    private val cipher = SecretCipher(softwareKeySource())

    @Test
    fun `a secret survives a round trip`() {
        val secret = "correct horse battery staple ✓ \"quoted\" \n newline"

        assertEquals(secret, cipher.decrypt(cipher.encrypt(secret)))
    }

    @Test
    fun `the stored form does not contain the secret`() {
        val stored = cipher.encrypt("hunter2-hunter2")

        assertFalse(stored.contains("hunter2"))
        assertFalse(
            String(Base64.getDecoder().decode(stored.removePrefix("v1:")), Charsets.ISO_8859_1).contains("hunter2"),
        )
        assertTrue(stored.startsWith("v1:"))
    }

    @Test
    fun `encrypting the same secret twice gives different output`() {
        // A fresh nonce each time: equal ciphertexts would reveal equal passwords.
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `a tampered value fails to decrypt instead of yielding something else`() {
        val stored = cipher.encrypt("secret")
        val bytes = Base64.getDecoder().decode(stored.removePrefix("v1:"))
        bytes[bytes.size - 1] = (bytes.last().toInt() xor 0x01).toByte()

        assertNull(cipher.decrypt("v1:" + Base64.getEncoder().encodeToString(bytes)))
    }

    @Test
    fun `a value written under a key that no longer exists cannot be read`() {
        // What a device restore does: the row survives, the Keystore key does not.
        val stored = cipher.encrypt("secret")

        assertNull(SecretCipher(softwareKeySource()).decrypt(stored))
    }

    @Test
    fun `things this class did not write are refused`() {
        listOf("", "plaintext-password", "v1:", "v1:not base64 !!", "v1:AAAA", "v2:AAAAAAAAAAAAAAAAAAAAAAAA").forEach {
            assertNull("decrypt(\"$it\")", cipher.decrypt(it))
        }
    }

    @Test
    fun `a key that cannot be obtained makes decryption fail softly`() {
        val stored = cipher.encrypt("secret")
        val broken = SecretCipher { throw GeneralSecurityException("keystore unavailable") }

        assertNull(broken.decrypt(stored))
    }

    @Test
    fun `a key that cannot be obtained makes encryption fail loudly, so nothing is stored unprotected`() {
        val broken = SecretCipher { throw GeneralSecurityException("keystore unavailable") }

        assertThrows(GeneralSecurityException::class.java) { broken.encrypt("secret") }
    }

    @Test
    fun `an empty secret round trips`() {
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
    }
}

/** A fresh in-memory AES-256 key, standing in for the Android Keystore. */
internal fun softwareKeySource(): SecretKeySource {
    val key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    return SecretKeySource { key }
}
