# The Nagios client is a plain-JVM module, and the only one split out

The Nagios client (`net.pgmac.nagwatch.nagios`: the model, HTTP, the parsers, commands and graphs) is a Gradle module of its own, `:nagios`, built with Kotlin/JVM and no Android. Everything else stays in `:app` (pgmac-net/nagios-android#115).

## Why this needed deciding

Stage 1 of the refactor (#114) cleaned up inside `:app`; #115 asked whether to split `:app` into modules, and proposed five (`:core:model`, `:core:nagios`, `:core:data`, `:ui`, `:app`). A module boundary is cheap to draw and expensive to move later, so where to draw them, and how many, was worth settling once.

## What was decided

- **One module, the client.** It was the one boundary already clean: the `nagios` packages imported nothing else of the app, and nothing from Android but `javax.inject`. The rest is not layered. `status` uses `notify`'s `OutageCause`; `notify` and the widget use `ui.theme`'s colours; `notify` uses the widget's bindings and `di`'s scope. Splitting it would mean moving those types first, for a single-developer app that gains little from more boundaries.
- **Plain JVM, not an Android library.** The client needs nothing from Android, so its tests (about 280) and the fuzzer run as plain JUnit, without Robolectric, and the module cannot grow an Android dependency without it being seen.
- **`:nagios`, with the packages as they were.** No import changed anywhere. A `:core:` prefix would only be worth it with more than one core module.
- **One convention plugin for what both modules hold themselves to** (`nagwatch.kotlin-quality` in `build-logic`): toolchain, bytecode level, warnings as errors, detekt, ktlint, the tools' security floors, dependency locking. A copy in each module would drift.
- **Public only where the app needs it.** `internal` now means the module, so the few things the app used (`ConnectionInterceptor`, `NagiosResult.map`, `NagiosClientFactory`'s full constructor for a live test) became public. No explicit-API mode: the only user of the module is the app.
- **Release checks stay on `:app`.** Licensee, the FOSS denylist and the SBOM read `:app`'s release classpath, which includes `:nagios` and everything it brings. The SBOM lists the module itself as a component (`net.pgmac:nagios`).

## What it costs

- **Two build files and two lockfiles** where there was one, and a convention plugin to read.
- **Coverage needs a matched pair of Kover variants** (`app` in both modules) for the client's lines to be in the report Codecov reads. Kover only joins an Android build variant to the same variant of an Android dependency.
- **The graph captures are test fixtures**, so the app's graph screen tests can use them, and the client's own tests read the same directory directly, since the fuzz corpus test walks it as files.
- **R8 sees a module boundary.** The release APK is the same size and has the same number of classes, but R8 now merges a different pair of them. That is an optimiser's choice, not a change in behaviour.

## Not done

- **`:core:data`, `:ui`, feature modules.** They wait until a type like `OutageCause` has a home that is not `notify`, or until build times or a second app make the case.
