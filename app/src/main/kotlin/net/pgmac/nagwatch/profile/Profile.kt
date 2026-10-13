// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile

/**
 * How often a profile is polled in the background. Android will not run this kind of work more
 * often than the first.
 */
@Suppress("MagicNumber") // The numbers are the definition.
enum class PollInterval(val minutes: Int) {
    FIFTEEN_MINUTES(15),
    THIRTY_MINUTES(30),
    ONE_HOUR(60),
    ;

    companion object {
        /** A number this version does not offer falls back to the shortest: sooner is the safer mistake. */
        fun ofMinutes(minutes: Int): PollInterval = entries.firstOrNull { it.minutes == minutes } ?: FIFTEEN_MINUTES
    }
}

/**
 * What a profile says about background checks and notifications.
 *
 * [interval] is how often the profile is polled in the background, whatever the reason for
 * polling it. The rest is about notifications. The name is from when notifications were the
 * only reason; the stored columns are unchanged.
 */
data class NotificationSettings(
    val enabled: Boolean = false,
    val interval: PollInterval = PollInterval.FIFTEEN_MINUTES,
    /** Off means acknowledged problems and those in downtime are announced as well. */
    val unhandledOnly: Boolean = true,
    /** Whether an outage that may pass by itself is worth a notification. */
    val tellUnreachable: Boolean = true,
)

/**
 * What a profile says about nagiosgraph.
 *
 * On unless switched off: a server without nagiosgraph costs one request, after which nothing
 * more is asked for the rest of the session, and nothing is ever shown about it. [path] is a
 * path on the profile's own server (see `GraphPath`); blank means where Nagios' CGIs are.
 */
data class GraphSettings(val enabled: Boolean = true, val path: String = "")

/**
 * What a quick acknowledgement says when the profile has no words of its own. Nagios requires a
 * comment and a quick action does not stop to ask for one (docs/adr/0012). It is what Nagios
 * keeps and shows to everyone, so it is one fixed text and not a translated one.
 */
const val DEFAULT_ACK_COMMENT = "Acknowledged from Nagwatch"

/**
 * A saved connection to a Nagios instance, as the UI sees it. Carries no
 * secrets: only whether each one is set. Secrets are write-only from the UI's
 * point of view and are never read back for display.
 */
data class Profile(
    val id: Long,
    val name: String,
    val baseUrl: String,
    val username: String,
    val hasPassword: Boolean,
    val accessClientId: String,
    val hasAccessClientSecret: Boolean,
    val customHeaderNames: List<String>,
    val allowCleartext: Boolean,
    val notifications: NotificationSettings = NotificationSettings(),
    /** The app has offered to switch notifications on for this profile. It offers once. */
    val notificationsOffered: Boolean = false,
    val graphs: GraphSettings = GraphSettings(),
    /** The profile's own comment for a quick acknowledgement, as stored. Empty for the default. */
    val ackComment: String = "",
) {
    val isCleartext: Boolean get() = baseUrl.trim().startsWith("http://", ignoreCase = true)

    /** The comment a quick acknowledgement sends: the profile's own, or [DEFAULT_ACK_COMMENT]. */
    val quickAckComment: String get() = ackComment.ifBlank { DEFAULT_ACK_COMMENT }
}

/** What to do with a stored secret when a profile is saved. */
sealed interface SecretInput {
    /** Leave whatever is stored. The UI shows "saved" and never the value. */
    data object Keep : SecretInput

    /** Store this value. Not a data class: it must not print itself. */
    class Replace(val value: String) : SecretInput {
        override fun toString(): String = "Replace(<redacted>)"
    }

    data object Clear : SecretInput
}

data class HeaderDraft(val name: String, val value: SecretInput)

/** A profile as submitted from the editor. [id] is null for a new profile. */
data class ProfileDraft(
    val id: Long?,
    val name: String,
    val baseUrl: String,
    val username: String,
    val password: SecretInput,
    val accessClientId: String,
    val accessClientSecret: SecretInput,
    val customHeaders: List<HeaderDraft>,
    val allowCleartext: Boolean,
    val notifications: NotificationSettings = NotificationSettings(),
    val graphs: GraphSettings = GraphSettings(),
    val ackComment: String = "",
)
