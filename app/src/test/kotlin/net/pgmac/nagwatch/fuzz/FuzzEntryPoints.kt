// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.fuzz

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.command.CommandPage
import net.pgmac.nagwatch.nagios.command.ServerClock
import net.pgmac.nagwatch.nagios.command.ServerDateFormat
import net.pgmac.nagwatch.nagios.graph.GraphPath
import net.pgmac.nagwatch.nagios.graph.GraphRules
import net.pgmac.nagwatch.nagios.graph.GraphSize
import net.pgmac.nagwatch.nagios.graph.PngHeader
import net.pgmac.nagwatch.nagios.http.AnnotationParser
import net.pgmac.nagwatch.nagios.http.MAX_REPAIRS
import net.pgmac.nagwatch.nagios.http.ObjectParser
import net.pgmac.nagwatch.nagios.http.StatusParser
import net.pgmac.nagwatch.nagios.http.interpretResponseBody
import net.pgmac.nagwatch.nagios.http.obj
import net.pgmac.nagwatch.nagios.http.parseJsonObject
import net.pgmac.nagwatch.nagios.http.repairInvalidEscapes
import net.pgmac.nagwatch.nagios.http.string
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * What the fuzz targets call. Kotlin, because the parsers are `internal`; the targets
 * themselves are Java, because that is the language the OpenSSF Scorecard looks for
 * Jazzer in. Each function takes whatever bytes it is given and must return normally:
 * the contract of this code is "a result or a NagiosError, nothing else", so any
 * exception that gets out is a finding.
 *
 * The same functions are replayed over the corpus on every build, by
 * `FuzzCorpusTest`, so a crash that was found stays found.
 */
object FuzzEntryPoints {
    // As in production (di/NetworkModule.kt).
    private val json = Json { ignoreUnknownKeys = true }
    private const val BYTE = 0xFFL

    private val GRAPH_BASE = "https://someone:made-up@nagios.example.org:8443/nagios/?q=1".toHttpUrl()
    private val GRAPH_SIZES = (0..800 step 60).map(GraphSize::inline).distinct() + GraphSize.FULL
    private val GRAPH_MAX_PIXELS =
        (GraphSize.FULL.width + GraphRules.MAX_EXTRA_WIDTH).toLong() *
            (GraphSize.FULL.height + GraphRules.MAX_EXTRA_HEIGHT)

    /** Each repair adds four characters, and there is an allowance of them. */
    private const val REPAIR_GROWTH_LIMIT = 4 * MAX_REPAIRS

    /** Keeps the stand-in clock within the years `Instant` can hold. */
    private const val MAX_EPOCH = 100_000_000_000L

    /** Text to "is this Nagios, and did it succeed": the first thing every response meets. */
    @JvmStatic
    fun responseBody(data: ByteArray) {
        val text = String(data, Charsets.UTF_8)
        interpretResponseBody(json, text)
        // The repair is applied to anything a server sends, so it has to be idempotent: a second
        // pass finds nothing, because nothing it writes is an escape it rewrites.
        val repaired = repairInvalidEscapes(text)
        check(repairInvalidEscapes(repaired) == repaired) { "repairing twice is not repairing once" }
        check(repaired.length <= text.length + REPAIR_GROWTH_LIMIT) { "a repair grew a body more than allowed" }
    }

    /**
     * Bytes that claim to be a graph, and text that claims to be a graph path. Both come from
     * outside (a server; a person, or a restored backup), and both are decided by this code
     * before anything else is trusted with them.
     */
    @JvmStatic
    fun graph(data: ByteArray) {
        val header = PngHeader.read(data)
        check(header == null || (header.width > 0 && header.height > 0)) { "a size that is not one: $header" }
        for (asked in GRAPH_SIZES) {
            val judged = GraphRules.judge(asked, data) ?: continue
            check(judged == header) { "judged a size the header does not have" }
            check(judged.width >= asked.width && judged.height >= asked.height) { "smaller than its own plot" }
            val pixels = judged.width.toLong() * judged.height
            check(pixels <= GRAPH_MAX_PIXELS) { "accepted $pixels pixels" }
        }

        val text = String(data, Charsets.UTF_8)
        val path = GraphPath.normalise(text) ?: return
        check(GraphPath.normalise(path) == path) { "normalising twice is not normalising once" }
        val resolved = GraphPath.resolve(GRAPH_BASE, text)
        if (path.isEmpty()) {
            check(resolved == null) { "an empty path resolved to something" }
        } else {
            checkNotNull(resolved) { "a valid path that does not resolve" }
            check(
                resolved.scheme == GRAPH_BASE.scheme && resolved.host == GRAPH_BASE.host &&
                    resolved.port == GRAPH_BASE.port,
            ) {
                "a graph path left the profile's own server: $resolved"
            }
            check(resolved.encodedPath == path) { "the path used is not the path stored" }
            check(resolved.username.isEmpty() && resolved.password.isEmpty()) { "credentials in a URL: $resolved" }
            check(resolved.query == null && resolved.fragment == null && ".." !in resolved.pathSegments) {
                "more than a path: $resolved"
            }
        }
    }

    /**
     * A whole conversation with a server that answers from the input, as the list fetcher has
     * it: see [ListFetcherScript] for the format and the properties.
     */
    @JvmStatic
    fun listFetcher(data: ByteArray) = ListFetcherScript.run(data)

    /** Whatever survives as a JSON object goes through every function that reads status. */
    @JvmStatic
    fun statusParser(data: ByteArray) {
        val body = objectOrNull(data) ?: return
        val hosts = StatusParser.hosts(body)
        val services = StatusParser.services(body)
        StatusParser.serverInfo(body)
        StatusParser.service(body)
        StatusParser.host(body)
        // Configuration, from objectjson.cgi: the same server, the same trust.
        ObjectParser.hostGroups(body)
        ObjectParser.serviceGroups(body)
        // The next stage after parsing, which reads every field the parser produced.
        ProblemClassifier.classify(
            StatusSnapshot(
                hosts = hosts,
                serviceProblems = services,
                fetchedAt = Instant.EPOCH,
                serviceStates = services.map { ServiceStateEntry(it.hostName, it.description, it.state) },
            ),
        )
    }

    /** Comments and downtimes, asked about the first object the document mentions and about a fixed one. */
    @JvmStatic
    fun annotationParser(data: ByteArray) {
        val body = objectOrNull(data) ?: return
        for (target in targetsIn(body)) {
            AnnotationParser.comments(body, target)
            AnnotationParser.downtimes(body, target)
        }
    }

    /**
     * A page from `cmd.cgi`: every question the app asks of one, and the server clock worked
     * out from whatever times it shows. The first eight bytes stand in for the server's JSON
     * clock, so that the comparison between the two is exercised and not only the parsing.
     */
    @JvmStatic
    fun commandPage(data: ByteArray) {
        val serverNow = Instant.ofEpochSecond(
            data.take(Long.SIZE_BYTES).fold(0L) { acc, b ->
                (acc shl 8) or (b.toLong() and BYTE)
            } % MAX_EPOCH,
        )
        val html = String(data, Charsets.UTF_8)
        CommandPage.result(html)
        CommandPage.isReadOnly(html)
        CommandPage.isForm(html)
        val start = CommandPage.field(html, "start_time") ?: return
        val end = CommandPage.field(html, "end_time")
        for (remembered in ServerDateFormat.entries + null) {
            val clock = ServerClock.read(start, end, serverNow, remembered) ?: continue
            clock.format(serverNow)
            clock.format(Instant.EPOCH)
        }
        // Whatever the page says the time is, taken at its word.
        ServerDateFormat.entries.forEach { format -> ServerClock.read(start, end, Instant.EPOCH, format) }
    }

    // The parse production uses, so the parsers are handed exactly what they would be handed.
    // Not JSON gives null: nothing for a parser to read. responseBody() covers that text.
    private fun objectOrNull(data: ByteArray): JsonObject? = parseJsonObject(json, String(data, Charsets.UTF_8))

    /**
     * Parsers only keep an annotation that is about the object asked for, so a target taken
     * from the document is what makes the interesting code run.
     */
    private fun targetsIn(body: JsonObject): List<ObjectRef> {
        val data = body.obj("data")
        val first = (data.obj("commentlist").values + data.obj("downtimelist").values)
            .filterIsInstance<JsonObject>()
            .firstOrNull()
        val named = first?.let { entry ->
            val host = entry.string("host_name")
            listOf(ObjectRef(host), ObjectRef(host, entry.string("service_description").ifBlank { null }))
        }.orEmpty()
        return named + ObjectRef("host12") + ObjectRef("host12", "Memory")
    }
}
