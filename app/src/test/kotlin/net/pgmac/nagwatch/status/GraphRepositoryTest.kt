// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.graph.GraphAnswer
import net.pgmac.nagwatch.nagios.graph.GraphClient
import net.pgmac.nagwatch.nagios.graph.GraphPeriod
import net.pgmac.nagwatch.nagios.graph.GraphRequest
import net.pgmac.nagwatch.nagios.graph.GraphSize
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.profile.GraphSettings
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.ProfileDraft
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.SecretInput
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** What is asked of nagiosgraph, what is remembered instead of asking, and for how long. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class GraphRepositoryTest {
    private val profileDatabase = inMemoryProfileDatabase()
    private val clock = MutableClock(T0)
    private val memory = GraphMemory()
    private val profiles =
        ProfileRepository(profileDatabase.profiles(), SecretCipher(softwareKeySource()), Json, setOf(memory))
    private val asked = mutableListOf<GraphRequest>()
    private val created = mutableListOf<ConnectionSettings>()
    private var answer: (GraphRequest) -> GraphAnswer = { picture() }
    private val repository = GraphRepository(
        profiles,
        { settings ->
            created += settings
            object : GraphClient {
                override suspend fun fetch(request: GraphRequest): GraphAnswer =
                    answer(request).also { asked += request }

                override suspend fun pageUrl(ref: ObjectRef): String = "page for ${ref.hostName}/${ref.description}"
            }
        },
        memory,
        clock,
    )
    private val disk = ObjectRef("web01", "Disk /")
    private val load = ObjectRef("web01", "Load")
    private val size = GraphSize(360, 120)

    @After
    fun close() = profileDatabase.close()

    // ---------------------------------------------------------------- a graph

    @Test
    fun `a graph is fetched and comes back with its size, its period, when, and where its page is`() = runBlocking {
        val id = profiles.saveTestProfile()

        val shown = graph(id, disk, GraphPeriod.WEEK) as GraphState.Shown

        assertEquals(listOf(GraphRequest("web01", "Disk /", GraphPeriod.WEEK, size)), asked)
        assertEquals(441 to 175, shown.width to shown.height)
        assertEquals(GraphPeriod.WEEK, shown.period)
        assertEquals(T0, shown.fetchedAt)
        assertEquals("page for web01/Disk /", shown.pageUrl)
    }

    @Test
    fun `the same graph again is the one in memory, not another request`() = runBlocking {
        val id = profiles.saveTestProfile()

        val first = graph(id, disk)
        val second = graph(id, disk)

        assertSame(first, second)
        assertEquals(1, asked.size)
    }

    @Test
    fun `another period, another size and another service are each their own graph`() = runBlocking {
        val id = profiles.saveTestProfile()

        graph(id, disk, GraphPeriod.DAY)
        graph(id, disk, GraphPeriod.YEAR)
        graph(id, disk, GraphPeriod.DAY, GraphSize.FULL)
        graph(id, load, GraphPeriod.DAY)
        graph(id, disk, GraphPeriod.DAY)

        assertEquals(4, asked.size)
    }

    @Test
    fun `a graph is fetched again once it is as old as the profile's interval, and not before`() = runBlocking {
        val id = profiles.saveTestProfile(interval = PollInterval.THIRTY_MINUTES)
        graph(id, disk)

        clock.advance(Duration.ofMinutes(29))
        graph(id, disk)
        assertEquals("still fresh", 1, asked.size)

        clock.advance(Duration.ofMinutes(1))
        val again = graph(id, disk) as GraphState.Shown
        assertEquals(2, asked.size)
        assertEquals(T0 + Duration.ofMinutes(30), again.fetchedAt)
    }

    @Test
    fun `a refresh fetches again however fresh the one in memory is`() = runBlocking {
        val id = profiles.saveTestProfile()
        graph(id, disk)

        graph(id, disk, refresh = true)

        assertEquals(2, asked.size)
    }

    @Test
    fun `only a few pictures are kept, the ones looked at longest ago going first`() = runBlocking {
        val id = profiles.saveTestProfile()
        val services = (1..13).map { ObjectRef("web01", "Service $it") }
        services.forEach { graph(id, it) }
        asked.clear()

        graph(id, services.last())
        assertEquals("the newest is still there", 0, asked.size)
        graph(id, services.first())
        assertEquals("the oldest was let go", 1, asked.size)
    }

    // ---------------------------------------------------------------- no graph

    @Test
    fun `a service with nothing drawn is not asked about again, at any period or size`() = runBlocking {
        val id = profiles.saveTestProfile()
        answer = { GraphAnswer.NoGraph }

        assertEquals(GraphState.None, graph(id, disk))
        assertEquals(GraphState.None, graph(id, disk, GraphPeriod.YEAR))
        assertEquals(GraphState.None, graph(id, disk, GraphPeriod.DAY, GraphSize.FULL))

        assertEquals(1, asked.size)
    }

    @Test
    fun `one service with nothing drawn says nothing about another`() = runBlocking {
        val id = profiles.saveTestProfile()
        answer = { if (it.service == "Disk /") GraphAnswer.NoGraph else picture() }
        graph(id, disk)

        assertTrue(graph(id, load) is GraphState.Shown)
    }

    @Test
    fun `a server with no nagiosgraph is asked once, for every service`() = runBlocking {
        val id = profiles.saveTestProfile()
        answer = { GraphAnswer.NotThere }

        assertEquals(GraphState.None, graph(id, disk))
        assertEquals(GraphState.None, graph(id, load))
        assertEquals(GraphState.None, graph(id, ObjectRef("db01", "Replication")))

        assertEquals(1, asked.size)
    }

    @Test
    fun `one profile with no nagiosgraph says nothing about another`() = runBlocking {
        val first = profiles.saveTestProfile("One")
        val second = profiles.saveTestProfile("Two")
        answer = { GraphAnswer.NotThere }
        graph(first, disk)
        answer = { picture() }

        assertTrue(graph(second, disk) is GraphState.Shown)
        assertEquals(GraphState.None, graph(first, disk))
    }

    @Test
    fun `a refresh asks again about what was ruled out, because a server can be fixed`() = runBlocking {
        val id = profiles.saveTestProfile()
        answer = { GraphAnswer.NoGraph }
        graph(id, disk)
        answer = { GraphAnswer.NotThere }
        graph(id, load)
        answer = { picture() }

        assertEquals("not asked", GraphState.None, graph(id, disk))
        assertTrue(graph(id, disk, refresh = true) is GraphState.Shown)
        assertTrue("and the profile is no longer ruled out either", graph(id, load) is GraphState.Shown)
    }

    @Test
    fun `a graph that stops being drawn takes its pictures with it`() = runBlocking {
        val id = profiles.saveTestProfile()
        graph(id, disk, GraphPeriod.DAY)
        graph(id, disk, GraphPeriod.WEEK)
        answer = { GraphAnswer.NoGraph }

        assertEquals(GraphState.None, graph(id, disk, GraphPeriod.DAY, refresh = true))
        assertEquals(
            "the other period's picture is not shown either",
            GraphState.None,
            graph(id, disk, GraphPeriod.WEEK),
        )
    }

    @Test
    fun `a picture from before a service was ruled out is never shown again, even when asking again fails`() =
        runBlocking {
            val id = profiles.saveTestProfile()
            graph(id, disk, GraphPeriod.WEEK)
            answer = { GraphAnswer.NoGraph }
            graph(id, disk, GraphPeriod.DAY, refresh = true)
            answer = { GraphAnswer.Unavailable(NagiosError.Http(500)) }

            // Asking again lifts the ruling; the failure would fall back on an older picture if one were left.
            assertEquals(GraphState.Unavailable, graph(id, disk, GraphPeriod.WEEK, refresh = true))
        }

    // ---------------------------------------------------------------- a failure that may pass

    @Test
    fun `a server that did not answer is asked again next time`() = runBlocking {
        val id = profiles.saveTestProfile()
        answer = { GraphAnswer.Unavailable(NagiosError.Http(500)) }

        assertEquals("not 'there is none': nobody could be asked", GraphState.Unavailable, graph(id, disk))
        answer = { picture() }

        assertTrue(graph(id, disk) is GraphState.Shown)
        assertEquals(2, asked.size)
    }

    @Test
    fun `an older picture is shown when a newer one cannot be had`() = runBlocking {
        val id = profiles.saveTestProfile()
        val first = graph(id, disk)
        answer = { GraphAnswer.Unavailable(NagiosError.Http(500)) }
        clock.advance(Duration.ofHours(2))

        assertSame(first, graph(id, disk))
        assertSame("on a refresh too", first, graph(id, disk, refresh = true))
    }

    // ---------------------------------------------------------------- nothing to ask

    @Test
    fun `a host is not asked about`() = runBlocking {
        val id = profiles.saveTestProfile()

        assertEquals(GraphState.None, graph(id, ObjectRef("web01")))
        assertTrue(asked.isEmpty() && created.isEmpty())
    }

    @Test
    fun `a profile with graphs off is not asked about, and nothing is decrypted for it`() = runBlocking {
        val id = profiles.saveTestProfile(graphs = GraphSettings(enabled = false))

        assertEquals(GraphState.None, graph(id, disk))
        assertTrue(asked.isEmpty() && created.isEmpty())
    }

    @Test
    fun `a profile that is gone has no graph`() = runBlocking {
        assertEquals(GraphState.None, graph(99, disk))
        assertTrue(asked.isEmpty())
    }

    // ---------------------------------------------------------------- what was learned belongs to where it was learned

    @Test
    fun `the client is made with the profile's graph path`() = runBlocking {
        val id = profiles.saveTestProfile(graphs = GraphSettings(path = "/nagiosgraph/cgi-bin"))

        graph(id, disk)

        assertEquals("/nagiosgraph/cgi-bin/", created.single().graphPath)
    }

    @Test
    fun `a new graph path forgets what was learned at the old one`() = runBlocking {
        val id = profiles.saveTestProfile()
        answer = { GraphAnswer.NotThere }
        graph(id, disk)
        answer = { picture() }

        profiles.save(draft(id = id, graphs = GraphSettings(path = "/nagiosgraph/cgi-bin/")))

        assertTrue(graph(id, disk) is GraphState.Shown)
    }

    @Test
    fun `a new address or a new user forgets the pictures too`() = runBlocking {
        val id = profiles.saveTestProfile()
        graph(id, disk)

        profiles.save(draft(id = id, baseUrl = "https://other.example.org/nagios"))
        graph(id, disk)
        profiles.save(draft(id = id, baseUrl = "https://other.example.org/nagios", username = "someone-else"))
        graph(id, disk)

        assertEquals("a picture of one server is not shown as another's", 3, asked.size)
    }

    @Test
    fun `saving a profile with nothing about graphs changed keeps what is known`() = runBlocking {
        val id = profiles.saveTestProfile()
        graph(id, disk)

        profiles.save(draft(id = id, name = "Renamed"))
        graph(id, disk)

        assertEquals(1, asked.size)
    }

    @Test
    fun `deleting a profile forgets everything about its graphs`() = runBlocking {
        val id = profiles.saveTestProfile()
        val other = profiles.saveTestProfile("Other")
        graph(id, disk)
        graph(other, disk)
        answer = { GraphAnswer.NoGraph }
        graph(id, load)

        profiles.delete(id)

        assertEquals(null, memory.picture(id, GraphRequest("web01", "Disk /", GraphPeriod.DAY, size)))
        assertEquals(false, memory.isRuledOut(id, "web01", "Load"))
        assertTrue(
            "the other profile's are untouched",
            memory.picture(other, GraphRequest("web01", "Disk /", GraphPeriod.DAY, size)) != null,
        )
    }

    // ---------------------------------------------------------------- what a screen is told without asking

    @Test
    fun `a graph is expected of a service on a profile with graphs on, until something rules it out`() = runBlocking {
        val id = profiles.saveTestProfile()
        val off = profiles.saveTestProfile("Off", graphs = GraphSettings(enabled = false))

        assertTrue(repository.expects(id, disk))
        assertEquals("a host", false, repository.expects(id, ObjectRef("web01")))
        assertEquals("graphs off", false, repository.expects(off, disk))
        assertEquals("no such profile", false, repository.expects(99, disk))
        assertTrue("answered without a request", asked.isEmpty() && created.isEmpty())

        answer = { GraphAnswer.NoGraph }
        graph(id, disk)
        assertEquals("ruled out", false, repository.expects(id, disk))
        assertTrue("another service is not", repository.expects(id, load))

        answer = { GraphAnswer.NotThere }
        graph(id, load)
        assertEquals("the whole profile is", false, repository.expects(id, ObjectRef("db01", "Replication")))
    }

    @Test
    fun `the period last chosen is remembered for its profile, until the profile goes`() = runBlocking {
        val id = profiles.saveTestProfile()
        val other = profiles.saveTestProfile("Other")
        assertEquals(GraphPeriod.DAY, repository.period(id).first())

        repository.choose(id, GraphPeriod.YEAR)

        assertEquals(GraphPeriod.YEAR, repository.period(id).first())
        assertEquals(GraphPeriod.DAY, repository.period(other).first())
        profiles.delete(id)
        assertEquals(GraphPeriod.DAY, repository.period(id).first())
    }

    @Test
    fun `a new address forgets what was ruled out but not which period was chosen`() = runBlocking {
        val id = profiles.saveTestProfile()
        repository.choose(id, GraphPeriod.MONTH)
        graph(id, disk)

        profiles.save(draft(id = id, baseUrl = "https://other.example.org/nagios"))
        graph(id, disk)

        assertEquals(GraphPeriod.MONTH, repository.period(id).first())
    }

    private suspend fun graph(
        profileId: Long,
        ref: ObjectRef,
        period: GraphPeriod = GraphPeriod.DAY,
        size: GraphSize = this.size,
        refresh: Boolean = false,
    ): GraphState = repository.graph(profileId, ref, period, size, refresh)

    private fun picture() = GraphAnswer.Picture(byteArrayOf(1, 2, 3), 441, 175)

    private suspend fun ProfileRepository.saveTestProfile(
        name: String = "Home",
        interval: PollInterval = PollInterval.FIFTEEN_MINUTES,
        graphs: GraphSettings = GraphSettings(),
    ): Long = save(draft(name = name, interval = interval, graphs = graphs))

    private fun draft(
        id: Long? = null,
        name: String = "Home",
        baseUrl: String = "https://nagios.example.org/nagios",
        username: String = "nagwatch",
        interval: PollInterval = PollInterval.FIFTEEN_MINUTES,
        graphs: GraphSettings = GraphSettings(),
    ) = ProfileDraft(
        id = id,
        name = name,
        baseUrl = baseUrl,
        username = username,
        password = if (id == null) SecretInput.Replace("pw") else SecretInput.Keep,
        accessClientId = "",
        accessClientSecret = SecretInput.Keep,
        customHeaders = emptyList(),
        allowCleartext = false,
        notifications = NotificationSettings(interval = interval),
        graphs = graphs,
    )
}
