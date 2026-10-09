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
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileProblem
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
