// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.localnet

import java.net.InetAddress
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.pgmac.nagwatch.nagios.LocalNetworkGuard

/** Whether Android's local network permission is held. Always true where it does not exist. */
interface LocalNetworkPermission {
    fun isHeld(): Boolean
}

/**
 * What the app remembers about asking, for the whole install: the permission belongs to the
 * app, not to a profile, so a second profile on the same LAN has nothing new to ask.
 */
interface LocalNetworkHistory {
    /** The app has shown its own explanation and, whatever the answer, will not raise the prompt by itself again. */
    suspend fun explained(): Boolean

    suspend fun markExplained()

    /** Android has stopped showing its prompt, so the only way left is the app's settings page. */
    suspend fun exhausted(): Boolean

    suspend fun setExhausted(exhausted: Boolean)
}

/**
 * Local network access, in one place: whether the app should ask before reaching a profile,
 * and whether a timeout is probably the protection.
 *
 * Nothing here ever stops a request. After a refusal the app still tries, because a Nagios
 * reached over a VPN needs no permission and the app cannot tell which way a request will go.
 */
@Singleton
class LocalNetwork internal constructor(
    private val permission: LocalNetworkPermission,
    private val history: LocalNetworkHistory,
    private val resolve: (String) -> List<InetAddress>,
    private val io: CoroutineDispatcher,
) : LocalNetworkGuard {
    @Inject
    constructor(permission: LocalNetworkPermission, history: LocalNetworkHistory) :
        this(permission, history, ::systemLookup, Dispatchers.IO)

    /** The permission is not held and [host] looks local: a timeout is probably Android. Blocking. */
    override fun likelyBlocking(host: String): Boolean =
        !permission.isHeld() && LocalAddresses.looksLocal(host, resolve)

    /**
     * Whether to explain and ask before contacting [host]: it looks local, the permission is not
     * held, and the app has not asked yet on this install. Cheap checks first; the lookup last.
     */
    suspend fun shouldAsk(host: String): Boolean = withContext(io) {
        !permission.isHeld() && !history.explained() && LocalAddresses.looksLocal(host, resolve)
    }

    suspend fun explained() = history.markExplained()

    /**
     * The system prompt came back. [canAskAgain] is Android's own `shouldShowRequestPermissionRationale`
     * after the answer: false after a refusal means it will not show the prompt again.
     */
    suspend fun answered(granted: Boolean, canAskAgain: Boolean) = history.setExhausted(!granted && !canAskAgain)

    /** True when the button should open settings, not raise a prompt Android would ignore. */
    suspend fun exhausted(): Boolean = history.exhausted()
}

private fun systemLookup(name: String): List<InetAddress> = try {
    InetAddress.getAllByName(name).toList()
} catch (_: UnknownHostException) {
    emptyList()
}
