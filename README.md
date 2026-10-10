# Nagwatch

A modern Android client for [Nagios Core](https://www.nagios.org/): see what is broken, acknowledge it, schedule downtime, look at the graphs, and keep a status widget on your home screen.

> **Status: early development (M2 in progress).** You can add Nagios profiles, see what is currently wrong (unhandled problems, sorted by severity, with hosts that are down and the services behind them grouped together), browse and search every host and service, and open any of them to see its output, status, comments and scheduled downtime. What was last seen is kept on the device, so the app opens with something to show and works offline. It is read-only so far: there are no actions, notifications or widget yet. The v1 design is locked in [`docs/design.md`](docs/design.md); work is tracked in this repo's issues (milestones M0-M8).

Nagwatch is an independent open-source project. It is **not affiliated with, endorsed by, or sponsored by Nagios Enterprises, LLC**. "Nagios" is a registered trademark of Nagios Enterprises, LLC and is used here only to describe compatibility.

## Planned v1

- Multiple Nagios instances (connection profiles), HTTP basic auth, optional Cloudflare Access service token and custom headers
- Problems view, plus browse and filter of hosts and services
- Host and service detail; acknowledge, remove acknowledgement, schedule downtime, force recheck, add comment
- Background polling with local notifications for new, worsened and recovered problems
- Responsive home-screen widget, 1x1 up to 4x6
- In-app nagiosgraph graphs, with an open-in-browser fallback

No backend, no Play Services, no telemetry. Talks straight to your Nagios.

**Nagios versions.** Tested on Nagios Core 4.5. Versions from 4.0.7 (the first with the JSON CGIs) are expected to work but have not been tried. Nothing is refused because of a version number; if part of a screen cannot get its data, that part says so. If you run another version, a note in the issues on what works and what does not would be very welcome.

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
