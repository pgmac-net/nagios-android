// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.localnet

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Whether an address is one Android's local network protection covers.
 *
 * Android defines a local network as an IP network on a broadcast-capable interface (Wi-Fi,
 * Ethernet; not cellular, not a VPN) and lists these ranges:
 * https://developer.android.com/privacy-and-security/local-network-definition
 *
 * This is the *address* half of that. The interface half cannot be known before connecting:
 * a `10.x` Nagios reached over a VPN is not local, and looks identical here. So the result is
 * "looks local", and the app never treats it as certain.
 */
object LocalAddresses {
    /** Names Android resolves through multicast DNS, which is local network traffic itself. */
    private const val MDNS_SUFFIX = ".local"

    /**
     * True if [host] is, or resolves to, a local network address, or is a `.local` name.
     * One address is enough when a name has several. A name that does not resolve does not
     * look local: the lookup failure is the more useful thing to show.
     *
     * [resolve] is a parameter so this has no network in it. A lookup is exempt from the
     * protection, so the real one is safe to make before connecting.
     */
    fun looksLocal(host: String, resolve: (String) -> List<InetAddress> = ::systemResolve): Boolean {
        val name = host.trim().trimEnd('.').lowercase()
        return when {
            name.isEmpty() -> false
            name.endsWith(MDNS_SUFFIX) -> true
            else -> resolve(name).any(::isLocal)
        }
    }

    /** One address, against the ranges Android lists. Loopback and the unspecified address are in none of them. */
    fun isLocal(address: InetAddress): Boolean = when {
        address.isMulticastAddress -> true
        address is Inet4Address -> isLocalV4(address.address)
        address is Inet6Address -> isLocalV6(address.address)
        else -> false
    }

    @Suppress("MagicNumber")
    private fun isLocalV4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        return when {
            a == 10 -> true

            // 172.16.0.0/12
            a == 172 && b in 16..31 -> true

            a == 192 && b == 168 -> true

            // 169.254.0.0/16, link-local
            a == 169 && b == 254 -> true

            // 100.64.0.0/10, carrier-grade NAT: also what Tailscale hands out
            a == 100 && b in 64..127 -> true

            // The limited broadcast address, 255.255.255.255
            bytes.all { it == 0xFF.toByte() } -> true

            else -> false
        }
    }

    /** fe80::/10 link-local, and fc00::/7 unique local. */
    @Suppress("MagicNumber")
    private fun isLocalV6(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        val linkLocal = first == 0xFE && (second and 0xC0) == 0x80
        val uniqueLocal = (first and 0xFE) == 0xFC
        return linkLocal || uniqueLocal
    }

    /** Blocking: call it off the main thread. */
    private fun systemResolve(name: String): List<InetAddress> = try {
        InetAddress.getAllByName(name).toList()
    } catch (_: UnknownHostException) {
        emptyList()
    }
}
