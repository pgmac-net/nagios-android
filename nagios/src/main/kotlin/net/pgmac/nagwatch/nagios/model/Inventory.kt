// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios.model

// How Nagios is configured, as opposed to how it is doing: groups, read from `objectjson.cgi`
// (pgmac-net/nagios-android#82). Changes when someone edits the config, not at every check.

/** A host group: its name, the alias people read, and the hosts in it. */
data class HostGroup(val name: String, val alias: String, val members: List<String>) {
    /** What to show: the alias, or the name for a group that has none. */
    val label: String get() = alias.ifBlank { name }
}

/** A service group. Its members are services, each on its host. */
data class ServiceGroup(val name: String, val alias: String, val members: List<ObjectRef>) {
    val label: String get() = alias.ifBlank { name }
}

/** A host as configured: for now, which hosts are its parents (`parent_hosts`). */
data class HostConfig(val name: String, val parents: List<String>)

/**
 * What a host's or service's definition says to show beside it: free text and two addresses.
 * With the host's alias and address, which the addresses may name through macros.
 */
data class ObjectConfig(
    val hostName: String,
    val hostAlias: String = "",
    val hostAddress: String = "",
    /** Null for a host. */
    val description: String? = null,
    val notes: String = "",
    val notesUrl: String = "",
    val actionUrl: String = "",
)
