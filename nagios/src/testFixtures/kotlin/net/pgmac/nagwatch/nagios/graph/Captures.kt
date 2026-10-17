// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.graph

/** Real answers from `showgraph.cgi`, in `/graphs`. See that directory's README. */
object Captures {
    fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/graphs/$name")) { "missing capture $name" }.use { it.readBytes() }
}

/** The first bytes of a PNG that says it is [width] by [height], and nothing a decoder could use. */
@Suppress("MagicNumber") // The numbers are the definition: a PNG's signature and header.
fun png(width: Int, height: Int): ByteArray {
    fun int(value: Int) = ByteArray(4) { index -> (value ushr (8 * (3 - index))).toByte() }
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val chunk = byteArrayOf(0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52)
    return signature + chunk + int(width) + int(height) + byteArrayOf(8, 2, 0, 0, 0)
}
