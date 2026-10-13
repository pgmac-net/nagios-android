// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.graph

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.graph.GraphAnswer
import net.pgmac.nagwatch.nagios.graph.GraphClient
import net.pgmac.nagwatch.nagios.graph.GraphPeriod
import net.pgmac.nagwatch.nagios.graph.GraphRequest
import net.pgmac.nagwatch.nagios.graph.GraphSize
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.profile.GraphSettings
import net.pgmac.nagwatch.profile.ProfileDraft
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.SecretInput
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import net.pgmac.nagwatch.status.GraphMemory
import net.pgmac.nagwatch.status.GraphRepository
import net.pgmac.nagwatch.status.MutableClock
import net.pgmac.nagwatch.status.T0
import net.pgmac.nagwatch.ui.detail.DetailViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What a graph's place on a screen shows, and what it asks for to show it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class GraphViewModelTest {
    private val profileDatabase = inMemoryProfileDatabase()
    private val memory = GraphMemory()
    private val profiles =
        ProfileRepository(profileDatabase.profiles(), SecretCipher(softwareKeySource()), Json, setOf(memory))

    // Written from whichever thread the repository resumes on, read from the test's.
    private val asked = java.util.concurrent.CopyOnWriteArrayList<GraphRequest>()
    private var answer: suspend (GraphRequest) -> GraphAnswer = { picture() }
    private val repository = GraphRepository(
        profiles,
        {
            object : GraphClient {
                override suspend fun fetch(request: GraphRequest): GraphAnswer {
                    asked += request
                    return answer(request)
                }

                override suspend fun pageUrl(ref: ObjectRef): String = "page"
            }
        },
        memory,
        MutableClock(T0),
    )
    private val created = mutableListOf<GraphViewModel>()
    private val inline = GraphSize(300, 120)

    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() {
        runBlocking { created.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        profileDatabase.close()
    }

    @Test
    fun `nothing is asked for until the screen says how much room there is`() {
        val model = service(saveProfile())

        val seen = watching(model) {
            model.refresh()
            model.choose(GraphPeriod.WEEK)
        }

        assertTrue("asked ${asked.size} times", asked.isEmpty())
        assertFalse(seen.any { it.visible })
    }

    @Test
    fun `a service's graph is asked for at the size there is room for, and shown`() {
        val model = service(saveProfile())

        model.show(inline)
        val state = model.awaitState { it.graph != null }

        assertEquals(listOf(GraphRequest("web01", "Disk /", GraphPeriod.DAY, inline)), asked)
        assertEquals("Disk /", state.service)
        assertTrue(state.visible && !state.loading)
    }

    @Test
    fun `the same size again is not another question, a different one is`() {
        val model = service(saveProfile())
        model.show(inline)
        model.awaitState { it.graph != null }

        // Turning the screen runs the layout again, with the same size: nothing should stir.
        val seen = watching(model) { model.show(inline) }
        assertFalse("the picture on screen got a spinner for no reason", seen.any { it.loading })
        assertEquals(1, asked.size)

        model.show(GraphSize.FULL)
        awaitAsked(2)
        model.awaitState { !it.loading }

        assertEquals(listOf(inline, GraphSize.FULL), asked.map { it.size })
    }

    @Test
    fun `room is left for a graph while it is on its way`() {
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            picture()
        }
        val model = service(saveProfile())

        model.show(inline)
        val waiting = model.awaitState { it.loading }

        assertTrue(waiting.visible)
        assertNull(waiting.graph)
        gate.complete(Unit)
        assertTrue(model.awaitState { !it.loading }.graph != null)
    }

    @Test
    fun `while another period loads, the graph already on screen stays`() {
        val model = service(saveProfile())
        model.show(inline)
        val day = model.awaitState { it.graph != null }.graph
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            picture()
        }

        model.choose(GraphPeriod.WEEK)
        val between = model.awaitState { it.loading }

        assertEquals(GraphPeriod.WEEK, between.period)
        assertSame("the day's picture, until the week's is here", day, between.graph)
        gate.complete(Unit)
        val week = model.awaitState { !it.loading }
        assertEquals(GraphPeriod.WEEK, week.graph?.period)
        assertEquals(listOf(GraphPeriod.DAY, GraphPeriod.WEEK), asked.map { it.period })
    }

    @Test
    fun `choosing the period already chosen asks nothing`() {
        val model = service(saveProfile())
        model.show(inline)
        model.awaitState { it.graph != null }

        val seen = watching(model) { model.choose(GraphPeriod.DAY) }

        assertEquals(1, asked.size)
        assertFalse("the picture on screen got a spinner for no reason", seen.any { it.loading })
    }

    @Test
    fun `the period chosen is the one the profile's next graph opens on`() {
        val id = saveProfile()
        val other = saveProfile("Other")
        val model = service(id)
        model.show(inline)
        model.choose(GraphPeriod.MONTH)

        assertEquals(GraphPeriod.MONTH, service(id, "Load").state.value.period)
        assertEquals("another profile is still on its own", GraphPeriod.DAY, service(other).state.value.period)
    }

    @Test
    fun `a period chosen on one screen is the period on another showing the same profile, which asks for it`() {
        val id = saveProfile()
        val detail = service(id)
        detail.show(inline)
        detail.awaitState { it.graph != null }
        val viewer = service(id)
        viewer.show(GraphSize.FULL)
        viewer.awaitState { it.graph != null }
        asked.clear()

        viewer.choose(GraphPeriod.WEEK)

        // Found on a phone: the detail screen under the viewer kept, and went on asking for, its own.
        assertEquals(GraphPeriod.WEEK, detail.awaitState { it.graph?.period == GraphPeriod.WEEK }.period)
        viewer.awaitState { it.graph?.period == GraphPeriod.WEEK }
        assertEquals(
            setOf(
                GraphRequest("web01", "Disk /", GraphPeriod.WEEK, inline),
                GraphRequest("web01", "Disk /", GraphPeriod.WEEK, GraphSize.FULL),
            ),
            asked.toSet(),
        )
    }

    @Test
    fun `a period chosen for one profile leaves a screen showing another alone`() {
        val one = service(saveProfile())
        one.show(inline)
        one.awaitState { it.graph != null }
        val other = service(saveProfile("Other"))

        val seen = watching(one) { other.choose(GraphPeriod.YEAR) }

        assertEquals(GraphPeriod.DAY, one.state.value.period)
        assertFalse(seen.any { it.loading })
        assertEquals(1, asked.size)
    }

    @Test
    fun `it is called what its detail screen is called`() {
        assertEquals("web01 / Disk /", service(saveProfile()).state.value.title)
        assertEquals("web01", host(saveProfile("Two")).state.value.title)
    }

    @Test
    fun `a service with no graph has no place on the screen, and the next visit does not even leave room`() {
        answer = { GraphAnswer.NoGraph }
        val id = saveProfile()
        val first = service(id)
        first.show(inline)
        awaitAsked(1)
        first.awaitState { !it.loading }
        assertFalse(first.state.value.visible)

        val second = service(id)
        val seen = watching(second) { second.show(inline) }

        assertFalse("never loading, never visible", seen.any { it.visible })
        assertEquals("and not asked about again", 1, asked.size)
    }

    @Test
    fun `a refresh asks again about a graph that was ruled out, and shows it if it is there now`() {
        answer = { GraphAnswer.NoGraph }
        val model = service(saveProfile())
        model.show(inline)
        awaitAsked(1)
        model.awaitState { !it.loading }
        answer = { picture() }

        model.refresh()

        assertTrue(model.awaitState { it.graph != null }.visible)
        assertEquals(2, asked.size)
    }

    @Test
    fun `asking again about a graph that was ruled out leaves no room for it until it turns up`() {
        answer = { GraphAnswer.NoGraph }
        val model = service(saveProfile())
        model.show(inline)
        awaitAsked(1)
        model.awaitState { !it.loading }
        val gate = CompletableDeferred<Unit>()
        answer = {
            gate.await()
            picture()
        }

        model.refresh()
        awaitAsked(2)

        assertFalse("nothing flickers on a screen that had no graph", model.state.value.visible)
        gate.complete(Unit)
        assertTrue(model.awaitState { it.graph != null }.visible)
    }

    @Test
    fun `a refresh that finds the graph gone takes it off the screen`() {
        val model = service(saveProfile())
        model.show(inline)
        model.awaitState { it.graph != null }
        answer = { GraphAnswer.NoGraph }

        model.refresh()

        assertFalse(model.awaitState { !it.loading && it.graph == null }.visible)
    }

    @Test
    fun `a period that cannot be fetched just now leaves the graph on screen, and the chips on its period`() {
        val model = service(saveProfile())
        model.show(inline)
        val day = model.awaitState { it.graph != null }.graph
        answer = { GraphAnswer.Unavailable(NagiosError.Http(500)) }

        val seen = watching(model) {
            model.choose(GraphPeriod.YEAR)
            awaitAsked(2)
        }
        val after = model.awaitState { !it.loading && it.period == GraphPeriod.DAY }

        assertTrue("the picture never left the screen, not even for a moment", seen.all { it.graph === day })

        // Found on a phone with the server stopped: the whole section went, chips and all.
        assertSame(day, after.graph)
        assertTrue(after.visible)
        assertFalse("there is something to show, so nothing to say", after.unreachable)
        assertEquals(
            "the next graph opens on a period that can be shown",
            GraphPeriod.DAY,
            service(1, "Load").state.value.period,
        )
    }

    @Test
    fun `with nothing to show and nobody to ask, it says so and takes no room`() {
        answer = { GraphAnswer.Unavailable(NagiosError.Http(500)) }
        val model = service(saveProfile())

        model.show(inline)
        awaitAsked(1)
        val state = model.awaitState { !it.loading && it.unreachable }

        assertFalse(state.visible)
        answer = { picture() }
        model.refresh()
        assertFalse("and stops saying so once there is a graph", model.awaitState { it.graph != null }.unreachable)
    }

    @Test
    fun `no graph is not the same as nobody to ask`() {
        answer = { GraphAnswer.NoGraph }
        val model = service(saveProfile())

        model.show(inline)
        awaitAsked(1)

        assertFalse(model.awaitState { !it.loading }.unreachable)
    }

    @Test
    fun `a host has no graph and is never asked about`() {
        val model = host(saveProfile())

        model.show(inline)
        model.refresh()

        assertFalse(model.state.value.visible)
        assertEquals("", model.state.value.service)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a profile with graphs off shows none and asks nothing`() {
        val model = service(saveProfile(graphs = GraphSettings(enabled = false)))

        model.show(inline)
        model.refresh()

        assertFalse(model.state.value.visible)
        assertTrue(asked.isEmpty())
    }

    private fun picture() = GraphAnswer.Picture(byteArrayOf(1, 2, 3), 381, 175)

    private fun saveProfile(name: String = "Home", graphs: GraphSettings = GraphSettings()): Long = runBlocking {
        profiles.save(
            ProfileDraft(
                id = null,
                name = name,
                baseUrl = "https://nagios.example.org/nagios",
                username = "nagwatch",
                password = SecretInput.Replace("pw"),
                accessClientId = "",
                accessClientSecret = SecretInput.Keep,
                customHeaders = emptyList(),
                allowCleartext = false,
                graphs = graphs,
            ),
        )
    }

    private fun service(profileId: Long, name: String = "Disk /") = model(
        mapOf(
            DetailViewModel.ARG_PROFILE to profileId,
            DetailViewModel.ARG_HOST to "web01",
            DetailViewModel.ARG_SERVICE to name,
        ),
    )

    private fun host(profileId: Long) =
        model(mapOf(DetailViewModel.ARG_PROFILE to profileId, DetailViewModel.ARG_HOST to "web01"))

    private fun model(arguments: Map<String, Any>) =
        GraphViewModel(SavedStateHandle(arguments), repository).also { created += it }

    /**
     * Every state [model] was in while [block] ran and for a little after. For asserting that
     * something did not happen: there is no moment to wait for when nothing is to happen, and an
     * assertion made at once would pass before the view model had had the chance to do it.
     */
    private fun watching(model: GraphViewModel, block: () -> Unit): List<GraphUiState> {
        val seen = java.util.concurrent.CopyOnWriteArrayList<GraphUiState>()
        val collector = model.viewModelScope.launch { model.state.collect { seen += it } }
        block()
        Thread.sleep(SETTLE_MS)
        collector.cancel()
        return seen
    }

    /**
     * Waits until nagiosgraph has been asked [times] times.
     *
     * Apart from [awaitState], and never inside its condition. A state flow tells a waiter about
     * a state only when it differs from the last one it told it about, and a view model that
     * goes idle, loading, idle quickly enough ends where it began: the waiter is told nothing,
     * and a condition that also depended on this count is never looked at again. That passed
     * here and timed out on a slower machine.
     */
    private fun awaitAsked(times: Int) = runBlocking {
        withTimeout(TIMEOUT_MS) { while (asked.size < times) delay(POLL_MS) }
    }

    /** [predicate] may read the state it is given and nothing else: see [awaitAsked]. */
    private fun GraphViewModel.awaitState(predicate: (GraphUiState) -> Boolean): GraphUiState =
        runBlocking { withTimeout(TIMEOUT_MS) { state.first(predicate) } }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 300L
        const val POLL_MS = 10L
    }
}
