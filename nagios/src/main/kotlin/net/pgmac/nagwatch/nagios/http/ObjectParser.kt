// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.pgmac.nagwatch.nagios.model.HostConfig
import net.pgmac.nagwatch.nagios.model.HostGroup
import net.pgmac.nagwatch.nagios.model.ObjectConfig
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceGroup

/**
 * Reads `objectjson.cgi` responses: how Nagios is configured. As tolerant as [StatusParser]:
 * a member that is not the expected shape is left out, not the whole group.
 */
internal object ObjectParser {
    /** `data.hostgrouplist` with `details=true`: group name -> group object. */
    fun hostGroups(body: JsonObject): List<HostGroup> =
        body.obj("data").obj("hostgrouplist").mapNotNull { (key, entry) ->
            val group = entry as? JsonObject ?: return@mapNotNull null
            HostGroup(
                name = group.string("group_name").ifBlank { key },
                alias = group.string("alias"),
                members = group.members().mapNotNull {
                    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                }
                    .filter(String::isNotEmpty)
                    .distinct(),
            )
        }

    /** `data.servicegrouplist` with `details=true`: members are `{host_name, service_description}`. */
    fun serviceGroups(body: JsonObject): List<ServiceGroup> =
        body.obj("data").obj("servicegrouplist").mapNotNull { (key, entry) ->
            val group = entry as? JsonObject ?: return@mapNotNull null
            ServiceGroup(
                name = group.string("group_name").ifBlank { key },
                alias = group.string("alias"),
                members = group.members().mapNotNull { member ->
                    val pair = member as? JsonObject ?: return@mapNotNull null
                    val host = pair.string("host_name")
                    val service = pair.string("service_description")
                    if (host.isEmpty() || service.isEmpty()) null else ObjectRef(host, service)
                }.distinct(),
            )
        }

    /** `data.hostlist` from `objectjson.cgi` with `details=true`: host name -> host object. */
    fun hostConfigs(body: JsonObject): List<HostConfig> = body.obj("data").obj("hostlist").mapNotNull { (key, entry) ->
        val host = entry as? JsonObject ?: return@mapNotNull null
        val name = host.string("name").ifBlank { key }
        HostConfig(name = name, parents = host.names("parent_hosts").filter { it != name })
    }

    /** `data.host` from `objectjson.cgi` `query=host`. Null when the response holds none. */
    fun host(body: JsonObject): ObjectConfig? {
        val host = body.obj("data")["host"] as? JsonObject ?: return null
        return ObjectConfig(
            hostName = host.string("name"),
            hostAlias = host.string("alias"),
            hostAddress = host.string("address"),
            notes = host.string("notes"),
            notesUrl = host.string("notes_url"),
            actionUrl = host.string("action_url"),
        )
    }

    /** `data.service` from `objectjson.cgi` `query=service`: its own notes, on [host]'s name, alias and address. */
    fun service(body: JsonObject, host: ObjectConfig): ObjectConfig? {
        val service = body.obj("data")["service"] as? JsonObject ?: return null
        return host.copy(
            description = service.string("description"),
            notes = service.string("notes"),
            notesUrl = service.string("notes_url"),
            actionUrl = service.string("action_url"),
        )
    }

    private fun JsonObject.names(key: String): List<String> = (this[key] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        .filter(String::isNotEmpty)
        .distinct()

    private fun JsonObject.members(): List<JsonElement> = this["members"] as? JsonArray ?: emptyList()
}
