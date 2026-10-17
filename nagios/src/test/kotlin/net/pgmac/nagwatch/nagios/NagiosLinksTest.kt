// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.nagios

import net.pgmac.nagwatch.nagios.model.ObjectConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What becomes of a notes or action address from the server (pgmac-net/nagios-android#82). */
class NagiosLinksTest {
    private val base = "https://nagios.example.org/nagios/cgi-bin/".toHttpUrl()
    private val host = ObjectConfig(hostName = "web 01", hostAlias = "Web & co", hostAddress = "203.0.113.7")
    private val service = host.copy(description = "Disk /")

    @Test
    fun `macros are filled in, encoded, as Nagios' own pages fill them`() {
        assertEquals(
            NagiosLink.Open("https://wiki.example.org/hosts?h=web+01&a=Web+%26+co&ip=203.0.113.7"),
            NagiosLinks.link(
                "https://wiki.example.org/hosts?h=\$HOSTNAME\$&a=\$HOSTALIAS\$&ip=\$HOSTADDRESS\$",
                host,
                base,
            ),
        )
        assertEquals(
            NagiosLink.Open("https://wiki.example.org/svc/Disk+%2F"),
            NagiosLinks.link("https://wiki.example.org/svc/\$SERVICEDESC\$", service, base),
        )
    }

    @Test
    fun `a macro the app does not fill in leaves the address as words`() {
        val raw = "https://graphs.example.org/\$HOSTNOTES\$"
        assertEquals(NagiosLink.Text(raw), NagiosLinks.link(raw, host, base))
        assertEquals(
            "a host has no service",
            NagiosLink.Text("https://x.example.org/\$SERVICEDESC\$"),
            NagiosLinks.link("https://x.example.org/\$SERVICEDESC\$", host, base),
        )
    }

    @Test
    fun `only web addresses are opened`() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;end",
            "javascript:alert(1)",
            "file:///sdcard/secret",
            "content://contacts/people",
            "market://details?id=x",
            "tel:123",
        ).forEach { raw -> assertEquals(raw, NagiosLink.Text(raw), NagiosLinks.link(raw, host, base)) }
    }

    @Test
    fun `a relative address is read against the profile's Nagios`() {
        assertEquals(
            NagiosLink.Open("https://nagios.example.org/pnp4nagios/graph?host=web+01"),
            NagiosLinks.link("/pnp4nagios/graph?host=\$HOSTNAME\$", host, base),
        )
        assertEquals(
            NagiosLink.Open("https://nagios.example.org/nagios/cgi-bin/extinfo.cgi"),
            NagiosLinks.link("extinfo.cgi", host, base),
        )
    }

    @Test
    fun `a user name and password in an address are removed`() {
        assertEquals(
            NagiosLink.Open("https://wiki.example.org/x"),
            NagiosLinks.link("https://admin:hunter2@wiki.example.org/x", host, base),
        )
    }

    @Test
    fun `a value cannot turn an address into another kind`() {
        val sly = host.copy(hostName = "javascript:alert(1)")
        // Encoded, the value is a path on the profile's own Nagios, never a scheme of its own.
        assertEquals(
            NagiosLink.Open("https://nagios.example.org/nagios/cgi-bin/javascript%3Aalert%281%29"),
            NagiosLinks.link("\$HOSTNAME\$", sly, base),
        )
    }

    @Test
    fun `nothing is nothing, and notes are filled in plain`() {
        assertNull(NagiosLinks.link("  ", host, base))
        assertEquals(
            "Ask about web 01 (Disk /)",
            NagiosLinks.notes("Ask about \$HOSTNAME\$ (\$SERVICEDESC\$)", service),
        )
    }
}
