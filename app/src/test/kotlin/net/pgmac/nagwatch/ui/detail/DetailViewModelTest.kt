// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.CommandClientProvider
import net.pgmac.nagwatch.status.CommandPermission
import net.pgmac.nagwatch.status.CommandRepository
import net.pgmac.nagwatch.status.DetailRepository
import net.pgmac.nagwatch.status.FakeClient
import net.pgmac.nagwatch.status.FakeCommandClient
import net.pgmac.nagwatch.status.MutableClock
import net.pgmac.nagwatch.status.StatusRepository
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.cache.CachedDetail
import net.pgmac.nagwatch.status.cache.DetailCache
import net.pgmac.nagwatch.status.cache.StatusCache
import net.pgmac.nagwatch.status.commandProvider
import net.pgmac.nagwatch.status.comment
import net.pgmac.nagwatch.status.failure
import net.pgmac.nagwatch.status.inMemoryStatusDatabase
import net.pgmac.nagwatch.status.provider
import net.pgmac.nagwatch.status.saveTestProfile
import net.pgmac.nagwatch.status.snapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class DetailViewModelTest {
    private val profileDatabase = inMemoryProfileDatabase()
    private val statusDatabase = inMemoryStatusDatabase()
    private val clock = MutableClock(T0)
    private val profiles = ProfileRepository(profileDatabase.profiles(), SecretCipher(softwareKeySource()), Json)
    private val client = FakeClient()
    private val statuses = StatusRepository(profiles, provider(client), StatusCache(statusDatabase.cache()), clock)
    private val details =
        DetailRepository(profiles, provider(client), DetailCache(statusDatabase.cache(), clock), clock)
    private val sender = FakeCommandClient()
    private val commands = CommandRepository(profiles, provider(client), commandProvider(sender), NO_WAIT)
    private val created = mutableListOf<DetailViewModel>()

    private val web01 = HostStatus("web01", HostState.UP, CheckStatus(pluginOutput = "PING OK"))
    private val ping = ServiceStatus("web01", "Ping", ServiceState.OK, CheckStatus(pluginOutput = "rta 0.4ms"))
    private val critical = ping.copy(state = ServiceState.CRITICAL)
    private var serviceFetches = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        client.hostResult = { NagiosResult.Success(web01) }
        client.serviceResult = {
            serviceFetches++
            NagiosResult.Success(ping)
        }
        client.commentsResult = { NagiosResult.Success(listOf(comment(1))) }
    }

    @After
    fun tearDown() {
        // A view model left running can reach for Dispatchers.Main while the next test replaces it.
        runBlocking { created.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        profileDatabase.close()
        statusDatabase.close()
    }

    @Test
    fun `it loads what its arguments name as soon as it exists, without being asked`() {
        val id = saveProfile()

        val state = service(id, "web01", "Ping").awaitState { !it.detail.refreshing && it.detail.record != null }

        assertEquals("web01 / Ping", state.title)
        assertEquals(ping, (state.detail.record as CachedDetail.Service).status)
        assertEquals(listOf(comment(1)), state.detail.comments.items)
        assertEquals(1, serviceFetches)
    }

    @Test
    fun `with no service argument it is a host`() {
        val id = saveProfile()

        val viewModel = host(id, "web01")

        assertEquals(ObjectRef("web01"), viewModel.ref)
        val state = viewModel.awaitState { !it.detail.refreshing && it.detail.record != null }
        assertEquals(web01, (state.detail.record as CachedDetail.Host).status)
    }

    @Test
    fun `awkward names arrive untouched`() {
        val id = saveProfile()

        val viewModel = service(id, "a b", "Disk /var (50%) & more")

        assertEquals(ObjectRef("a b", "Disk /var (50%) & more"), viewModel.ref)
    }

    @Test
    fun `a host shows its services from the last poll beside its own record`() {
        val id = saveProfile()
        client.statusResult = {
            NagiosResult.Success(
                snapshot(hosts = listOf(web01)).copy(
                    serviceStates = listOf(ServiceStateEntry("web01", "Ping", ServiceState.OK)),
                ),
            )
        }
        runBlocking { statuses.refresh(id) }

        val state = host(id, "web01").awaitState { it.services.isNotEmpty() && !it.detail.refreshing }

        assertEquals(listOf("Ping"), state.services.map { it.service })
    }

    @Test
    fun `refresh asks Nagios again and keeps what is on screen meanwhile`() {
        val id = saveProfile()
        val viewModel = service(id, "web01", "Ping")
        viewModel.awaitState { !it.detail.refreshing && it.detail.record != null }

        viewModel.refresh()

        val state = viewModel.awaitState { serviceFetches == 2 && !it.detail.refreshing }
        assertEquals(ping, (state.detail.record as CachedDetail.Service).status)
    }

    @Test
    fun `when nothing can be loaded the reason reaches the screen`() {
        val id = saveProfile()
        client.serviceResult = { failure() }

        val state = service(id, "web01", "Ping").awaitState { it.detail.error != null && !it.detail.refreshing }

        assertNull(state.detail.record)
        assertTrue(state.detail.comments.items.isNotEmpty())
    }

    @Test
    fun `it finds out whether the user may send commands, once it exists`() {
        val id = saveProfile()
        sender.mayCommandResult = NagiosResult.Success(false)

        val state = service(id, "web01", "Ping").awaitState { it.permission != CommandPermission.UNKNOWN }

        assertEquals(CommandPermission.READ_ONLY, state.permission)
        assertFalse(state.actionsEnabled)
    }

    @Test
    fun `an acknowledgement is sent once, confirmed, and the screen brought up to date`() {
        val id = saveProfile()
        var acknowledged = false
        client.serviceResult = {
            serviceFetches++
            NagiosResult.Success(critical.copy(check = CheckStatus(acknowledged = acknowledged)))
        }
        sender.outcome = {
            acknowledged = true
            CommandOutcome.Accepted
        }
        val viewModel = service(id, "web01", "Ping")
        viewModel.awaitState { !it.detail.refreshing && it.canAcknowledge }

        viewModel.acknowledge("on it", sticky = false, notify = true, persistent = false)

        val state = viewModel.awaitState { it.action is ActionProgress.Confirmed && it.check?.acknowledged == true }
        assertEquals(listOf<Command>(Command.Acknowledge(ObjectRef("web01", "Ping"), "on it")), sender.sent)
        assertTrue(state.canRemoveAcknowledgement)
        assertFalse(state.canAcknowledge)
    }

    @Test
    fun `what was chosen in the dialog is what is sent`() {
        val id = saveProfile()
        val viewModel = host(id, "web01")

        viewModel.acknowledge("mine", sticky = true, notify = false, persistent = true)
        viewModel.awaitState { it.action?.inFlight == false }
        viewModel.addComment("a note")
        viewModel.awaitSettled<Command.AddComment>()
        viewModel.removeAcknowledgement()
        viewModel.awaitSettled<Command.RemoveAcknowledgement>()
        viewModel.forceCheck()
        viewModel.awaitSettled<Command.ForceCheck>()

        val web = ObjectRef("web01")
        assertEquals(
            listOf(
                Command.Acknowledge(web, "mine", sticky = true, notify = false, persistent = true),
                Command.AddComment(web, "a note"),
                Command.RemoveAcknowledgement(web),
                Command.ForceCheck(web),
            ),
            sender.sent,
        )
    }

    @Test
    fun `a second tap while a command is on its way sends nothing`() {
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
        }
        val slow = CommandRepository(profiles, provider(client), CommandClientProvider { held }, NO_WAIT)
        val viewModel = service(id, "web01", "Ping", slow)

        viewModel.forceCheck()
        viewModel.forceCheck()
        viewModel.addComment("again")
        val enabledMeanwhile = viewModel.awaitState { it.action != null }.actionsEnabled
        // Time for a second command to arrive, if one was wrongly on its way behind the first.
        runBlocking {
            begun.await()
            withTimeoutOrNull(SETTLE_MS) { while (held.sends < 2) delay(POLL_MS) }
        }
        release.complete(Unit)

        val state = viewModel.awaitState { it.action is ActionProgress.Refused }
        assertFalse("the buttons were off while it was on its way", enabledMeanwhile)
        assertEquals(1, held.sends)
        assertTrue("and then the buttons work again", state.actionsEnabled)
    }

    @Test
    fun `being refused as read-only turns the actions off`() {
        val id = saveProfile()
        sender.outcome = { CommandOutcome.Refused(Refusal.READ_ONLY) }
        val viewModel = service(id, "web01", "Ping")
        viewModel.awaitState { it.permission == CommandPermission.ALLOWED }

        viewModel.forceCheck()

        val state = viewModel.awaitState { it.action is ActionProgress.Refused }
        assertEquals(CommandPermission.READ_ONLY, state.permission)
        assertFalse(state.actionsEnabled)
    }

    @Test
    fun `a command that was not sent leaves the screen as it was`() {
        val id = saveProfile()
        sender.outcome = { CommandOutcome.NotSent(NagiosError.Http(502)) }
        val viewModel = service(id, "web01", "Ping")
        viewModel.awaitState { !it.detail.refreshing && it.detail.record != null }
        val before = serviceFetches

        viewModel.forceCheck()

        // The one fetch is the look at the last check time, taken before sending.
        val state = viewModel.awaitState { it.action is ActionProgress.NotSent }
        assertEquals(before + 1, serviceFetches)
        assertTrue(state.actionsEnabled)
    }

    private fun host(profileId: Long, name: String) = DetailViewModel(
        SavedStateHandle(mapOf(DetailViewModel.ARG_PROFILE to profileId, DetailViewModel.ARG_HOST to name)),
        details,
        statuses,
        commands,
    ).also(created::add)

    private fun service(
        profileId: Long,
        hostName: String,
        description: String,
        sending: CommandRepository = commands,
    ) = DetailViewModel(
        SavedStateHandle(
            mapOf(
                DetailViewModel.ARG_PROFILE to profileId,
                DetailViewModel.ARG_HOST to hostName,
                DetailViewModel.ARG_SERVICE to description,
            ),
        ),
        details,
        statuses,
        sending,
    ).also(created::add)

    private fun DetailViewModel.awaitState(predicate: (DetailUiState) -> Boolean): DetailUiState =
        runBlocking { withTimeout(TIMEOUT_MS) { state.first(predicate) } }

    /** Waits until a command of this kind has an answer. */
    private inline fun <reified C : Command> DetailViewModel.awaitSettled() = awaitState { state ->
        state.action.let { it != null && it.command is C && !it.inFlight }
    }

    private fun saveProfile(): Long = runBlocking { profiles.saveTestProfile() }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 500L
        const val POLL_MS = 10L

        /** Three looks for a command's effect, with no waiting between them. */
        val NO_WAIT = List(3) { java.time.Duration.ZERO }
    }
}
