# Contributing to Nagwatch

Thanks for looking. Nagwatch is early: the v1 design is in [`docs/design.md`](docs/design.md) and work is tracked as milestone issues. If you want to build something, comment on the issue first so effort is not duplicated.

## Ground rules

- **Licence.** Contributions are accepted under GPL-3.0-or-later, the project licence. Every Kotlin source and build script starts with `// SPDX-License-Identifier: GPL-3.0-or-later`; the build checks this.
- **Signed commits** are required on `main`. Set up commit signing before opening a PR.
- **Scope.** v1 scope and non-goals are in the design doc. Features outside it need an issue and agreement first.
- **No telemetry.** No analytics, crash reporters or third-party network calls. The app talks only to the Nagios instances the user configures.

## Building and checking

See [`docs/development.md`](docs/development.md). Before pushing:

```
./gradlew check assembleDebug
```

CI runs `build`, `lint` and `foss`; all three must pass to merge. Static analysis is strict (warnings are errors) and there is no baseline, so fix findings rather than suppressing them. If a suppression is right, keep it narrow and say why in a comment.

## Dependencies

Nagwatch ships **FOSS-only** dependencies so it can be distributed through F-Droid and so users can trust what is in it. Two checks enforce that, on every shipped classpath including transitive dependencies:

1. **Licence allowlist** (Licensee). Currently `Apache-2.0`, `MIT`, `BSD-2-Clause`, `BSD-3-Clause`.
2. **Denylist** of Maven groups (`config/foss-denylist.txt`): Google Play Services, Firebase, crash reporters, analytics and similar. Some of these carry a free licence but need proprietary services at runtime, which is why a licence check alone is not enough.

If a dependency you need fails:

- **Denylisted:** find an alternative. Entries are not removed to make a build pass.
- **Licence not on the allowlist:** open a PR that adds the SPDX id to the `licensee` block in `app/build.gradle.kts`, naming the dependency that needs it and confirming the licence is GPL-3.0-compatible and F-Droid-acceptable. That change is reviewed on its own merits.

Add a dependency only in the change that uses it, then refresh and commit the lockfile:

```
./gradlew :app:resolveAndLockAll --write-locks
```

## Pull requests

- One logical change per PR. Reference the issue.
- Include tests for behaviour you add or change.
- Update `docs/` when behaviour, build steps or decisions change. Hard-to-reverse decisions get an ADR in `docs/adr/`.

## Trademark

Nagwatch is not affiliated with Nagios Enterprises, LLC. Do not use "Nagios" in the app's name or branding; use it only to describe compatibility.
