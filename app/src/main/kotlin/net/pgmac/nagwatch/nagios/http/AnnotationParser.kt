// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import java.time.Duration
import kotlinx.serialization.json.JsonObject
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.Downtime
import net.pgmac.nagwatch.nagios.model.ObjectRef

/**
 * Reads `commentlist` and `downtimelist` (requested with `details=true` and
 * `formatoptions=enumerate`).
 *
 * Both lists are filtered by the server on host name, and that filter returns
 * the host's own entries **and** every entry for its services, mixed together
 * (measured on Nagios Core 4.5.9). So the entries are filtered again here to
 * the one object asked about.
 */
internal object AnnotationParser {
    /** Newest first. */
    fun comments(body: JsonObject, target: ObjectRef): List<Comment> = body.obj("data").obj("commentlist").values
        .filterIsInstance<JsonObject>()
        .filter { it.isFor(target) }
        .map { entry ->
            Comment(
                id = entry.long("comment_id"),
                kind = commentKind(entry.string("entry_type")),
                author = entry.string("author"),
                text = entry.string("comment_data"),
                enteredAt = entry.instant("entry_time"),
                persistent = entry.boolean("persistent", default = false),
                expiresAt = entry.instant("expire_time").takeIf { entry.boolean("expires", default = false) },
            )
        }
        .sortedWith(compareByDescending<Comment> { it.enteredAt }.thenByDescending { it.id })

    /** Soonest start first. */
    fun downtimes(body: JsonObject, target: ObjectRef): List<Downtime> = body.obj("data").obj("downtimelist").values
        .filterIsInstance<JsonObject>()
        .filter { it.isFor(target) }
        .map { entry ->
            Downtime(
                id = entry.long("downtime_id"),
                author = entry.string("author"),
                comment = entry.string("comment"),
                start = entry.instant("start_time"),
                end = entry.instant("end_time"),
                fixed = entry.boolean("fixed", default = true),
                duration = duration(entry.long("duration")),
                inEffect = entry.boolean("is_in_effect", default = false),
            )
        }
        .sortedWith(compareBy<Downtime> { it.start }.thenBy { it.id })

    /**
     * The length of a flexible downtime, in seconds. Nagios writes this one as a plain
     * integer (`json_object_append_integer` in cgi/statusjson.c), unlike its timestamps,
     * which it turns into milliseconds.
     */
    private fun duration(seconds: Long): Duration? = seconds.takeIf { it > 0 }?.let(Duration::ofSeconds)

    private fun JsonObject.isFor(target: ObjectRef): Boolean =
        string("host_name") == target.hostName && string("service_description") == target.description.orEmpty()

    private fun commentKind(word: String): Comment.Kind = when (word.lowercase()) {
        "user" -> Comment.Kind.USER
        "acknowledgement" -> Comment.Kind.ACKNOWLEDGEMENT
        "downtime" -> Comment.Kind.DOWNTIME
        "flapping" -> Comment.Kind.FLAPPING
        else -> Comment.Kind.OTHER
    }
}
