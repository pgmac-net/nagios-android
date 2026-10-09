// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import java.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.HostStatus
import net.pgmac.nagwatch.nagios.model.ServerInfo
import net.pgmac.nagwatch.nagios.model.ServiceState
import net.pgmac.nagwatch.nagios.model.ServiceStatus
import net.pgmac.nagwatch.nagios.model.StateType

/**
 * Reads `statusjson.cgi` responses requested with `formatoptions=enumerate`
 * (states as words, not bitmasks). Tolerant by design: a missing or oddly
 * typed field becomes a neutral default rather than failing the whole poll.
 *
 * Each list comes in two shapes. With `details=true` an entry is an object;
 * without, it is just the state word. The second shape is what a degraded
 * record looks like.
 */
internal object StatusParser {
    fun serverInfo(body: JsonObject): ServerInfo {
        val result = body.obj("result")
        val status = body.obj("data").obj("programstatus")
        return ServerInfo(
            version = status.string("version"),
            programStart = status.instant("program_start") ?: result.instant("program_start"),
            lastDataUpdate = result.instant("last_data_update"),
        )
    }

    /** `data.hostlist`: host name -> detail object, or host name -> state word. */
    fun hosts(body: JsonObject): List<HostStatus> = body.obj("data").obj("hostlist").map { (name, entry) ->
        when (entry) {
            is JsonObject -> HostStatus(name, hostState(entry.string("status")), check(entry))
            else -> HostStatus(name, hostState(entry.word()), DEGRADED)
        }
    }

    /**
     * `data.servicelist`: host name -> description -> detail object or state word.
     * Nagios emits hosts with no matching services as empty objects; they yield nothing.
     */
    fun services(body: JsonObject): List<ServiceStatus> =
        body.obj("data").obj("servicelist").flatMap { (hostName, services) ->
            (services as? JsonObject).orEmpty().map { (description, entry) ->
                when (entry) {
                    is JsonObject -> ServiceStatus(
                        hostName,
                        description,
                        serviceState(entry.string("status")),
                        check(entry),
                    )

                    else -> ServiceStatus(hostName, description, serviceState(entry.word()), DEGRADED)
                }
            }
        }

    private fun check(entry: JsonObject) = CheckStatus(
        stateType = if (entry.string(
                "state_type",
            ).equals("soft", ignoreCase = true)
        ) {
            StateType.SOFT
        } else {
            StateType.HARD
        },
        pluginOutput = entry.string("plugin_output"),
        currentAttempt = entry.int("current_attempt"),
        maxAttempts = entry.int("max_attempts"),
        lastCheck = entry.instant("last_check"),
        lastStateChange = entry.instant("last_state_change"),
        acknowledged = entry.boolean("problem_has_been_acknowledged", default = false),
        downtimeDepth = entry.int("scheduled_downtime_depth"),
        checksEnabled = entry.boolean("checks_enabled", default = true),
        notificationsEnabled = entry.boolean("notifications_enabled", default = true),
    )

    /** An unrecognised state is surfaced as a problem, never silently treated as fine. */
    private fun hostState(word: String): HostState = when (word.lowercase()) {
        "up" -> HostState.UP
        "down" -> HostState.DOWN
        "pending" -> HostState.PENDING
        else -> HostState.UNREACHABLE
    }

    /** Same rule as hosts: anything unrecognised becomes UNKNOWN. */
    private fun serviceState(word: String): ServiceState = when (word.lowercase()) {
        "ok" -> ServiceState.OK
        "warning" -> ServiceState.WARNING
        "critical" -> ServiceState.CRITICAL
        "pending" -> ServiceState.PENDING
        else -> ServiceState.UNKNOWN
    }

    private val DEGRADED = CheckStatus(detailsAvailable = false)

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonElement.word(): String = (this as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun JsonObject.int(key: String): Int = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

    private fun JsonObject.boolean(key: String, default: Boolean): Boolean =
        (this[key] as? JsonPrimitive)?.booleanOrNull ?: default

    /** Nagios sends epoch milliseconds, and 0 for "never". */
    private fun JsonObject.instant(key: String): Instant? =
        (this[key] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
}
