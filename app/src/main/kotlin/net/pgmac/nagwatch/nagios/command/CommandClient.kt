// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.command

import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.http.NagiosApi
import net.pgmac.nagwatch.nagios.http.ResilientListFetcher
import okhttp3.HttpUrl

/**
 * Sends commands to one Nagios instance. Obtain one from `NagiosClientFactory`.
 *
 * This is the only way the app changes anything on a Nagios, and it is written to be
 * careful about that in three ways:
 *
 * - **A command is sent at most once.** Nothing here retries. If the connection fails
 *   after the request may have left, the answer is [CommandOutcome.Unknown], not another go.
 * - **Success is only what Nagios called success.** Any page that is not recognised is
 *   [CommandOutcome.Unknown].
 * - **A time is never guessed.** See [ServerClock].
 *
 * What it does not do is check that a command took effect: Nagios accepting a command
 * and Nagios having carried it out are different things, and the second is found by
 * reading the object again.
 */
interface CommandClient {
    /**
     * Whether this user may send commands at all: false for a user Nagios treats as
     * read-only. One GET of a command form; it changes nothing.
     *
     * True does not promise that every command will be accepted: Nagios only says whether
     * a user may command a particular object when that is tried.
     */
    suspend fun mayCommand(): NagiosResult<Boolean>

    /**
     * Sends [command], once.
     *
     * @param rememberedFormat the server's date format as seen on an earlier day, used only
     *   when today's date cannot show it. Ignored by commands that carry no date.
     */
    suspend fun send(command: Command, rememberedFormat: ServerDateFormat? = null): CommandReport
}

internal class OkHttpCommandClient(
    private val settings: ConnectionSettings,
    /** Must be built on a client that does not retry: see [NagiosApi.submit]. */
    private val api: NagiosApi,
    /** The CGI directory, found the same way the reading client finds it. */
    private val cgiBase: suspend () -> NagiosResult<HttpUrl>,
) : CommandClient {
    override suspend fun mayCommand(): NagiosResult<Boolean> = when (val base = cgiBase()) {
        is NagiosResult.Failure -> base

        is NagiosResult.Success -> when (val page = api.page(base.value, CMD_CGI, listOf(TYPE to PROBE_TYPE))) {
            is NagiosResult.Failure -> page

            is NagiosResult.Success -> when {
                CommandPage.isReadOnly(page.value) -> NagiosResult.Success(false)

                CommandPage.isForm(page.value) -> NagiosResult.Success(true)

                // Something answered at cmd.cgi's address that is neither of the pages it serves.
                else -> NagiosResult.Failure(NagiosError.NotNagios)
            }
        }
    }

    override suspend fun send(command: Command, rememberedFormat: ServerDateFormat?): CommandReport =
        when (val base = cgiBase()) {
            // Nothing has been sent yet at any point where this method returns NotSent.
            is NagiosResult.Failure -> CommandReport(CommandOutcome.NotSent(base.error))

            is NagiosResult.Success -> when (command) {
                is Command.ForceCheck -> forceCheck(base.value, command)
                is Command.ScheduleDowntime -> scheduleDowntime(base.value, command, rememberedFormat)
                else -> CommandReport(commit(base.value, fields(command)))
            }
        }

    /**
     * A forced check has to be given a time, in the server's format. "Now" is all that is
     * wanted, and the form already says "now" in the server's own words, so those words are
     * sent back as they came. Nothing about the format needs to be understood.
     */
    private suspend fun forceCheck(base: HttpUrl, command: Command.ForceCheck): CommandReport {
        val form = when (val page = form(base, command)) {
            is FormPage.Unavailable -> return CommandReport(page.outcome)
            is FormPage.Shown -> page.html
        }
        val now = CommandPage.field(form, START_TIME)
            ?: return CommandReport(CommandOutcome.NotSent(NagiosError.NotNagios))
        return CommandReport(commit(base, fields(command) + listOf(START_TIME to now, "force_check" to ON)))
    }

    private suspend fun scheduleDowntime(
        base: HttpUrl,
        command: Command.ScheduleDowntime,
        rememberedFormat: ServerDateFormat?,
    ): CommandReport = when (val reading = serverClock(base, command, rememberedFormat)) {
        is ClockReading.Unavailable -> CommandReport(reading.outcome)

        is ClockReading.Read -> {
            val clock = reading.clock
            val start = clock.format(command.start)
            val end = clock.format(command.end)
            if (start == null || end == null) {
                // Not sent: writing this window would mean guessing the order of day and month.
                CommandReport(CommandOutcome.DateOrderNeeded(clock.nowText))
            } else {
                val times = listOf(START_TIME to start, END_TIME to end)
                CommandReport(commit(base, fields(command) + times), learnedFormat = clock.format)
            }
        }
    }

    private sealed interface ClockReading {
        class Read(val clock: ServerClock) : ClockReading

        /** The clock could not be read, and so nothing was sent. */
        class Unavailable(val outcome: CommandOutcome) : ClockReading
    }

    /**
     * The server's clock, from two things it says about the present moment: the number its
     * JSON API gives, and the text its own downtime form is pre-filled with.
     */
    private suspend fun serverClock(
        base: HttpUrl,
        command: Command.ScheduleDowntime,
        rememberedFormat: ServerDateFormat?,
    ): ClockReading {
        val serverNow = when (val status = api.query(base, STATUS_CGI, listOf("query" to "programstatus"))) {
            is NagiosResult.Failure -> return ClockReading.Unavailable(CommandOutcome.NotSent(status.error))
            is NagiosResult.Success -> status.value.queryTime()
        }
        val form = when (val page = form(base, command)) {
            is FormPage.Unavailable -> return ClockReading.Unavailable(page.outcome)
            is FormPage.Shown -> page.html
        }
        val start = CommandPage.field(form, START_TIME)
        val clock = if (serverNow == null || start == null) {
            null
        } else {
            ServerClock.read(start, CommandPage.field(form, END_TIME), serverNow, rememberedFormat)
        }
        // Something answered, but not with a clock that can be read: not the Nagios this was written for.
        return clock?.let(ClockReading::Read) ?: ClockReading.Unavailable(CommandOutcome.NotSent(NagiosError.NotNagios))
    }

    private sealed interface FormPage {
        class Shown(val html: String) : FormPage

        class Unavailable(val outcome: CommandOutcome) : FormPage
    }

    /** The form Nagios would show for [command]. Asking for it changes nothing. */
    private suspend fun form(base: HttpUrl, command: Command): FormPage =
        when (val page = api.page(base, CMD_CGI, target(command))) {
            is NagiosResult.Failure -> FormPage.Unavailable(CommandOutcome.NotSent(page.error))

            is NagiosResult.Success -> when {
                CommandPage.isReadOnly(page.value) -> FormPage.Unavailable(CommandOutcome.Refused(Refusal.READ_ONLY))
                else -> FormPage.Shown(page.value)
            }
        }

    private suspend fun commit(base: HttpUrl, fields: List<Pair<String, String>>): CommandOutcome =
        when (val sent = api.submit(base, CMD_CGI, fields + COMMIT)) {
            is NagiosApi.Submission.NotSent -> CommandOutcome.NotSent(sent.error)

            is NagiosApi.Submission.Unknown -> CommandOutcome.Unknown(sent.error)

            is NagiosApi.Submission.Answered -> when (val result = CommandPage.result(sent.html)) {
                CommandPage.Result.Accepted -> CommandOutcome.Accepted

                is CommandPage.Result.Refused -> CommandOutcome.Refused(result.reason, result.detail)

                // It was sent, and what came back says neither yes nor no.
                CommandPage.Result.Unrecognised -> CommandOutcome.Unknown()
            }
        }

    /** Which command, about which object: what `cmd.cgi` needs to show a form or accept a commit. */
    private fun target(command: Command): List<Pair<String, String>> = buildList {
        add(TYPE to type(command).toString())
        if (command !is Command.CancelDowntime) {
            add("host" to command.target.hostName)
            command.target.description?.let { add("service" to it) }
        }
    }

    private fun fields(command: Command): List<Pair<String, String>> = target(command) + when (command) {
        is Command.Acknowledge -> comment(command.comment) + listOfNotNull(
            ("sticky_ack" to ON).takeIf { command.sticky },
            ("send_notification" to ON).takeIf { command.notify },
            ("persistent" to ON).takeIf { command.persistent },
        )

        is Command.AddComment -> comment(command.comment) + ("persistent" to ON)

        is Command.ScheduleDowntime -> comment(command.comment) + listOfNotNull(
            "trigger" to NO_TRIGGER,
            "fixed" to FIXED,
            // Only read for a flexible downtime, but the form always sends them.
            "hours" to "2",
            "minutes" to "0",
            ("childoptions" to NO_CHILDREN).takeIf { command.target.isHost },
        )

        is Command.CancelDowntime -> listOf("down_id" to command.downtimeId.toString())

        is Command.RemoveAcknowledgement, is Command.ForceCheck -> emptyList()
    }

    /**
     * Nagios normally ignores the author and uses the logged-in user (`lock_author_names`).
     * Where that is switched off it insists on one, so the user name is always sent.
     */
    private fun comment(text: String) = listOf("com_author" to settings.username, "com_data" to storedCommentText(text))

    private fun type(command: Command): Int {
        val host = command.target.isHost
        return when (command) {
            is Command.Acknowledge -> if (host) ACKNOWLEDGE_HOST else ACKNOWLEDGE_SERVICE
            is Command.RemoveAcknowledgement -> if (host) REMOVE_HOST_ACK else REMOVE_SERVICE_ACK
            is Command.AddComment -> if (host) COMMENT_HOST else COMMENT_SERVICE
            is Command.ForceCheck -> if (host) CHECK_HOST else CHECK_SERVICE
            is Command.ScheduleDowntime -> if (host) DOWNTIME_HOST else DOWNTIME_SERVICE
            is Command.CancelDowntime -> if (host) CANCEL_HOST_DOWNTIME else CANCEL_SERVICE_DOWNTIME
        }
    }

    /** `result.query_time`: when Nagios answered, in epoch milliseconds. */
    private fun JsonObject.queryTime(): Instant? =
        ((this["result"] as? JsonObject)?.get("query_time") as? JsonPrimitive)?.longOrNull
            ?.takeIf { it > 0 }
            ?.let(Instant::ofEpochMilli)

    private companion object {
        const val CMD_CGI = "cmd.cgi"
        const val STATUS_CGI = ResilientListFetcher.STATUS_CGI
        const val TYPE = "cmd_typ"
        const val START_TIME = "start_time"
        const val END_TIME = "end_time"
        const val ON = "on"
        const val FIXED = "1"
        const val NO_TRIGGER = "0"
        const val NO_CHILDREN = "0"

        /** `cmd_mod=2` is "commit"; without it `cmd.cgi` only shows the form. */
        val COMMIT = listOf("cmd_mod" to "2", "btnSubmit" to "Commit")

        // Nagios' cmd_typ values (include/common.h). A forced check is the ordinary "schedule a
        // check" with force_check set: cmd.cgi does not accept the separate forced types (54, 98).
        const val COMMENT_HOST = 1
        const val COMMENT_SERVICE = 3
        const val CHECK_SERVICE = 7
        const val ACKNOWLEDGE_HOST = 33
        const val ACKNOWLEDGE_SERVICE = 34
        const val REMOVE_HOST_ACK = 51
        const val REMOVE_SERVICE_ACK = 52
        const val DOWNTIME_HOST = 55
        const val DOWNTIME_SERVICE = 56
        const val CANCEL_HOST_DOWNTIME = 78
        const val CANCEL_SERVICE_DOWNTIME = 79
        const val CHECK_HOST = 96

        /** Any command type will do for the read-only probe; this one's form asks for nothing. */
        const val PROBE_TYPE = "51"
    }
}
