// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.testing.unit.GlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.assertHasRunCallbackClickAction
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasTestTag
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.nagios.model.ProblemCounts
import net.pgmac.nagwatch.nagios.model.Severity
import net.pgmac.nagwatch.notify.NotificationTarget
import net.pgmac.nagwatch.notify.OutageCause
import net.pgmac.nagwatch.ui.widget.WidgetConfigActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The widget as it is drawn, at each size and in each state. What is shown was decided by
 * `WidgetModel`; these check that each layout draws it, and that none of them makes old data
 * look current.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class StatusWidgetContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val disk =
        WidgetRow(
            ObjectRef("web01", "Disk /"),
            Severity.CRITICAL,
            T0 - Duration.ofHours(2),
            "DISK CRITICAL - 97%",
            soft = false,
        )
    private val db01 =
        WidgetRow(ObjectRef("db01"), Severity.HOST_DOWN, T0 - Duration.ofMinutes(5), "no reply", soft = false)
    private val load = WidgetRow(ObjectRef("web01", "Load"), Severity.WARNING, null, "", soft = true)
    private val current = WidgetContent(
        state = WidgetState.CURRENT,
        profileName = "Home",
        // A different number of each kind, so that a count shown under the wrong name is seen.
        counts = ProblemCounts(hostsDown = 1, critical = 2, warning = 3, unknown = 0),
        handled = 2,
        worst = Severity.HOST_DOWN,
        rows = listOf(db01, disk, load),
        asOf = T0,
    )
    private val allWell = current.copy(
        counts = ProblemCounts(0, 0, 0, 0),
        worst = null,
        rows = emptyList(),
        handled = 0,
    )

    // ---------------------------------------------------------------- which layout

    @Test
    fun `each size draws its own layout`() {
        draw(current, BADGE) { onNode(hasTestTag(WidgetTags.BADGE)).assertExists() }
        draw(current, COUNTS) { onNode(hasTestTag(WidgetTags.COUNTS)).assertExists() }
        draw(current, SHORT) { onNode(hasTestTag(WidgetTags.SHORT_LIST)).assertExists() }
        draw(current, FULL) { onNode(hasTestTag(WidgetTags.FULL_LIST)).assertExists() }
    }

    // ---------------------------------------------------------------- badge

    @Test
    fun `the badge is the number of unhandled problems`() = draw(current, BADGE) {
        onNode(hasTestTag(WidgetTags.NUMBER)).assert(hasTextEqualTo("6"))
    }

    @Test
    fun `with nothing wrong the badge is a tick and no number`() = draw(allWell, BADGE) {
        onNode(hasTestTag(WidgetTags.BADGE)).assertExists()
        onNode(hasTestTag(WidgetTags.NUMBER)).assertDoesNotExist()
    }

    @Test
    fun `an out-of-date badge keeps its number, zero included - a tick would say all is well`() {
        draw(current.copy(state = WidgetState.STALE), BADGE) {
            onNode(hasTestTag(WidgetTags.NUMBER)).assert(hasTextEqualTo("6"))
        }
        draw(allWell.copy(state = WidgetState.UNREACHABLE), BADGE) {
            onNode(hasTestTag(WidgetTags.NUMBER)).assert(hasTextEqualTo("0"))
        }
    }

    // ---------------------------------------------------------------- counts

    @Test
    fun `the counts are the four kinds, each with its number`() = draw(current, COUNTS) {
        onNode(hasTestTag(WidgetTags.count("down"))).assert(hasTextEqualTo("DOWN 1"))
        onNode(hasTestTag(WidgetTags.count("critical"))).assert(hasTextEqualTo("CRIT 2"))
        onNode(hasTestTag(WidgetTags.count("warning"))).assert(hasTextEqualTo("WARN 3"))
        onNode(hasTestTag(WidgetTags.count("unknown"))).assert(hasTextEqualTo("UNKN 0"))
    }

    @Test
    fun `a narrow row of counts has no room for the time, a wide one shows it`() {
        draw(current, COUNTS) { onNode(hasTestTag(WidgetTags.STATUS)).assertDoesNotExist() }
        draw(current, COUNTS_WIDE) { onNode(hasTestTag(WidgetTags.STATUS)).assert(hasText("Updated")) }
    }

    @Test
    fun `old or unconfirmed counts say so even where there is no room for the time`() {
        draw(current.copy(state = WidgetState.STALE), COUNTS) {
            onNode(hasTestTag(WidgetTags.STATUS)).assert(hasText("Out of date"))
            onNode(hasTestTag(WidgetTags.count("critical"))).assert(hasTextEqualTo("CRIT 2"))
        }
        draw(current.copy(state = WidgetState.UNREACHABLE, cause = OutageCause.UNREACHABLE), COUNTS) {
            onNode(hasTestTag(WidgetTags.STATUS)).assert(hasText("Can\u2019t reach Nagios"))
        }
    }

    // ---------------------------------------------------------------- lists

    @Test
    fun `a short list shows as many problems as fit, worst first, and no plugin output`() = draw(current, SHORT) {
        onNode(hasTestTag(WidgetTags.row(0))).assertExists()
        onNode(hasTestTag(WidgetTags.row(1))).assertExists()
        onNode(hasTestTag(WidgetTags.row(2))).assertDoesNotExist()
        onNode(hasText("db01")).assertExists()
        onNode(hasText("web01 / Disk /")).assertExists()
        onNode(hasText("DISK CRITICAL")).assertDoesNotExist()
        onNode(hasTestTag(WidgetTags.STATUS)).assertExists()
    }

    @Test
    fun `a full list has the profile's name, every problem with a line of output, and how long each has lasted`() =
        draw(current, FULL) {
            onNode(hasTextEqualTo("Home")).assertExists()
            onNode(hasTestTag(WidgetTags.row(0))).assertExists()
            onNode(hasTestTag(WidgetTags.row(2))).assertExists()
            onNode(hasTextEqualTo("DISK CRITICAL - 97%")).assertExists()
            onNode(hasTextEqualTo("2h 00m")).assertExists()
            onNode(hasTestTag(WidgetTags.STATUS)).assert(hasText("Updated"))
        }

    @Test
    fun `a full list says how many problems it left out`() {
        draw(current.copy(more = 7), FULL) { onNode(hasTextEqualTo("and 7 more problems")).assertExists() }
        draw(current.copy(more = 1), FULL) { onNode(hasTextEqualTo("and 1 more problem")).assertExists() }
        draw(current, FULL) { onNode(hasText("more")).assertDoesNotExist() }
    }

    // ---------------------------------------------------------------- nothing known

    @Test
    fun `with nothing known no size shows a number, only what kind of nothing`() {
        val noData = WidgetContent(WidgetState.NO_DATA, "Home")
        val unreachable = WidgetContent(WidgetState.UNREACHABLE, "Home", cause = OutageCause.CREDENTIALS_REJECTED)
        listOf(BADGE, COUNTS, SHORT, FULL).forEach { size ->
            draw(noData, size) {
                onNode(hasTestTag(WidgetTags.MESSAGE)).assertExists()
                onNode(hasTextEqualTo("No data yet")).assertExists()
                onNode(hasTestTag(WidgetTags.NUMBER)).assertDoesNotExist()
                onNode(hasTestTag(WidgetTags.count("critical"))).assertDoesNotExist()
            }
            draw(unreachable, size) { onNode(hasTextEqualTo("Can\u2019t reach Nagios")).assertExists() }
        }
    }

    // ---------------------------------------------------------------- taps

    @Test
    fun `the widget itself opens its profile's problems`() {
        listOf(BADGE, COUNTS, SHORT, FULL).forEach { size ->
            draw(current, size) {
                onNode(
                    hasTestTag(WidgetTags.ROOT),
                ).assertHasStartActivityClickAction(NotificationTarget(PROFILE).toIntent(context))
            }
        }
    }

    @Test
    fun `a row opens the host or the service it is about`() = draw(current, FULL) {
        onNode(hasTestTag(WidgetTags.row(0)))
            .assertHasStartActivityClickAction(NotificationTarget(PROFILE, ObjectRef("db01")).toIntent(context))
        onNode(hasTestTag(WidgetTags.row(1)))
            .assertHasStartActivityClickAction(
                NotificationTarget(PROFILE, ObjectRef("web01", "Disk /")).toIntent(context),
            )
    }

    @Test
    fun `Refresh asks for a poll of this widget's profile, and is only on the full list`() {
        draw(current, FULL) {
            onNode(hasTestTag(WidgetTags.REFRESH))
                .assertHasRunCallbackClickAction<RefreshAction>(actionParametersOf(RefreshAction.PROFILE to PROFILE))
        }
        draw(current, SHORT) { onNode(hasTestTag(WidgetTags.REFRESH)).assertDoesNotExist() }
    }

    @Test
    fun `a widget with no profile says so, and a tap opens the screen that chooses one`() {
        val view = WidgetView(profileId = null, content = WidgetContent(WidgetState.PROFILE_GONE), now = T0)

        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(COUNTS)
            provideComposable { GlanceTheme { StatusWidgetContent(view, WIDGET) } }

            onNode(hasTextEqualTo("No profile. Tap to choose one.")).assertExists()
            // Not a profile's problems: it has no profile. The screen that chooses one, for this widget.
            onNode(hasTestTag(WidgetTags.ROOT)).assertHasStartActivityClickAction(
                Intent(context, WidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, WIDGET)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---------------------------------------------------------------- the status line

    @Test
    fun `the status line says how far what is shown can be trusted`() {
        val at = "10:00"

        assertEquals("Updated $at", status(current))
        assertEquals("Out of date \u00b7 as of $at", status(current.copy(state = WidgetState.STALE)))
        assertEquals("Can\u2019t reach Nagios \u00b7 as of $at", status(current.copy(state = WidgetState.UNREACHABLE)))
        assertEquals("Can\u2019t reach Nagios", status(WidgetContent(WidgetState.UNREACHABLE, "Home")))
        assertEquals("No data yet", status(WidgetContent(WidgetState.NO_DATA, "Home")))
        assertEquals("No profile. Tap to choose one.", status(WidgetContent(WidgetState.PROFILE_GONE)))
    }

    @Test
    fun `the time is in the phone's own zone`() {
        assertEquals("Updated 20:00", statusText(context, current, ZoneOffset.ofHours(10)))
    }

    // ----------------------------------------------------------------

    private fun status(content: WidgetContent) = statusText(context, content, ZoneOffset.UTC)

    private fun draw(content: WidgetContent, size: DpSize, check: GlanceAppWidgetUnitTest.() -> Unit) =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(size)
            provideComposable { GlanceTheme { StatusWidgetContent(WidgetView(PROFILE, content, T0), WIDGET) } }
            check()
        }

    private companion object {
        const val PROFILE = 7L
        const val WIDGET = 41
        val T0: Instant = Instant.parse("2026-10-13T10:00:00Z")
        val BADGE = DpSize(57.dp, 102.dp)
        val COUNTS = DpSize(130.dp, 102.dp)
        val COUNTS_WIDE = DpSize(276.dp, 102.dp)
        val SHORT = DpSize(203.dp, 112.dp)
        val FULL = DpSize(276.dp, 338.dp)
    }
}
