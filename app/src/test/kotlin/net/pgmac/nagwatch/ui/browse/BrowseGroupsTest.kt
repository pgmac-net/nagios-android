// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.browse

import net.pgmac.nagwatch.nagios.model.Handling
import net.pgmac.nagwatch.nagios.model.HostGroup
import net.pgmac.nagwatch.nagios.model.HostState
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ServiceGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How shown rows are put into groups (pgmac-net/nagios-android#82). */
class BrowseGroupsTest {
    private val web01 = host("web01", HostState.DOWN)
    private val web02 = host("web02", HostState.UP)
    private val db01 = host("db01", HostState.UP)
    private val lone = host("lone", HostState.UP)

    private val groups = BrowseGroups.hostGroups(
        listOf(
            HostGroup("web", "Web servers", listOf("web01", "web02")),
            HostGroup("all", "", listOf("web01", "web02", "db01")),
            HostGroup("empty", "Nothing", emptyList()),
        ),
    )

    @Test
    fun `groups are in name order, a host in two groups is in both, and the rest come last`() {
        val sections = BrowseGroups.sections(listOf(web01, web02, db01, lone), groups)

        assertEquals(listOf("all", "web", ""), sections.map { it.key })
        assertEquals(listOf("web01", "web02", "db01"), sections[0].rows.map { it.hostName })
        assertEquals(listOf("web01", "web02"), sections[1].rows.map { it.hostName })
        assertEquals(listOf("lone"), sections[2].rows.map { it.hostName })
    }

    @Test
    fun `a group with nothing shown is left out, and so is an empty one`() {
        val sections = BrowseGroups.sections(listOf(db01), groups)

        assertEquals(listOf("all"), sections.map { it.key })
    }

    @Test
    fun `a host the filters hid is not counted as in no group`() {
        val sections = BrowseGroups.sections(listOf(web01), groups)

        assertTrue(sections.none { it.group == null })
    }

    @Test
    fun `a section's worst state is its first row's, and it needs someone only for an unhandled problem`() {
        val down = BrowseGroups.sections(listOf(web01, web02), groups).first { it.key == "web" }
        assertEquals(HostState.DOWN, down.worst)
        assertTrue(down.needsSomeone)

        val handled = web01.copy(handling = Handling.ACKNOWLEDGED)
        assertFalse(BrowseGroups.sections(listOf(handled, web02), groups).first { it.key == "web" }.needsSomeone)
    }

    @Test
    fun `service group members match services by host and name`() {
        val serviceGroups = BrowseGroups.serviceGroups(
            listOf(ServiceGroup("disks", "Disks", listOf(ObjectRef("web01", "Disk /")))),
        )
        val disk = BrowseRow("web01", "Disk /", HostState.UP, null, null, emptySet())
        val otherHost = BrowseRow("web02", "Disk /", HostState.UP, null, null, emptySet())

        val sections = BrowseGroups.sections(listOf(disk, otherHost), serviceGroups)

        assertEquals(listOf(disk), sections.first { it.key == "disks" }.rows)
        assertEquals(listOf(otherHost), sections.first { it.group == null }.rows)
    }

    @Test
    fun `two groups of one name are one group holding both their members, so nobody is lost`() {
        val twice = BrowseGroups.hostGroups(
            listOf(HostGroup("web", "A", listOf("web02")), HostGroup("web", "B", listOf("web01"))),
        )

        val sections = BrowseGroups.sections(listOf(web01, web02), twice)

        assertEquals(1, sections.size)
        assertEquals(
            "the down host is still shown",
            setOf("web01", "web02"),
            sections.single().rows.map {
                it.hostName
            }.toSet(),
        )
    }

    private fun host(name: String, state: HostState) = BrowseRow(
        hostName = name,
        service = null,
        state = state,
        check = null,
        handling = if (state == HostState.UP) null else Handling.UNHANDLED,
        markers = emptySet(),
    )
}
