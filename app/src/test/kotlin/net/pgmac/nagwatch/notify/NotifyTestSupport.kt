// SPDX-License-Identifier: GPL-3.0-or-later

package net.pgmac.nagwatch.notify

import java.util.concurrent.CopyOnWriteArrayList
import net.pgmac.nagwatch.profile.NotificationSettings
import net.pgmac.nagwatch.profile.PollInterval
import net.pgmac.nagwatch.profile.Profile
import net.pgmac.nagwatch.profile.ProfileDraft
import net.pgmac.nagwatch.profile.SecretInput
import net.pgmac.nagwatch.status.ActionProgress

class FakeNotificationHistory(var exhausted: Boolean = false) : NotificationHistory {
    override suspend fun exhausted(): Boolean = exhausted

    override suspend fun setExhausted(exhausted: Boolean) {
        this.exhausted = exhausted
    }
}

/** Android's answer about notifications, as the test sets it. */
class TestNotificationAccess(
    var held: Boolean = false,
    val history: FakeNotificationHistory = FakeNotificationHistory(),
) {
    val access = NotificationAccess(NotificationPermission { held }, history)
}

/** Records what was asked of the scheduler, in order: "schedule 1 15", "cancel 1". */
class FakePollScheduler : PollScheduler {
    val events = CopyOnWriteArrayList<String>()

    override fun schedule(profileId: Long, interval: PollInterval) {
        events += "schedule $profileId ${interval.minutes}"
    }

    override fun cancel(profileId: Long) {
        events += "cancel $profileId"
    }
}

/** Records what was delivered, and can look at the world at the moment of delivery. */
class FakeNotifier(private val onDeliver: (Profile) -> Unit = {}) : Notifier {
    val delivered = mutableListOf<Pair<Long, Decision>>()

    /** Written from the schedule's coroutine and read from the test's thread. */
    val cleared = CopyOnWriteArrayList<Long>()
    val swept = CopyOnWriteArrayList<Set<Long>>()

    /** Whether each delivery was told the profile's user may send commands. */
    val mayAct = mutableListOf<Boolean>()
    val acting = CopyOnWriteArrayList<ActionRequest>()
    val acted = CopyOnWriteArrayList<Pair<ActionRequest, ActionProgress>>()
    val needsUnlock = CopyOnWriteArrayList<ActionRequest>()

    override fun deliver(profile: Profile, decision: Decision, mayAct: Boolean) {
        onDeliver(profile)
        delivered += profile.id to decision
        this.mayAct += mayAct
    }

    override fun acting(request: ActionRequest) {
        acting += request
    }

    override fun acted(request: ActionRequest, progress: ActionProgress) {
        acted += request to progress
    }

    override fun needsUnlock(request: ActionRequest) {
        needsUnlock += request
    }

    override fun clear(profileId: Long) {
        cleared += profileId
    }

    override fun sweep(keep: Set<Long>) {
        swept += keep
    }
}

fun draftWith(
    notifications: NotificationSettings = NotificationSettings(enabled = true),
    id: Long? = null,
    name: String = "Home",
) = ProfileDraft(
    id = id,
    name = name,
    baseUrl = "https://nagios.example.org/nagios",
    username = "nagwatch",
    password = if (id == null) SecretInput.Replace("pw") else SecretInput.Keep,
    accessClientId = "",
    accessClientSecret = SecretInput.Keep,
    customHeaders = emptyList(),
    allowCleartext = false,
    notifications = notifications,
)
