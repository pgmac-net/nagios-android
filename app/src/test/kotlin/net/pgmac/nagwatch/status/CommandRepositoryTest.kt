// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.CommandClient
import net.pgmac.nagwatch.nagios.command.CommandOutcome
import net.pgmac.nagwatch.nagios.command.CommandReport
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.command.ServerDateFormat
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.profile.ProfileDraft
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.SecretInput
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What the app says became of a command, and what it never does with one. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class CommandRepositoryTest {
    private val profileDatabase = inMemoryProfileDatabase()
    private val profiles = ProfileRepository(profileDatabase.profiles(), SecretCipher(softwareKeySource()), Json)
    private val reader = FakeClient()
    private val sender = FakeCommandClient()
    private val users = mutableListOf<String>()
    private val repository = CommandRepository(
        profiles,
        provider(reader),
        commandProvider(sender) { users += it.username },
        LOOKS,
    )

    private val diskRef = ObjectRef("web01", "Disk /")
    private val hostRef = ObjectRef("db01")
    private var looks = 0

    @After
    fun close() = profileDatabase.close()

    @Test
    fun `a user who may send commands is asked about once and remembered`() = runBlocking {
        val id = profiles.saveTestProfile()

        assertEquals(CommandPermission.ALLOWED, repository.permission(id))
        assertEquals(CommandPermission.ALLOWED, repository.permission(id))

        assertEquals(1, sender.probes)
    }

    @Test
    fun `a read-only user is known as one`() = runBlocking {
        val id = profiles.saveTestProfile()
        sender.mayCommandResult = NagiosResult.Success(false)

        assertEquals(CommandPermission.READ_ONLY, repository.permission(id))
    }

    @Test
    fun `when it cannot be found out the answer is unknown, and it is asked again next time`() = runBlocking {
        val id = profiles.saveTestProfile()
        sender.mayCommandResult = failure(NagiosError.Unreachable(NagiosError.Unreachable.Reason.TIMEOUT, ""))

        assertEquals(CommandPermission.UNKNOWN, repository.permission(id))
        sender.mayCommandResult = NagiosResult.Success(false)

        assertEquals(CommandPermission.READ_ONLY, repository.permission(id))
        assertEquals(2, sender.probes)
    }

    @Test
    fun `what was learned about one user is not assumed of the next`() = runBlocking {
        val id = profiles.saveTestProfile()
        sender.mayCommandResult = NagiosResult.Success(false)
        assertEquals(CommandPermission.READ_ONLY, repository.permission(id))

        profiles.save(draft(id, username = "operator"))
        sender.mayCommandResult = NagiosResult.Success(true)

        assertEquals(CommandPermission.ALLOWED, repository.permission(id))
        assertEquals(listOf("nagwatch", "operator"), users)
    }

    @Test
    fun `a profile that is gone is not asked about`() = runBlocking {
        assertEquals(CommandPermission.UNKNOWN, repository.permission(NO_SUCH_PROFILE))

        assertEquals(0, sender.probes)
    }

    @Test
    fun `an acknowledgement is confirmed once the object shows it`() = runBlocking {
        val id = profiles.saveTestProfile()
        // Not on the first look: Nagios takes a few seconds.
        serveDisk { CheckStatus(acknowledged = looks >= 2) }
        val command = Command.Acknowledge(diskRef, "on it")

        val seen = repository.run(id, command).toList()

        assertEquals(
            listOf(
                ActionProgress.Sending(command),
                ActionProgress.Sent(command),
                ActionProgress.Confirmed(command),
            ),
            seen,
        )
        assertEquals(listOf<Command>(command), sender.sent)
        assertEquals("it stops looking once it has seen it", 2, looks)
    }

    @Test
    fun `accepted but never seen is not confirmed, and is never sent again`() = runBlocking {
        val id = profiles.saveTestProfile()
        serveDisk { CheckStatus(acknowledged = false) }
        val command = Command.Acknowledge(diskRef, "on it")

        val seen = repository.run(id, command).toList()

        assertEquals(ActionProgress.NotConfirmed(command), seen.last())
        assertEquals(LOOKS.size, looks)
        assertEquals("sent once, however long it went unseen", 1, sender.sent.size)
    }

    @Test
    fun `a look that fails does not end the looking`() = runBlocking {
        val id = profiles.saveTestProfile()
        reader.serviceResult = {
            looks++
            if (looks < 2) failure() else NagiosResult.Success(disk(CheckStatus(acknowledged = true)))
        }
        val command = Command.Acknowledge(diskRef, "on it")

        assertEquals(ActionProgress.Confirmed(command), repository.run(id, command).toList().last())
    }

    @Test
    fun `removing an acknowledgement is confirmed when it is gone`() = runBlocking {
        val id = profiles.saveTestProfile()
        serveDisk { CheckStatus(acknowledged = looks < 2) }
        val command = Command.RemoveAcknowledgement(diskRef)

        assertEquals(ActionProgress.Confirmed(command), repository.run(id, command).toList().last())
        assertEquals(2, looks)
    }

    @Test
    fun `a recheck is confirmed only by a check later than the one before it`() = runBlocking {
        val id = profiles.saveTestProfile()
        // The first look is the one taken before sending.
        serveDisk { CheckStatus(lastCheck = if (looks >= 3) T0.plusSeconds(5) else T0) }
        val command = Command.ForceCheck(diskRef)

        assertEquals(ActionProgress.Confirmed(command), repository.run(id, command).toList().last())
        assertEquals(3, looks)
    }

    @Test
    fun `a recheck that leaves the last check time where it was is not confirmed`() = runBlocking {
        val id = profiles.saveTestProfile()
        serveDisk { CheckStatus(lastCheck = T0) }
        val command = Command.ForceCheck(diskRef)

        assertEquals(ActionProgress.NotConfirmed(command), repository.run(id, command).toList().last())
    }

    @Test
    fun `a recheck of a host looks at the host`() = runBlocking {
        val id = profiles.saveTestProfile()
        reader.hostResult = { name ->
            looks++
            val check = CheckStatus(lastCheck = if (looks >= 2) T0.plusSeconds(5) else T0)
            NagiosResult.Success(HostStatus(name, HostState.DOWN, check))
        }
        val command = Command.ForceCheck(hostRef)

        assertEquals(ActionProgress.Confirmed(command), repository.run(id, command).toList().last())
    }

    @Test
    fun `a comment is confirmed by the words Nagios keeps, not the words typed`() = runBlocking {
        val id = profiles.saveTestProfile()
        val typed = "  see <ticket>; 42  "
        reader.commentsResult = { NagiosResult.Success(listOf(comment(1), comment(2, text = "see ticket  42"))) }
        val command = Command.AddComment(diskRef, typed)

        assertEquals(ActionProgress.Confirmed(command), repository.run(id, command).toList().last())
    }

    @Test
    fun `somebody else's comment does not confirm this one`() = runBlocking {
        val id = profiles.saveTestProfile()
        reader.commentsResult = { NagiosResult.Success(listOf(comment(1, text = "something else"))) }
        val command = Command.AddComment(diskRef, "mine")

        assertEquals(ActionProgress.NotConfirmed(command), repository.run(id, command).toList().last())
    }

    @Test
    fun `a refusal is passed on with its reason and nothing is looked for`() = runBlocking {
        val id = profiles.saveTestProfile()
        serveDisk { CheckStatus(acknowledged = true) }
        sender.outcome = { CommandOutcome.Refused(Refusal.NOT_AUTHORISED, "Sorry") }
        val command = Command.Acknowledge(diskRef, "on it")

        val seen = repository.run(id, command).toList()

        assertEquals(
            listOf(
                ActionProgress.Sending(command),
                ActionProgress.Refused(command, Refusal.NOT_AUTHORISED, "Sorry"),
            ),
            seen,
        )
        assertEquals(0, looks)
    }

    @Test
    fun `being refused as read-only settles the question of permission`() = runBlocking {
        val id = profiles.saveTestProfile()
        assertEquals(CommandPermission.ALLOWED, repository.permission(id))
        sender.outcome = { CommandOutcome.Refused(Refusal.READ_ONLY) }

        repository.run(id, Command.ForceCheck(diskRef)).toList()

        assertEquals(CommandPermission.READ_ONLY, repository.permission(id))
        assertEquals("without asking again", 1, sender.probes)
    }

    @Test
    fun `being refused for one object says nothing about the user`() = runBlocking {
        val id = profiles.saveTestProfile()
        assertEquals(CommandPermission.ALLOWED, repository.permission(id))
        sender.outcome = { CommandOutcome.Refused(Refusal.NOT_AUTHORISED) }

        repository.run(id, Command.ForceCheck(diskRef)).toList()

        assertEquals(CommandPermission.ALLOWED, repository.permission(id))
    }

    @Test
    fun `not sent and unknown are kept apart`() = runBlocking {
        val id = profiles.saveTestProfile()
        val refused = NagiosError.Unreachable(NagiosError.Unreachable.Reason.REFUSED, "")
        val timeout = NagiosError.Unreachable(NagiosError.Unreachable.Reason.TIMEOUT, "")
        val command = Command.RemoveAcknowledgement(diskRef)

        sender.outcome = { CommandOutcome.NotSent(refused) }
        assertEquals(
            ActionProgress.NotSent(command, StatusError.Nagios(refused)),
            repository.run(id, command).toList().last(),
        )

        sender.outcome = { CommandOutcome.Unknown(timeout) }
        assertEquals(ActionProgress.Unknown(command, timeout), repository.run(id, command).toList().last())
        assertEquals("neither was tried a second time", 2, sender.sent.size)
    }

    @Test
    fun `with no profile nothing is sent`() = runBlocking {
        val command = Command.ForceCheck(diskRef)

        val seen = repository.run(NO_SUCH_PROFILE, command).toList()

        assertEquals(ActionProgress.NotSent(command, StatusError.ProfileMissing), seen.last())
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun `a send that has begun is finished even when nobody is waiting for the answer`() = runBlocking {
        val id = profiles.saveTestProfile()
        val begun = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val slow = object : CommandClient {
            override suspend fun mayCommand(): NagiosResult<Boolean> = NagiosResult.Success(true)

            override suspend fun send(command: Command, rememberedFormat: ServerDateFormat?): CommandReport {
                begun.complete(Unit)
                release.await()
                finished.complete(Unit)
                return CommandReport(CommandOutcome.Accepted)
            }
        }
        val waiting = CommandRepository(profiles, provider(reader), CommandClientProvider { slow }, LOOKS)
        val screen = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            waiting.run(id, Command.RemoveAcknowledgement(diskRef)).toList()
        }
        begun.await()

        // The screen goes away while the request is on the wire, and only then does Nagios answer.
        screen.cancel()
        release.complete(Unit)
        screen.join()

        assertTrue("the request was not cut off part-way", finished.isCompleted)
    }

    private fun serveDisk(check: () -> CheckStatus) {
        reader.serviceResult = {
            looks++
            NagiosResult.Success(disk(check()))
        }
    }

    private fun disk(check: CheckStatus) = ServiceStatus("web01", "Disk /", ServiceState.CRITICAL, check)

    private fun draft(id: Long, username: String) = ProfileDraft(
        id = id,
        name = "Home",
        baseUrl = "https://nagios.example.org/nagios",
        username = username,
        password = SecretInput.Keep,
        accessClientId = "",
        accessClientSecret = SecretInput.Keep,
        customHeaders = emptyList(),
        allowCleartext = false,
    )

    private companion object {
        const val NO_SUCH_PROFILE = 999L

        /** Four looks for a command's effect, with no waiting between them. */
        val LOOKS: List<Duration> = List(4) { Duration.ZERO }
    }
}
