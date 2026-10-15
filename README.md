# Nagwatch

[![CI](https://github.com/pgmac-net/nagios-android/actions/workflows/ci.yml/badge.svg)](https://github.com/pgmac-net/nagios-android/actions/workflows/ci.yml)
[![CodeQL](https://github.com/pgmac-net/nagios-android/actions/workflows/codeql.yml/badge.svg)](https://github.com/pgmac-net/nagios-android/actions/workflows/codeql.yml)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/pgmac-net/nagios-android/badge)](https://scorecard.dev/viewer/?uri=github.com/pgmac-net/nagios-android)
[![OpenSSF Best Practices](https://www.bestpractices.dev/projects/15514/badge)](https://www.bestpractices.dev/projects/15514)
[![codecov](https://codecov.io/gh/pgmac-net/nagios-android/branch/main/graph/badge.svg)](https://codecov.io/gh/pgmac-net/nagios-android)
[![Dependency-Track vulnerabilities](https://sbom.pgmac.net.au/api/v1/badge/vulns/project/pgmac-net%2Fnagios-android/0.1.1)](https://github.com/pgmac-net/nagios-android/releases/latest)
[![Release](https://img.shields.io/github/v/release/pgmac-net/nagios-android)](https://github.com/pgmac-net/nagios-android/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/github/license/pgmac-net/nagios-android)](LICENSE)

A modern Android client for [Nagios Core](https://www.nagios.org/): see what is broken, acknowledge it, schedule downtime, look at the graphs, and keep a status widget on your home screen.

> **Status: early development (0.1.1 released; M8, F-Droid, next).** You can add Nagios profiles, see what is currently wrong (unhandled problems, sorted by severity, with hosts that are down and the services behind them grouped together), browse and search every host and service, and open any of them to see its output, status, comments and scheduled downtime. What was last seen is kept on the device, so the app opens with something to show and works offline. You can swipe between the three lists, and press and hold a row to acknowledge, remove an acknowledgement or ask for a recheck without opening it. From a host or service you can acknowledge a problem, remove an acknowledgement, add a comment and ask for a recheck; the app tells you when Nagios has accepted a command and, separately, when it has seen it take effect. You can also schedule a fixed downtime and cancel one: the app reads every downtime back from Nagios and tells you if it landed anywhere other than where you asked. You can have it check a Nagios in the background and notify you about new problems (see below). There is a home-screen widget, from a single number up to a scrolling list (see below). Where the server has nagiosgraph, a service's screen shows its graph (see below). The v1 design is locked in [`docs/design.md`](docs/design.md); work is tracked in this repo's issues (milestones M0-M8).

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

**Acting from a list or a notification.** Press and hold a host, service or problem for a short menu: Acknowledge, Remove acknowledgement, Recheck, Open. A problem's notification has Acknowledge and Recheck buttons too (and so does a paired watch). An acknowledgement made this way is **sent at once, with no form**. It says "Acknowledged from Nagwatch" unless the profile has words of its own (under "Quick actions" in the profile's settings), and everyone who uses that Nagios will see it. In the app you are told when Nagios has confirmed it and offered Undo; on a notification the notification itself says what happened. For a comment of your own, a sticky acknowledgement or a downtime, open the host or service. Pressed on the phone's lock screen, a button waits for you to unlock. Pressed on a paired watch, it works with the phone locked, so give the watch a lock of its own. If a button's command does not work, the notification says why, with a sound. A read-only Nagios user is shown the actions switched off.

**Notifications can be late.** There is no server in the middle: your phone asks your Nagios. Android lets an app do that about every 15 minutes at most, not on the dot, and less often while the phone is idle or saving battery. Some phones also stop background work for apps that have not been opened lately. Expect news up to the interval you chose after the fact, sometimes later. If you need to be woken within seconds, keep the alerting you have; Nagwatch is for knowing what is wrong when you look at your phone.

Each kind of notification (down and critical, warning, unknown, recovered, can't reach Nagios, summaries) is a separate channel in Android's settings, so you can silence or block any of them there, and give each its own sound from Android's list: the profile's "Sounds and importance" button goes straight there. Where the notification shade draws the app's small icon, it takes the colour of the state, red, amber or orange for a problem and green for a recovery. The status bar always draws it white, and some phones (a Pixel on Android 17, for one) draw the app's own icon in the shade instead, so there you will not see the colour; the title says the state in words and starts with an emoji (coloured circles by default: 🔴 down, unreachable and critical, 🟡 warning, 🟠 unknown, 🟢 recovered), which every phone draws. Settings, in the profile menu, picks the set: none, coloured circles, signs, coloured squares or faces. Tapping one opens the host or service it is about.

**The widget.** Add it from your launcher's widget list and choose which Nagios it shows; resize it to change what it is. At one cell it is a colour and a number (the worst state and how many unhandled problems). A row of cells gives a count for each kind. Taller, it lists the problems, and at its largest it scrolls, shows a line of each plugin's output and has a Refresh button. Tap the widget to open the Problems list, or a row to open that host or service.

**A see-through widget.** When you place a widget you choose its Nagios, then its **Background**, from 0% (your wallpaper shows through) to 100% (solid), with a preview. The background is washed with the colour of the worst problem, red, amber or orange, and green when all is well; at one cell the badge's colour fades the same way, and when it is faint the number takes the colour instead. A widget that is out of date is never fainter than 60%, so it can't fade into the wallpaper unnoticed. To change it later, long-press the widget and choose to reconfigure it (Android 12 and later; on older Android, remove the widget and place it again). Each widget keeps its own setting.

A widget is only as good as how recent it is, so **placing one means Nagwatch checks that Nagios in the background**, at the interval set in the profile under "Background checks", for as long as the widget is on your home screen. That happens even with notifications off, and does not turn them on. Remove the widget and it stops. The same limits apply as for notifications: about every 15 minutes at best. When what it shows is old, or the last check failed, the widget goes grey, keeps the last numbers, and says so with the time they were true; it never shows old good news as current. It puts host and service names on your home screen, and is not offered for the lock screen.

**Graphs.** If your Nagios has [nagiosgraph](https://sourceforge.net/projects/nagiosgraph/), a service's screen shows its graph for the last day, week, month or year. Tap it for a full-screen view on its side that you can pinch and drag; double-tap to zoom in and out. "Open graphs in browser" hands nagiosgraph's own page to your browser, which has none of the app's credentials, so it works only where the browser can already sign in.

- **There is nothing to set up** if nagiosgraph's `showgraph.cgi` is in the same `cgi-bin` as Nagios. If it is somewhere else, put its path in the profile under "Graphs", for example `/nagiosgraph/cgi-bin/`. It has to be a path on the same server; a full URL is refused, because the profile's credentials only ever go to the address the profile names.
- **A service with no graph simply has no graph section.** The app does not say why: nagiosgraph may have no rule for that check, the check may print no performance data, or the path may be wrong. If you expected a graph and there is none, the path is the first thing to check, and a pull to refresh asks again.
- **Hosts have no graph.** nagiosgraph files a host's own check under whatever name your Nagios configuration gives it, so the app has no way to know what to ask for.
- The graph is nagiosgraph's own picture, in nagiosgraph's own colours, on white whatever your theme. It is asked for small so that its labels are readable on a phone, which makes it a little soft. Graphs are kept in memory while the app runs and are never written to the device.
- To turn graphs off for a profile, use the switch under "Graphs". They are on by default; a server without nagiosgraph is asked once each time the app starts, and then left alone.

The widget has been seen on Android 17 only. It is built to work back to Android 8, with plainer colours below Android 12, but that is untested: if you run it on an older phone, a note in the issues on how it looks would be very welcome.

**Nagios versions.** Tested on Nagios Core 4.5. Versions from 4.0.7 (the first with the JSON CGIs) are expected to work but have not been tried. Nothing is refused because of a version number; if part of a screen cannot get its data, that part says so. If you run another version, a note in the issues on what works and what does not would be very welcome.

## Install

Nagwatch is not on Google Play. It is published as a signed APK on this repository's [Releases](https://github.com/pgmac-net/nagios-android/releases) page.

- **With [Obtainium](https://obtainium.imranr.dev/)** (recommended, because it also tells you about updates): add an app, give it `https://github.com/pgmac-net/nagios-android`, and install. Each release has exactly one APK, so there is nothing to choose.
- **By hand:** download `nagwatch-X.Y.Z.apk` from the latest release and open it. Android will ask you to allow your browser or file manager to install apps. You will have to come back for updates yourself.

**If Android refuses to install it.** A phone with Google's Advanced Protection switched on does not allow apps from outside Google Play, and neither way above will work: there is no prompt to allow it, only a refusal. Installing from a computer still does. With [USB debugging](https://developer.android.com/studio/debug/dev-options) on and the phone connected, `adb install nagwatch-X.Y.Z.apk`, and the same command with `-r` for each update. (Seen on a Pixel 8a with Android 17.)

Android 8.0 or later. Every release is signed with the same key, so a new version installs over the old one and keeps your profiles.

**Checking what you downloaded.** You do not have to, but you can. Every release is signed with the certificate whose SHA-256 fingerprint is:

```
FE:56:B4:F7:78:72:7D:9C:19:19:CE:76:18:8C:8B:AF:ED:21:0A:78:54:C4:F7:F2:7C:23:E5:67:1C:DA:D9:5D
```

`apksigner` prints the same thing without the colons, in lower case: `fe56b4f778727d9c1919ce76188c8bafed210a7854c4f7f27c23e5671cdad95d`. That is the one line in [`config/release-signing-cert.sha256`](config/release-signing-cert.sha256), and the release workflow refuses to publish an APK signed with anything else. Obtainium and [AppVerifier](https://github.com/soupslurpr/AppVerifier) can check it for you on the phone. On a computer:

```
apksigner verify --print-certs nagwatch-X.Y.Z.apk        # the fingerprint above
gh attestation verify nagwatch-X.Y.Z.apk --repo pgmac-net/nagios-android   # built by this repository's release workflow, from that tag
sha256sum -c SHA256SUMS                                  # the download is intact
```

Each release also carries `nagwatch-X.Y.Z.cdx.json`, a CycloneDX list of every library in the APK and its licence.

## Security

Found a security problem? Please report it privately: see [SECURITY.md](SECURITY.md).

## Distribution

Signed APKs on GitHub Releases (see [Install](#install)). F-Droid planned after v1 stabilises. No Google Play listing planned.

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
