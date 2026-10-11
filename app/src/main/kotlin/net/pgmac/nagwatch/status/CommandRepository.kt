// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.status

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import net.pgmac.nagwatch.nagios.ConnectionSettings
import net.pgmac.nagwatch.nagios.NagiosClient
import net.pgmac.nagwatch.nagios.NagiosClientFactory
import net.pgmac.nagwatch.nagios.NagiosError
import net.pgmac.nagwatch.nagios.NagiosResult
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.command.CommandClient
import net.pgmac.nagwatch.nagios.command.CommandOutcome
import net.pgmac.nagwatch.nagios.command.Refusal
import net.pgmac.nagwatch.nagios.command.storedCommentText
import net.pgmac.nagwatch.nagios.model.CheckStatus
import net.pgmac.nagwatch.nagios.model.Comment
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.profile.SettingsResult

/** Makes a command client for some settings. A seam so sending can be tested without a network. */
fun interface CommandClientProvider {
    fun create(settings: ConnectionSettings): CommandClient
}

class FactoryCommandClientProvider @Inject constructor(private val factory: NagiosClientFactory) :
    CommandClientProvider {
    override fun create(settings: ConnectionSettings): CommandClient = factory.createCommands(settings)
}

/** Whether a profile's Nagios user can send commands at all. */
enum class CommandPermission {
    /** Not asked yet, or the question could not be answered. Treated as "may": a wrong "cannot" is worse. */
    UNKNOWN,
    ALLOWED,

    /** Nagios takes no commands from this user, for any object. */
    READ_ONLY,
}

/**
 * Where a command has got to, in words the screen can use as they are.
 *
 * The line between [Sent] and [Confirmed] is the important one. Nagios accepting a command
 * means it wrote the command down; it acts on it some seconds later, and sometimes not at
 * all. So "done" is only ever said after the object has been read again and the effect seen.
 */
sealed interface ActionProgress {
    val command: Command

    /** On its way. */
    data class Sending(override val command: Command) : ActionProgress

    /** Nagios accepted it. Now looking for its effect. */
    data class Sent(override val command: Command) : ActionProgress

    /** The object was read again and shows the effect. */
    data class Confirmed(override val command: Command) : ActionProgress

    /**
     * Nagios accepted it and the effect has not shown in the time allowed. Not an error: a slow
     * check, a busy server, or a command Nagios quietly dropped (acknowledging something that
     * has just recovered).
     */
    data class NotConfirmed(override val command: Command) : ActionProgress

    /** Nagios answered, and said no. */
    data class Refused(override val command: Command, val reason: Refusal, val detail: String) : ActionProgress

    /** It certainly did not reach Nagios. Nothing changed. */
    data class NotSent(override val command: Command, val error: StatusError) : ActionProgress

    /** It may or may not have reached Nagios. Look before trying again. */
    data class Unknown(override val command: Command, val error: NagiosError?) : ActionProgress

    /** True while there is nothing for the user to do but wait. */
    val inFlight: Boolean get() = this is Sending || this is Sent
}

/**
 * Sends commands for a profile and finds out whether they took effect.
 *
 * Nothing here retries a command, and nothing is queued for later: a command that cannot be
 * sent now is reported as not sent, and it is for the person to decide whether they still
 * want it.
 */
@Singleton
class CommandRepository internal constructor(
    private val profiles: ProfileRepository,
    private val readers: NagiosClientProvider,
    private val senders: CommandClientProvider,
    /** How long to wait before each look for the effect of a command. */
    private val confirmAfter: List<Duration>,
) {
    @Inject
    constructor(profiles: ProfileRepository, readers: NagiosClientProvider, senders: CommandClientProvider) :
        this(profiles, readers, senders, CONFIRM_AFTER)

    /** What has been learned about each profile's user, for as long as the app runs. */
    private val permissions = ConcurrentHashMap<String, CommandPermission>()

    /**
     * Whether this profile's user may send commands. Asked of Nagios once (one request that
     * changes nothing) and remembered until the app restarts or the profile's address or user
     * changes. An answer that could not be had is not remembered.
     */
    suspend fun permission(profileId: Long): CommandPermission {
        val settings = (profiles.settingsFor(profileId) as? SettingsResult.Ready)?.settings
            ?: return CommandPermission.UNKNOWN
        permissions[settings.key(profileId)]?.let { return it }
        val answer = when (val asked = senders.create(settings).mayCommand()) {
            is NagiosResult.Failure -> return CommandPermission.UNKNOWN
            is NagiosResult.Success -> if (asked.value) CommandPermission.ALLOWED else CommandPermission.READ_ONLY
        }
        permissions[settings.key(profileId)] = answer
        return answer
    }

    /** Sends [command] once, then reports what became of it. */
    fun run(profileId: Long, command: Command): Flow<ActionProgress> = flow {
        emit(ActionProgress.Sending(command))
        val settings = when (val found = profiles.settingsFor(profileId)) {
            is SettingsResult.Ready -> found.settings

            else -> {
                emit(ActionProgress.NotSent(command, found.asError()))
                return@flow
            }
        }
        val reader = readers.create(settings)
        // What "the check has run again" has to be later than, taken before anything is sent.
        val lastCheckBefore = if (command is Command.ForceCheck) check(reader, command.target)?.lastCheck else null

        // Once begun, a send is seen through even if the screen that asked has gone: a command
        // cut off part-way is the one case where nobody could say whether it arrived.
        when (val outcome = withContext(NonCancellable) { senders.create(settings).send(command).outcome }) {
            CommandOutcome.Accepted -> {
                emit(ActionProgress.Sent(command))
                val seen = confirm { effectSeen(reader, command, lastCheckBefore) }
                emit(if (seen) ActionProgress.Confirmed(command) else ActionProgress.NotConfirmed(command))
            }

            is CommandOutcome.Refused -> {
                // Learned the hard way, but learned: no need to ask again.
                if (outcome.reason == Refusal.READ_ONLY) {
                    permissions[settings.key(profileId)] = CommandPermission.READ_ONLY
                }
                emit(ActionProgress.Refused(command, outcome.reason, outcome.detail))
            }

            is CommandOutcome.NotSent -> emit(ActionProgress.NotSent(command, StatusError.Nagios(outcome.error)))

            is CommandOutcome.Unknown -> emit(ActionProgress.Unknown(command, outcome.error))

            // Only a command that carries a date can end here, and nothing sends one yet.
            is CommandOutcome.DateOrderNeeded -> emit(ActionProgress.Unknown(command, null))
        }
    }

    /** Looks for the effect a few times, with a wait before each look. */
    private suspend fun confirm(seen: suspend () -> Boolean): Boolean {
        for (wait in confirmAfter) {
            delay(wait.toMillis())
            if (seen()) return true
        }
        return false
    }

    /**
     * Whether the object now shows what [command] asked for. A read that fails is "not seen":
     * the next look may succeed, and if none does the answer is "not confirmed", which is true.
     */
    private suspend fun effectSeen(reader: NagiosClient, command: Command, lastCheckBefore: Instant?): Boolean =
        when (command) {
            is Command.Acknowledge -> check(reader, command.target)?.acknowledged == true

            is Command.RemoveAcknowledgement -> check(reader, command.target)?.acknowledged == false

            is Command.ForceCheck -> {
                val lastCheck = check(reader, command.target)?.lastCheck
                lastCheck != null && (lastCheckBefore == null || lastCheck > lastCheckBefore)
            }

            // The author is whoever Nagios says it is, so the comment is recognised by its words.
            is Command.AddComment -> {
                val words = storedCommentText(command.comment)
                comments(reader, command.target).any { it.text == words }
            }

            // Downtimes are confirmed by comparing windows, which arrives with the downtime screens.
            is Command.ScheduleDowntime, is Command.CancelDowntime -> false
        }

    private suspend fun check(reader: NagiosClient, target: ObjectRef): CheckStatus? = when {
        target.isHost -> (reader.fetchHost(target.hostName) as? NagiosResult.Success)?.value?.check
        else -> (reader.fetchService(target) as? NagiosResult.Success)?.value?.check
    }

    private suspend fun comments(reader: NagiosClient, target: ObjectRef): List<Comment> =
        (reader.fetchComments(target) as? NagiosResult.Success)?.value.orEmpty()

    private fun SettingsResult.asError(): StatusError = when (this) {
        SettingsResult.CredentialsUnavailable -> StatusError.CredentialsUnavailable
        SettingsResult.InvalidUrl -> StatusError.InvalidUrl
        SettingsResult.NotFound, is SettingsResult.Ready -> StatusError.ProfileMissing
    }

    /** A different address or user is a different answer. Holds no secret. */
    private fun ConnectionSettings.key(profileId: Long): String =
        "$profileId|${baseUrl.host}:${baseUrl.port}${baseUrl.encodedPath}|$username"

    private companion object {
        /**
         * Nagios rewrites its status every ten seconds or so, and on a test instance a command's
         * effect showed between 3 and 10 seconds after it was accepted. Six looks over 25 seconds.
         */
        val CONFIRM_AFTER: List<Duration> = listOf(2L, 3L, 4L, 5L, 5L, 6L).map(Duration::ofSeconds)
    }
}
