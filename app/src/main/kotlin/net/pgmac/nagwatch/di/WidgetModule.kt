// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import net.pgmac.nagwatch.status.PollObserver
import net.pgmac.nagwatch.widget.DataStoreWidgetBindings
import net.pgmac.nagwatch.widget.WidgetBindings
import net.pgmac.nagwatch.widget.WidgetRefresher

@Module
@InstallIn(SingletonComponent::class)
abstract class WidgetModule {
    @Binds
    abstract fun bindings(bindings: DataStoreWidgetBindings): WidgetBindings

    /** Whoever wants to know that a poll has finished. Declared so that it may be empty. */
    @Multibinds
    abstract fun pollObservers(): Set<PollObserver>

    /** The widgets are redrawn when a poll finishes, whoever asked for it. */
    @Binds
    @IntoSet
    abstract fun widgetsAreRedrawn(refresher: WidgetRefresher): PollObserver
}
