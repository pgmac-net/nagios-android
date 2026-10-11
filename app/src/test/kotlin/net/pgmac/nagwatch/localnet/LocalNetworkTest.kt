// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.localnet

import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeLocalNetworkPermission(var held: Boolean = false) : LocalNetworkPermission {
    override fun isHeld(): Boolean = held
}

class FakeLocalNetworkHistory(var explained: Boolean = false, var exhausted: Boolean = false) : LocalNetworkHistory {
    override suspend fun explained(): Boolean = explained

    override suspend fun markExplained() {
        explained = true
    }

    override suspend fun exhausted(): Boolean = exhausted

    override suspend fun setExhausted(exhausted: Boolean) {
        this.exhausted = exhausted
    }
}

/**
 * A [LocalNetwork] with no Android in it. Every name resolves to [addresses] and every lookup is
 * counted, so a test can say that a cheap check came first.
 */
class TestLocalNetwork(
    val permission: FakeLocalNetworkPermission = FakeLocalNetworkPermission(),
    val history: FakeLocalNetworkHistory = FakeLocalNetworkHistory(),
    addresses: List<String> = listOf("192.168.1.20"),
) {
    var lookups = 0
        private set

    val network = LocalNetwork(
        permission,
        history,
        { _ ->
            lookups++
            addresses.map(InetAddress::getByName)
        },
        Dispatchers.Unconfined,
    )
}

/** A network with no questions in it: the permission is held, so nothing is ever asked and nothing is blocked. */
fun noLocalNetworkQuestions(): LocalNetwork = TestLocalNetwork(FakeLocalNetworkPermission(held = true)).network

class LocalNetworkTest {
    private val host = "nagios.example.org"

    @Test
    fun `without the permission, a timeout to a local-looking host is probably Android`() {
        assertTrue(TestLocalNetwork().network.likelyBlocking(host))
    }

    @Test
    fun `with the permission it is not Android`() {
        assertFalse(TestLocalNetwork(FakeLocalNetworkPermission(held = true)).network.likelyBlocking(host))
    }

    @Test
    fun `a timeout to a public address is not Android`() {
        assertFalse(TestLocalNetwork(addresses = listOf("93.184.216.34")).network.likelyBlocking(host))
    }

    @Test
    fun `the first time, a local-looking host without the permission is asked about`() = runBlocking {
        assertTrue(TestLocalNetwork().network.shouldAsk(host))
    }

    @Test
    fun `nothing is asked once the permission is held, or the app has already asked, or the host is public`() =
        runBlocking {
            assertFalse(TestLocalNetwork(FakeLocalNetworkPermission(held = true)).network.shouldAsk(host))
            assertFalse(TestLocalNetwork(history = FakeLocalNetworkHistory(explained = true)).network.shouldAsk(host))
            assertFalse(TestLocalNetwork(addresses = listOf("8.8.8.8")).network.shouldAsk(host))
        }

    @Test
    fun `the cheap checks come before the lookup`() = runBlocking {
        val held = TestLocalNetwork(FakeLocalNetworkPermission(held = true))
        val asked = TestLocalNetwork(history = FakeLocalNetworkHistory(explained = true))

        held.network.shouldAsk(host)
        asked.network.shouldAsk(host)

        assertEquals(0, held.lookups)
        assertEquals(0, asked.lookups)
    }

    @Test
    fun `explaining is remembered for the whole install, so a second profile has nothing to ask`() = runBlocking {
        val test = TestLocalNetwork()
        assertTrue(test.network.shouldAsk(host))

        test.network.explained()

        assertFalse(test.network.shouldAsk("another.example.org"))
    }

    @Test
    fun `being refused with the prompt still available is not the end, and being refused without it is`() =
        runBlocking {
            val test = TestLocalNetwork()

            test.network.answered(granted = false, canAskAgain = true)
            assertFalse("Android will ask again", test.network.exhausted())

            test.network.answered(granted = false, canAskAgain = false)
            assertTrue("Android has stopped asking", test.network.exhausted())

            test.network.answered(granted = true, canAskAgain = false)
            assertFalse("a grant ends it", test.network.exhausted())
        }
}
