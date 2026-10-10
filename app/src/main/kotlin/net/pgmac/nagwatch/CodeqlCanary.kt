// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * TEMPORARY, never to be merged: a deliberately broken cipher, committed only to
 * prove that CodeQL reads this project's Kotlin. It is reverted in the next commit.
 */
internal object CodeqlCanary {
    fun weak(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
        return cipher.doFinal(data)
    }
}
