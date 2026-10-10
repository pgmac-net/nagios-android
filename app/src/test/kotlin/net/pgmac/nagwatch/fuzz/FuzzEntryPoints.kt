// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.fuzz

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.http.AnnotationParser
import net.pgmac.nagwatch.nagios.http.StatusParser
import net.pgmac.nagwatch.nagios.http.interpretResponseBody
import net.pgmac.nagwatch.nagios.http.obj
import net.pgmac.nagwatch.nagios.http.parseJsonObject
import net.pgmac.nagwatch.nagios.http.string
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceStateEntry
import net.pgmac.nagwatch.nagios.model.StatusSnapshot

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

    /** Text to "is this Nagios, and did it succeed": the first thing every response meets. */
    @JvmStatic
    fun responseBody(data: ByteArray) {
        interpretResponseBody(json, String(data, Charsets.UTF_8))
    }

    /** Whatever survives as a JSON object goes through every function that reads status. */
    @JvmStatic
    fun statusParser(data: ByteArray) {
        val body = objectOrNull(data) ?: return
        val hosts = StatusParser.hosts(body)
        val services = StatusParser.services(body)
        StatusParser.serverInfo(body)
        StatusParser.service(body)
        StatusParser.host(body)
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
