// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import net.pgmac.nagwatch.status.cache.CacheJanitor

@HiltAndroidApp
class NagwatchApplication : Application() {
    @Inject
    lateinit var cacheJanitor: CacheJanitor

    override fun onCreate() {
        super.onCreate()
        cacheJanitor.start()
    }
}
