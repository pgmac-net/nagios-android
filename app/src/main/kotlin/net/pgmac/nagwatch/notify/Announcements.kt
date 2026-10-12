// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.model.HostProblem
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.Problem
import net.pgmac.nagwatch.nagios.model.ProblemReport
import net.pgmac.nagwatch.nagios.model.ServiceProblem
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.status.StatusError

/** What a profile's settings say about notifying. */
data class NotifySettings(
    /** Off means acknowledged problems and those in downtime are announced as well. */
    val unhandledOnly: Boolean = true,
    /** Whether an outage that may pass by itself is worth a notification. A LAN-only Nagios is why it can be off. */
    val tellUnreachable: Boolean = true,
)

/** One background poll, as far as announcing is concerned. */
sealed interface PollOutcome {
    data class Polled(val report: ProblemReport) : PollOutcome

    data class Failed(val error: StatusError) : PollOutcome
}

/**
 * A problem the app knows about, as it last stood.
 *
 * [individually] is whether the person was told about it by a notification of its own. Only
 * those have a notification to bring up to date or to turn into a recovery; a problem that
 * was part of a summary has neither.
 */
data class Known(val severity: Severity, val individually: Boolean)

/**
 * What the app remembers having said about one profile. This is what stops it repeating itself
 * every poll, and what lets it say "recovered" about the right things.
 *
 * It records what was *announced*, not what is still showing: a notification swiped away is
 * still announced, so swiping never causes a repeat.
 */
data class AnnouncedState(
    /** False until one poll has succeeded. The first that does is summarised, not itemised. */
    val initialised: Boolean = false,
    val known: Map<ObjectRef, Known> = emptyMap(),
    /** Failed polls in a row. */
    val failures: Int = 0,
    /** The outage the person has been told about and not yet told is over. */
    val outage: OutageCause? = null,
)

/**
 * Why a poll failed, as far as it changes what is said and when.
 *
 * [mayPass] is the line that matters. A failure that may pass by itself (no answer, a café's
 * captive portal) is worth a notification only when it has lasted. One that will not (a
 * rejected password) is worth one at once: alerts have stopped, and waiting will not help.
 */
enum class OutageCause(val mayPass: Boolean) {
    UNREACHABLE(mayPass = true),
    CERTIFICATE(mayPass = true),
    WRONG_ANSWER(mayPass = true),
    SERVER_ERROR(mayPass = true),

    /** Android 17's local network protection, probably. An inference from a timeout (ADR 0007). */
    LOCAL_NETWORK(mayPass = true),
    CREDENTIALS_REJECTED(mayPass = false),
    ACCESS_REJECTED(mayPass = false),
    NOT_ALLOWED(mayPass = false),
    CREDENTIALS_UNREADABLE(mayPass = false),
    MISCONFIGURED(mayPass = false),
    ;

    companion object {
        /** Null for a profile that no longer exists: there is nobody to tell and nothing to remember. */
        fun of(error: StatusError): OutageCause? = when (error) {
            StatusError.ProfileMissing -> null
            StatusError.CredentialsUnavailable -> CREDENTIALS_UNREADABLE
            StatusError.InvalidUrl -> MISCONFIGURED
            is StatusError.Nagios -> of(error.error)
        }

        private fun of(error: NagiosError): OutageCause = when (error) {
            NagiosError.BadCredentials -> CREDENTIALS_REJECTED

            NagiosError.AccessRejected -> ACCESS_REJECTED

            NagiosError.Forbidden -> NOT_ALLOWED

            NagiosError.CleartextRefused, NagiosError.AccessOverCleartext -> MISCONFIGURED

            NagiosError.LocalNetworkBlocked -> LOCAL_NETWORK

            is NagiosError.Unreachable -> UNREACHABLE

            is NagiosError.Certificate -> CERTIFICATE

            // A redirect or a page that is not Nagios is what a captive portal looks like from here.
            NagiosError.NotNagios, is NagiosError.Redirected -> WRONG_ANSWER

            is NagiosError.Http, is NagiosError.Api, NagiosError.ResponseTooLarge -> SERVER_ERROR
        }
    }
}

/** Something to put in front of the person. How it looks is not decided here. */
sealed interface Notice {
    /**
     * A problem with a notification of its own.
     *
     * @param change [Change.UPDATED] brings a showing notification up to date without interrupting.
     */
    data class Raised(val ref: ObjectRef, val problem: Problem, val change: Change) : Notice

    enum class Change { NEW, WORSE, UPDATED }

    /** Takes the place of the problem's own notification. Never interrupts. */
    data class Recovered(val ref: ObjectRef) : Notice

    /**
     * Stands in for many notifications at once.
     *
     * @param first true for the first successful poll (everything there is); false when a later
     *   poll brought more changes than are worth listing one by one.
     */
    data class Summary(val count: Int, val first: Boolean) : Notice

    /**
     * Nagios could not be polled.
     *
     * @param quiet true when the person already knows of an outage and only its cause has changed.
     */
    data class Outage(val cause: OutageCause, val error: StatusError, val quiet: Boolean) : Notice
}

/** What one poll calls for: what to show, what to take down, and what to remember from now on. */
data class Decision(
    val post: List<Notice> = emptyList(),
    /** Problems whose own notification comes down without a word: they are being dealt with. */
    val remove: List<ObjectRef> = emptyList(),
    /** The outage is over, or is no longer the one that was announced. */
    val removeOutage: Boolean = false,
    val state: AnnouncedState,
)

/** The host or service a problem is about. */
val Problem.ref: ObjectRef
    get() = when (this) {
        is HostProblem -> ObjectRef(host.name)
        is ServiceProblem -> ObjectRef(service.hostName, service.description)
    }
