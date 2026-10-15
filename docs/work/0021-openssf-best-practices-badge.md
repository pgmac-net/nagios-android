# OpenSSF Best Practices badge

Ticket: pgmac-net/nagios-android#83. Follows the answer sheet in `docs/openssf-best-practices.md`.

## What was built

- The Passing answers on bestpractices.dev, project 15514, entered from the sheet (<https://www.bestpractices.dev/projects/15514>).
- The badge in the README row after Scorecard, and the project link at the top of the sheet.

## Decisions

| Decision | Outcome | Why |
|---|---|---|
| Two pull requests | #86 (sheet), #88 (badge) | Evidence links point at `main`; the project ID only exists after registration |
| Badge timing | Added once the project existed, reading "in progress 75%" | Honest about progress; it turned to passing once the answers were complete |

## Deviations from the plan

- The badge was added while the form read "in progress 75%", ahead of passing. It turned to passing before merge.

## What was found, and by what

- The critical-vulnerability answer (`vulnerabilities_critical_fixed`) was set to Met after #87 fixed the freemarker alert the same day.
- The three answers only the maintainer can confirm (`know_secure_design`, `know_common_errors`, `dynamic_analysis_fixed`) were confirmed on bestpractices.dev.

## Follow-ups

- Silver gaps are listed in the sheet and are not worked. `access_continuity` and `bus_factor` need a second person.
- Revisit the sheet on each release, and when a process it describes changes.
