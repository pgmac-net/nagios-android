# Loud alerts play on the alarm stream, at a volume the app sets and puts back

A profile can have loud alerts: a down or critical problem, new or worse, is heard even with the phone on silent or in Do Not Disturb (pgmac-net/nagios-android#109). The app does this by playing Android's default alarm sound itself, as an alarm, for at most ten seconds, after raising the phone's alarm volume to the level set in the app, and putting that volume back afterwards. The notification itself is posted silent, so the sound is not heard twice. It plays inside a short foreground service, and needs the exact-alarm permission, because Android 17 hears nothing else from an app in the background.

## Why this needed deciding

Until now the person, not the app, decided how loud each channel is, and Android's settings were the only place to do it (see the sounds work for #62). Being heard through Do Not Disturb, at a volume the app sets, is the opposite of that, and Android offers two routes, neither of them free.

## What was decided

- **The alarm stream, not a channel that bypasses Do Not Disturb.** A channel can be allowed through Do Not Disturb, but only once the person has given the app notification-policy access, a separate trip to a settings page, and its volume is then the system's. The ticket asks for a volume the app sets, which a channel cannot have. Sound played as an alarm (`USAGE_ALARM`) is not muted by silent or vibrate mode, and Do Not Disturb lets alarms through unless the person has said otherwise.
- **A foreground service and the exact-alarm permission, because of Android 17.** Found while building, not in the grilling. Android 17 [hardens background audio](https://developer.android.com/about/versions/17/changes/bg-audio): an app targeting API 37 that plays sound or changes a volume from the background is silenced and ignored, with no exception, unless a foreground service is running and either it has while-in-use capability (which one started from the background cannot have) or the app holds the exact-alarm permission and the audio is `USAGE_ALARM`. So the sound plays in `LoudAlertService`, of type `mediaPlayback`, for the ten seconds and no longer, and the app declares `SCHEDULE_EXACT_ALARM`. A poll in the background may not start a foreground service either (Android 12), and holding the permission is not enough to change that: tried on a Pixel 8a, the start was refused (`BFGS denied`). What is allowed is starting one as an exact alarm goes off. So when a loud alert is due the app sets an exact alarm for now (`setExactAndAllowWhileIdle`), and its receiver starts the service; Android logged that start as allowed by `ALARM_MANAGER_WHILE_IDLE`. That is the one thing the app uses the permission for. It costs about five seconds between the poll and the sound. `SCHEDULE_EXACT_ALARM` and not `USE_EXACT_ALARM`: the person grants it, as "Alarms & reminders", when they switch loud alerts on, and can take it back, where `USE_EXACT_ALARM` is granted at install, cannot be taken back, and is allowed on Google Play only for alarm clocks. Without it a critical notification is posted with its channel's own sound, and the profile and Settings say loud alerts cannot sound.
- **The app raises the alarm volume and puts it back.** A player's own volume is a fraction of the stream's, so with the alarm stream turned down there would be nothing to hear. The app sets the stream to the chosen level, plays, and restores what was there. It restores only if the volume is still the one it set, so a change the person makes meanwhile stands. What was there is saved first, so an app killed mid-sound puts it back on its next start.
- **It vibrates too, as an alarm.** A silent notification does not vibrate, so the app does it, with alarm vibration attributes for the same reason as the sound.
- **Down and critical only, and one per poll.** Warnings, unknowns, recoveries and outages keep their channels. A summary that stands in for a new or worse critical is loud; the first poll's summary never is, since it is what was already wrong. Nothing is restarted or queued while one is playing.
- **The default alarm sound, ten seconds, no picker.** It is the sound the person chose to be woken by. A picker was left out for the reason #62 left one out: the bundled sounds differ from phone to phone.
- **On/off per profile, volume for the app.** Which Nagios may wake someone differs; how loud a phone should be does not.

## What it costs

- **Two permissions more, and a foreground service.** "Alarms & reminders" is a trip to a settings page, and a person may reasonably wonder why a Nagios client wants it; the hint says. The service's own notification ("Loud alert", with Stop) is shown for the ten seconds. `FOREGROUND_SERVICE` was removed from the manifest when only WorkManager asked for it, and is back.
- **The app changes a system volume**, briefly. Anything else playing an alarm in those ten seconds is at the app's level.
- **It is not a guarantee.** A Do Not Disturb mode that blocks alarms blocks this too; the settings say so. It sounds only when a poll runs, so it is as late as polling is.
- **About five seconds later than the notification**, the time the exact alarm takes to go off.
- **No audio focus.** Android 15 and later refuse focus to an app in the background, so the sound is played without it and may overlap other audio.

## Not done

- **Repeating until acknowledged**, as a pager would. It would keep the foreground service, and the alarm volume, up for as long as nobody answers, and is far more intrusive. Worth another look with push alerts (#66).
- **Other channels, or a sound picker.**
