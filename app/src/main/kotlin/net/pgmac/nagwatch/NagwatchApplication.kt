// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import net.pgmac.nagwatch.notify.PollSchedule
import net.pgmac.nagwatch.status.cache.CacheJanitor
import net.pgmac.nagwatch.widget.WidgetRefresher

@HiltAndroidApp
class NagwatchApplication : Application() {
    @Inject
    lateinit var cacheJanitor: CacheJanitor

    @Inject
    lateinit var pollSchedule: PollSchedule

    @Inject
    lateinit var widgetRefresher: WidgetRefresher

    override fun onCreate() {
        super.onCreate()
        cacheJanitor.start()
        // Before the schedule looks: a widget Android no longer has must not keep its profile polled.
        widgetRefresher.start()
        pollSchedule.start()
    }
}
