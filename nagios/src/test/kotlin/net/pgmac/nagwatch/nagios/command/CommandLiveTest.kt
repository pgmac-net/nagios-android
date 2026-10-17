// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.command

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosClient
import net.pgmac.nagwatch.nagios.NagiosClientFactory
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.Downtime
import net.pgmac.nagwatch.nagios.model.ObjectRef
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Sends real commands to the throwaway test Nagios (`scripts/test-nagios`). Skipped unless
 * asked for, and never run in CI.
 *
 *     scripts/test-nagios/live-test.sh
 *
 * These tests change the server they talk to. So before any of them sends anything, the
 * server has to prove it is the made-up one: the address must be this machine, and a host
 * named `web01` must answer with the made-up output that only `objects.cfg` gives it. A
 * real Nagios fails that check and nothing is sent to it.
 */
class CommandLiveTest {
    private val url = System.getenv("NAGWATCH_TEST_NAGIOS").orEmpty()
    private val serverFormat = System.getenv("NAGWATCH_TEST_DATE_FORMAT").orEmpty()
    private val factory = NagiosClientFactory(
        OkHttpClient.Builder().callTimeout(Duration.ofSeconds(30)).build(),
        Json { ignoreUnknownKeys = true },
        Dispatchers.IO,
        Clock.systemUTC(),
    )
    private val disk = ObjectRef("web01", "Disk /")
    private val load = ObjectRef("web01", "Load")
    private val http = ObjectRef("web01", "HTTP")

    @Before
    fun onlyTheTestInstance() {
        assumeTrue("set NAGWATCH_TEST_NAGIOS (scripts/test-nagios/live-test.sh does)", url.isNotEmpty())
        val host = url.toHttpUrl().host
        check(host == "127.0.0.1" || host == "localhost") { "refusing to send commands to $host: not this machine" }
        val web01 = runBlocking { reader("operator").fetchHost("web01") }
        check(web01 is NagiosResult.Success && "made up" in web01.value.check.pluginOutput) {
            "refusing to send commands: this is not the test Nagios from scripts/test-nagios"
        }
    }

    @Test
    fun `a read-only user is recognised without sending anything`() = runBlocking {
        assertEquals(NagiosResult.Success(true), commands("operator").mayCommand())
        assertEquals(NagiosResult.Success(true), commands("limited").mayCommand())
        assertEquals(NagiosResult.Success(false), commands("viewer").mayCommand())
    }

    @Test
    fun `acknowledge, then remove it`() = runBlocking {
        val reader = reader("operator")
        awaitTrue("Disk is critical") { reader.service(disk).state.isProblem }

        val sent = commands("operator").send(Command.Acknowledge(disk, "live test"))
        assertEquals(CommandOutcome.Accepted, sent.outcome)
        awaitTrue("acknowledged") { reader.service(disk).check.acknowledged }

        assertEquals(CommandOutcome.Accepted, commands("operator").send(Command.RemoveAcknowledgement(disk)).outcome)
        awaitTrue("acknowledgement gone") { !reader.service(disk).check.acknowledged }
    }

    @Test
    fun `each way Nagios says no is told apart`() = runBlocking {
        val notAllowed = commands("limited").send(Command.Acknowledge(ObjectRef("db01", "Replication"), "x"))
        assertEquals(CommandOutcome.Refused(Refusal.NOT_AUTHORISED), notAllowed.outcome)

        val readOnly = commands("viewer").send(Command.Acknowledge(disk, "x"))
        assertEquals(CommandOutcome.Refused(Refusal.READ_ONLY), readOnly.outcome)

        val noComment = commands("operator").send(Command.Acknowledge(disk, ""))
        assertEquals(CommandOutcome.Refused(Refusal.INVALID, "Comment was not entered"), noComment.outcome)

        val gone = commands("operator").send(Command.AddComment(ObjectRef("web01", "No such service"), "x"))
        assertEquals(
            "Nagios says the same for an object that does not exist",
            Refusal.NOT_AUTHORISED,
            (gone.outcome as CommandOutcome.Refused).reason,
        )
    }

    @Test
    fun `a comment is kept exactly as the app says it will be, under the logged-in user's name`() = runBlocking {
        // Nagios drops angle brackets and semicolons. The app predicts that, so it can find its own comment.
        val typed = "live test: <b>markup</b>; \"quotes\" & ampersand, 50% ${Instant.now().epochSecond}"
        val kept = storedCommentText(typed)
        assertTrue("the test is only worth something if Nagios changes the text", kept != typed)

        assertEquals(CommandOutcome.Accepted, commands("operator").send(Command.AddComment(load, typed)).outcome)

        val reader = reader("operator")
        awaitTrue("comment listed as predicted") { reader.comments(load).any { it.text == kept } }
        assertEquals("Test Operator", reader.comments(load).first { it.text == kept }.author)
    }

    @Test
    fun `a forced check runs without the date format being understood`() = runBlocking {
        val reader = reader("operator")
        val before = reader.service(http).check.lastCheck ?: Instant.EPOCH
        // The made-up checks run every minute; wait past the second so "moved" means this one.
        delay(1_100)

        assertEquals(CommandOutcome.Accepted, commands("operator").send(Command.ForceCheck(http)).outcome)

        awaitTrue("last check moved") { (reader.service(http).check.lastCheck ?: Instant.EPOCH) > before }
    }

    @Test
    fun `a downtime lands on exactly the window asked for, and can be cancelled`() = runBlocking {
        val reader = reader("operator")
        cancelAll(reader)
        // Tomorrow, so that on a day whose number equals its month the order of day and month still matters.
        val start = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofHours(26))
        val end = start.plus(Duration.ofMinutes(90))

        val report = commands(
            "operator",
        ).send(Command.ScheduleDowntime(disk, "live test window", start, end), knownFormat())
        assertEquals(CommandOutcome.Accepted, report.outcome)

        awaitTrue("downtime listed") { reader.downtimes(disk).isNotEmpty() }
        val scheduled = reader.downtimes(disk).single()
        println(
            "live: asked $start to $end; Nagios scheduled ${scheduled.start} to ${scheduled.end} " +
                "(format ${report.learnedFormat})",
        )
        assertEquals("start", start, scheduled.start)
        assertEquals("end", end, scheduled.end)
        assertTrue(scheduled.fixed)

        assertEquals(
            CommandOutcome.Accepted,
            commands("operator").send(Command.CancelDowntime(disk, scheduled.id)).outcome,
        )
        awaitTrue("downtime gone") { reader.downtimes(disk).isEmpty() }
    }

    @Test
    fun `when the order of day and month cannot be told, nothing is sent`() = runBlocking {
        val reader = reader("operator")
        cancelAll(reader)
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        assumeTrue("only meaningful for US or European order", serverFormat == "us" || serverFormat == "euro")
        assumeTrue("only on a day whose number equals its month", today.dayOfMonth == today.monthValue)
        // Far enough ahead that neither the window nor the form's own two hours can show the order...
        val start = Instant.now().truncatedTo(ChronoUnit.MINUTES).plus(Duration.ofDays(3))

        val report = commands("operator").send(Command.ScheduleDowntime(disk, "x", start, start.plusSeconds(600)), null)

        // ...unless the form's two hours happen to cross midnight on the server, which does show it.
        if (report.outcome is CommandOutcome.DateOrderNeeded) {
            delay(SETTLE_MS)
            assertTrue("nothing was scheduled", reader.downtimes(disk).isEmpty())
        } else {
            assertEquals(CommandOutcome.Accepted, report.outcome)
            awaitTrue("downtime listed") { reader.downtimes(disk).isNotEmpty() }
            assertEquals("the order was read from the form and was right", start, reader.downtimes(disk).single().start)
            cancelAll(reader)
        }
    }

    @Test
    fun `the wrong order of day and month is silent, which is why the window is read back`() = runBlocking {
        assumeTrue("only meaningful for US or European order", serverFormat == "us" || serverFormat == "euro")
        val reader = reader("operator")
        cancelAll(reader)
        val wrong = if (serverFormat == "us") ServerDateFormat.EURO else ServerDateFormat.US
        // 3 February next year. Read the other way round it is 2 March: also in the future, so
        // Nagios keeps it. (A window that lands in the past is accepted and then dropped.)
        val start = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusYears(1).withMonth(2).withDayOfMonth(3)
            .atTime(12, 0).toInstant(java.time.ZoneOffset.UTC)
        val today = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        assumeTrue("needs a day when the form cannot show the order", today.dayOfMonth == today.monthValue)

        val report = commands(
            "operator",
        ).send(Command.ScheduleDowntime(disk, "x", start, start.plusSeconds(3600)), wrong)

        if (report.learnedFormat == wrong) {
            // Nagios took it without complaint, and put it somewhere else.
            assertEquals(CommandOutcome.Accepted, report.outcome)
            awaitTrue("downtime listed") { reader.downtimes(disk).isNotEmpty() }
            val scheduled = reader.downtimes(disk).single()
            println("live: asked for $start with the wrong order; Nagios scheduled ${scheduled.start}")
            assertTrue("it is not where it was asked for", scheduled.start != start)
        }
        cancelAll(reader)
    }

    private fun knownFormat(): ServerDateFormat? = when (serverFormat) {
        "us" -> ServerDateFormat.US
        "euro" -> ServerDateFormat.EURO
        "iso8601" -> ServerDateFormat.ISO8601
        "strict-iso8601" -> ServerDateFormat.STRICT_ISO8601
        else -> null
    }

    private suspend fun cancelAll(reader: NagiosClient) {
        reader.downtimes(disk).forEach { commands("operator").send(Command.CancelDowntime(disk, it.id)) }
        awaitTrue("no downtimes left") { reader.downtimes(disk).isEmpty() }
    }

    private fun settings(user: String) = ConnectionSettings(
        baseUrl = url.toHttpUrl(),
        username = user,
        // Not a secret: the test Nagios' made-up users, see scripts/test-nagios/start.sh.
        password = "$user-pw",
        allowCleartext = true,
    )

    private fun reader(user: String): NagiosClient = factory.create(settings(user))

    private fun commands(user: String): CommandClient = factory.createCommands(settings(user))

    private suspend fun NagiosClient.service(ref: ObjectRef) = (fetchService(ref) as NagiosResult.Success).value

    private suspend fun NagiosClient.comments(ref: ObjectRef) = (fetchComments(ref) as NagiosResult.Success).value

    private suspend fun NagiosClient.downtimes(ref: ObjectRef): List<Downtime> =
        (fetchDowntimes(ref) as NagiosResult.Success).value

    /** Nagios acts on a command some seconds after accepting it. */
    private suspend fun awaitTrue(what: String, condition: suspend () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(WAIT_SECONDS).toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return
            delay(500)
        }
        throw AssertionError("not seen within ${WAIT_SECONDS}s: $what")
    }

    private companion object {
        const val WAIT_SECONDS = 40L
        const val SETTLE_MS = 15_000L
    }
}
