// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import net.pgmac.nagwatch.nagios.command.Command
import net.pgmac.nagwatch.nagios.model.ObjectRef
import net.pgmac.nagwatch.profile.ProfileRepository
import net.pgmac.nagwatch.status.ActionProgress
import net.pgmac.nagwatch.status.CommandRepository

/** What a button on a problem's notification does. Each is sent at once (docs/adr/0012). */
enum class NoticeAction { ACKNOWLEDGE, RECHECK }

/**
 * A press of a button on a problem's notification: what to do, to what, on which profile, and
 * enough of the notification to draw it again.
 *
 * The profile is part of it, and is the only place the profile comes from. Nothing here asks
 * which profile the app happens to be showing: two Nagios servers can each have a `web01`.
 *
 * The notification's own words travel with it because by the time the answer comes back the
 * notification may have been swiped away, and the answer still has to be said somewhere.
 */
data class ActionRequest(
    val profileId: Long,
    val profileName: String,
    val ref: ObjectRef,
    val action: NoticeAction,
    val channelId: String,
    val title: String,
    val text: String,
    val color: Int,
) {
    /**
     * Addressed to [NotificationActionReceiver] by name, which is not exported, so only Nagwatch
     * and Android acting on one of Nagwatch's own notifications can send it. The data makes each
     * button's intent its own: Android would otherwise hand every button the last one's extras.
     */
    fun toPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setData(
                Uri.Builder().scheme("nagwatch").authority("action")
                    .appendPath(action.name)
                    .appendPath(profileId.toString())
                    .appendPath(ref.hostName)
                    .appendPath(ref.description.orEmpty())
                    .build(),
            )
            .putExtra(PROFILE, profileId)
            .putExtra(PROFILE_NAME, profileName)
            .putExtra(HOST, ref.hostName)
            .putExtra(SERVICE, ref.description)
            .putExtra(ACTION, action.name)
            .putExtra(CHANNEL, channelId)
            .putExtra(TITLE, title)
            .putExtra(TEXT, text)
            .putExtra(COLOR, color)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun toData(): Data = Data.Builder()
        .putLong(PROFILE, profileId)
        .putString(PROFILE_NAME, profileName)
        .putString(HOST, ref.hostName)
        .putString(SERVICE, ref.description)
        .putString(ACTION, action.name)
        .putString(CHANNEL, channelId)
        .putString(TITLE, title)
        .putString(TEXT, text)
        .putInt(COLOR, color)
        .build()

    companion object {
        private const val PROFILE = "profile"
        private const val PROFILE_NAME = "profile_name"
        private const val HOST = "host"
        private const val SERVICE = "service"
        private const val ACTION = "action"
        private const val CHANNEL = "channel"
        private const val TITLE = "title"
        private const val TEXT = "text"
        private const val COLOR = "color"
        private const val NO_PROFILE = -1L

        /** Null for anything that is not a whole request: it is then ignored, not guessed at. */
        fun from(intent: Intent): ActionRequest? = of(
            profileId = intent.getLongExtra(PROFILE, NO_PROFILE),
            profileName = intent.getStringExtra(PROFILE_NAME),
            host = intent.getStringExtra(HOST),
            service = intent.getStringExtra(SERVICE),
            action = intent.getStringExtra(ACTION),
            channelId = intent.getStringExtra(CHANNEL),
            title = intent.getStringExtra(TITLE),
            text = intent.getStringExtra(TEXT),
            color = intent.getIntExtra(COLOR, 0),
        )

        fun from(data: Data): ActionRequest? = of(
            profileId = data.getLong(PROFILE, NO_PROFILE),
            profileName = data.getString(PROFILE_NAME),
            host = data.getString(HOST),
            service = data.getString(SERVICE),
            action = data.getString(ACTION),
            channelId = data.getString(CHANNEL),
            title = data.getString(TITLE),
            text = data.getString(TEXT),
            color = data.getInt(COLOR, 0),
        )

        // One argument for each thing a request is made of; grouping them would only hide that.
        @Suppress("LongParameterList")
        private fun of(
            profileId: Long,
            profileName: String?,
            host: String?,
            service: String?,
            action: String?,
            channelId: String?,
            title: String?,
            text: String?,
            color: Int,
        ): ActionRequest? {
            val kind = NoticeAction.entries.firstOrNull { it.name == action } ?: return null
            val channel = NotifyChannel.entries.firstOrNull { it.id == channelId } ?: return null
            if (profileId == NO_PROFILE || host.isNullOrBlank() || title.isNullOrBlank()) return null
            return ActionRequest(
                profileId = profileId,
                profileName = profileName.orEmpty(),
                ref = ObjectRef(host, service?.takeIf { it.isNotEmpty() }),
                action = kind,
                channelId = channel.id,
                title = title,
                text = text.orEmpty(),
                color = color,
            )
        }
    }
}

/**
 * Where a button's press arrives. Not exported.
 *
 * It says "on its way" on the notification there and then, so the press is seen to have done
 * something, and hands the rest to a job: sending a command and reading back its effect takes
 * longer than a receiver is allowed.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = ActionRequest.from(intent) ?: return
        val app = context.applicationContext
        val notifier = EntryPointAccessors.fromApplication(app, Dependencies::class.java).notifier()
        // The buttons ask Android to unlock first, which Android does from version 12. This is
        // the same rule for every version, and for anything that reaches here another way.
        if (isLocked(app)) {
            notifier.needsUnlock(request)
            return
        }
        notifier.acting(request)
        val work = OneTimeWorkRequestBuilder<NotificationActionWorker>().setInputData(request.toData())
        // Before Android 12 an expedited job has to run as a foreground service with a notification
        // of its own. There it is an ordinary job, which Android may hold back for a while.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            work.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        WorkManager.getInstance(app).enqueue(work.build())
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun notifier(): Notifier
    }

    companion object {
        /**
         * True while the phone would need a PIN, pattern, password or fingerprint to be used. A
         * phone with no lock set is never locked in this sense: there is nothing to prove.
         */
        fun isLocked(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    }
}

/** WorkManager's side of a button's press. Everything it does is in [NotificationActionRunner]. */
class NotificationActionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        ActionRequest.from(inputData)?.let { request ->
            EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java).runner().run(request)
        }
        // Whatever became of it has been said on the notification. Trying again by itself would
        // send a command the person asked for once, a second time.
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun runner(): NotificationActionRunner
    }
}

/**
 * Sends what a notification's button asked for and says on the notification what became of it.
 *
 * The same [CommandRepository] the screens use, so it is accepted and then confirmed by reading
 * the object back, and nothing is retried.
 */
@Singleton
class NotificationActionRunner @Inject constructor(
    private val profiles: ProfileRepository,
    private val commands: CommandRepository,
    private val notifier: Notifier,
) {
    suspend fun run(request: ActionRequest) {
        val profile = profiles.get(request.profileId)
        if (profile == null) {
            // Deleted since the notification was posted: nothing of its should be left showing.
            notifier.clear(request.profileId)
            return
        }
        val command = when (request.action) {
            NoticeAction.ACKNOWLEDGE -> Command.Acknowledge(request.ref, profile.quickAckComment)
            NoticeAction.RECHECK -> Command.ForceCheck(request.ref)
        }
        commands.run(profile.id, command).collect { progress ->
            if (!progress.inFlight) notifier.acted(request, progress)
        }
    }
}

/** True for an answer that leaves the problem as it was, so its notification should offer its buttons again. */
internal val ActionProgress.leavesTheProblem: Boolean
    get() = !(this is ActionProgress.Confirmed && command is Command.Acknowledge)
