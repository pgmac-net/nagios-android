// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import net.pgmac.nagwatch.nagios.NagiosClientFactory
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.profile.ProfileDraft
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SecretCipher
import net.pgmac.nagwatch.profile.SecretInput
import net.pgmac.nagwatch.profile.SettingsResult
import net.pgmac.nagwatch.profile.inMemoryProfileDatabase
import net.pgmac.nagwatch.profile.softwareKeySource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What a screen is told when a real command goes to the throwaway test Nagios
 * (`scripts/test-nagios`), with the waits the app really uses. Skipped unless asked for, and
 * never run in CI.
 *
 *     scripts/test-nagios/live-test.sh
 *
 * These tests change the server they talk to, so the same proof is asked of it as in
 * `CommandLiveTest`: it must be on this machine and answer as the made-up instance.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class)
class CommandRepositoryLiveTest {
    private val url = System.getenv("NAGWATCH_TEST_NAGIOS").orEmpty()
    private val profileDatabase = inMemoryProfileDatabase()
    private val profiles = ProfileRepository(profileDatabase.profiles(), SecretCipher(softwareKeySource()), Json)
    private val factory = NagiosClientFactory(
        OkHttpClient.Builder().callTimeout(Duration.ofSeconds(30)).build(),
        Json { ignoreUnknownKeys = true },
        Dispatchers.IO,
        Clock.systemUTC(),
    )
    private val repository = CommandRepository(profiles, factory::create, factory::createCommands)

    private val disk = ObjectRef("web01", "Disk /")
    private val load = ObjectRef("web01", "Load")
    private val http = ObjectRef("web01", "HTTP")

    @Before
    fun onlyTheTestInstance() {
        assumeTrue("set NAGWATCH_TEST_NAGIOS (scripts/test-nagios/live-test.sh does)", url.isNotEmpty())
        val host = url.toHttpUrl().host
        check(host == "127.0.0.1" || host == "localhost") { "refusing to send commands to $host: not this machine" }
        val web01 = runBlocking {
            val settings = (profiles.settingsFor(profile("operator")) as SettingsResult.Ready).settings
            factory.create(settings).fetchHost("web01")
        }
        check(web01 is NagiosResult.Success && "made up" in web01.value.check.pluginOutput) {
            "refusing to send commands: this is not the test Nagios from scripts/test-nagios"
        }
    }

    @After
    fun close() = profileDatabase.close()

    @Test
    fun `an acknowledgement and its removal are each confirmed in the time the app allows`() = runBlocking {
        val operator = profile("operator")

        assertEquals(ActionProgress.Confirmed::class, timed("acknowledge", operator, Command.Acknowledge(disk, "live")))
        assertEquals(
            ActionProgress.Confirmed::class,
            timed("remove acknowledgement", operator, Command.RemoveAcknowledgement(disk)),
        )
    }

    @Test
    fun `a comment Nagios alters is still recognised as the one that was sent`() = runBlocking {
        val typed = "live: <b>markup</b>; and more ${Instant.now().epochSecond}"

        assertEquals(
            ActionProgress.Confirmed::class,
            timed("comment", profile("operator"), Command.AddComment(load, typed)),
        )
    }

    @Test
    fun `a recheck is confirmed by a check that ran after it was asked for`() = runBlocking {
        // The last check time has whole seconds: let one pass so that "later" can be seen.
        delay(Duration.ofSeconds(2).toMillis())

        assertEquals(ActionProgress.Confirmed::class, timed("recheck", profile("operator"), Command.ForceCheck(load)))
    }

    @Test
    fun `an acknowledgement Nagios drops without a word is not called done`() = runBlocking {
        // HTTP is OK, and Nagios accepts an acknowledgement of it and then does nothing.
        assertEquals(
            ActionProgress.NotConfirmed::class,
            timed("acknowledge something that is fine", profile("operator"), Command.Acknowledge(http, "live")),
        )
    }

    @Test
    fun `a read-only user is known before anything is tried, and refused if it is`() = runBlocking {
        val viewer = profile("viewer")

        assertEquals(CommandPermission.READ_ONLY, repository.permission(viewer))
        val last = repository.run(viewer, Command.ForceCheck(load)).toList().last()
        assertEquals(Refusal.READ_ONLY, (last as ActionProgress.Refused).reason)
    }

    @Test
    fun `a user who may command some things is refused only for the others`() = runBlocking {
        val limited = profile("limited")

        assertEquals(CommandPermission.ALLOWED, repository.permission(limited))
        val other = repository.run(limited, Command.ForceCheck(ObjectRef("db01", "Replication"))).toList().last()
        assertEquals(Refusal.NOT_AUTHORISED, (other as ActionProgress.Refused).reason)
        assertEquals("and is still not thought read-only", CommandPermission.ALLOWED, repository.permission(limited))
        assertEquals(ActionProgress.Confirmed::class, timed("recheck as limited", limited, Command.ForceCheck(http)))
    }

    /** Runs [command] and says how long the answer took, so the waits can be judged against a real server. */
    private suspend fun timed(what: String, profileId: Long, command: Command) = run {
        val started = System.nanoTime()
        val seen = repository.run(profileId, command).toList()
        val seconds = Duration.ofNanos(System.nanoTime() - started).toMillis() / MILLIS_PER_SECOND
        println("live: $what -> ${seen.last()::class.simpleName} after ${"%.1f".format(seconds)}s")
        assertTrue("it was accepted first: $seen", seen.any { it is ActionProgress.Sent })
        seen.last()::class
    }

    private fun profile(user: String): Long = runBlocking {
        profiles.save(
            ProfileDraft(
                id = null,
                name = user,
                baseUrl = url,
                username = user,
                // Not secrets: they belong to the made-up instance (scripts/test-nagios/start.sh).
                password = SecretInput.Replace("$user-pw"),
                accessClientId = "",
                accessClientSecret = SecretInput.Keep,
                customHeaders = emptyList(),
                allowCleartext = true,
            ),
        )
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1000.0
    }
}
