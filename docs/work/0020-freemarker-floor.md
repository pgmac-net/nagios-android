# FreeMarker floor for Kover's report generator

Ticket: pgmac-net/nagios-android#85. Unblocks the one pending answer on #83 (`vulnerabilities_critical_fixed`).

## What was built

- A security floor for `org.freemarker:freemarker` at 2.3.35, applied as a build-script classpath constraint beside the other floors.
- A line in `docs/development.md` (Security floors) saying when to remove it.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Fix | A floor, following the existing pattern | Kover 0.9.11 is the latest release and is kept; it is what the badge work chose |
| Kover | Stays | JaCoCo over-counts Kotlin, and the coverage work already uses Kover |

## Deviations from the plan

- None.

## What was found, and by what

- **Where it came from:** Kover 0.9.11, added in #70, pulls `freemarker:2.3.32` onto the build-script classpath through `intellij-coverage-reporter` and `coverage-report`. The alert opened the same day as #70 merged.
- **The advisory:** CVE-2026-84939, a path traversal in FreeMarker's template loading. It affects 2.2.0 to 2.3.34 and is fixed in 2.3.35.
- **Exposure:** the build script only. It is not in the APK, and `:app:dependencies` does not show it.
- **Resolved version:** `./gradlew buildEnvironment` shows `freemarker:2.3.32 -> 2.3.35`.
- **Kover still works on 2.3.35:** `koverXmlReportDebug` and `koverHtmlReportDebug` both run, and the HTML report is written (`app/build/reports/kover/htmlDebug/index.html`). FreeMarker is what renders that HTML.
- **Full check:** `./gradlew check assembleDebug assembleRelease` passes.

## What is left

- After merge, confirm the Dependabot alert closes once the dependency-submission run on `main` completes.
- Answer `vulnerabilities_critical_fixed` on #83 as Met, once the alert has closed.
- Remove this floor when Kover ships a release that asks for FreeMarker 2.3.35 or later.
