// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.model

import java.time.Duration
import java.time.Instant

/** What a host or service is, as a key: a host has no [description]. */
data class ObjectRef(val hostName: String, val description: String? = null) {
    val isHost: Boolean get() = description == null
}

/** A note attached to a host or service, written by a person or by Nagios itself. */
data class Comment(
    val id: Long,
    val kind: Kind,
    val author: String,
    val text: String,
    val enteredAt: Instant?,
    val persistent: Boolean,
    /** When Nagios will remove it, if it expires. */
    val expiresAt: Instant?,
) {
    enum class Kind {
        /** Written by a person. */
        USER,

        /** Left by acknowledging a problem: the reason someone gave. */
        ACKNOWLEDGEMENT,

        /** Added by Nagios when a downtime was scheduled. */
        DOWNTIME,

        /** Added by Nagios while the object is flapping. */
        FLAPPING,
        OTHER,
    }
}

/** A scheduled window in which a host or service is expected to be down. */
data class Downtime(
    val id: Long,
    val author: String,
    val comment: String,
    val start: Instant?,
    val end: Instant?,
    /** False for a flexible downtime, which starts when the problem does and lasts [duration]. */
    val fixed: Boolean,
    val duration: Duration?,
    val inEffect: Boolean,
)
