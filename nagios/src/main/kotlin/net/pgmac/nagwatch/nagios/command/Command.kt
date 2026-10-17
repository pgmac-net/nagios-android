// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.command

import java.time.Instant
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.model.ObjectRef

/**
 * Something the app can ask Nagios to do. These are the only writes the app makes.
 *
 * The numbers are Nagios' `cmd_typ` values, checked against a Nagios Core 4.5 `cmd.cgi`
 * by sending each one (docs/design.md, section 5).
 */
sealed interface Command {
    /** The host or service the command is about. */
    val target: ObjectRef

    /**
     * Tells Nagios that someone has seen a problem.
     *
     * [sticky] is off unless asked for: a sticky acknowledgement survives the problem getting
     * worse, and so can hide it. [notify] and [persistent] follow Nagios' own defaults.
     */
    data class Acknowledge(
        override val target: ObjectRef,
        val comment: String,
        val sticky: Boolean = false,
        val notify: Boolean = true,
        val persistent: Boolean = false,
    ) : Command

    data class RemoveAcknowledgement(override val target: ObjectRef) : Command

    /** A note on the object. Kept across a Nagios restart, as the web interface does by default. */
    data class AddComment(override val target: ObjectRef, val comment: String) : Command

    /** Asks for a check now, whatever the schedule and whether or not active checks are on. */
    data class ForceCheck(override val target: ObjectRef) : Command

    /** A fixed window during which problems on the object are expected and do not notify. */
    data class ScheduleDowntime(
        override val target: ObjectRef,
        val comment: String,
        val start: Instant,
        val end: Instant,
    ) : Command

    /** Ends, or prevents, a downtime. [downtimeId] is Nagios' own id for it. */
    data class CancelDowntime(override val target: ObjectRef, val downtimeId: Long) : Command
}

/** Why Nagios said no. Each has its own wording in `cmd.cgi`, and gets its own in the app. */
enum class Refusal {
    /** The user is marked read-only in Nagios' CGI configuration. Applies to every object. */
    READ_ONLY,

    /**
     * The user may not command this object. Nagios says exactly the same when the object
     * does not exist, or a downtime id is not one it knows.
     */
    NOT_AUTHORISED,

    /** Nagios did not accept what was sent: a missing comment, a time it could not read. */
    INVALID,

    /** Nagios is configured not to accept commands at all. */
    COMMANDS_DISABLED,

    /** Nagios accepted the command and then could not write it to its command file. */
    COULD_NOT_WRITE,

    /** The CGIs have authentication switched off, and refuse to send commands without it. */
    AUTHENTICATION_DISABLED,
}

/**
 * What is known after trying to send a command. The cases are kept apart on purpose:
 * "Nagios said no", "it never left", and "nobody knows" call for different things from
 * the person holding the phone, and only one of them is safe to repeat without looking.
 */
sealed interface CommandOutcome {
    /**
     * Nagios accepted the command. That means it was written to the command file, not that
     * it has been carried out: Nagios reads that file later, and an acknowledgement of
     * something that is no longer a problem is dropped without a word.
     */
    data object Accepted : CommandOutcome

    /** Nagios answered, and refused. [detail] is its own sentence, for [Refusal.INVALID]. */
    data class Refused(val reason: Refusal, val detail: String = "") : CommandOutcome

    /** The command certainly did not reach Nagios. Nothing changed; it can be tried again. */
    data class NotSent(val error: NagiosError) : CommandOutcome

    /**
     * The command may or may not have reached Nagios: the connection failed after the
     * request could have left, or the server answered with something that says neither yes
     * nor no. Never reported as success or as failure. The thing to do is look.
     */
    data class Unknown(val error: NagiosError? = null) : CommandOutcome

    /**
     * The command needs a date, and whether this server writes month-day or day-month cannot
     * be told from what it showed. Nothing was sent. [example] is how the server wrote today.
     */
    data class DateOrderNeeded(val example: String) : CommandOutcome
}

/**
 * An outcome, and anything learned about the server on the way to it.
 *
 * [learnedFormat] is set when the server's date format was seen unambiguously, so that it
 * can be remembered for a day when it cannot be.
 */
data class CommandReport(val outcome: CommandOutcome, val learnedFormat: ServerDateFormat? = null)

/**
 * Comment text as Nagios will actually keep it.
 *
 * `cmd.cgi` removes `<` and `>` from a comment and turns `;` into a space (the semicolon
 * separates the fields of the command it writes). A line break would end that command
 * early, so it becomes a space here too. Doing the same before sending means what is sent
 * is what is stored: the app can show the user what will be kept, and can recognise its
 * own comment when it reads the object back.
 */
fun storedCommentText(typed: String): String = typed
    .filterNot { it == '<' || it == '>' }
    .map { if (it == ';' || it == '\n' || it == '\r') ' ' else it }
    .joinToString("")
    .trim()
