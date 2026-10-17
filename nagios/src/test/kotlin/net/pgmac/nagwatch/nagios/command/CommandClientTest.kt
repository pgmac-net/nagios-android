// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.command

import java.time.Instant
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.respond
import net.pgmac.nagwatch.nagios.settingsFor
import net.pgmac.nagwatch.nagios.status
import net.pgmac.nagwatch.nagios.testFactory
import net.pgmac.nagwatch.nagios.testHttpClient
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the command client puts on the wire, and what it makes of the answer. The server here
 * is a stand-in that replays pages captured from a real Nagios; `CommandLiveTest` is the one
 * that talks to the real thing.
 */
class CommandClientTest {
    private val server = MockWebServer()
    private val requests = mutableListOf<RecordedRequest>()
    private val disk = ObjectRef("web01", "Disk /")
    private val web01 = ObjectRef("web01")

    /** What the stand-in answers a commit with. */
    private var commitAnswer: () -> MockResponse = { html("result_accepted") }

    /** What it shows when asked for a form. */
    private var formAnswer: (RecordedRequest) -> MockResponse = { html("form_acknowledge") }

    /** The server's clock, as its JSON API reports it. */
    private var jsonNow: Instant = Instant.parse("2026-10-10T13:29:32Z")

    @Before
    fun start() {
        server.start()
        server.respond { request ->
            requests += request
            when {
                request.url.encodedPath.endsWith("statusjson.cgi") ->
                    json("""{"result":{"type_code":0,"query_time":${jsonNow.toEpochMilli()}},"data":{}}""")

                request.method == "POST" -> commitAnswer()

                else -> formAnswer(request)
            }
        }
    }

    @After
    fun stop() = server.close()

    @Test
    fun `an acknowledgement is one POST carrying exactly what was asked`() {
        val report = send(Command.Acknowledge(disk, "looking at it"))

        assertEquals(CommandOutcome.Accepted, report.outcome)
        val post = requests.single()
        assertEquals("POST", post.method)
        assertEquals("/nagios/cgi-bin/cmd.cgi", post.url.encodedPath)
        assertEquals(
            mapOf(
                "cmd_typ" to "34",
                "host" to "web01",
                "service" to "Disk /",
                "com_author" to "nagwatch",
                "com_data" to "looking at it",
                "send_notification" to "on",
                "cmd_mod" to "2",
                "btnSubmit" to "Commit",
            ),
            post.form(),
        )
    }

    @Test
    fun `sticky and persistent are sent only when asked for, and notify can be switched off`() {
        send(Command.Acknowledge(disk, "x", sticky = true, notify = false, persistent = true))

        val form = requests.single().form()
        assertEquals("on", form["sticky_ack"])
        assertEquals("on", form["persistent"])
        assertFalse("send_notification" in form)
    }

    @Test
    fun `a host and a service use different command numbers`() {
        val expected = listOf(
            Command.Acknowledge(web01, "x") to "33",
            Command.Acknowledge(disk, "x") to "34",
            Command.RemoveAcknowledgement(web01) to "51",
            Command.RemoveAcknowledgement(disk) to "52",
            Command.AddComment(web01, "x") to "1",
            Command.AddComment(disk, "x") to "3",
            Command.CancelDowntime(web01, 7) to "78",
            Command.CancelDowntime(disk, 7) to "79",
        )
        expected.forEach { (command, type) ->
            requests.clear()
            send(command)
            val form = requests.single().form()
            assertEquals("$command", type, form["cmd_typ"])
            // Cancelling names the downtime and nothing else; see the next test.
            if (command !is Command.CancelDowntime) {
                assertEquals("a host command names no service", command.target.description, form["service"])
            }
        }
    }

    @Test
    fun `cancelling a downtime names the downtime and nothing else`() {
        send(Command.CancelDowntime(disk, 42))

        assertEquals(
            mapOf("cmd_typ" to "79", "down_id" to "42", "cmd_mod" to "2", "btnSubmit" to "Commit"),
            requests.single().form(),
        )
    }

    @Test
    fun `a comment is cleaned as Nagios would clean it, before it is sent`() {
        send(Command.AddComment(disk, "see <b>ticket</b>; ask\nBob"))

        assertEquals("see bticket/b  ask Bob", requests.single().form()["com_data"])
    }

    @Test
    fun `a forced check asks for the form and sends its own now straight back`() {
        formAnswer = { html("form_check_strict_iso8601") }

        val report = send(Command.ForceCheck(disk))

        assertEquals(CommandOutcome.Accepted, report.outcome)
        val (form, post) = requests
        assertEquals("GET", form.method)
        assertEquals("7", form.url.queryParameter("cmd_typ"))
        assertNull("asking for the form commits nothing", form.url.queryParameter("cmd_mod"))
        assertEquals("POST", post.method)
        assertEquals("2026-10-10T13:35:46", post.form()["start_time"])
        assertEquals("on", post.form()["force_check"])
        assertEquals("7", post.form()["cmd_typ"])
    }

    @Test
    fun `a forced check of a host is the host's scheduling command`() {
        formAnswer = { html("form_check_us") }

        send(Command.ForceCheck(web01))

        assertEquals("96", requests.last().form()["cmd_typ"])
    }

    @Test
    fun `a downtime is written in the server's format and the server's time`() {
        // A Brisbane server writing month first, as its form showed just before midnight.
        formAnswer = { html("form_downtime_us_crossing_midnight") }
        jsonNow = Instant.parse("2026-10-10T13:37:48Z")
        val start = Instant.parse("2026-11-02T02:00:00Z")

        val report = send(Command.ScheduleDowntime(disk, "patching", start, start.plusSeconds(5400)))

        assertEquals(CommandOutcome.Accepted, report.outcome)
        assertEquals(ServerDateFormat.US, report.learnedFormat)
        val form = requests.last().form()
        assertEquals("the 2nd of November at noon in Brisbane, month first", "11-02-2026 12:00:00", form["start_time"])
        assertEquals("11-02-2026 13:30:00", form["end_time"])
        assertEquals("1", form["fixed"])
        assertEquals("0", form["trigger"])
        assertEquals("56", form["cmd_typ"])
        assertEquals("patching", form["com_data"])
        assertFalse("only a host downtime says what to do about children", "childoptions" in form)
        assertEquals(listOf("GET", "GET", "POST"), requests.map { it.method })
    }

    @Test
    fun `the same downtime on a European server is written day first`() {
        formAnswer = { html("form_downtime_euro_berlin") }
        jsonNow = Instant.parse("2026-10-10T13:39:48Z")
        val start = Instant.parse("2026-11-02T02:00:00Z")

        val report =
            send(Command.ScheduleDowntime(web01, "patching", start, start.plusSeconds(3600)), ServerDateFormat.EURO)

        assertEquals(CommandOutcome.Accepted, report.outcome)
        val form = requests.last().form()
        assertEquals("Berlin was two hours ahead when the form was read", "02-11-2026 04:00:00", form["start_time"])
        assertEquals("55", form["cmd_typ"])
        assertEquals("0", form["childoptions"])
    }

    @Test
    fun `when the order of day and month is not known, nothing is sent`() {
        formAnswer = { html("form_downtime_us") }
        val start = Instant.parse("2026-11-02T02:00:00Z")

        val report = send(Command.ScheduleDowntime(disk, "x", start, start.plusSeconds(3600)))

        assertEquals(CommandOutcome.DateOrderNeeded("10-10-2026 13:29:32"), report.outcome)
        assertNull(report.learnedFormat)
        assertTrue("no POST was made", requests.none { it.method == "POST" })
    }

    @Test
    fun `on such a day a window that reads the same either way is still sent`() {
        formAnswer = { html("form_downtime_us") }
        // Later on the 10th of October itself: 10-10 whichever comes first.
        val start = Instant.parse("2026-10-10T20:00:00Z")

        val report = send(Command.ScheduleDowntime(disk, "x", start, start.plusSeconds(3600)))

        assertEquals(CommandOutcome.Accepted, report.outcome)
        assertEquals("10-10-2026 20:00:00", requests.last().form()["start_time"])
        assertNull("and nothing was learned about the order", report.learnedFormat)
    }

    @Test
    fun `if the form's clock and the JSON clock disagree, nothing is sent`() {
        formAnswer = { html("form_downtime_iso8601") }
        // Four hours and twenty-odd minutes from what the form says: no time zone is that.
        jsonNow = Instant.parse("2026-10-10T09:10:00Z")

        val report = send(Command.ScheduleDowntime(disk, "x", jsonNow, jsonNow.plusSeconds(3600)))

        assertEquals(CommandOutcome.NotSent(NagiosError.NotNagios), report.outcome)
        assertTrue(requests.none { it.method == "POST" })
    }

    @Test
    fun `a read-only user is refused at the form, before anything is committed`() {
        formAnswer = { html("form_readonly") }

        val check = send(Command.ForceCheck(disk))
        val downtime = send(Command.ScheduleDowntime(disk, "x", jsonNow, jsonNow.plusSeconds(60)))

        assertEquals(CommandOutcome.Refused(Refusal.READ_ONLY), check.outcome)
        assertEquals(CommandOutcome.Refused(Refusal.READ_ONLY), downtime.outcome)
        assertTrue(requests.none { it.method == "POST" })
    }

    @Test
    fun `each of Nagios' refusals comes back as itself`() {
        mapOf(
            "result_readonly" to CommandOutcome.Refused(Refusal.READ_ONLY),
            "result_not_authorised" to CommandOutcome.Refused(Refusal.NOT_AUTHORISED),
            "result_comment_missing" to CommandOutcome.Refused(Refusal.INVALID, "Comment was not entered"),
            "result_commands_disabled_synthetic" to CommandOutcome.Refused(Refusal.COMMANDS_DISABLED),
            "result_could_not_write_synthetic" to CommandOutcome.Refused(Refusal.COULD_NOT_WRITE),
        ).forEach { (page, outcome) ->
            commitAnswer = { html(page) }
            assertEquals(page, outcome, send(Command.Acknowledge(disk, "x")).outcome)
        }
    }

    @Test
    fun `an answer that says neither yes nor no is unknown, not success`() {
        listOf("form_acknowledge", "form_unsupported_type").forEach { page ->
            commitAnswer = { html(page) }
            assertEquals(page, CommandOutcome.Unknown(), send(Command.Acknowledge(disk, "x")).outcome)
        }
        commitAnswer = { MockResponse.Builder().code(200).body("<html>welcome</html>").build() }
        assertEquals(CommandOutcome.Unknown(), send(Command.Acknowledge(disk, "x")).outcome)
    }

    @Test
    fun `a command is sent once even when the connection dies without an answer`() {
        commitAnswer = { MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }

        val report = send(Command.Acknowledge(disk, "x"))

        assertTrue("it may have arrived: ${report.outcome}", report.outcome is CommandOutcome.Unknown)
        assertEquals("and it was not tried again", 1, requests.count { it.method == "POST" })
    }

    @Test
    fun `a command is not sent again to a host's other address when the first try gets no answer`() {
        // This is the case where the HTTP library would try again by itself: a name with more
        // than one address (IPv4 and IPv6, say). The first address took the request and said
        // nothing; the library's instinct is to try the next. For a read that is right. For an
        // acknowledgement it sends the command twice. Here both "addresses" are the same server.
        val twoAddresses = Dns { name -> Dns.SYSTEM.lookup(name).take(1).let { it + it } }
        var attempts = 0
        commitAnswer = {
            if (attempts++ ==
                0
            ) {
                MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()
            } else {
                html("result_accepted")
            }
        }
        val client = testFactory(testHttpClient { dns(twoAddresses) }).createCommands(settings())

        val report = runBlocking { client.send(Command.Acknowledge(disk, "x")) }

        assertEquals("the server saw the command once", 1, requests.count { it.method == "POST" })
        assertTrue(
            "and the app does not claim to know what became of it: ${report.outcome}",
            report.outcome is CommandOutcome.Unknown,
        )
    }

    @Test
    fun `a server that never answers leaves the outcome unknown, with one attempt`() {
        commitAnswer = { MockResponse.Builder().onResponseStart(SocketEffect.Stall).build() }

        val report = send(Command.Acknowledge(disk, "x"))

        assertTrue("${report.outcome}", report.outcome is CommandOutcome.Unknown)
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test
    fun `a server error after the command was received is unknown`() {
        commitAnswer = { status(500, "Internal Server Error") }

        assertEquals(CommandOutcome.Unknown(NagiosError.Http(500)), send(Command.Acknowledge(disk, "x")).outcome)
        assertEquals(1, requests.count { it.method == "POST" })
    }

    @Test
    fun `being turned away before the CGI ran is certain, and says why`() {
        commitAnswer = { status(401, "", "WWW-Authenticate" to "Basic realm=\"Nagios Access\"") }
        assertEquals(CommandOutcome.NotSent(NagiosError.BadCredentials), send(Command.Acknowledge(disk, "x")).outcome)

        commitAnswer = { status(302, "", "Location" to "https://elsewhere.example.org/login") }
        val redirected = send(Command.Acknowledge(disk, "x")).outcome
        assertTrue("$redirected", redirected is CommandOutcome.NotSent && redirected.error is NagiosError.Redirected)
        assertEquals("the redirect was not followed", 2, requests.size)
    }

    @Test
    fun `nothing listening is certain too`() {
        val dead = server.url("/nagios/")
        server.close()

        val outcome = runBlocking {
            commands(settingsFor(dead, cgiBase = dead.resolve("cgi-bin/"))).send(Command.Acknowledge(disk, "x"))
        }.outcome

        assertTrue("$outcome", outcome is CommandOutcome.NotSent)
    }

    @Test
    fun `a profile that may not use plain http sends nothing at all`() {
        val base = server.url("/nagios/")
        val refused = settingsFor(base, allowCleartext = false, cgiBase = base.resolve("cgi-bin/"))

        val outcome = runBlocking { commands(refused).send(Command.Acknowledge(disk, "x")) }.outcome

        assertEquals(CommandOutcome.NotSent(NagiosError.CleartextRefused), outcome)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `the probe tells a read-only user from one who may command, and commits nothing`() {
        formAnswer = { html("form_remove_acknowledgement") }
        assertEquals(NagiosResult.Success(true), runBlocking { commands().mayCommand() })

        formAnswer = { html("form_readonly") }
        assertEquals(NagiosResult.Success(false), runBlocking { commands().mayCommand() })

        formAnswer = { MockResponse.Builder().code(200).body("<html>not cmd.cgi</html>").build() }
        assertEquals(NagiosResult.Failure(NagiosError.NotNagios), runBlocking { commands().mayCommand() })

        assertTrue(requests.all { it.method == "GET" && it.url.queryParameter("cmd_mod") == null })
    }

    @Test
    fun `credentials travel in the header, never in the form or the address`() {
        send(Command.Acknowledge(disk, "x"))

        val post = requests.single()
        assertTrue(post.headers["Authorization"].orEmpty().startsWith("Basic "))
        assertFalse(post.body?.utf8().orEmpty().contains("s3cr3t-p4ss"))
        assertFalse(post.url.toString().contains("s3cr3t-p4ss"))
        assertEquals("the author sent is the user name", "nagwatch", post.form()["com_author"])
    }

    private fun send(command: Command, remembered: ServerDateFormat? = null): CommandReport =
        runBlocking { commands().send(command, remembered) }

    private fun commands(settings: ConnectionSettings = settings()): CommandClient =
        testFactory().createCommands(settings)

    private fun settings(): ConnectionSettings {
        val base: HttpUrl = server.url("/nagios/")
        return settingsFor(base, cgiBase = base.resolve("cgi-bin/"))
    }

    private fun html(name: String): MockResponse = MockResponse.Builder().code(200)
        .addHeader("Content-Type", "text/html; charset=utf-8")
        .body(CommandFixtures.page(name))
        .build()

    private fun json(body: String): MockResponse =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    /** The form fields of a POST, decoded. */
    private fun RecordedRequest.form(): Map<String, String> {
        val encoded = "http://form.invalid/?${body?.utf8().orEmpty()}".toHttpUrl()
        return (0 until encoded.querySize).associate {
            encoded.queryParameterName(it) to
                encoded.queryParameterValue(it).orEmpty()
        }
    }
}
