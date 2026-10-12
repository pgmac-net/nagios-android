// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.provideContent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import net.pgmac.nagwatch.notify.PollNow

/**
 * The home-screen widget: one profile's problems, at four sizes.
 *
 * It decides nothing. What to show is [WidgetModel]'s, read from the cache by
 * [WidgetContentLoader]; which layout is [WidgetTier]'s; this only puts the two together and
 * redraws when [WidgetRefresher] says there is something new.
 */
class StatusWidget : GlanceAppWidget() {
    // One layout is composed for each of these and Android shows whichever fits. They are the
    // smallest size of each layout, and a wide version of the two that gain from the width.
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(48.dp, 48.dp),
            DpSize(WidgetTier.COUNTS_FROM_WIDTH.dp, 48.dp),
            DpSize(WIDE.dp, 48.dp),
            DpSize(WidgetTier.COUNTS_FROM_WIDTH.dp, WidgetTier.LIST_FROM_HEIGHT.dp),
            DpSize(WIDE.dp, WidgetTier.LIST_FROM_HEIGHT.dp),
            DpSize(WIDE.dp, WidgetTier.FULL_FROM_HEIGHT.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dependencies = dependencies(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val first = dependencies.widgetContentLoader().load(appWidgetId)
        provideContent {
            val change by dependencies.widgetRefresher().changes.collectAsState()
            val view by produceState(first, change) { value = dependencies.widgetContentLoader().load(appWidgetId) }
            GlanceTheme { StatusWidgetContent(view, appWidgetId) }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun widgetContentLoader(): WidgetContentLoader

        fun widgetRefresher(): WidgetRefresher

        fun widgetBindings(): WidgetBindings

        fun pollNow(): PollNow
    }

    companion object {
        /** From three cells wide or so: room for the time beside the counts. */
        const val WIDE = 200

        fun dependencies(context: Context): Dependencies =
            EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
    }
}

/** What Android talks to. Glance does the work; this adds forgetting a widget that was removed. */
class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        forget(context, appWidgetIds)
    }

    companion object {
        /**
         * Forgets widgets that were removed from the home screen. A broadcast has a few seconds
         * and this is one small write; the profile it frees stops being polled as soon as it lands.
         */
        fun forget(context: Context, appWidgetIds: IntArray) = runBlocking {
            StatusWidget.dependencies(context).widgetBindings().unbind(appWidgetIds.toList())
        }
    }
}

/** The Refresh button: one poll of the widget's profile, now. */
class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val profileId = parameters[PROFILE] ?: return
        StatusWidget.dependencies(context).pollNow().request(profileId)
    }

    companion object {
        val PROFILE = ActionParameters.Key<Long>("profile")
    }
}
