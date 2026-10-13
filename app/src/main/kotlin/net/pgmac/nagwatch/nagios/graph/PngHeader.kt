// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.graph

/**
 * How big a PNG says it is, read from its first bytes and nothing else.
 *
 * This is read before anything is handed to a decoder. The bytes come from the network, and a
 * decoder asked to open a few kilobytes that claim to be 30,000 pixels square will try to find
 * the memory for it.
 */
data class PngHeader(val width: Int, val height: Int) {
    companion object {
        /** The signature, then the first chunk's length and name, then the two sizes. */
        const val LENGTH = 24

        private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val FIRST_CHUNK = byteArrayOf(0, 0, 0, IHDR_LENGTH, 0x49, 0x48, 0x44, 0x52)
        private const val IHDR_LENGTH: Byte = 13
        private const val WIDTH_AT = 16
        private const val HEIGHT_AT = 20
        private const val BYTE = 0xFF
        private const val BITS = 8
        private const val INT_BYTES = 4

        /** Null for anything that is not the start of a PNG with a size a PNG may have. */
        fun read(bytes: ByteArray): PngHeader? {
            if (bytes.size < LENGTH) return null
            val starts = SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] } &&
                FIRST_CHUNK.indices.all { bytes[SIGNATURE.size + it] == FIRST_CHUNK[it] }
            val width = int(bytes, WIDTH_AT)
            val height = int(bytes, HEIGHT_AT)
            // A size is four bytes and may not be zero or have its top bit set, which is what
            // a negative number here is.
            return if (starts && width > 0 && height > 0) PngHeader(width, height) else null
        }

        private fun int(bytes: ByteArray, at: Int): Int =
            (0 until INT_BYTES).fold(0) { value, index -> (value shl BITS) or (bytes[at + index].toInt() and BYTE) }
    }
}
