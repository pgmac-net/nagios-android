# The Nagios client as a module of its own

Ticket: pgmac-net/nagios-android#115 (stage 2 of the refactor in #114). One pull request. Decision recorded in [ADR 0015](../adr/0015-the-nagios-client-is-a-plain-jvm-module.md).

## What was done

- **`:nagios`** (`nagios/`) holds `net.pgmac.nagwatch.nagios.*`: the model, HTTP, parsers, commands and graphs. It is plain Kotlin/JVM. `git mv` from `app/`, with no package or import changes. Its tests, the six fuzz targets, the fixtures, command pages, invalid-escape captures and fuzz corpus moved with it. The graph captures and `Captures`/`png()` are now test fixtures (`src/testFixtures`), shared with the app's graph screen tests.
- **`nagwatch.kotlin-quality`**, a convention plugin in `build-logic`, holds what both modules hold themselves to: JDK 21 toolchain, Java 17 bytecode, warnings as errors, detekt, ktlint, the tool security floors, dependency locking and `resolveAndLockAll`. Those blocks left `app/build.gradle.kts`.
- **Fuzzing** moved to `:nagios` (`:nagios:fuzz<Target>`, working files under `nagios/build/fuzz`).
- **Coverage**: a Kover variant named `app` in both modules. `:app:koverXmlReportApp` writes `reportApp.xml`, which covers the app's debug tests and the client's, and is what Codecov now reads.
- **Widened from `internal` to public**, as the compiler asked: `ConnectionInterceptor`, `NagiosResult.map`, and `NagiosClientFactory`'s full constructor, which the app's command live test uses.
- **CI**: the unit-test step adds `:nagios:test`, Codecov reads `reportApp.xml`, the fuzz workflow calls `:nagios:fuzz<Target>`, and the test and lint report artifacts include `nagios/build/reports`. The JDK comments in every workflow and in `renovate.json` point at the convention plugin.
- **Scripts**: `live-test.sh` and `live-smoke-test.sh` run the live tests in whichever module they now live in.
- **Docs**: a Modules section and the moved paths in `docs/development.md`, plus `CONTRIBUTING.md`, `docs/design.md`, `docs/openssf-best-practices.md` and ADR 0015.

## Decisions (from the grilling session)

| Decision | Outcome |
|---|---|
| How far | One module, the client; stop. The rest of `:app` is cyclic (`OutageCause` in `notify`, colours in `ui.theme`, widget bindings) |
| Name | `:nagios`, packages unchanged |
| Shared build settings | A convention plugin, not a copy per module, nor `subprojects {}` from the root |
| API | Widen only what the compiler names; no explicit-API mode |

## Checks

- `./gradlew check assembleDebug assembleRelease testReleaseUnitTest checkSpdxHeaders checkReadmeBadges`: green. `:nagios` has 283 tests, all plain JUnit.
- `:nagios:fuzzStatusParser -PfuzzSeconds=10`: ran, no crash, seeded from the moved corpus.
- `scripts/foss-self-test.sh`: both checks still reject the banned dependency.
- **Release classpath**: `releaseRuntimeClasspath` in `app/gradle.lockfile` is identical to `main`'s. The only new locked artifact is `kover-jvm-agent`, from the coverage merge.
- **SBOM**: the same 158 third-party components as v0.3.0's, plus the module itself (`net.pgmac:nagios`).
- **Release APK**: 3,387,405 bytes against `main`, identical, and 5,775 classes in R8's mapping on both. R8 kept one different class (`OkHttpGraphClient` now merged away, one lifecycle class kept).

## Deviations from the plan

- **Kover**: the plan had `:app` merging `:nagios` into its existing `reportDebug`. Kover does not join an Android build variant to a JVM module's, so both modules got a custom variant named `app`, and the report Codecov reads became `reportApp.xml`.
- **The graph captures** went to test fixtures, and the client's tests also read that directory directly (`sourceSets.test.resources.srcDir`): the fuzz corpus test walks the captures as files, and resources inside the fixtures jar are not files.
- **A bug carried over from `:app`** was fixed: the `fuzz` aggregate task configured the other fuzz tasks while it was itself being created, which broke `./gradlew tasks --all`.
- **Stale lockfile lines**: Gradle kept `:app`'s `fuzzer` entries after that configuration left the module. They were removed by hand.
- **`group = "net.pgmac"`** on `:nagios`, so its SBOM entry is not under a made-up group. Its version stays `unspecified`; it is part of the app, not released on its own.
- **`@Suppress("MagicNumber")` on `png()`**: detekt's default test exclusions do not cover `testFixtures`.
- Implemented on Opus 5.5, not Fable 5: the model switch was declined.
