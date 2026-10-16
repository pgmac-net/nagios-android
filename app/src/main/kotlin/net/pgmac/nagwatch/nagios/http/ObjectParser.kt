// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.http

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.pgmac.nagwatch.nagios.model.HostGroup
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

    private fun JsonObject.members(): List<JsonElement> = this["members"] as? JsonArray ?: emptyList()
}
