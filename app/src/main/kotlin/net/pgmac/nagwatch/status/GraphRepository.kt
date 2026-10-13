// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosClientFactory
import net.pgmac.nagwatch.nagios.graph.GraphAnswer
import net.pgmac.nagwatch.nagios.graph.GraphClient
import net.pgmac.nagwatch.nagios.graph.GraphPeriod
import net.pgmac.nagwatch.nagios.graph.GraphRequest
import net.pgmac.nagwatch.nagios.graph.GraphSize
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.profile.ProfileCleanup
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SettingsResult

/** Makes a graph client for some settings. A seam so the repository can be tested without a network. */
fun interface GraphClientProvider {
    fun create(settings: ConnectionSettings): GraphClient
}

class FactoryGraphClientProvider @Inject constructor(private val factory: NagiosClientFactory) : GraphClientProvider {
    override fun create(settings: ConnectionSettings): GraphClient = factory.createGraphs(settings)
}

/** A graph to show, or nothing. There is no third thing: a graph that cannot be had is not an error. */
sealed interface GraphState {
    /**
     * [png] is a PNG of [width] by [height], checked. [pageUrl] is nagiosgraph's own page for
     * the service, for a browser.
     */
    class Shown(
        val png: ByteArray,
        val width: Int,
        val height: Int,
        val period: GraphPeriod,
        val fetchedAt: Instant,
        val pageUrl: String?,
    ) : GraphState

    data object None : GraphState
}

/**
 * What is remembered about graphs while the app runs, and no longer: nothing here is written
 * anywhere. A graph is a picture of someone's infrastructure, and the next one is a request away.
 *
 * Three things. A few pictures, so that going back to a period already looked at is instant.
 * The services nagiosgraph has nothing for, so that they are not asked about on every visit.
 * And the profiles with no nagiosgraph at all, so that one request settles it for every service.
 */
@Singleton
class GraphMemory @Inject constructor() : ProfileCleanup {
    private data class Service(val profileId: Long, val hostName: String, val service: String)

    private data class Key(val service: Service, val period: GraphPeriod, val size: GraphSize)

    private val pictures = LinkedHashMap<Key, GraphState.Shown>(MAX_PICTURES, LOAD_FACTOR, true)
    private val noGraph = LinkedHashSet<Service>()
    private val notThere = HashSet<Long>()
    private val scopes = HashMap<Long, String>()
    private val periods = HashMap<Long, GraphPeriod>()

    /** The period last chosen for this profile, so that the next graph opens on it. */
    @Synchronized
    fun period(profileId: Long): GraphPeriod = periods[profileId] ?: GraphPeriod.DAY

    @Synchronized
    fun choose(profileId: Long, period: GraphPeriod) {
        periods[profileId] = period
    }

    /**
     * Says what the profile's graphs are being asked of. When that has changed (another server,
     * another path, another user), what was learned was learned about something else.
     */
    @Synchronized
    fun scope(profileId: Long, scope: String) {
        if (scopes.put(profileId, scope) != scope) forget(profileId, keepScope = true)
    }

    /** Nothing is to be had for this service: it has no graph, or its profile has no nagiosgraph. */
    @Synchronized
    fun isRuledOut(profileId: Long, hostName: String, service: String): Boolean =
        profileId in notThere || Service(profileId, hostName, service) in noGraph

    @Synchronized
    fun picture(profileId: Long, request: GraphRequest): GraphState.Shown? = pictures[request.key(profileId)]

    @Synchronized
    fun keep(profileId: Long, request: GraphRequest, picture: GraphState.Shown) {
        pictures[request.key(profileId)] = picture
        while (pictures.size > MAX_PICTURES) pictures.remove(pictures.keys.first())
    }

    @Synchronized
    fun ruleOut(profileId: Long, request: GraphRequest) {
        noGraph += request.service(profileId)
        pictures.keys.removeAll { it.service == request.service(profileId) }
        while (noGraph.size > MAX_RULED_OUT) noGraph.remove(noGraph.first())
    }

    @Synchronized
    fun ruleOutProfile(profileId: Long) {
        notThere += profileId
        pictures.keys.removeAll { it.service.profileId == profileId }
    }

    /** The person asked again. What was ruled out is asked about once more; a server can be fixed. */
    @Synchronized
    fun askAgain(profileId: Long, request: GraphRequest) {
        notThere -= profileId
        noGraph -= request.service(profileId)
    }

    override suspend fun onProfileDeleted(profileId: Long) = forget(profileId, keepScope = false)

    @Synchronized
    private fun forget(profileId: Long, keepScope: Boolean) {
        pictures.keys.removeAll { it.service.profileId == profileId }
        noGraph.removeAll { it.profileId == profileId }
        notThere -= profileId
        if (!keepScope) {
            scopes -= profileId
            periods -= profileId
        }
    }

    private fun GraphRequest.service(profileId: Long) = Service(profileId, hostName, service)

    private fun GraphRequest.key(profileId: Long) = Key(service(profileId), period, size)

    private companion object {
        /** A picture is a few tens of kilobytes. Four periods at two sizes, and a little over. */
        const val MAX_PICTURES = 12
        const val MAX_RULED_OUT = 1_000
        const val LOAD_FACTOR = 0.75f
    }
}

/**
 * A service's graph, from memory if it is there and fresh, and otherwise from nagiosgraph.
 *
 * Every way of not having one comes out as [GraphState.None]: graphs switched off, a host, no
 * nagiosgraph, nothing drawn for this service, a server that did not answer. The screen has one
 * thing to do about all of them, which is to leave the graph out.
 */
@Singleton
class GraphRepository @Inject constructor(
    private val profiles: ProfileRepository,
    private val clients: GraphClientProvider,
    private val memory: GraphMemory,
    private val clock: Clock,
) {
    /** The period a graph for this profile opens on: the one last chosen, while the app runs. */
    fun period(profileId: Long): GraphPeriod = memory.period(profileId)

    fun choose(profileId: Long, period: GraphPeriod) = memory.choose(profileId, period)

    /**
     * Whether asking for [ref]'s graph could come to anything, answered without asking: a
     * service, on a profile with graphs on, that nothing has ruled out. It is what says whether
     * to leave room for a graph while it loads, so that a screen with none does not flicker.
     */
    suspend fun expects(profileId: Long, ref: ObjectRef): Boolean =
        !ref.isHost && profiles.get(profileId)?.graphs?.enabled == true &&
            !memory.isRuledOut(profileId, ref.hostName, ref.description.orEmpty())

    /** @param refresh the person asked: nothing remembered is trusted, and it is fetched again. */
    suspend fun graph(
        profileId: Long,
        ref: ObjectRef,
        period: GraphPeriod,
        size: GraphSize,
        refresh: Boolean = false,
    ): GraphState {
        val request = GraphRequest.of(ref, period, size)
        val source = request?.let { sourceFor(profileId) }
        return if (request == null || source == null) GraphState.None else load(profileId, request, source, refresh)
    }

    private class Source(val settings: ConnectionSettings, val freshFor: Duration)

    /** Null when this profile's graphs are off, or it cannot be asked anything at all. */
    private suspend fun sourceFor(profileId: Long): Source? {
        val profile = profiles.get(profileId)?.takeIf { it.graphs.enabled }
        val ready = profile?.let { profiles.settingsFor(profileId) as? SettingsResult.Ready }
        // A graph is as old as a poll may be before the app calls its numbers out of date.
        return ready?.let { Source(it.settings, Duration.ofMinutes(profile.notifications.interval.minutes.toLong())) }
    }

    private suspend fun load(profileId: Long, request: GraphRequest, source: Source, refresh: Boolean): GraphState {
        val settings = source.settings
        memory.scope(profileId, "${settings.baseUrl}\n${settings.graphPath}\n${settings.username}")
        if (refresh) memory.askAgain(profileId, request)
        val kept = memory.picture(profileId, request)
        val fresh = kept?.takeIf { !refresh && Duration.between(it.fetchedAt, clock.instant()) < source.freshFor }
        return when {
            memory.isRuledOut(profileId, request.hostName, request.service) -> GraphState.None
            fresh != null -> fresh
            else -> fetch(profileId, request, clients.create(settings), kept)
        }
    }

    private suspend fun fetch(
        profileId: Long,
        request: GraphRequest,
        client: GraphClient,
        kept: GraphState.Shown?,
    ): GraphState = when (val answer = client.fetch(request)) {
        is GraphAnswer.Picture -> GraphState.Shown(
            png = answer.png,
            width = answer.width,
            height = answer.height,
            period = request.period,
            fetchedAt = clock.instant(),
            pageUrl = client.pageUrl(ObjectRef(request.hostName, request.service)),
        ).also { memory.keep(profileId, request, it) }

        GraphAnswer.NoGraph -> GraphState.None.also { memory.ruleOut(profileId, request) }

        GraphAnswer.NotThere -> GraphState.None.also { memory.ruleOutProfile(profileId) }

        // Not learned from: the next visit asks again. An older picture is better than none.
        is GraphAnswer.Unavailable -> kept ?: GraphState.None
    }
}
