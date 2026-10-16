# Loud alerts: heard through silent mode and Do Not Disturb

Ticket: pgmac-net/nagios-android#109. One pull request. Decision recorded in [ADR 0013](../adr/0013-loud-alerts-play-on-the-alarm-stream.md); the term is in `CONTEXT.md`.

## What was built

- **A per-profile switch**, "Loud alerts for down and critical", in the profile editor's Notifications section, off by default. Stored as `loud_alerts` (Room 5 to 6, off for existing profiles).
- **A volume for the app**, in Settings: a slider from 1 to 100% of the phone's highest alarm volume, 80% by default, with Test (Stop while it sounds).
- **The alert**: for a new or worse down or critical problem, or a later summary that stands in for one, the notification is posted silent and the app plays the phone's alarm sound as an alarm, and buzzes, for up to ten seconds, after raising the alarm volume. The volume is put back afterwards, unless the person moved it meanwhile, and at the next start if the app was killed. One per poll. Opening the app, swiping the notification away, or Stop ends it.
- **Android 17's rules**: it plays inside `LoudAlertService` (a `mediaPlayback` foreground service) and needs `SCHEDULE_EXACT_ALARM` ("Alarms & reminders"), which switching loud alerts on asks for. Without it the editor and Settings say so, and criticals make their channel's sound.
- **Debug**: `LoudAlertNowReceiver` sounds one from a background worker after a delay, to try it locked, on silent and in DND.

## Decisions (from the grilling session)

| Decision | Outcome |
|---|---|
| Which notifications | Down, unreachable and critical, new or worse. Not warning, unknown, recovered, outage or quiet updates |
| Summaries | A later summary with a new or worse critical is loud; the first poll's never is |
| Mechanism | The app plays on the alarm stream at its own volume, and vibrates as an alarm; not a channel bypassing DND (no volume of its own, and a policy-access grant) |
| Scope | On/off per profile; volume for the app |
| Sound | Android's default alarm sound, at most 10 s, no picker |
| Repeats | One per poll; none queued or restarted while one sounds; no cooldown |
| Volume control | Slider 1-100%, default 80%, with Test |
| Name | Loud alert |

## Deviations from the plan

- **Android 17 background audio hardening** (found while building, before the phone spike). For an app targeting API 37, playback and volume changes from the background are silently dropped unless a foreground service is running and the app holds the exact-alarm permission and plays as `USAGE_ALARM`. The plan said "no new permission". Agreed with the maintainer: `SCHEDULE_EXACT_ALARM`, asked for when loud alerts are switched on, revocable, plus a ten-second `mediaPlayback` foreground service with a Stop button. `USE_EXACT_ALARM` was rejected (granted at install, cannot be revoked, Play-restricted). Posted on the ticket.
- **New manifest entries**: `FOREGROUND_SERVICE` (previously removed from WorkManager's merged manifest, now used), `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `SCHEDULE_EXACT_ALARM`, `VIBRATE`; the service and a non-exported stop receiver.
- **A seventh channel**, "Loud alert sounding" (low importance), for the service's notification.
- **Order**: the spike ran after the build (no phone was attached), using the real code through the debug receiver.

## Tests

- `AnnouncerTest`: a summary's `critical` flag (new critical among warnings, known critical not counted, first poll never).
- `AndroidNotifierTest`: what is loud and what is not, one per poll, silent and with a delete intent when loud, the channel's own sound without the permission or with the switch off.
- `AlarmSoundTest` and `AlarmVolumeTest`: raise and put back, the person's change kept, the ledger, the leftover at start, ten seconds, one at a time, no sound still buzzes.
- `ProfileMigrationTest`: 5 to 6.
- `NotificationUiTest` and `SettingsScreenTest`: the switch, the permission page and warning, slider, Test and Stop.

## On a phone

See the pull request: what was tried on the Pixel 8a (Android 17), and what was not.
