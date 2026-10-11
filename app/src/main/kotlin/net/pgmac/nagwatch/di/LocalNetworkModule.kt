// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.pgmac.nagwatch.localnet.AndroidLocalNetworkPermission
import net.pgmac.nagwatch.localnet.DataStoreLocalNetworkHistory
import net.pgmac.nagwatch.localnet.LocalNetwork
import net.pgmac.nagwatch.localnet.LocalNetworkHistory
import net.pgmac.nagwatch.localnet.LocalNetworkPermission
import net.pgmac.nagwatch.nagios.LocalNetworkGuard

@Module
@InstallIn(SingletonComponent::class)
abstract class LocalNetworkModule {
    @Binds
    abstract fun permission(permission: AndroidLocalNetworkPermission): LocalNetworkPermission

    @Binds
    abstract fun history(history: DataStoreLocalNetworkHistory): LocalNetworkHistory

    /** The Nagios client asks this after a timeout, so it can say when Android is the likely cause. */
    @Binds
    abstract fun guard(network: LocalNetwork): LocalNetworkGuard
}
