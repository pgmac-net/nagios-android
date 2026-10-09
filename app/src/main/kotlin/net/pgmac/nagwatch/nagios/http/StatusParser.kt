// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import kotlinx.serialization.json.JsonObject
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

    /**
     * `data.service` from `query=service`: one detail object, the same shape as a list entry.
     * Null when the response holds no such object.
     */
    fun service(body: JsonObject): ServiceStatus? {
        val entry = body.obj("data")["service"] as? JsonObject ?: return null
        return ServiceStatus(
            hostName = entry.string("host_name"),
            description = entry.string("description"),
            state = serviceState(entry.string("status")),
            check = check(entry),
        )
    }

    /** `data.host` from `query=host`. */
    fun host(body: JsonObject): HostStatus? {
        val entry = body.obj("data")["host"] as? JsonObject ?: return null
        return HostStatus(entry.string("name"), hostState(entry.string("status")), check(entry))
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
        longOutput = entry.string("long_plugin_output"),
        perfData = entry.string("perf_data"),
        currentAttempt = entry.int("current_attempt"),
        maxAttempts = entry.int("max_attempts"),
        lastCheck = entry.instant("last_check"),
        nextCheck = entry.instant("next_check"),
        lastStateChange = entry.instant("last_state_change"),
        acknowledged = entry.boolean("problem_has_been_acknowledged", default = false),
        downtimeDepth = entry.int("scheduled_downtime_depth"),
        checksEnabled = entry.boolean("checks_enabled", default = true),
        notificationsEnabled = entry.boolean("notifications_enabled", default = true),
        activeCheck = !entry.string("check_type").equals("passive", ignoreCase = true),
        flapping = entry.boolean("is_flapping", default = false),
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
}
