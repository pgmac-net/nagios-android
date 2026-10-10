// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import android.database.sqlite.SQLiteException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosClient
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.map
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.Downtime
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SettingsResult
import net.pgmac.nagwatch.status.cache.CachedAnnotations
import net.pgmac.nagwatch.status.cache.CachedDetail
import net.pgmac.nagwatch.status.cache.DetailCache
import okhttp3.HttpUrl

/**
 * The comments or the downtimes of one object.
 *
 * They come from their own request and can fail on their own: an older Nagios,
 * or a user without the right to read them, must not take the rest of the
 * screen with it. So each carries its own [error], and keeps whatever was
 * saved earlier beside it.
 */
data class AnnotationSection<T>(
    val items: List<T> = emptyList(),
    /** When [items] were fetched; null if they never have been. */
    val fetchedAt: Instant? = null,
    val loading: Boolean = false,
    val error: StatusError? = null,
)

/** Everything known about one host or service, for its detail screen. */
data class ObjectDetail(
    val ref: ObjectRef,
    /** The last good record, with when it was fetched. It survives a failed refresh. */
    val record: CachedDetail? = null,
    val refreshing: Boolean = false,
    /** Why the latest fetch of [record] failed, if it did. */
    val error: StatusError? = null,
    val comments: AnnotationSection<Comment> = AnnotationSection(),
    val downtimes: AnnotationSection<Downtime> = AnnotationSection(),
    /** This object's page in the Nagios web interface, once the CGI directory is known. */
    val nagiosUrl: String? = null,
)

/**
 * Loads one host or service for its detail screen: what is saved first, so
 * there is something to read at once and offline, then the record, its
 * comments and its downtimes from Nagios, each saved as it arrives.
 */
@Singleton
class DetailRepository @Inject constructor(
    private val profiles: ProfileRepository,
    private val clients: NagiosClientProvider,
    private val cache: DetailCache,
    private val clock: Clock,
) {
    /**
     * Emits the state of the object as it is learned. [from] is what is already
     * on screen, for a refresh: without it the first thing emitted is the cache.
     */
    fun open(profileId: Long, ref: ObjectRef, from: ObjectDetail? = null): Flow<ObjectDetail> = flow {
        val saved = from ?: cached(profileId, ref).also { emit(it) }
        val loading = saved.copy(
            refreshing = true,
            comments = saved.comments.copy(loading = true),
            downtimes = saved.downtimes.copy(loading = true),
        )
        emit(loading)
        when (val settings = profiles.settingsFor(profileId)) {
            is SettingsResult.Ready -> refresh(profileId, loading, settings.settings) { emit(it) }
            SettingsResult.NotFound -> emit(loading.failed(StatusError.ProfileMissing))
            SettingsResult.CredentialsUnavailable -> emit(loading.failed(StatusError.CredentialsUnavailable))
            SettingsResult.InvalidUrl -> emit(loading.failed(StatusError.InvalidUrl))
        }
    }

    private suspend fun cached(profileId: Long, ref: ObjectRef): ObjectDetail = ObjectDetail(
        ref = ref,
        record = cache.load(profileId, ref),
        comments = cache.loadComments(profileId, ref).toSection(),
        downtimes = cache.loadDowntimes(profileId, ref).toSection(),
    )

    private suspend fun refresh(
        profileId: Long,
        start: ObjectDetail,
        settings: ConnectionSettings,
        emit: suspend (ObjectDetail) -> Unit,
    ) {
        val client = clients.create(settings)
        val ref = start.ref
        var detail = start.copy(nagiosUrl = nagiosUrl(settings.cgiBase, ref))

        val failure = when (val record = fetchRecord(client, ref)) {
            is NagiosResult.Success -> {
                store { save(profileId, record.value) }
                detail = detail.copy(record = record.value, error = null)
                null
            }

            is NagiosResult.Failure -> {
                detail = detail.copy(error = StatusError.Nagios(record.error))
                record.error
            }
        }
        if (failure != null && !failure.isAboutThisRecord()) {
            // Nagios could not be reached or would not let us in. Asking twice more would only
            // make the user wait for the same answer twice more.
            emit(detail.failed(StatusError.Nagios(failure)))
            return
        }
        emit(detail)

        // Comments and downtimes hang off the saved record; with none, they are shown and not kept.
        val keep = detail.record != null
        detail = detail.copy(
            comments = section(client.fetchComments(ref), detail.comments) { items ->
                if (keep) store { cache.saveComments(profileId, ref, items) }
            },
        )
        emit(detail)
        detail = detail.copy(
            refreshing = false,
            downtimes = section(client.fetchDowntimes(ref), detail.downtimes) { items ->
                if (keep) store { cache.saveDowntimes(profileId, ref, items) }
            },
        )
        emit(detail)
    }

    private suspend fun fetchRecord(client: NagiosClient, ref: ObjectRef): NagiosResult<CachedDetail> {
        val now = clock.instant()
        return when {
            ref.isHost -> client.fetchHost(ref.hostName).map { CachedDetail.Host(it, now) }
            else -> client.fetchService(ref).map { CachedDetail.Service(it, now) }
        }
    }

    private suspend fun save(profileId: Long, record: CachedDetail) = when (record) {
        is CachedDetail.Host -> cache.saveOpened(profileId, record.status)
        is CachedDetail.Service -> cache.saveOpened(profileId, record.status)
    }

    private suspend fun <T> section(
        result: NagiosResult<List<T>>,
        held: AnnotationSection<T>,
        save: suspend (List<T>) -> Unit,
    ): AnnotationSection<T> = when (result) {
        is NagiosResult.Success -> {
            save(result.value)
            AnnotationSection(items = result.value, fetchedAt = clock.instant())
        }

        // What was saved earlier stays, with the reason it could not be refreshed beside it.
        is NagiosResult.Failure -> held.copy(loading = false, error = StatusError.Nagios(result.error))
    }

    /**
     * The cache is a convenience. If it cannot be written (a full disk, say) what
     * was fetched is still on screen, and the next visit tries again.
     */
    private suspend fun store(write: suspend () -> Unit) {
        try {
            write()
        } catch (_: SQLiteException) {
            // Nothing to do: the result is already shown, and nothing depends on the write.
        }
    }

    private fun ObjectDetail.failed(error: StatusError): ObjectDetail = copy(
        refreshing = false,
        error = error,
        comments = comments.copy(loading = false, error = error),
        downtimes = downtimes.copy(loading = false, error = error),
    )

    private fun <T> CachedAnnotations<T>.toSection() = AnnotationSection(items = items, fetchedAt = fetchedAt)

    /**
     * True when Nagios answered and the answer was about this one record: an error
     * from its API, or a server error such as the one a record with unusual output
     * causes. Its comments and downtimes are separate requests and may still work.
     */
    private fun NagiosError.isAboutThisRecord(): Boolean = this is NagiosError.Api || this is NagiosError.Http

    companion object {
        /**
         * The object's page in Nagios' own web interface. Built from the profile's
         * address and nothing else: no credentials are ever put in a URL that is
         * handed to another app.
         */
        fun nagiosUrl(cgiBase: HttpUrl?, ref: ObjectRef): String? {
            val page = cgiBase?.resolve(EXTINFO_CGI) ?: return null
            val url = page.newBuilder()
                .username("")
                .password("")
                .addQueryParameter("type", if (ref.isHost) EXTINFO_HOST else EXTINFO_SERVICE)
                .addQueryParameter("host", ref.hostName)
            ref.description?.let { url.addQueryParameter("service", it) }
            return url.build().toString()
        }

        private const val EXTINFO_CGI = "extinfo.cgi"
        private const val EXTINFO_HOST = "1"
        private const val EXTINFO_SERVICE = "2"
    }
}
