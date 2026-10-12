// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.notify

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
import net.pgmac.nagwatch.notify.TestNotificationAccess
import net.pgmac.nagwatch.notify.draftWith
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.status.FakeClient
import net.pgmac.nagwatch.status.FakeSelectedProfile
import net.pgmac.nagwatch.status.MutableClock
import net.pgmac.nagwatch.status.StatusRepository
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.status.cache.StatusCache
import net.pgmac.nagwatch.status.failure
import net.pgmac.nagwatch.status.inMemoryStatusDatabase
import net.pgmac.nagwatch.status.provider
import net.pgmac.nagwatch.status.snapshot
import net.pgmac.nagwatch.ui.PermissionRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Notifications are offered once for a profile, after it has loaded, and never switched on unasked. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class NotificationOfferViewModelTest {
    private val database = inMemoryProfileDatabase()
    private val profiles = ProfileRepository(database.profiles(), SecretCipher(softwareKeySource()), Json)
    private val statusDatabase = inMemoryStatusDatabase()
    private val client = FakeClient()
    private val statuses =
        StatusRepository(profiles, provider(client), StatusCache(statusDatabase.cache()), MutableClock(T0))
    private val created = mutableListOf<NotificationOfferViewModel>()

    // Written from the view model's coroutine, read from the test's thread.
    private val calls = CopyOnWriteArrayList<String>()
    private val request = PermissionRequest({ calls += "prompt" }, { calls += "settings" })

    private val off = NotificationSettings(enabled = false)

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        runBlocking { created.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        database.close()
        statusDatabase.close()
    }

    @Test
    fun `nothing is offered until the profile has loaded`() {
        save()

        assertNull(viewModel().settledOffer())
    }

    @Test
    fun `once the profile has loaded, notifications are offered for it`() {
        val id = loaded()

        assertEquals(id, viewModel().awaitOffer { it != null }?.id)
    }

    @Test
    fun `a profile that failed to load is not offered notifications`() {
        val id = save()
        client.statusResult = { failure() }
        runBlocking { statuses.refresh(id) }

        assertNull(viewModel().settledOffer())
    }

    @Test
    fun `the offer waits while a profile showing an earlier result is being fetched again`() {
        val id = loaded()
        val fetching = CountDownLatch(1)
        val release = CountDownLatch(1)
        client.statusResult = {
            fetching.countDown()
            release.await(WAIT_SECONDS, TimeUnit.SECONDS)
            NagiosResult.Success(snapshot())
        }
        val app = thread { runBlocking { statuses.refresh(id) } }
        assertTrue(fetching.await(WAIT_SECONDS, TimeUnit.SECONDS))
        val viewModel = viewModel()

        assertNull("the fetch may yet fail", viewModel.settledOffer())

        release.countDown()
        app.join()
        assertEquals(id, viewModel.awaitOffer { it != null }?.id)
    }

    @Test
    fun `a profile that loaded once and has since failed is not offered notifications`() {
        val id = loaded()
        client.statusResult = { failure() }
        runBlocking { statuses.refresh(id) }

        assertNull("not the moment: the last thing it did was fail", viewModel().settledOffer())
    }

    @Test
    fun `nothing is offered for a profile that has them on, or that was offered them before`() {
        val on = loaded(NotificationSettings(enabled = true))
        // On, and by some route that never recorded an offer: there is still nothing to offer.
        database.openHelper.writableDatabase.execSQL("UPDATE profiles SET notify_offered = 0")
        assertNull(viewModel(FakeSelectedProfile(on)).settledOffer())

        val offered = loaded(name = "Office")
        runBlocking { profiles.notificationsOffered(offered, accepted = false) }
        assertNull(viewModel(FakeSelectedProfile(offered)).settledOffer())
    }

    @Test
    fun `the offer is for the profile on screen`() {
        loaded()
        val office = loaded(name = "Office")

        assertEquals(office, viewModel(FakeSelectedProfile(office)).awaitOffer { it != null }?.id)
    }

    @Test
    fun `Turn on switches them on at once when Android already allows notifications`() {
        val id = loaded()
        val viewModel = viewModel(access = TestNotificationAccess(held = true))
        viewModel.awaitOffer { it != null }

        viewModel.accept(request)

        assertTrue(awaitProfile(id) { it.notifications.enabled }.notificationsOffered)
        assertTrue("Android was not asked for what it had already given", calls.isEmpty())
        assertNull(viewModel.awaitOffer { it == null })
    }

    @Test
    fun `Turn on asks Android first, and switches them on only when it says yes`() {
        val id = loaded()
        val viewModel = viewModel(access = TestNotificationAccess(held = false))
        viewModel.awaitOffer { it != null }

        viewModel.accept(request)
        awaitCalls(1)
        val asked = awaitProfile(id) { it.notificationsOffered }

        assertEquals(listOf("prompt"), calls.toList())
        assertFalse("not before Android has answered", asked.notifications.enabled)

        viewModel.answered(granted = true, canAskAgain = true)
        assertTrue(awaitProfile(id) { it.notifications.enabled }.notifications.enabled)
    }

    @Test
    fun `a refusal leaves them off, and the offer is not made again`() {
        val id = loaded()
        val access = TestNotificationAccess(held = false)
        val viewModel = viewModel(access = access)
        viewModel.awaitOffer { it != null }
        viewModel.accept(request)
        awaitCalls(1)
        awaitProfile(id) { it.notificationsOffered }

        viewModel.answered(granted = false, canAskAgain = false)
        pause()

        assertFalse(runBlocking { profiles.get(id) }!!.notifications.enabled)
        assertTrue("Android will not ask again, and the app remembers that", access.history.exhausted)
        assertNull(viewModel.settledOffer())
    }

    @Test
    fun `when Android has stopped asking, Turn on opens its settings and switches nothing on`() {
        val id = loaded()
        val access = TestNotificationAccess(held = false).also { it.history.exhausted = true }
        val viewModel = viewModel(access = access)
        viewModel.awaitOffer { it != null }

        viewModel.accept(request)
        awaitCalls(1)
        val after = awaitProfile(id) { it.notificationsOffered }
        pause()

        assertEquals(listOf("settings"), calls.toList())
        assertFalse(after.notifications.enabled)
    }

    @Test
    fun `Not now is remembered, and nothing is switched on`() {
        val id = loaded()
        val viewModel = viewModel()
        viewModel.awaitOffer { it != null }

        viewModel.decline()

        assertFalse(awaitProfile(id) { it.notificationsOffered }.notifications.enabled)
        assertNull(viewModel.awaitOffer { it == null })
        assertTrue(calls.isEmpty())
    }

    private fun save(settings: NotificationSettings = off, name: String = "Home"): Long =
        runBlocking { profiles.save(draftWith(settings, name = name)) }

    /** A saved profile whose first load has succeeded. */
    private fun loaded(settings: NotificationSettings = off, name: String = "Home"): Long =
        save(settings, name).also { id -> runBlocking { statuses.refresh(id) } }

    private fun viewModel(
        selected: FakeSelectedProfile = FakeSelectedProfile(),
        access: TestNotificationAccess = TestNotificationAccess(held = true),
    ) = NotificationOfferViewModel(profiles, selected, statuses, access.access).also(created::add)

    private fun NotificationOfferViewModel.awaitOffer(predicate: (Profile?) -> Boolean): Profile? =
        runBlocking { withTimeout(TIMEOUT_MS) { offerFor.first(predicate) } }

    /** What is offered once things have had time to settle: for the cases where the answer is "nothing". */
    private fun NotificationOfferViewModel.settledOffer(): Profile? = runBlocking {
        var last: Profile? = null
        withTimeoutOrNull(NOTHING_HAPPENS_MS) { offerFor.collect { last = it } }
        last
    }

    private fun awaitProfile(id: Long, predicate: (Profile) -> Boolean): Profile = runBlocking {
        withTimeout(TIMEOUT_MS) {
            var profile = profiles.get(id)
            while (profile == null || !predicate(profile)) {
                delay(POLL_MS)
                profile = profiles.get(id)
            }
            profile
        }
    }

    private fun pause() = runBlocking { delay(NOTHING_HAPPENS_MS) }

    /**
     * Android is asked after the offer has been written down, from a coroutine. Seeing the write
     * does not mean the asking has happened yet, so the tests wait for the asking itself.
     */
    private fun awaitCalls(count: Int) = runBlocking {
        withTimeout(TIMEOUT_MS) {
            while (calls.size < count) delay(POLL_MS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val POLL_MS = 10L
        const val NOTHING_HAPPENS_MS = 300L
        const val WAIT_SECONDS = 5L
    }
}
