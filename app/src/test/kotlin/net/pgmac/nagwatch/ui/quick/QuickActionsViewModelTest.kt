// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.quick

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.CommandClient
import net.pgmac.nagwatch.nagios.command.CommandOutcome
import net.pgmac.nagwatch.nagios.command.CommandReport
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.command.ServerClock
import net.pgmac.nagwatch.nagios.command.ServerDateFormat
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
import net.pgmac.nagwatch.status.CommandClientProvider
import net.pgmac.nagwatch.status.CommandPermission
import net.pgmac.nagwatch.status.CommandRepository
import net.pgmac.nagwatch.status.FakeClient
import net.pgmac.nagwatch.status.FakeCommandClient
import net.pgmac.nagwatch.status.MutableClock
import net.pgmac.nagwatch.status.StatusRepository
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.cache.StatusCache
import net.pgmac.nagwatch.status.commandProvider
import net.pgmac.nagwatch.status.inMemoryStatusDatabase
import net.pgmac.nagwatch.status.provider
import net.pgmac.nagwatch.status.saveTestProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A quick action is the detail screen's command, sent without the form. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class QuickActionsViewModelTest {
    private val profileDatabase = inMemoryProfileDatabase()
    private val statusDatabase = inMemoryStatusDatabase()
    private val profiles = ProfileRepository(profileDatabase.profiles(), SecretCipher(softwareKeySource()), Json)
    private val client = FakeClient()
    private val statuses =
        StatusRepository(profiles, provider(client), StatusCache(statusDatabase.cache()), MutableClock(T0))
    private val sender = FakeCommandClient()
    private val commands = CommandRepository(profiles, provider(client), commandProvider(sender), NO_WAIT)
    private val created = mutableListOf<QuickActionsViewModel>()

    private val disk = ObjectRef("web01", "Disk /")
    private var acknowledged = false

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        // What Nagios says of the service when it is read back: acknowledged once that has been sent.
        client.serviceResult = {
            NagiosResult.Success(
                ServiceStatus("web01", "Disk /", ServiceState.CRITICAL, CheckStatus(acknowledged = acknowledged)),
            )
        }
        sender.outcome = { command ->
            when (command) {
                is Command.Acknowledge -> acknowledged = true
                is Command.RemoveAcknowledgement -> acknowledged = false
                else -> Unit
            }
            CommandOutcome.Accepted
        }
    }

    @After
    fun tearDown() {
        runBlocking { created.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        profileDatabase.close()
        statusDatabase.close()
    }

    @Test
    fun `an acknowledgement is sent at once with the app's words and the form's own flags`() {
        val id = saveProfile()
        val viewModel = viewModel()

        viewModel.run(id, QuickAction.ACKNOWLEDGE, disk)

        viewModel.awaitState { it.progress is ActionProgress.Confirmed }
        val sent = sender.sent.single() as Command.Acknowledge
        assertEquals(disk, sent.target)
        assertEquals(DEFAULT_ACK_COMMENT, sent.comment)
        assertEquals("not sticky, notify, not persistent", Triple(false, true, false), sent.flags())
    }

    @Test
    fun `a profile with words of its own sends those`() {
        val id = saveProfile()
        runBlocking {
            val stored = checkNotNull(profiles.get(id))
            profiles.save(draftOf(stored.id, "Seen, on it"))
        }
        val viewModel = viewModel()

        viewModel.run(id, QuickAction.ACKNOWLEDGE, disk)

        viewModel.awaitState { it.progress is ActionProgress.Confirmed }
        assertEquals("Seen, on it", (sender.sent.single() as Command.Acknowledge).comment)
    }

    @Test
    fun `once Nagios has confirmed, the lists are fetched again`() {
        val id = saveProfile()
        val viewModel = viewModel()

        viewModel.run(id, QuickAction.ACKNOWLEDGE, disk)

        viewModel.awaitState { it.progress is ActionProgress.Confirmed }
        runBlocking { withTimeout(TIMEOUT_MS) { while (client.fetches == 0) delay(POLL_MS) } }
        assertTrue(client.fetches > 0)
    }

    @Test
    fun `undo takes back the acknowledgement that was just confirmed`() {
        val id = saveProfile()
        val viewModel = viewModel()
        viewModel.run(id, QuickAction.ACKNOWLEDGE, disk)
        viewModel.awaitState { it.progress is ActionProgress.Confirmed }

        viewModel.undo()

        viewModel.awaitState { (it.progress as? ActionProgress.Confirmed)?.command is Command.RemoveAcknowledgement }
        assertEquals(Command.RemoveAcknowledgement(disk), sender.sent.last())
        assertEquals(2, sender.sent.size)
    }

    @Test
    fun `there is nothing to undo after a recheck, or before anything was confirmed`() {
        val id = saveProfile()
        val viewModel = viewModel()

        viewModel.undo()
        viewModel.run(id, QuickAction.RECHECK, disk)
        viewModel.awaitState { it.progress?.inFlight == false }
        viewModel.undo()

        assertEquals(listOf<Command>(Command.ForceCheck(disk)), sender.sent)
    }

    @Test
    fun `a second action while one is on its way sends nothing`() {
        val id = saveProfile()
        val begun = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = object : CommandClient {
            var sends = 0

            override suspend fun mayCommand(): NagiosResult<Boolean> = NagiosResult.Success(true)

            override suspend fun send(command: Command, rememberedFormat: ServerDateFormat?): CommandReport {
                sends++
                begun.complete(Unit)
                release.await()
                return CommandReport(CommandOutcome.Refused(Refusal.NOT_AUTHORISED))
            }

            override suspend fun serverClock(target: ObjectRef, rememberedFormat: ServerDateFormat?): ServerClock? =
                null
        }
        val slow = CommandRepository(profiles, provider(client), CommandClientProvider { held }, NO_WAIT)
        val viewModel = viewModel(slow)

        viewModel.run(id, QuickAction.RECHECK, disk)
        runBlocking { begun.await() }
        val enabledMeanwhile = viewModel.state.value.forProfile(id).enabled
        viewModel.run(id, QuickAction.ACKNOWLEDGE, disk)
        runBlocking { withTimeoutOrNull(SETTLE_MS) { while (held.sends < 2) delay(POLL_MS) } }
        release.complete(Unit)

        viewModel.awaitState { it.progress is ActionProgress.Refused }
        assertFalse("the menu's items are off while one is on its way", enabledMeanwhile)
        assertEquals(1, held.sends)
    }

    @Test
    fun `opening a menu learns that the user is read-only, and the items go off`() {
        val id = saveProfile()
        sender.mayCommandResult = NagiosResult.Success(false)
        val viewModel = viewModel()
        assertTrue("until it is known, nothing is held back", viewModel.state.value.forProfile(id).enabled)

        viewModel.opened(id)

        val state = viewModel.awaitState { it.forProfile(id).permission != CommandPermission.UNKNOWN }.forProfile(id)
        assertTrue(state.readOnly)
        assertFalse(state.enabled)
    }

    @Test
    fun `a refusal for being read-only is remembered, and said`() {
        val id = saveProfile()
        sender.outcome = { CommandOutcome.Refused(Refusal.READ_ONLY) }
        val viewModel = viewModel()

        viewModel.run(id, QuickAction.RECHECK, disk)

        val state = viewModel.awaitState { it.progress is ActionProgress.Refused }
        assertTrue(state.forProfile(id).readOnly)
    }

    @Test
    fun `what one Nagios says its user may do says nothing about another`() {
        val home = saveProfile()
        val office = runBlocking { profiles.saveTestProfile("Office") }
        sender.outcome = { CommandOutcome.Refused(Refusal.READ_ONLY) }
        val viewModel = viewModel()

        viewModel.run(home, QuickAction.RECHECK, disk)

        val state = viewModel.awaitState { it.progress is ActionProgress.Refused }
        assertTrue(state.forProfile(home).readOnly)
        assertFalse("read-only on Home is not read-only on Office", state.forProfile(office).readOnly)
        assertEquals(CommandPermission.UNKNOWN, state.forProfile(office).permission)
    }

    @Test
    fun `an undo goes to the Nagios the acknowledgement went to, whatever profile is on screen by then`() {
        val home = saveProfile()
        runBlocking { profiles.saveTestProfile("Office") }
        val viewModel = viewModel()
        viewModel.run(home, QuickAction.ACKNOWLEDGE, disk)
        val confirmed = viewModel.awaitState { it.progress is ActionProgress.Confirmed }
        assertEquals(home, confirmed.sentTo)

        // The person has switched to Office meanwhile. Nothing here is told that, and nothing needs to be.
        viewModel.undo()

        val undone = viewModel.awaitState {
            (it.progress as? ActionProgress.Confirmed)?.command is Command.RemoveAcknowledgement
        }
        assertEquals("the undo is about Home too", home, undone.sentTo)
        assertEquals(Command.RemoveAcknowledgement(disk), sender.sent.last())
    }

    @Test
    fun `news is cleared when it has been read, but never the news of a later command`() {
        val id = saveProfile()
        val viewModel = viewModel()
        viewModel.run(id, QuickAction.RECHECK, disk)
        val first = checkNotNull(viewModel.awaitState { it.progress?.inFlight == false }.progress)
        viewModel.run(id, QuickAction.ACKNOWLEDGE, disk)
        val second = checkNotNull(viewModel.awaitState { it.progress is ActionProgress.Confirmed }.progress)

        viewModel.dismiss(first)
        assertEquals(
            "the first's time is up, but it is the second that is showing",
            second,
            viewModel.state.value.progress,
        )

        viewModel.dismiss(second)
        assertNull(viewModel.state.value.progress)
    }

    private fun Command.Acknowledge.flags() = Triple(sticky, notify, persistent)

    private fun viewModel(sending: CommandRepository = commands) =
        QuickActionsViewModel(sending, profiles, statuses).also(created::add)

    private fun QuickActionsViewModel.awaitState(predicate: (QuickState) -> Boolean): QuickState =
        runBlocking { withTimeout(TIMEOUT_MS) { state.first(predicate) } }

    private fun saveProfile(): Long = runBlocking { profiles.saveTestProfile() }

    private fun draftOf(id: Long, ackComment: String) = net.pgmac.nagwatch.profile.ProfileDraft(
        id = id,
        name = "Home",
        baseUrl = "https://nagios.example.org/nagios",
        username = "nagwatch",
        password = net.pgmac.nagwatch.profile.SecretInput.Keep,
        accessClientId = "",
        accessClientSecret = net.pgmac.nagwatch.profile.SecretInput.Keep,
        customHeaders = emptyList(),
        allowCleartext = false,
        ackComment = ackComment,
    )

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 500L
        const val POLL_MS = 10L

        /** Three looks for a command's effect, with no waiting between them. */
        val NO_WAIT = List(3) { Duration.ZERO }
    }
}
