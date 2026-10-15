# Fuzzing the list fetcher

Ticket: pgmac-net/nagios-android#32. Follows #29 (`docs/development.md`, "Fuzzing").

## What was built

- A sixth fuzz target, `ListFetcher`, that runs `ResilientListFetcher` against a server answering from the input. `ListFetcherScript` (test code) holds the format and the checks; `FuzzEntryPoints.listFetcher` and `ListFetcherFuzzTarget` are the entry and the target.
- Input: four bytes of limits, then two bytes per reply (kind, number), cycling when it runs out. Small limits so that the budget and the ceiling are a few replies away.
- Checked: finishes in time; a result or a `NagiosError`; requests <= pages + budget, pages <= ceiling / page size + 1; list <= ceiling; degraded <= cap; the internal `Http(0)` marker never returned.
- Wired into `build.gradle.kts` (`fuzzListFetcher`), `fuzz.yml` (matrix) and `FuzzCorpusTest` (every corpus file is replayed as a script).
- Eight seeds in `fuzz/corpus/list-*.bin`: healthy, one poisoned, never ends, budget spent mid-search, larger than asked, every record poisoned, errors between pages, without details.

## Decisions

| Decision | Outcome | Why |
|---|---|---|
| Input | Raw bytes, positional grammar | Like the other targets; every mutation is a valid script |
| Limits | From the input, small | The edges are reachable in a few replies; properties are asserted against whatever was chosen |
| Script exhausted | Cycle | A short input is a server that never stops, which is what the limits are for |
| Language/location | Kotlin under `src/test/kotlin` | The repo's targets are; the ticket said `src/test/java` |

## Deviations from the plan

- None.

## What was found, and by what

- **No finding.** 327,000 runs in 3 minutes locally (557 coverage points, 133 corpus entries), none failed. The fetcher holds its budget, its ceiling and its degraded cap.
- **The harness was shown to find something.** With the fetcher's budget check loosened by one (`<= maxExtraRequests + 1`), the fuzzer failed within a minute with "4 requests, where 1 pages and 2 extra are allowed", and the existing `ResilientListFetcherTest` budget test failed too. Reverted.
- `android.util.Log.isLoggable` is printed once at the start of a `ListFetcher` run (OkHttp's platform probe against the android.jar stub); it is not an error and the other targets do not show it because they do not build an `OkHttpClient`.

## Follow-ups

- The command flow (`CommandClient`) is a conversation too (form, then submit); it could take the same shape of target.
- Longer runs: the weekly job gives it 10 minutes.
