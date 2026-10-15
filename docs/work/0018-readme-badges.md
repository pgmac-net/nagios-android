# README badges, with the coverage and Scorecard publishing they needed

Ticket: pgmac-net/nagios-android#70. Follow-up: pgmac-net/nagios-android#83.

## What was built

- **One badge row under the title:** CI, CodeQL, OpenSSF Scorecard, Codecov, Dependency-Track vulnerabilities, latest release, licence.
- **Coverage.** Kover 0.9.11 (`koverXmlReportDebug`) runs in the `build` job after the unit tests, and `codecov/codecov-action` uploads the report. Informational only: `codecov.yml` sets the project and patch statuses to informational and turns PR comments off, and the upload does not fail the build. Generated code is excluded. Locally the report measures 5613 of 6156 lines covered.
- **Scorecard.** `scorecard.yml` is now this repository's own workflow with `publish_results: true` and `fail_on_publish_error: true`, because the shared `pg-actions` workflow hard-codes `publish_results: false` and the badge needs a published result. Still weekly and by hand, never on push.
- **Dependency-Track.** The badge is pinned to the released version, vulnerabilities only. `checkReadmeBadges`, in the `lint` job, fails if the badge's version is not `versionName`.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Scope | Badges and the plumbing, one PR | A badge that says "unknown" adds nothing |
| Dependency-Track version | Pinned, checked in CI | DT 5.2.0's badge routes take a name and a version, or a per-version UUID; there is no "latest" |
| Dependency-Track kind | Vulnerabilities only | Violations depend on policies a reader cannot see |
| Scorecard | A workflow in this repository | The Scorecard API rejects reusable-workflow results and the shared one does not publish |
| Coverage | Kover, informational, no PR comments | Mutation gates already enforce test quality; Codecov must never block a merge |
| Extra badges | Release and licence | Self-updating. Fuzz status, min SDK and F-Droid skipped; Best Practices is #83 |

## Deviations from the plan

- **No unit test for `checkReadmeBadges`.** `build-logic` has no test source set and the other checks (`checkSpdxHeaders`) have none. It was exercised by hand instead: a wrong version in the README fails it with the message below, the right one passes.

## What was found, and by what

- **DT has no "latest" badge** (OpenAPI document of the live 5.2.0 server).
- **`gh api` could not resolve annotated tag objects** for the new actions; `git ls-remote` with `^{}` gave the commit SHAs that are pinned.
- **JDK 21 comes from mise** (`~/.local/share/mise/installs/java/temurin-21`); Gradle's toolchain resolution finds none otherwise.

## To do after merge

- Run `gh workflow run scorecard.yml` and confirm `https://api.scorecard.dev/projects/github.com/pgmac-net/nagios-android` answers.
- Confirm the Codecov upload on the first `main` build; if it fails, the org token is not a global upload token and a repository token is needed.
