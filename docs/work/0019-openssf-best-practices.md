# OpenSSF Best Practices: the answer sheet

Ticket: pgmac-net/nagios-android#83. Raised from #70 (README badges). Related: #85 (freemarker), which one answer waits on.

## What was built

- **`docs/openssf-best-practices.md`**: an answer to each of the 67 Passing criteria (Met, N/A or pending), a one-line reason and an evidence link on `main`, grouped as the form groups them, plus the Silver criteria that are not met and what meeting them would take.
- **A link from `docs/development.md`**, beside the badge, coverage and Scorecard notes.
- **No code changes.**

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Deliverable | An answer sheet in the repo, pasted into the form by the maintainer | Only the maintainer can register and submit; the sheet stays to be kept current |
| Level | Passing in full, Silver gaps listed and not worked | Passing gets the badge; the list shows what Silver costs without growing the ticket |
| Pull requests | Two: the sheet, then the badge once there is a project ID | Evidence links point at `main`, and the ID only exists after submission |
| Freemarker | Its own ticket, #85 | A build-classpath floor; not documentation |

## Deviations from the plan

- None so far. The badge pull request is still to come.

## What was found, and by what

- **The criteria text had to come from upstream.** Fetched `criteria/criteria.yml` and `config/locales/en.yml` from the badge project: 67 Passing, 55 Silver, 23 Gold. The two files nest differently; the descriptions are under `criteria.<level>.<id>.description`.
- **Three answers are not facts about files.** `know_secure_design`, `know_common_errors` and `dynamic_analysis_fixed` are marked for the maintainer to confirm.
- **One answer waits on a fix.** `vulnerabilities_critical_fixed`: a critical freemarker alert opened 2026-10-15 on the build classpath (not shipped), tracked as #85.
- **Evidence links were checked.** Every repository URL in the sheet returned 200 except the code-scanning page, which needs a login; replaced by the public Scorecard result. Release tags verify as signed (`git tag -v v0.1.1`).
- **Silver gaps that need a second person** (`access_continuity`, `bus_factor`) are marked; Gold is out of reach for a solo project.

## What is left

- The maintainer registers the project, pastes the answers, and gives back the project ID.
- #85 merges, and `vulnerabilities_critical_fixed` is answered Met.
- Pull request 2: the badge in the README row after Scorecard, and the project URL at the top of the sheet.
