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

`scripts/foss-self-test.sh` adds a known-banned dependency (`-PfossSelfTest=true`) and asserts that the licence and denylist checks both **fail**. CI runs it on every PR, so a silently broken check turns CI red.

## Dependencies

- Versions live in `gradle/libs.versions.toml`. Add a library in the change that first uses it, not before.
- After any dependency change, refresh the lockfile and commit it.
- Kotlin, KSP and the Compose compiler plugin move together. AGP and the Gradle wrapper have a compatibility matrix. Renovate groups them for that reason.
- FOSS only. See [CONTRIBUTING.md](../CONTRIBUTING.md#dependencies).

## Tests

JVM unit tests only, for now. Compose screens are tested under Robolectric (`app/src/test`), which is how CI stands in for "the app launches" without an emulator. Robolectric is pinned to an Android version in `app/src/test/resources/robolectric.properties`; it trails `targetSdk` deliberately.

A real-device launch is checked by hand: download the `nagwatch-debug-apk` artifact from a CI run and install it.

## CI

`.github/workflows/ci.yml` has three jobs, `build`, `lint` and `foss`, on GitHub-hosted runners. **Those job names are required status checks** on `main`, set in `pgmac-net/terraform-github`. Rename one there first, or every merge blocks.

Actions are pinned by commit SHA and kept current by Renovate. `scorecard.yml` and `dependency-submission.yml` run on `main` only and are not required.

## Layout

```
app/                     the application module
build-logic/             Gradle plugin with this repo's own checks (FOSS denylist, SPDX)
config/                  detekt config, FOSS denylist
gradle/libs.versions.toml  version catalog
scripts/                 CI helper scripts
docs/                    design, ADRs, this file
```
