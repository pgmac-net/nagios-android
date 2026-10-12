// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import net.pgmac.nagwatch.notify.AndroidNotificationPermission
import net.pgmac.nagwatch.notify.AndroidNotifier
import net.pgmac.nagwatch.notify.DataStoreNotificationHistory
import net.pgmac.nagwatch.notify.NotificationHistory
import net.pgmac.nagwatch.notify.NotificationPermission
import net.pgmac.nagwatch.notify.Notifier
import net.pgmac.nagwatch.notify.PollScheduler
import net.pgmac.nagwatch.notify.WorkManagerPollScheduler

@Module
@InstallIn(SingletonComponent::class)
abstract class NotifyModule {
    @Binds
    abstract fun notifier(notifier: AndroidNotifier): Notifier

    @Binds
    abstract fun scheduler(scheduler: WorkManagerPollScheduler): PollScheduler

    @Binds
    abstract fun permission(permission: AndroidNotificationPermission): NotificationPermission

    @Binds
    abstract fun history(history: DataStoreNotificationHistory): NotificationHistory
}
