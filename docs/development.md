# Development

## Requirements

- A full **JDK 21** (with `javac`; a JRE is not enough).
- The **Android SDK**. Either Android Studio, or the command-line tools with `ANDROID_HOME` set (or `sdk.dir` in an untracked `local.properties`). The build needs the platform and build-tools for the `compileSdk` in `app/build.gradle.kts`; with licences accepted, the Android Gradle Plugin fetches missing pieces itself.
- Nothing else. Gradle comes from the wrapper (`./gradlew`), pinned by checksum.

## Everyday commands

| Task | Command |
|---|---|
| Build a debug APK | `./gradlew assembleDebug` (output in `app/build/outputs/apk/debug/`) |
| Unit tests | `./gradlew testDebugUnitTest` |
| Everything CI runs | `./gradlew check assembleDebug assembleRelease` then `scripts/foss-self-test.sh` |
| Auto-format Kotlin | `./gradlew ktlintFormat` |
| Refresh the lockfile | `./gradlew :app:resolveAndLockAll --write-locks` |

The debug build installs as `net.pgmac.nagwatch.debug`, labelled "Nagwatch (debug)", so it sits beside a release build. Release builds are minified with R8 and **unsigned** until the release pipeline exists (M7).

## What `check` enforces

| Check | Task | Notes |
|---|---|---|
| Kotlin style | `ktlintCheck` | Settings in `.editorconfig` |
| Static analysis | `detekt` | `config/detekt/detekt.yml`; no baseline, warnings are errors |
| Android Lint | `lintDebug` | Warnings are errors. Version-nag checks are off; Renovate owns bumps |
| Compiler warnings | (compile) | `allWarningsAsErrors` |
| Licence allowlist | `licensee` | Allowed SPDX ids in `app/build.gradle.kts` |
| Dependency denylist | `checkFossDependencies` | Prefixes in `config/foss-denylist.txt` |
| SPDX headers | `checkSpdxHeaders` | First line of every `.kt` / `.kts` |
| Locked dependencies | (resolution) | Any dependency not in `app/gradle.lockfile` fails the build |
| No leaked fixtures | `testDebugUnitTest` (`FixtureLeakTest`) | Fixtures must be sanitised |

`scripts/foss-self-test.sh` adds a known-banned dependency (`-PfossSelfTest=true`) and asserts that the licence and denylist checks both **fail**. CI runs it on every PR, so a silently broken check turns CI red.

## Dependencies

- Versions live in `gradle/libs.versions.toml`. Add a library in the change that first uses it, not before.
- After any dependency change, refresh the lockfile and commit it.
- Kotlin, KSP and the Compose compiler plugin move together. AGP and the Gradle wrapper have a compatibility matrix. Renovate groups them for that reason.
- FOSS only. See [CONTRIBUTING.md](../CONTRIBUTING.md#dependencies).

## Tests

JVM unit tests only, for now. Compose screens are tested under Robolectric (`app/src/test`), which is how CI stands in for "the app launches" without an emulator. Robolectric is pinned to an Android version in `app/src/test/resources/robolectric.properties`; it trails `targetSdk` deliberately.

A real-device launch is checked by hand: download the `nagwatch-debug-apk` artifact from a CI run and install it.

## Nagios fixtures

Parser and client tests replay responses captured from a real Nagios. This repository is public, so captures are **sanitised before they are committed**: hosts, services, plugin output and the user name are replaced with generic values; structure, states and timestamps are kept.

```
NAGWATCH_URL=https://nagios.example.org/nagios/cgi-bin scripts/capture-fixtures.sh /tmp/nagwatch-raw
scripts/sanitise_fixtures.py /tmp/nagwatch-raw app/src/test/resources/fixtures
```

- Raw captures name real hosts. Keep them outside the repository (the capture script refuses to write inside it) and delete them afterwards.
- Credentials come from `~/.config/nagwatch/dev.env` (`USER=` and `PASS=` lines). Use a read-only Nagios user.
- The sanitiser fails if any original name survives. `FixtureLeakTest` is the backstop in CI: it fails on host or service names that are not the sanitiser's generic ones, on private addresses and on anything that looks like a real domain.

## Testing against a real Nagios

```
scripts/live-smoke-test.sh https://nagios.example.org/nagios
```

Runs the real client (connect, CGI-path detection, a full poll, classification, and a full detailed service list) against a live instance, using the same credentials file. It prints counts only, never names. It is skipped in normal test runs and **never runs in CI**: CI has no credentials and must not have any.

## Profiles and stored secrets

Profiles live in a Room database (`profiles.db`). Passwords, the Cloudflare Access client secret and custom header values are stored only as `SecretCipher` output (AES-256-GCM, key in the Android Keystore).

- **Never add a way to read a secret back into the UI.** The editor shows "saved" and offers Replace; `SecretInput.Keep` means "leave what is stored".
- **Never log or `toString` a secret.** `ConnectionSettings`, `SecretInput.Replace`, `SecretField` and `SettingsResult.Ready` are deliberately not data classes, or override `toString`, for this reason. Tests assert on it.
- **Schema changes need a migration.** Raising `ProfileDatabase.VERSION` exports a new file under `app/schemas`; commit it, write the `Migration`, and add a `MigrationTestHelper` test. There is no destructive fallback, so a missing migration crashes on upgrade rather than silently deleting profiles.
- `SecretCipher` is unit-tested with a software key. `KeystoreKeySource`, the few lines that talk to the Android Keystore, cannot run on the JVM and is only exercised on a device.

## Network rules

All traffic to Nagios goes through one OkHttp interceptor (`ConnectionInterceptor`) that refuses `http://` unless the profile opted in, never sends Cloudflare Access credentials over `http://`, and only sends credentials to the configured origin. Redirects are not followed. Any new HTTP client must be derived from the per-profile client so it inherits these rules; see [ADR 0004](adr/0004-cleartext-permitted-in-manifest-enforced-in-app.md).

## CI

`.github/workflows/ci.yml` has three jobs, `build`, `lint` and `foss`, on GitHub-hosted runners. **Those job names are required status checks** on `main`, set in `pgmac-net/terraform-github`. Rename one there first, or every merge blocks.

Actions are pinned by commit SHA and kept current by Renovate. The JDK that CI installs (`java-version`) must match `jvmToolchain()` in `app/build.gradle.kts`; Renovate is told not to bump its major, so moving to a newer JDK is one change to both. `scorecard.yml` and `dependency-submission.yml` run on `main` only and are not required.

## Layout

```
app/                     the application module
build-logic/             Gradle plugin with this repo's own checks (FOSS denylist, SPDX)
config/                  detekt config, FOSS denylist
gradle/libs.versions.toml  version catalog
scripts/                 CI helper scripts
docs/                    design, ADRs, this file
```
