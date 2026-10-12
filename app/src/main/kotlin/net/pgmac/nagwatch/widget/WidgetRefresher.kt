// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.glance.appwidget.updateAll
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.di.ApplicationScope
import net.pgmac.nagwatch.status.PollObserver

/**
 * Has the widgets redrawn when there is something new to draw: a poll has finished, or a widget
 * was pointed at another profile.
 *
 * A widget that is on screen is already running and only needs to be told to look again
 * ([changes]). One that is not has to be started, which is what asking Glance to update does.
 * Both are done every time, because from here there is no telling which case it is.
 */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bindings: WidgetBindings,
    @param:ApplicationScope private val scope: CoroutineScope,
) : PollObserver {
    private val counter = MutableStateFlow(0)

    /** Changes each time the widgets should look at the cache again. */
    val changes: StateFlow<Int> = counter.asStateFlow()

    override suspend fun polled(profileId: Long) = redraw()

    suspend fun redraw() {
        counter.update { it + 1 }
        StatusWidget().updateAll(context)
    }

    /** At app start: forget widgets Android no longer has, in case one went while the app could not be told. */
    fun start() {
        scope.launch {
            val provider = ComponentName(context, StatusWidgetReceiver::class.java)
            bindings.retainOnly(AppWidgetManager.getInstance(context).getAppWidgetIds(provider).toList())
        }
    }
}
