// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.CommandOutcome
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.profile.DEFAULT_ACK_COMMENT
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.CommandRepository
import net.pgmac.nagwatch.status.FakeClient
import net.pgmac.nagwatch.status.FakeCommandClient
import net.pgmac.nagwatch.status.commandProvider
import net.pgmac.nagwatch.status.provider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A button's press, from the job that carries it out to what the notification is told. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class NotificationActionRunnerTest {
    private val database = inMemoryProfileDatabase()
    private val profiles = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)
    private val client = FakeClient()
    private val sender = FakeCommandClient()

    /** The address each command was sent to: which Nagios, not just which command. */
    private val sentTo = mutableListOf<ConnectionSettings>()
    private val commands =
        CommandRepository(profiles, provider(client), commandProvider(sender) { sentTo += it }, NO_WAIT)
    private val notifier = FakeNotifier()
    private val runner = NotificationActionRunner(profiles, commands, notifier)

    private val disk = ObjectRef("web01", "Disk /")
    private var acknowledged = false

    init {
        client.serviceResult = {
            NagiosResult.Success(
                ServiceStatus("web01", "Disk /", ServiceState.CRITICAL, CheckStatus(acknowledged = acknowledged)),
            )
        }
        sender.outcome = {
            if (it is Command.Acknowledge) acknowledged = true
            CommandOutcome.Accepted
        }
    }

    @After
    fun close() = database.close()

    @Test
    fun `Acknowledge sends the profile's own words with the form's flags, and tells the notification once`() =
        runBlocking {
            val id = profiles.save(draftWith(name = "Home").copy(ackComment = "Seen, on it"))
            val request = request(id, NoticeAction.ACKNOWLEDGE)

            runner.run(request)

            assertEquals(listOf<Command>(Command.Acknowledge(disk, "Seen, on it")), sender.sent)
            val (told, progress) = notifier.acted.single()
            assertEquals(request, told)
            assertTrue("only the answer, not every step on the way", progress is ActionProgress.Confirmed)
        }

    @Test
    fun `a profile with no words of its own sends the app's`() = runBlocking {
        val id = profiles.save(draftWith())

        runner.run(request(id, NoticeAction.ACKNOWLEDGE))

        assertEquals(DEFAULT_ACK_COMMENT, (sender.sent.single() as Command.Acknowledge).comment)
    }

    @Test
    fun `Recheck asks for a check and nothing else`() = runBlocking {
        val id = profiles.save(draftWith())

        runner.run(request(id, NoticeAction.RECHECK))

        assertEquals(listOf<Command>(Command.ForceCheck(disk)), sender.sent)
        assertEquals(1, notifier.acted.size)
    }

    @Test
    fun `it goes to the Nagios the notification was about, whichever else there is`() = runBlocking {
        profiles.save(draftWith(name = "Home"))
        val office = profiles.save(
            draftWith(name = "Office").copy(baseUrl = "https://office.example.org/nagios", username = "officer"),
        )

        runner.run(request(office, NoticeAction.RECHECK))

        val used = sentTo.last()
        assertEquals("office.example.org", used.baseUrl.host)
        assertEquals("officer", used.username)
    }

    @Test
    fun `a refusal is what the notification is told`() = runBlocking {
        val id = profiles.save(draftWith())
        sender.outcome = { CommandOutcome.Refused(Refusal.READ_ONLY) }

        runner.run(request(id, NoticeAction.ACKNOWLEDGE))

        val progress = notifier.acted.single().second as ActionProgress.Refused
        assertEquals(Refusal.READ_ONLY, progress.reason)
    }

    @Test
    fun `a profile that has gone sends nothing, and what it had showing is taken down`() = runBlocking {
        runner.run(request(profileId = 42, NoticeAction.ACKNOWLEDGE))

        assertTrue(sender.sent.isEmpty())
        assertTrue(notifier.acted.isEmpty())
        assertEquals(listOf(42L), notifier.cleared.toList())
    }

    private fun request(profileId: Long, action: NoticeAction) = ActionRequest(
        profileId = profileId,
        profileName = "Home",
        ref = disk,
        action = action,
        channelId = "problems_critical",
        title = "CRITICAL: Disk / on web01",
        text = "DISK CRITICAL - 3% free",
        color = 0,
    )

    private companion object {
        /** Three looks for a command's effect, with no waiting between them. */
        val NO_WAIT = List(3) { Duration.ZERO }
    }
}
