// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import android.database.sqlite.SQLiteException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosClient
import net.pgmac.nagwatch.nagios.NagiosClientFactory
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.ProblemClassifier
import net.pgmac.nagwatch.nagios.model.ProblemReport
import net.pgmac.nagwatch.nagios.model.StatusSnapshot
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SettingsResult
import net.pgmac.nagwatch.status.cache.StatusCache

/** Makes a client for some settings. A seam so the repository can be tested without a network. */
fun interface NagiosClientProvider {
    fun create(settings: ConnectionSettings): NagiosClient
}

class FactoryClientProvider @Inject constructor(private val factory: NagiosClientFactory) : NagiosClientProvider {
    override fun create(settings: ConnectionSettings): NagiosClient = factory.create(settings)
}

/** Why a profile has no fresh status. Each one gets its own explanation on screen. */
sealed interface StatusError {
    data class Nagios(val error: NagiosError) : StatusError

    /** A stored secret cannot be decrypted (Keystore key lost); the user has to enter it again. */
    data object CredentialsUnavailable : StatusError

    data object InvalidUrl : StatusError

    data object ProfileMissing : StatusError
}

/**
 * Everything known about one profile's status.
 *
 * [report] is the last *good* result and survives a failed refresh: a network
 * error must not blank a screen that was showing real problems a minute ago.
 * It may have come from the cache, from an earlier run of the app; [lastSuccess]
 * says how old it is. [error] describes the latest attempt only, and clears on
 * the next success.
 */
data class ProfileStatus(
    val report: ProblemReport? = null,
    /** The poll [report] was made from: every host, and every service's state, for the lists. */
    val snapshot: StatusSnapshot? = null,
    val error: StatusError? = null,
    val refreshing: Boolean = false,
    val lastSuccess: Instant? = null,
) {
    /** Never shown as current: old data is labelled, not passed off as fresh. */
    fun isStale(now: Instant): Boolean = lastSuccess != null && Duration.between(lastSuccess, now) > STALE_AFTER

    companion object {
        /** Twice the 15 minute poll interval the design allows (docs/design.md, section 8). */
        val STALE_AFTER: Duration = Duration.ofMinutes(30)
    }
}

/**
 * Polls profiles and holds the latest result, in memory and in the on-disk
 * cache. On first use of a profile the cached result is served at once, so the
 * screen has something to show before the network answers; a refresh then
 * replaces it.
 */
@Singleton
class StatusRepository @Inject constructor(
    private val profiles: ProfileRepository,
    private val clients: NagiosClientProvider,
    private val cache: StatusCache,
    private val clock: Clock,
) {
    private val mutableStatuses = MutableStateFlow<Map<Long, ProfileStatus>>(emptyMap())
    val statuses: StateFlow<Map<Long, ProfileStatus>> = mutableStatuses.asStateFlow()

    /**
     * Profiles being refreshed now, so overlapping requests (resume plus pull-to-refresh) collapse into one.
     * Changed together with the `refreshing` flag under [lock]: seeing `refreshing = false`
     * must mean a new refresh would be accepted.
     */
    private val running = mutableSetOf<Long>()
    private val lock = Any()

    fun now(): Instant = clock.instant()

    /**
     * Puts the cached result for a profile on screen, if there is one and nothing
     * newer is already held. Cheap, and safe to call every time a profile is shown.
     */
    suspend fun showCached(profileId: Long) {
        if (mutableStatuses.value[profileId]?.report != null) return
        val cached = cache.loadPoll(profileId) ?: return
        mutableStatuses.update { current ->
            val held = current[profileId] ?: ProfileStatus()
            // A refresh may have finished while the cache was being read; it wins.
            if (held.report != null) {
                current
            } else {
                current +
                    (
                        profileId to
                            held.copy(report = classify(cached), snapshot = cached, lastSuccess = cached.fetchedAt)
                        )
            }
        }
    }

    /** Refreshes unless the profile was refreshed within [maxAge], or a refresh is already running. */
    suspend fun refreshIfOlderThan(profileId: Long, maxAge: Duration) {
        showCached(profileId)
        val last = mutableStatuses.value[profileId]?.lastSuccess
        if (last == null || Duration.between(last, clock.instant()) >= maxAge) refresh(profileId)
    }

    suspend fun refresh(profileId: Long) {
        val accepted = synchronized(lock) {
            (running.add(profileId)).also { added -> if (added) update(profileId) { it.copy(refreshing = true) } }
        }
        if (!accepted) return
        try {
            when (val outcome = fetch(profileId)) {
                is Outcome.Fresh -> {
                    // Still refreshing until the cache is written and the marker cleared below:
                    // "not refreshing" must mean a new refresh would be accepted.
                    update(profileId) {
                        ProfileStatus(
                            report = classify(outcome.snapshot),
                            snapshot = outcome.snapshot,
                            lastSuccess = outcome.snapshot.fetchedAt,
                            refreshing = true,
                        )
                    }
                    store(profileId, outcome.snapshot)
                }

                is Outcome.Failed -> update(profileId) { it.copy(error = outcome.error) }
            }
        } finally {
            // Also runs when the caller is cancelled mid-refresh (leaving the screen).
            synchronized(lock) {
                running.remove(profileId)
                update(profileId) { it.copy(refreshing = false) }
            }
        }
    }

    /**
     * The cache is a convenience. If it cannot be written (a full disk, say) the
     * fresh result is still on screen, and the next poll tries again.
     */
    private suspend fun store(profileId: Long, snapshot: StatusSnapshot) {
        try {
            cache.savePoll(profileId, snapshot)
        } catch (_: SQLiteException) {
            // Nothing to do: the result is already shown, and nothing depends on the write.
        }
    }

    private suspend fun fetch(profileId: Long): Outcome = when (val settings = profiles.settingsFor(profileId)) {
        SettingsResult.NotFound -> Outcome.Failed(StatusError.ProfileMissing)
        SettingsResult.CredentialsUnavailable -> Outcome.Failed(StatusError.CredentialsUnavailable)
        SettingsResult.InvalidUrl -> Outcome.Failed(StatusError.InvalidUrl)
        is SettingsResult.Ready -> poll(profileId, settings.settings)
    }

    private suspend fun poll(profileId: Long, settings: ConnectionSettings): Outcome {
        val client = clients.create(settings)
        if (settings.cgiBase == null) {
            // First poll for this profile: find the CGI directory once and remember it.
            val connection = client.connect()
            if (connection is NagiosResult.Failure) return Outcome.Failed(StatusError.Nagios(connection.error))
            profiles.rememberCgiBase(profileId, (connection as NagiosResult.Success).value.cgiBase)
        }
        return when (val result = client.fetchStatus()) {
            is NagiosResult.Success -> Outcome.Fresh(result.value)
            is NagiosResult.Failure -> Outcome.Failed(StatusError.Nagios(result.error))
        }
    }

    private fun classify(snapshot: StatusSnapshot): ProblemReport = ProblemClassifier.classify(snapshot)

    private fun update(profileId: Long, change: (ProfileStatus) -> ProfileStatus) =
        mutableStatuses.update { it + (profileId to change(it[profileId] ?: ProfileStatus())) }

    private sealed interface Outcome {
        class Fresh(val snapshot: StatusSnapshot) : Outcome

        class Failed(val error: StatusError) : Outcome
    }
}
