# M0: project scaffold, CI and FOSS-dependency checks

Ticket: pgmac-net/nagios-android#2. PR: pgmac-net/nagios-android#11. Follow-up: a `pgmac-net/terraform-github` PR making the CI jobs required.

## What was built

A buildable Android project with every quality gate in place and no features.

- **Toolchain:** Gradle 9.8.1 (wrapper, checksum-pinned), AGP 9.4.1, Kotlin 2.4.21, KSP 2.3.12, Compose BOM 2026.09.00, Hilt 2.60.1. Versions were looked up from upstream metadata on 2026-10-08, not assumed. `compileSdk`/`targetSdk` 37, `minSdk` 26, JDK 21 toolchain with Java 17 bytecode.
- **App:** `NagwatchApplication` (Hilt), `MainActivity` (edge-to-edge), Material 3 theme with dynamic colour on Android 12+ and a static fallback, fixed Nagios state colours as theme tokens, one placeholder screen. No permissions declared; backups disabled.
- **Checks under `check`:** ktlint, detekt (no baseline), Android Lint and compiler warnings as errors, Licensee licence allowlist, Maven-group denylist, SPDX headers, locked dependencies.
- **CI:** `build`, `lint`, `foss` on GitHub-hosted runners, actions pinned by SHA; Scorecard and Gradle dependency submission on `main`.
- **Renovate:** no automerge; groups for Kotlin/KSP, AGP/Gradle, AndroidX, GitHub Actions; lockfile maintenance.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Local verification | Android SDK installed on the dev machine; launch proven by a Robolectric Compose test plus a manual sideload | Debugging a new Gradle/AGP project through CI pushes alone is slow; an emulator in CI is heavy |
| SBOM | Deferred to M7 (#9) | The shared `sbom.yml` defaults to a self-hosted runner and scans source with Syft, which does not read a Gradle version catalog: it would produce a near-empty SBOM |
| Dependency security now | Gradle lockfiles + GitHub dependency submission + Scorecard | Accurate transitive graph for Dependabot; reproducible builds |
| Slack notifications | Nothing to wire | The org `workflow_run` webhook to n8n already covers every repo |
| FOSS check | Licence allowlist **and** group denylist | Some Firebase/Play artifacts are Apache-licensed but need proprietary services; a licence check alone misses them |
| Proof the check works | Permanent CI self-test | A one-off demonstration says nothing about next month |
| Required checks | `build`, `lint`, `foss` via `terraform-github` | Three names say what broke without opening logs |
| Renovate | No automerge; SHA-pinned actions | Automerge would mean letting a bot bypass review on a public repo that will hold a signing key |
| Skeleton | Only dependencies the code uses | Each dependency is locked, licence-checked and bumped; unused ones are pure cost |

## Deviations from the plan

- `build-logic` holds the SPDX header check as well as the FOSS task. A custom task covers `.kt` and `.kts` uniformly; detekt's own licence rule would not cover build scripts.
- `lifecycle-runtime-compose` was planned but not added: nothing uses it yet.
- A full JDK 21 had to be installed (via mise, not activated globally): the system Java 21 was a JRE without `javac`.

## Things that bit, and how they were handled

- **Lockfile generation.** Resolving every configuration's files fails on AGP configurations that only get artifact attributes from their tasks. `resolveAndLockAll` resolves the dependency *graph* instead, which is what locking records.
- **Robolectric on JDK 21** needs `--add-exports java.base/jdk.internal.access=ALL-UNNAMED`.
- **Robolectric SDK level.** On the newest Android image the Compose test libraries call `InputManager.getInstance()`, which no longer exists. Robolectric is pinned to SDK 35 in `robolectric.properties` and trails `targetSdk` deliberately.
- **Deprecated Compose test rule.** With warnings as errors, `createComposeRule` had to be the `v2` variant.
- **Self-test and locking.** The self-test adds an unlocked dependency, so dependency locking is switched off when `-PfossSelfTest=true`; otherwise the build would fail on locking before reaching the checks under test.
- **Lint and backups.** `dataExtractionRules` alone is not enough for `minSdk 26`; a `fullBackupContent` rules file is needed for Android 11 and below.

## Verification

- Local, cache off: `./gradlew --no-build-cache --rerun-tasks check assembleDebug assembleRelease` green (170 tasks); 3 tests pass.
- APKs inspected with `aapt2`: `net.pgmac.nagwatch.debug` / `net.pgmac.nagwatch`, version 0.1.0, target SDK 37, no real permissions.
- `scripts/foss-self-test.sh`: denylist check fails with "FOSS check failed: 3 denied dependencies"; Licensee rejects `play-services-base`.
- CI on the PR: `build`, `lint`, `foss` all green on the first run; the debug APK artifact is produced.
- **Not verified by automation:** launch on a real device. The maintainer sideloads the CI debug APK.

## Known gaps

- Kotlin in `build-logic` is not covered by ktlint or detekt (it is a separate included build).
- Renovate against Gradle lockfiles is untested in this org until its first run.
- Release builds are unsigned until M7.
