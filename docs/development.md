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

Two things the tests could not see, and what to do about each:

- **A test that measures layout needs real fonts.** Robolectric's default stub fonts make every piece of text a few dp wide, so a row of chips that overflows on a phone fits comfortably in the test. Annotate such a test with `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and a phone-sized qualifier (`w360dp-h640dp-xhdpi`), then check it fails against the broken layout before trusting it.
- **A cold start is its own case.** Arriving at a screen from another screen and arriving from a fresh process differ in what has loaded by then. Anything a screen needs on arrival should be driven by the view model once the data exists, not requested by the screen at the moment it starts; test it from a newly built view model.

## Nagios fixtures

Parser and client tests replay responses captured from a real Nagios. This repository is public, so captures are **sanitised before they are committed**: hosts, services, plugin output and the user name are replaced with generic values; structure, states and timestamps are kept.

```
NAGWATCH_URL=https://nagios.example.org/nagios/cgi-bin scripts/capture-fixtures.sh /tmp/nagwatch-raw
scripts/sanitise_fixtures.py /tmp/nagwatch-raw app/src/test/resources/fixtures
```

- Raw captures name real hosts. Keep them outside the repository (the capture script refuses to write inside it) and delete them afterwards.
- Credentials come from `~/.config/nagwatch/dev.env` (`USER=` and `PASS=` lines). Use a read-only Nagios user.
- The detail and comment captures ask about one host and one of its services: set `NAGWATCH_HOST` and `NAGWATCH_SERVICE` to ones that have comments.
- Re-running the sanitiser regenerates **every** fixture from the new capture, and the tests assert on their contents. Regenerate only the files you mean to and update the tests that read them.
- `downtimelist_synthetic.json` is hand-written, not captured: the instance the others came from had no downtime. Its fields follow `json_status_downtime_details` in Nagios Core's `cgi/statusjson.c`.
- The sanitiser fails if any original name survives. `FixtureLeakTest` is the backstop in CI: it fails on host or service names that are not the sanitiser's generic ones, on private addresses and on anything that looks like a real domain.

## Testing against a real Nagios

```
scripts/live-smoke-test.sh https://nagios.example.org/nagios
```

Runs the real client (connect, CGI-path detection, a full poll, classification, and a full detailed service list) against a live instance, using the same credentials file. `ACCESS_CLIENT_ID` and `ACCESS_CLIENT_SECRET` in that file are used only for `https://` URLs, as Access credentials are never sent over `http://`. It prints counts only, never names. It is skipped in normal test runs and **never runs in CI**: CI has no credentials and must not have any.

## Profiles and stored secrets

Profiles live in a Room database (`profiles.db`). Passwords, the Cloudflare Access client secret and custom header values are stored only as `SecretCipher` output (AES-256-GCM, key in the Android Keystore).

- **Never add a way to read a secret back into the UI.** The editor shows "saved" and offers Replace; `SecretInput.Keep` means "leave what is stored".
- **Never log or `toString` a secret.** `ConnectionSettings`, `SecretInput.Replace`, `SecretField` and `SettingsResult.Ready` are deliberately not data classes, or override `toString`, for this reason. Tests assert on it.
- **Schema changes need a migration.** Raising `ProfileDatabase.VERSION` exports a new file under `app/schemas`; commit it, write the `Migration`, and add a `MigrationTestHelper` test. There is no destructive fallback, so a missing migration crashes on upgrade rather than silently deleting profiles.
- `SecretCipher` is unit-tested with a software key. `KeystoreKeySource`, the few lines that talk to the Android Keystore, cannot run on the JVM and is only exercised on a device.

## Status and the Problems view

`StatusRepository` polls a profile (`NagiosClient`, then `ProblemClassifier`) and holds the latest result in memory, per profile: the classified report for the Problems tab and the poll it was made from for the Hosts and Services lists. Both are also written to the status cache (below).

- A failed refresh keeps the last *good* report and records the error beside it; the next success clears the error.
- One refresh per profile at a time: a second request while one is running does nothing. The running marker and the `refreshing` flag change together under a lock, so seeing `refreshing = false` means a new refresh would be accepted.
- A cancelled refresh (leaving the screen mid-fetch) resets the flag in `finally`.
- The first poll of a profile finds the CGI directory and stores it on the profile; later polls skip the search.
- Screens are stateless composables fed by a `...UiState` and an actions interface, so they are tested without a view model.

## Navigation and the tabs

`HomeScreen` holds the three tabs. One `HomeViewModel` serves all of them: which profile is shown and its status are the same whichever tab is open.

- The current tab, and each tab's search, chips and scroll position, are saved UI state (`rememberSaveable` under a `SaveableStateHolder`, keyed by profile and tab), not view-model state. What is typed has to reach the text field in the same frame.
- Rows for the lists are built by `BrowseRows` and filtered by `BrowseFilter`, both plain functions with their own tests.
- `Routes` builds every destination. Host and service names are free text from someone's Nagios config, so they travel as encoded query arguments, never as path segments; `DetailRoutesTest` sends awkward names through the real graph.
- Icons are vector drawables drawn for the app (`res/drawable/ic_*.xml`). There is no icon library in the build, on purpose: one more dependency to license-check for a handful of shapes.

## Detail screens

`DetailRepository.open` is a flow: the cache first, then a "loading" state, then the record, the comments and the downtimes, emitted as each arrives. `DetailViewModel` collects it and joins it with the last poll (`detailUiState`) for the stand-in record, the host link and a host's service list.

- **Three requests, three outcomes.** The record's error is `ObjectDetail.error`; comments and downtimes each carry their own in an `AnnotationSection`. Do not fold them together: an unreadable comment list must not blank a screen.
- **A failure that is not about this record ends the refresh.** Unreachable, rejected credentials and the like would fail twice more the same way, and each failure can be a timeout. Only an API error or an HTTP error from Nagios lets the comments and downtimes be tried.
- **A refresh starts from what is on screen** (`from`), not from the cache, which keeps fewer comments than a fetch returns.
- **Comments and downtimes are saved only if the record is**: the cache hangs them off its row.
- The screen is a `LazyColumn` of rows, including one row per comment. A busy object can have a couple of hundred.
- The view model reads its arguments from `SavedStateHandle` under the names `Routes` uses; `detailDestinations` takes its content as a parameter so the routes can be tested without Hilt.

## The status cache

`status.db` holds the last poll of each profile and the detail of objects the user has opened (`StatusCache`, `DetailCache`). It exists so the app can show something before the network answers.

- **It is disposable.** Its migrations are destructive: change the schema, raise `StatusDatabase.VERSION`, and the cache is wiped and refilled. Do not put anything in it that cannot be fetched again. See [ADR 0005](adr/0005-status-cache-in-a-separate-disposable-database.md).
- **Every multi-row write is one transaction** (`StatusCacheDao`), so a crash never leaves half a poll.
- **Everything is bounded per profile.** Adding a table means deciding what stops it growing.
- **No foreign key to profiles.** Deleting a profile clears its cache through `ProfileCleanup`; `CacheJanitor` sweeps orphans at startup. A new store keyed by profile id needs both.
- `StatusRepository` serves the cached result first and keeps `refreshing` true until the fresh result is on disk: "not refreshing" has to mean a new refresh would be accepted.

## Network rules

All traffic to Nagios goes through one OkHttp interceptor (`ConnectionInterceptor`) that refuses `http://` unless the profile opted in, never sends Cloudflare Access credentials over `http://`, and only sends credentials to the configured origin. Redirects are not followed. Any new HTTP client must be derived from the per-profile client so it inherits these rules; see [ADR 0004](adr/0004-cleartext-permitted-in-manifest-enforced-in-app.md).

## CI

`.github/workflows/ci.yml` has three jobs, `build`, `lint` and `foss`, on GitHub-hosted runners. **Those job names are required status checks** on `main`, set in `pgmac-net/terraform-github`. Rename one there first, or every merge blocks.

Actions are pinned by commit SHA and kept current by Renovate. The JDK that CI installs (`java-version`) must match `jvmToolchain()` in `app/build.gradle.kts`; Renovate is told not to bump its major, so moving to a newer JDK is one change to both. `scorecard.yml` and `dependency-submission.yml` run on `main` only and are not required. Their findings appear under the repository's Security tab.


Beside those three, and not required for a merge:

- **CodeQL** (`codeql.yml`) analyses the Kotlin, the workflows and the scripts on every pull request, on `main`, and weekly. Kotlin is analysed by watching a real compilation, so that job builds the app with the daemon and the build cache off: a compilation restored from a cache is one CodeQL never saw. It is not a required check because CodeQL's support for a new Kotlin release can lag the release; if the Kotlin job fails after a Kotlin upgrade, that is the first thing to check.
- **The Gradle wrapper is verified** in the `build` job. `gradle-wrapper.jar` is a binary in the repository that runs on every build; it is checked against the checksums Gradle publishes before anything runs it.

## Layout

```
app/                     the application module
build-logic/             Gradle plugin with this repo's own checks (FOSS denylist, SPDX)
config/                  detekt config, FOSS denylist
gradle/libs.versions.toml  version catalog
scripts/                 CI helper scripts
docs/                    design, ADRs, this file
```
