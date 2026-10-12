# M4: background polling and notifications

Ticket: pgmac-net/nagios-android#6. Three pull requests, stacked: #39 (deciding what to say), #42 (polling in the background), #44 (posting the notifications).

## What was built

- **The rules** (`Announcer`, #39): a plain function from what was announced before, one poll's result and the profile's settings, to what to post, what to take down and what to remember. Only HARD problems; new, and again when worse; the first poll and any poll with more than five at once is one summary; handled problems lose their notification quietly; a recovery only for something that had its own notification; a failed poll never changes what is known. Outages that may pass are told after three failed polls, those that will not (a rejected password) at once. What was announced is kept in the disposable `status.db`. ADR 0008.
- **Background polling** (#42): per-profile settings (notifications on or off, interval, only unhandled, tell me when unreachable) in `profiles.db` version 3; a "Notifications" section in the profile editor; a one-time offer after a profile has loaded; Android's notification permission asked for when the switch is turned on; one WorkManager periodic job per profile with notifications on.
- **Notifications** (#44): one per problem, replaced as it changes; six channels; a recovery that clears itself after an hour; an outage with one sentence per cause; a tap that opens the host, the service or the Problems list; a group per profile.

## Decisions (from the grilling session)

| Decision | Outcome |
|---|---|
| Which problems | HARD only, whether or not Nagios itself would notify |
| First poll | One summary; its problems count as known and recover silently |
| How many | One per problem, grouped by profile; more than five at once is a summary |
| On or off | Off until switched on, per profile; offered once after a profile loads |
| Failed polls | Three in a row if it may pass, the first if it will not; a per-profile switch for the first kind |
| On the notification | No buttons; a tap opens the thing |
| Where the memory lives | The disposable cache database; losing it costs one summary |

## Deviations from the plan

- **The three pull requests were written back to back**, stacked, without waiting for each merge.
- **A forged tap.** Opening a host from a notification made the main activity act on what an intent said, and that activity is exported because the launcher starts it. Another app could have made Nagwatch switch profile and look up a host of its choosing (it could not send a command). The commit security review caught it before a merge. Taps now arrive by a second, non-exported name for the activity (an activity alias) and nothing else is believed; the whole component is compared; an intent Android replays from the recent apps list is ignored.
- **A debug-only "poll now".** The plan was to force the job with Android's scheduler. That does not work: WorkManager ends a periodic job that is started before its interval is up. Debug builds have `PollNowReceiver` instead, which queues the same worker once and can only be sent by adb's shell. It is not in a release build.
- **WorkManager's `FOREGROUND_SERVICE` permission is removed** in the manifest; the app does no foreground work.
- **The offer is once per profile**, and profiles from before the update are offered once too. None is switched on by an update.
- **The "Allow notifications" button is the switch again**, not a second control.

## Found on the phone (Pixel 8a, Android 17)

Against the throwaway test Nagios on the LAN. Nagwatch's own notification records and Android's event log were read as text; the notification shade and the lock screen were never opened.

- **The upgrade kept every profile**, with notifications off and nothing offered yet: the migration to version 3, on a real database.
- **The offer and the permission prompt worked**, and Android then ran the first poll by itself: "3 problems on …", on the Summaries channel.
- **Forcing the job did nothing** (see above). Found here, not in the unit tests.
- **Each rule, with the app in the background:** new ("CRITICAL: HTTP on web01", high importance); worse ("Now CRITICAL: Load on web01"); better without recovering (updated, silenced); acknowledged (taken down); recovered (the same notification replaced, on the silent Recovered channel, with a one-hour timeout where the others have Android's 72 hours); six at once ("6 new or worse problems on …"); Nagios stopped (nothing at the first and second failed polls, "Can't check …" at the third, and nothing called recovered meanwhile); a rejected password (told at the first failed poll, in its own words) and its removal by the next good poll, which Android's log records as cancelled by the app.
- **An empty group was left behind.** When the last notification of a profile was taken down, the group's own entry stayed until the next poll. Cancelling is a request, and Android's list of what is showing still held the notification that had just been cancelled. Robolectric cancels at once, so no unit test could see it. Fixed: what was just taken down is not counted, and having just posted counts; three tests now make Android's list lag.
- **Taps.** With the app running, a tap opened the service it was about. With the app's process ended, a tap started the app and opened the service. Reopening the app afterwards did not go there again. Tapped notifications removed themselves, and the group's entry went with the last one.
- **A tap cannot be forged.** Starting the alias from adb's shell was refused by Android ("not exported"). An intent with the same extras sent to the main activity was delivered and opened nothing.
- **Critical notifications arrive as a heads-up** over whatever is on screen, and get swiped away or tapped at once. Worth knowing when checking: use warnings for anything that has to stay put.
- **"Needs a network" means a validated one.** Android's job record shows the requirement as an internet-capable, validated network. A Wi-Fi with a Nagios and no way out would not run the job.

Not seen on the phone: where a tap on a summary, an outage and a recovery landed (they were tapped, by the maintainer, before they could be read back; the unit tests cover the targets); the hour's timeout actually firing; a second scheduled run by Android itself; behaviour in Doze; the editor's section, and the job being cancelled when notifications are switched off.

## How it was checked

- 761 unit tests (19 of them the live tests, skipped without a Nagios), `./gradlew check`, both builds, the FOSS self-test.
- Rules proven by breaking them on purpose: 94 breaks for #39, 67 for #42 (one equivalent), 78 for #44 and the tap check. Eleven were not caught at first and now are; closing them also made the offer's answer a single database write.
- CI on all three pull requests: build, lint, FOSS, CodeQL, four fuzz jobs.
