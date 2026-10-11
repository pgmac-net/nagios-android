# Nagwatch

A modern Android client for [Nagios Core](https://www.nagios.org/): see what is broken, acknowledge it, schedule downtime, look at the graphs, and keep a status widget on your home screen.

> **Status: early development (M4 done, M5 next).** You can add Nagios profiles, see what is currently wrong (unhandled problems, sorted by severity, with hosts that are down and the services behind them grouped together), browse and search every host and service, and open any of them to see its output, status, comments and scheduled downtime. What was last seen is kept on the device, so the app opens with something to show and works offline. From a host or service you can acknowledge a problem, remove an acknowledgement, add a comment and ask for a recheck; the app tells you when Nagios has accepted a command and, separately, when it has seen it take effect. You can also schedule a fixed downtime and cancel one: the app reads every downtime back from Nagios and tells you if it landed anywhere other than where you asked. You can have it check a Nagios in the background and notify you about new problems (see below). The widget is not here yet. The v1 design is locked in [`docs/design.md`](docs/design.md); work is tracked in this repo's issues (milestones M0-M8).

Nagwatch is an independent open-source project. It is **not affiliated with, endorsed by, or sponsored by Nagios Enterprises, LLC**. "Nagios" is a registered trademark of Nagios Enterprises, LLC and is used here only to describe compatibility.

## Planned v1

- Multiple Nagios instances (connection profiles), HTTP basic auth, optional Cloudflare Access service token and custom headers
- Problems view, plus browse and filter of hosts and services
- Host and service detail; acknowledge, remove acknowledgement, schedule downtime, force recheck, add comment
- Background polling with local notifications for new, worsened and recovered problems
- Responsive home-screen widget, 1x1 up to 4x6
- In-app nagiosgraph graphs, with an open-in-browser fallback

No backend, no Play Services, no telemetry. Talks straight to your Nagios.

**What Nagwatch asks Android for.** Three permissions you might be asked about or see listed: Internet; on Android 17 and later, access to your local network (which Android words as "Nearby devices"); and, on Android 13 and later, showing notifications. Notifications are asked for only when you switch them on for a profile. Background checking adds three that Android grants without asking: staying awake for the length of one check, knowing whether there is a network, and starting again after the phone restarts. Local network access is asked for only the first time you test or load a profile whose address looks like one on your own network (`192.168.x.x`, `10.x.x.x`, a `.local` name and the like), and Nagwatch explains why first. It only talks to the address you entered: it does not scan or look for other devices. If you say no it still tries, which is right when you reach Nagios over a VPN; if the connection then times out it says Android may be blocking it and offers to ask again.

**Notifications.** Off until you switch them on, per profile, in the profile's settings; the app offers once after a profile first loads. Nagwatch then checks that Nagios in the background and tells you about a problem when it is new and again if it gets worse, and replaces that with a quiet "recovered" when it clears. Only problems Nagios has confirmed (HARD) are reported, and by default only unhandled ones. The first check, and any check that finds more than five new problems at once, gives one summary instead of a pile. You are also told if Nagios cannot be checked: at once for a rejected password, after three failed checks for anything that might pass by itself, and that second kind can be switched off for a Nagios you only reach at home.

**Notifications can be late.** There is no server in the middle: your phone asks your Nagios. Android lets an app do that about every 15 minutes at most, not on the dot, and less often while the phone is idle or saving battery. Some phones also stop background work for apps that have not been opened lately. Expect news up to the interval you chose after the fact, sometimes later. If you need to be woken within seconds, keep the alerting you have; Nagwatch is for knowing what is wrong when you look at your phone.

Each kind of notification (down and critical, warning, unknown, recovered, can't reach Nagios, summaries) is a separate channel in Android's settings, so you can silence or block any of them there. Tapping one opens the host or service it is about.

**Nagios versions.** Tested on Nagios Core 4.5. Versions from 4.0.7 (the first with the JSON CGIs) are expected to work but have not been tried. Nothing is refused because of a version number; if part of a screen cannot get its data, that part says so. If you run another version, a note in the issues on what works and what does not would be very welcome.

## Security

Found a security problem? Please report it privately: see [SECURITY.md](SECURITY.md).

## Distribution

Signed APKs on GitHub Releases. F-Droid planned after v1 stabilises. No Google Play listing planned.

## Building

Needs a full JDK 21 and the Android SDK. Then:

```
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Details, checks and conventions are in [`docs/development.md`](docs/development.md); contribution rules are in [`CONTRIBUTING.md`](CONTRIBUTING.md).

## Project docs

- [`docs/development.md`](docs/development.md) - building, checks, CI

- [`docs/design.md`](docs/design.md) - v1 design
- [`docs/adr/`](docs/adr/) - architecture decision records
- [`CONTEXT.md`](CONTEXT.md) - project glossary

## License

GPL-3.0-or-later. See [`LICENSE`](LICENSE).
