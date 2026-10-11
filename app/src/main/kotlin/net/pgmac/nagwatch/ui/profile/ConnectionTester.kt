// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import javax.inject.Inject
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosClient
import net.pgmac.nagwatch.nagios.NagiosClientFactory
import net.pgmac.nagwatch.nagios.NagiosResult

/** "Test connection": does this profile reach a Nagios? A seam so the editor can be tested without a network. */
fun interface ConnectionTester {
    suspend fun test(settings: ConnectionSettings): NagiosResult<NagiosClient.Connection>

    /**
     * Whether Nagios takes commands from this user: false for a read-only one, null when it
     * could not be found out. One request that changes nothing, made after [test] succeeds.
     */
    suspend fun mayCommand(settings: ConnectionSettings): Boolean? = null
}

class NagiosConnectionTester @Inject constructor(private val factory: NagiosClientFactory) : ConnectionTester {
    override suspend fun test(settings: ConnectionSettings): NagiosResult<NagiosClient.Connection> =
        factory.create(settings).connect()

    override suspend fun mayCommand(settings: ConnectionSettings): Boolean? =
        (factory.createCommands(settings).mayCommand() as? NagiosResult.Success)?.value
}
