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
