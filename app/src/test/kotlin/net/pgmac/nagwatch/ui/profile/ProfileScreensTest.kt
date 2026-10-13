// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.ui.profile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.profile.GraphSettings
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileProblem
import net.pgmac.nagwatch.ui.localnet.LocalNetworkTags
import net.pgmac.nagwatch.ui.notify.NotificationTags
import net.pgmac.nagwatch.ui.theme.NagwatchTheme
import net.pgmac.nagwatch.ui.toUiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The editor's rules as the user meets them, and the profile list. */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class ProfileScreensTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(ProfileEditorState(loading = false))
    private val calls = mutableListOf<String>()
    private val actions = object : ProfileEditorActions {
        override fun edit(change: (ProfileEditorState) -> ProfileEditorState) {
            state = change(state)
        }

        override fun testConnection() {
            calls += "test"
        }

        override fun setNotifications(enabled: Boolean) {
            calls += "setNotifications $enabled"
        }

        override fun editNotifications(change: (NotificationSettings) -> NotificationSettings) {
            state = state.copy(notifications = change(state.notifications))
        }

        override fun localNetworkContinue() {
            calls += "localNetworkContinue"
        }

        override fun localNetworkNotNow() {
            calls += "localNetworkNotNow"
        }

        override fun allowLocalNetwork() {
            calls += "allowLocalNetwork"
        }

        override fun save() {
            calls += "save"
        }

        override fun delete() {
            calls += "delete"
        }

        override fun close() {
            calls += "close"
        }
    }

    @Test
    fun `an https URL shows no cleartext warning or switch`() {
        showEditor(ProfileEditorState(loading = false, baseUrl = "https://nagios.example.org"))

        composeRule.onNodeWithTag(ProfileEditorTags.CLEARTEXT_WARNING).assertDoesNotExist()
        composeRule.onNodeWithTag(ProfileEditorTags.CLEARTEXT_SWITCH).assertDoesNotExist()
    }

    @Test
    fun `typing an http URL brings up the warning and an opt-in that starts off`() {
        showEditor(ProfileEditorState(loading = false))

        composeRule.onNodeWithTag(ProfileEditorTags.URL).performTextInput("http://nagios.lan:8080")

        composeRule.onNodeWithTag(ProfileEditorTags.CLEARTEXT_WARNING).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfileEditorTags.CLEARTEXT_SWITCH).assertIsOff()
        assertEquals("never switched on for the user", false, state.allowCleartext)
    }

    @Test
    fun `the opt-in is a deliberate tap`() {
        showEditor(ProfileEditorState(loading = false, baseUrl = "http://nagios.lan"))

        composeRule.onNodeWithTag(ProfileEditorTags.CLEARTEXT_SWITCH).performClick()

        composeRule.onNodeWithTag(ProfileEditorTags.CLEARTEXT_SWITCH).assertIsOn()
        assertEquals(true, state.allowCleartext)
    }

    @Test
    fun `a stored password is shown as saved, never as text`() {
        showEditor(ProfileEditorState.from(storedProfile))

        composeRule.onNodeWithTag(ProfileEditorTags.PASSWORD_SAVED).assertTextContains("saved", substring = true)
        composeRule.onNodeWithTag(ProfileEditorTags.PASSWORD).assertDoesNotExist()
    }

    @Test
    fun `replace opens an empty masked field rather than revealing the old value`() {
        showEditor(ProfileEditorState.from(storedProfile))

        composeRule.onAllNodesWithText("Replace")[0].performClick()
        composeRule.onNodeWithTag(ProfileEditorTags.PASSWORD).performTextInput("new-password")

        assertEquals("new-password", state.password.input)
        // Masking is visual; what marks the field as a password is this semantic,
        // which is also what keeps it out of accessibility read-outs.
        composeRule.onNodeWithTag(ProfileEditorTags.PASSWORD)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test
    fun `validation problems are listed in words`() {
        showEditor(
            ProfileEditorState(
                loading = false,
                problems = setOf(ProfileProblem.ACCESS_OVER_CLEARTEXT, ProfileProblem.NAME_REQUIRED),
            ),
        )

        composeRule.onNodeWithTag(ProfileEditorTags.PROBLEMS).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("never sent over unencrypted http", substring = true).assertExists()
        composeRule.onNodeWithText("Give the profile a name.").assertExists()
    }

    @Test
    fun `each kind of connection failure has its own message`() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val messages = SAMPLE_ERRORS.map { error ->
            val text = error.toUiText()
            context.getString(text.id, *text.args.toTypedArray())
        }

        assertEquals("every error class must read differently", messages.size, messages.toSet().size)
        assertTrue("no message may be blank or a bare placeholder", messages.all { it.length > MIN_MESSAGE_LENGTH })
    }

    @Test
    fun `a failed test shows that failure's message`() {
        showEditor(ProfileEditorState(loading = false, test = TestState.Failed(NagiosError.BadCredentials)))

        composeRule.onNodeWithTag(ProfileEditorTags.TEST_RESULT).performScrollTo()
            .assertTextContains("rejected the username or password", substring = true)
    }

    @Test
    fun `a successful test names the Nagios version`() {
        showEditor(ProfileEditorState(loading = false, test = TestState.Succeeded("4.5.9")))

        composeRule.onNodeWithTag(
            ProfileEditorTags.TEST_RESULT,
        ).performScrollTo().assertTextContains("4.5.9", substring = true)
    }

    @Test
    fun `a current Nagios gets no note about its version`() {
        showEditor(ProfileEditorState(loading = false, test = TestState.Succeeded("4.5.9")))

        composeRule.onNodeWithTag(ProfileEditorTags.TEST_RESULT).performScrollTo()
        composeRule.onNodeWithTag(ProfileEditorTags.TEST_NOTE).assertDoesNotExist()
    }

    @Test
    fun `testing a local address asks first with the app's own sentence`() {
        showEditor(ProfileEditorState(loading = false, explainLocalNetwork = true))

        composeRule.onNodeWithTag(LocalNetworkTags.EXPLANATION).assertIsDisplayed()
        composeRule.onNodeWithTag(LocalNetworkTags.CONTINUE).performClick()
        composeRule.onNodeWithTag(LocalNetworkTags.NOT_NOW).performClick()

        assertEquals(listOf("localNetworkContinue", "localNetworkNotNow"), calls)
    }

    @Test
    fun `a test that failed because Android is probably blocking it says so, with the way to grant it`() {
        showEditor(ProfileEditorState(loading = false, test = TestState.Failed(NagiosError.LocalNetworkBlocked)))

        composeRule.onNodeWithTag(ProfileEditorTags.TEST_RESULT).performScrollTo()
            .assertTextContains("Android may be blocking Nagwatch", substring = true)
        composeRule.onNodeWithTag(LocalNetworkTags.ALLOW).performScrollTo().performClick()

        assertEquals(listOf("allowLocalNetwork"), calls)
    }

    @Test
    fun `other failed tests have no such button`() {
        showEditor(ProfileEditorState(loading = false, test = TestState.Failed(NagiosError.BadCredentials)))

        composeRule.onNodeWithTag(ProfileEditorTags.TEST_RESULT).performScrollTo()
        composeRule.onNodeWithTag(LocalNetworkTags.ALLOW).assertDoesNotExist()
    }

    @Test
    fun `a read-only user still connects, and is told what that means`() {
        showEditor(ProfileEditorState(loading = false, test = TestState.Succeeded("4.5.9", readOnly = true)))

        composeRule.onNodeWithTag(
            ProfileEditorTags.TEST_RESULT,
        ).performScrollTo().assertTextContains("Connected", substring = true)
        composeRule.onNodeWithTag(ProfileEditorTags.TEST_READ_ONLY)
            .performScrollTo()
            .assertTextContains("read-only", substring = true)
    }

    @Test
    fun `a user who may send commands gets no note about it`() {
        showEditor(ProfileEditorState(loading = false, test = TestState.Succeeded("4.5.9")))

        composeRule.onNodeWithTag(ProfileEditorTags.TEST_RESULT).performScrollTo()
        composeRule.onNodeWithTag(ProfileEditorTags.TEST_READ_ONLY).assertDoesNotExist()
    }

    @Test
    fun `an older Nagios still connects, and is told what to expect`() {
        showEditor(ProfileEditorState(loading = false, test = TestState.Succeeded("4.3.4")))

        composeRule.onNodeWithTag(
            ProfileEditorTags.TEST_RESULT,
        ).performScrollTo().assertTextContains("Connected", substring = true)
        composeRule.onNodeWithTag(ProfileEditorTags.TEST_NOTE)
            .performScrollTo()
            .assertTextContains("tested on Nagios 4.5", substring = true)
    }

    @Test
    fun `unreadable stored credentials are explained`() {
        showEditor(ProfileEditorState(loading = false, test = TestState.CredentialsUnavailable))

        composeRule.onNodeWithTag(ProfileEditorTags.TEST_RESULT).performScrollTo()
            .assertTextContains("can no longer be read", substring = true)
    }

    @Test
    fun `save and test are passed on`() {
        showEditor(ProfileEditorState(loading = false))

        composeRule.onNodeWithTag(ProfileEditorTags.TEST).performScrollTo().performClick()
        composeRule.onNodeWithTag(ProfileEditorTags.SAVE).performScrollTo().performClick()

        assertEquals(listOf("test", "save"), calls)
    }

    @Test
    fun `deleting asks first`() {
        showEditor(ProfileEditorState.from(storedProfile))

        composeRule.onNodeWithText("Delete").performClick()
        assertEquals("nothing deleted yet", emptyList<String>(), calls)
        composeRule.onNodeWithText("Delete profile?").assertIsDisplayed()

        composeRule.onAllNodesWithText("Delete")[1].performClick()
        assertEquals(listOf("delete"), calls)
    }

    @Test
    fun `the editor shows how often the profile is checked even with notifications off, and it can be changed`() {
        showEditor(ProfileEditorState(loading = false))

        composeRule.onNodeWithTag(
            NotificationTags.interval(PollInterval.FIFTEEN_MINUTES),
        ).performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag(NotificationTags.interval(PollInterval.ONE_HOUR)).performClick()

        assertEquals(PollInterval.ONE_HOUR, state.toDraft().notifications.interval)
        assertEquals("and nothing was switched on by it", false, state.toDraft().notifications.enabled)
    }

    @Test
    fun `the editor has a notifications section, off for a new profile`() {
        showEditor(ProfileEditorState(loading = false))

        composeRule.onNodeWithTag(NotificationTags.SWITCH).performScrollTo().assertIsDisplayed().assertIsOff()
        composeRule.onNodeWithTag(NotificationTags.SWITCH).performClick()
        assertEquals(listOf("setNotifications true"), calls)
    }

    @Test
    fun `a profile with notifications on shows its choices, and a change to one is a change to the profile`() {
        showEditor(ProfileEditorState.from(storedProfile.copy(notifications = NotificationSettings(enabled = true))))

        composeRule.onNodeWithTag(NotificationTags.SWITCH).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(NotificationTags.UNREACHABLE).performScrollTo().performClick()

        assertEquals(NotificationSettings(enabled = true, tellUnreachable = false), state.toDraft().notifications)
    }

    @Test
    fun `a new profile cannot be deleted`() {
        showEditor(ProfileEditorState(loading = false))

        composeRule.onNodeWithText("Delete").assertDoesNotExist()
    }

    @Test
    fun `the empty profile list says what a profile is`() {
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                ProfilesContent(ProfilesState(loading = false, version = "0.1.0"), {}, {})
            }
        }

        composeRule.onNodeWithTag(ProfilesTags.EMPTY).assertIsDisplayed()
        composeRule.onNodeWithTag(ProfilesTags.ADD).assertIsDisplayed()
        composeRule.onNodeWithText("Nagwatch 0.1.0").assertIsDisplayed()
    }

    @Test
    fun `the list marks unencrypted profiles and opens the one tapped`() {
        val opened = mutableListOf<Long>()
        val profiles = listOf(storedProfile, storedProfile.copy(id = 8, name = "LAN", baseUrl = "http://nagios.lan"))
        composeRule.setContent {
            NagwatchTheme(dynamicColor = false) {
                ProfilesContent(ProfilesState(loading = false, profiles = profiles), {}, { opened += it })
            }
        }

        composeRule.onAllNodesWithText("Unencrypted HTTP").assertCountEquals(1)
        composeRule.onNodeWithText("LAN").performClick()
        assertEquals(listOf(8L), opened)
    }

    @Test
    fun `graphs are on in a new profile, with a path that can be typed`() {
        showEditor(ProfileEditorState(loading = false))

        composeRule.onNodeWithTag(ProfileEditorTags.GRAPHS_SWITCH).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(
            ProfileEditorTags.GRAPH_PATH,
        ).performScrollTo().performTextInput("/nagiosgraph/cgi-bin/")

        assertEquals(GraphSettings(enabled = true, path = "/nagiosgraph/cgi-bin/"), state.toDraft().graphs)
    }

    @Test
    fun `switching graphs off hides the path and keeps what was typed`() {
        showEditor(ProfileEditorState(loading = false, graphs = GraphSettings(path = "/nagiosgraph/cgi-bin/")))

        composeRule.onNodeWithTag(ProfileEditorTags.GRAPHS_SWITCH).performScrollTo().performClick()

        composeRule.onNodeWithTag(ProfileEditorTags.GRAPH_PATH).assertDoesNotExist()
        assertEquals(GraphSettings(enabled = false, path = "/nagiosgraph/cgi-bin/"), state.toDraft().graphs)
    }

    @Test
    fun `a stored profile's graph settings are what the editor opens with`() {
        showEditor(ProfileEditorState.from(storedProfile.copy(graphs = GraphSettings(enabled = false, path = "/g/"))))

        composeRule.onNodeWithTag(ProfileEditorTags.GRAPHS_SWITCH).performScrollTo().assertIsOff()
        assertEquals(GraphSettings(enabled = false, path = "/g/"), state.toDraft().graphs)
    }

    @Test
    fun `a graph path that is not a path is said to be the problem`() {
        showEditor(
            ProfileEditorState(loading = false, problems = setOf(ProfileProblem.GRAPH_PATH_INVALID)),
        )

        composeRule.onNodeWithText("cannot be a full URL", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `Cloudflare Access and custom headers start folded on a new profile`() {
        showEditor(ProfileEditorState(loading = false))

        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_TOGGLE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_FIELDS).assertDoesNotExist()
        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_SET, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(ProfileEditorTags.HEADERS_TOGGLE).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ProfileEditorTags.HEADERS_FIELDS).assertDoesNotExist()
    }

    @Test
    fun `tapping the title opens a section and tapping it again folds it`() {
        showEditor(ProfileEditorState(loading = false))

        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_TOGGLE).performScrollTo().performClick()
        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_FIELDS).assertExists()
        composeRule.onNodeWithText("Client ID").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_TOGGLE).performClick()
        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_FIELDS).assertDoesNotExist()
    }

    @Test
    fun `a profile that has Access set opens with it showing`() {
        showEditor(ProfileEditorState.from(storedProfile.copy(accessClientId = "abc.access", hasAccessClientSecret = true)))

        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_FIELDS).assertExists()
        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_SET, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a folded section with something in it says Set, and folding changes nothing that is saved`() {
        val before = ProfileEditorState.from(storedProfile.copy(accessClientId = "abc.access", hasAccessClientSecret = true))
        showEditor(before)

        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_TOGGLE).performScrollTo().performClick()

        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_FIELDS).assertDoesNotExist()
        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_SET, useUnmergedTree = true).assertExists()
        assertEquals("abc.access", state.accessClientId)
        assertEquals(before.accessClientSecret, state.accessClientSecret)
        assertEquals(before.toDraft(), state.toDraft())

        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_TOGGLE).performClick()

        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_FIELDS).assertExists()
    }

    @Test
    fun `custom headers open when there is one, and say Set when folded`() {
        showEditor(ProfileEditorState.from(storedProfile).copy(headers = listOf(HeaderField("X-Env", SecretField(stored = true)))))

        composeRule.onNodeWithTag(ProfileEditorTags.HEADERS_FIELDS).assertExists()

        composeRule.onNodeWithTag(ProfileEditorTags.HEADERS_TOGGLE).performScrollTo().performClick()

        composeRule.onNodeWithTag(ProfileEditorTags.HEADERS_SET, useUnmergedTree = true).assertExists()
        assertEquals(1, state.headers.size)
    }

    @Test
    fun `a section with a problem is open, so the error is not out of sight`() {
        showEditor(ProfileEditorState(loading = false, problems = setOf(ProfileProblem.ACCESS_INCOMPLETE)))

        composeRule.onNodeWithTag(ProfileEditorTags.ACCESS_FIELDS).assertExists()
        composeRule.onNodeWithTag(ProfileEditorTags.HEADERS_FIELDS).assertDoesNotExist()
    }

    private fun showEditor(initial: ProfileEditorState) {
        state = initial
        composeRule.setContent { NagwatchTheme(dynamicColor = false) { ProfileEditorContent(state, actions) } }
    }

    private companion object {
        const val MIN_MESSAGE_LENGTH = 20

        val storedProfile = Profile(
            id = 7,
            name = "Home",
            baseUrl = "https://nagios.example.org/nagios",
            username = "nagwatch",
            hasPassword = true,
            accessClientId = "",
            hasAccessClientSecret = false,
            customHeaderNames = emptyList(),
            allowCleartext = false,
        )

        /** One of every error class, and of every reason within Unreachable. */
        val SAMPLE_ERRORS = listOf(
            NagiosError.AccessRejected,
            NagiosError.BadCredentials,
            NagiosError.Forbidden,
            NagiosError.Certificate("PKIX path building failed"),
            NagiosError.Unreachable(NagiosError.Unreachable.Reason.DNS, "x"),
            NagiosError.Unreachable(NagiosError.Unreachable.Reason.REFUSED, "x"),
            NagiosError.Unreachable(NagiosError.Unreachable.Reason.TIMEOUT, "x"),
            NagiosError.Unreachable(NagiosError.Unreachable.Reason.OTHER, "socket closed"),
            NagiosError.NotNagios,
            NagiosError.Api(6, "Option Value Invalid", "The query option value is invalid."),
            NagiosError.CleartextRefused,
            NagiosError.AccessOverCleartext,
            NagiosError.Redirected("https://elsewhere.example.org/"),
            NagiosError.ResponseTooLarge,
            NagiosError.Http(500),
        )
    }
}
