// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch
import net.pgmac.nagwatch.MainActivity
import net.pgmac.nagwatch.debug.DebugChrome
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import net.pgmac.nagwatch.widget.StatusWidgetReceiver
import net.pgmac.nagwatch.widget.WidgetBindings
import net.pgmac.nagwatch.widget.WidgetRefresher

/**
 * Chooses which profile a widget shows. Android opens it when a widget is placed, and again
 * from the widget's own menu to change it.
 *
 * It has to be exported, because the launcher starts it, which means any app can. So it accepts
 * one thing from the intent, a widget's number, and only if that number is one of this app's own
 * widgets. All it can then do is point that widget at a profile the person picks on this screen.
 */
@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {
    @Inject
    lateinit var bindings: WidgetBindings

    @Inject
    lateinit var refresher: WidgetRefresher

    private val viewModel: WidgetConfigViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        DebugChrome.applyToWindow(this)
        val appWidgetId = ownWidgetId(intent)
        // Until a profile is chosen the answer is "cancelled": Android then removes a widget that was being placed.
        setResult(RESULT_CANCELED, result(appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        lifecycleScope.launch {
            // What the widget is set to now, so reconfiguring starts from it. Nothing for a new one.
            val profileId = bindings.profileOf(appWidgetId)
            val current = WidgetSetup(profileId, bindings.opacityOf(appWidgetId), placed = profileId != null)
            setContent {
                val state by viewModel.state.collectAsStateWithLifecycle()
                NagwatchTheme {
                    DebugChrome.Frame {
                        WidgetConfigContent(
                            state = state,
                            current = current,
                            onSave = { profile, opacity -> save(appWidgetId, profile, opacity) },
                            onAddProfile = {
                                startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java))
                            },
                            onCancel = ::finish,
                        )
                    }
                }
            }
        }
    }

    private fun save(appWidgetId: Int, profileId: Long, opacity: Int) {
        lifecycleScope.launch {
            bindings.setOpacity(appWidgetId, opacity)
            bindings.bind(appWidgetId, profileId)
            refresher.redraw()
            setResult(RESULT_OK, result(appWidgetId))
            finish()
        }
    }

    /** The widget this was opened for, if it is one of ours; otherwise none. */
    private fun ownWidgetId(intent: Intent?): Int {
        val id = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)?.provider
        val ours = ComponentName(this, StatusWidgetReceiver::class.java)
        return if (provider == ours) id else AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun result(appWidgetId: Int) = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}
