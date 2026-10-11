// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.localnet

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which addresses look like the local network to Android 17's protection. Every range is tried
 * at both of its edges and one step outside, because a range written one off is the usual bug.
 */
class LocalAddressesTest {
    @Test
    fun `10 slash 8`() {
        assertLocal("10.0.0.0", "10.0.0.1", "10.255.255.255")
        assertPublic("9.255.255.255", "11.0.0.0")
    }

    @Test
    fun `172 16 slash 12`() {
        assertLocal("172.16.0.0", "172.20.1.1", "172.31.255.255")
        assertPublic("172.15.255.255", "172.32.0.0")
    }

    @Test
    fun `192 168 slash 16`() {
        assertLocal("192.168.0.0", "192.168.1.20", "192.168.255.255")
        assertPublic("192.167.255.255", "192.169.0.0")
    }

    @Test
    fun `link-local 169 254 slash 16`() {
        assertLocal("169.254.0.1", "169.254.255.255")
        assertPublic("169.253.255.255", "169.255.0.0")
    }

    @Test
    fun `carrier-grade NAT 100 64 slash 10, which is also where Tailscale lives`() {
        assertLocal("100.64.0.0", "100.100.100.100", "100.127.255.255")
        assertPublic("100.63.255.255", "100.128.0.0")
    }

    @Test
    fun `public addresses are not local`() {
        assertPublic("8.8.8.8", "1.1.1.1", "93.184.216.34", "172.217.14.206")
    }

    @Test
    fun `loopback and the unspecified address are not the local network`() {
        assertPublic("127.0.0.1", "127.255.255.255", "0.0.0.0", "::1", "::")
    }

    @Test
    fun `multicast and the limited broadcast address are`() {
        assertLocal("224.0.0.1", "239.255.255.250", "255.255.255.255", "ff02::1")
    }

    @Test
    fun `IPv6 link-local fe80 slash 10 and unique local fc00 slash 7`() {
        assertLocal("fe80::1", "febf:ffff::1", "fc00::1", "fd12:3456:789a::1", "fdff::1")
        // Just outside: the deprecated site-local fec0::/10, the rest of fe00::/9, and the neighbours of fc00::/7.
        assertPublic("fec0::1", "fe00::1", "fbff::1", "fe7f::1", "2001:db8::1", "2606:4700::1111")
    }

    @Test
    fun `an IPv4 address written as IPv6 is judged as the IPv4 address it is`() {
        assertLocal("::ffff:192.168.1.5")
        assertPublic("::ffff:8.8.8.8")
    }

    @Test
    fun `a dot local name is local, in any case, with or without a trailing dot, and is not looked up`() {
        val asked = mutableListOf<String>()
        val resolve = { name: String ->
            asked += name
            emptyList<InetAddress>()
        }

        assertTrue(LocalAddresses.looksLocal("nagios.local", resolve))
        assertTrue(LocalAddresses.looksLocal("NAGIOS.Local", resolve))
        assertTrue(LocalAddresses.looksLocal("nagios.local.", resolve))
        assertEquals("mDNS names are not resolved here", emptyList<String>(), asked)
    }

    @Test
    fun `a name is local if it resolves to a local address, and one is enough`() {
        val addresses = mapOf(
            "nagios.example.org" to listOf("192.168.1.5"),
            "both.example.org" to listOf("8.8.8.8", "10.0.0.1"),
            "public.example.org" to listOf("8.8.8.8", "93.184.216.34"),
            "none.example.org" to emptyList(),
        )
        val resolve = { name: String -> addresses.getValue(name).map(InetAddress::getByName) }

        assertTrue(LocalAddresses.looksLocal("nagios.example.org", resolve))
        assertTrue(LocalAddresses.looksLocal("both.example.org", resolve))
        assertFalse(LocalAddresses.looksLocal("public.example.org", resolve))
        assertFalse(
            "a name that does not resolve does not look local",
            LocalAddresses.looksLocal("none.example.org", resolve),
        )
    }

    @Test
    fun `an address written out is judged by the real resolver without any lookup over the network`() {
        assertTrue(LocalAddresses.looksLocal("192.168.1.20"))
        assertFalse(LocalAddresses.looksLocal("8.8.8.8"))
        assertTrue(LocalAddresses.looksLocal("fd12::5"))
    }

    @Test
    fun `an empty host is not local`() {
        assertFalse(LocalAddresses.looksLocal("") { error("nothing to look up") })
        assertFalse(LocalAddresses.looksLocal("  ") { error("nothing to look up") })
    }

    private fun assertLocal(vararg addresses: String) = addresses.forEach {
        assertTrue("$it should look local", LocalAddresses.isLocal(InetAddress.getByName(it)))
    }

    private fun assertPublic(vararg addresses: String) = addresses.forEach {
        assertFalse("$it should not look local", LocalAddresses.isLocal(InetAddress.getByName(it)))
    }
}
