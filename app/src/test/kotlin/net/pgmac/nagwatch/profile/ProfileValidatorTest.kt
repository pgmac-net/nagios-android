// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.profile

import net.pgmac.nagwatch.profile.SecretInput.Clear
import net.pgmac.nagwatch.profile.SecretInput.Keep
import net.pgmac.nagwatch.profile.SecretInput.Replace
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileValidatorTest {
    private val valid = ProfileDraft(
        id = null,
        name = "Home",
        baseUrl = "https://nagios.example.org/nagios",
        username = "nagwatch",
        password = Replace("s3cret"),
        accessClientId = "",
        accessClientSecret = Keep,
        customHeaders = emptyList(),
        allowCleartext = false,
    )
    private val stored = Profile(
        id = 7,
        name = "Home",
        baseUrl = "https://nagios.example.org/nagios",
        username = "nagwatch",
        hasPassword = true,
        accessClientId = "id.access",
        hasAccessClientSecret = true,
        customHeaderNames = listOf("X-Proxy-Token"),
        allowCleartext = false,
    )

    @Test
    fun `a complete https profile has no problems`() {
        assertEquals(emptySet<ProfileProblem>(), problems(valid))
    }

    @Test
    fun `required fields are required`() {
        val empty = valid.copy(name = " ", baseUrl = "", username = "", password = Replace(""))

        assertEquals(
            setOf(
                ProfileProblem.NAME_REQUIRED,
                ProfileProblem.URL_REQUIRED,
                ProfileProblem.USERNAME_REQUIRED,
                ProfileProblem.PASSWORD_REQUIRED,
            ),
            problems(empty),
        )
    }

    @Test
    fun `a URL that is not http or https is invalid`() {
        listOf("nagios.example.org", "ftp://nagios.example.org", "https://", "not a url").forEach { url ->
            assertEquals(url, setOf(ProfileProblem.URL_INVALID), problems(valid.copy(baseUrl = url)))
        }
    }

    @Test
    fun `http needs the explicit opt-in`() {
        val http = valid.copy(baseUrl = "http://nagios.lan:8080")

        assertEquals(setOf(ProfileProblem.CLEARTEXT_NOT_ALLOWED), problems(http))
        assertEquals(emptySet<ProfileProblem>(), problems(http.copy(allowCleartext = true)))
    }

    @Test
    fun `the opt-in is not needed, and changes nothing, for https`() {
        assertEquals(emptySet<ProfileProblem>(), problems(valid.copy(allowCleartext = true)))
    }

    @Test
    fun `Access credentials over http are refused even with the opt-in`() {
        val draft = valid.copy(
            baseUrl = "http://nagios.lan",
            allowCleartext = true,
            accessClientId = "id.access",
            accessClientSecret = Replace("secret"),
        )

        assertEquals(setOf(ProfileProblem.ACCESS_OVER_CLEARTEXT), problems(draft))
    }

    @Test
    fun `stored Access credentials also block a change to http`() {
        val draft = valid.copy(
            id = 7,
            baseUrl = "http://nagios.lan",
            allowCleartext = true,
            password = Keep,
            accessClientId = "id.access",
        )

        assertEquals(
            "the stored secret would be sent, so keeping it counts",
            setOf(ProfileProblem.ACCESS_OVER_CLEARTEXT),
            ProfileValidator.validate(draft, stored),
        )
    }

    @Test
    fun `clearing the client ID is enough to remove Access, the kept secret is dropped with it`() {
        val draft = valid.copy(id = 7, baseUrl = "http://nagios.lan", allowCleartext = true, password = Keep)

        assertEquals(emptySet<ProfileProblem>(), ProfileValidator.validate(draft, stored))
    }

    @Test
    fun `Access needs both halves`() {
        assertEquals(setOf(ProfileProblem.ACCESS_INCOMPLETE), problems(valid.copy(accessClientId = "id.access")))
        assertEquals(setOf(ProfileProblem.ACCESS_INCOMPLETE), problems(valid.copy(accessClientSecret = Replace("s"))))
        assertEquals(
            emptySet<ProfileProblem>(),
            problems(valid.copy(accessClientId = "id.access", accessClientSecret = Replace("s"))),
        )
    }

    @Test
    fun `keeping a stored password satisfies the requirement, clearing it does not`() {
        val editing = valid.copy(id = 7, accessClientId = "id.access")

        assertEquals(emptySet<ProfileProblem>(), ProfileValidator.validate(editing.copy(password = Keep), stored))
        assertEquals(
            setOf(ProfileProblem.PASSWORD_REQUIRED),
            ProfileValidator.validate(editing.copy(password = Clear), stored),
        )
    }

    @Test
    fun `keep means nothing for a new profile, where nothing is stored`() {
        assertEquals(setOf(ProfileProblem.PASSWORD_REQUIRED), problems(valid.copy(password = Keep)))
    }

    @Test
    fun `header names must be valid tokens and may not override the app's own headers`() {
        fun header(name: String) = valid.copy(customHeaders = listOf(HeaderDraft(name, Replace("v"))))

        assertEquals(emptySet<ProfileProblem>(), problems(header("X-Proxy-Token")))
        assertEquals(setOf(ProfileProblem.HEADER_NAME_INVALID), problems(header("")))
        assertEquals(setOf(ProfileProblem.HEADER_NAME_INVALID), problems(header("Bad Name")))
        assertEquals(setOf(ProfileProblem.HEADER_NAME_INVALID), problems(header("Evil\r\nInjected: 1")))
        listOf("Authorization", "authorization", "CF-Access-Client-Secret", "Host").forEach { reserved ->
            assertEquals(reserved, setOf(ProfileProblem.HEADER_NAME_RESERVED), problems(header(reserved)))
        }
    }

    @Test
    fun `a header needs a value, unless one is already stored under that name`() {
        val keepHeader = valid.copy(id = 7, accessClientId = "id.access", password = Keep)

        assertEquals(
            setOf(ProfileProblem.HEADER_VALUE_REQUIRED),
            problems(valid.copy(customHeaders = listOf(HeaderDraft("X-New", Replace(""))))),
        )
        assertEquals(
            emptySet<ProfileProblem>(),
            ProfileValidator.validate(
                keepHeader.copy(customHeaders = listOf(HeaderDraft("x-proxy-token", Keep))),
                stored,
            ),
        )
        assertEquals(
            setOf(ProfileProblem.HEADER_VALUE_REQUIRED),
            ProfileValidator.validate(keepHeader.copy(customHeaders = listOf(HeaderDraft("X-Other", Keep))), stored),
        )
    }

    private fun problems(draft: ProfileDraft) = ProfileValidator.validate(draft, stored = null)
}
