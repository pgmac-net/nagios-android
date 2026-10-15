// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.content.res.Resources
import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import net.pgmac.nagwatch.R
import net.pgmac.nagwatch.nagios.model.Severity

/**
 * The emoji a notification's title starts with, as the person chose (pgmac-net/nagios-android#81).
 * The emoji themselves are in `emoji_sets.xml`, not here.
 */
enum class EmojiSet(val id: String, @StringRes val label: Int, @ArrayRes private val emoji: Int?) {
    NONE("none", R.string.emoji_set_none, null),
    CIRCLES("circles", R.string.emoji_set_circles, R.array.emoji_set_circles),
    SIGNS("signs", R.string.emoji_set_signs, R.array.emoji_set_signs),
    SQUARES("squares", R.string.emoji_set_squares, R.array.emoji_set_squares),
    FACES("faces", R.string.emoji_set_faces, R.array.emoji_set_faces),
    ;

    fun table(resources: Resources): EmojiTable {
        val items = emoji?.let(resources::getStringArray)?.toList() ?: List(EmojiTable.SIZE) { "" }
        return EmojiTable(items)
    }

    companion object {
        val DEFAULT = CIRCLES

        /** A stored id this version does not know (a newer one's, or damaged) is the default, never a crash. */
        fun of(id: String?): EmojiSet = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** One set's seven emoji, empty strings for a set that has none. */
class EmojiTable(private val items: List<String>) {
    init {
        require(items.size == SIZE) { "an emoji set has $SIZE entries, not ${items.size}" }
    }

    fun of(severity: Severity): String = items[
        when (severity) {
            Severity.HOST_DOWN -> 0
            Severity.HOST_UNREACHABLE -> 1
            Severity.CRITICAL -> CRITICAL
            Severity.WARNING -> WARNING
            Severity.UNKNOWN -> UNKNOWN
        },
    ]

    val recovered: String get() = items[RECOVERED]
    val outage: String get() = items[OUTAGE]

    /** The four that matter most, for showing what a set looks like. */
    val preview: String get() = listOf(CRITICAL, WARNING, UNKNOWN, RECOVERED).joinToString(" ") { items[it] }

    companion object {
        const val SIZE = 7

        // Where each is in the set's array in emoji_sets.xml (host down and unreachable are 0 and 1).
        private const val CRITICAL = 2
        private const val WARNING = 3
        private const val UNKNOWN = 4
        private const val RECOVERED = 5
        private const val OUTAGE = 6
    }
}
