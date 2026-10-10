# Fuzzing what a Nagios server sends back

Ticket: pgmac-net/nagios-android#29. One pull request (#31). Follow-up filed: #32. Part of clearing the Scorecard findings tracked in #26.

## What was built

- Three Jazzer fuzz targets for the places a server's response is handled: body interpretation, the status parsers (with `ProblemClassifier` after them), and the annotation parsers.
- `FuzzCorpusTest`, which replays every fixture, a hand-made corpus and every saved crash input through all three on every build.
- `./gradlew :app:fuzz`, and `fuzz.yml`: a minute per target on pull requests, ten minutes weekly, and by hand. Not a required check.
- A fix for the bug the work turned up: a response nested deeply enough overflowed the stack and crashed the app.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Approach | Jazzer, with targets in Java | Scorecard only credits Jazzer through `*.java` files; a Kotlin target would fuzz and earn nothing |
| When it runs | Every pull request (60 s per target) and weekly (10 min per target); never a required check | Random, so it must not be able to block a merge that did not cause what it found |
| What is fuzzed | Body interpretation, `StatusParser`, `AnnotationParser`; the fetcher as a follow-up | The first three are functions of one response. The fetcher is a conversation and needs its own harness |
| What counts as a failure | Any exception, an input taking over 10 s, or the 512 MB heap running out | The contract is "a result or a `NagiosError`, nothing else" |
| Findings | Fixed in the same PR, input saved as a permanent regression input | A crash that was found stays found |

Rejected: ClusterFuzzLite (a Docker image that can build an Android project, for the same engine underneath) and property-based tests (not credited by Scorecard for Java or Kotlin, so they could never close the alert).

## What it found

**One real bug.** kotlinx.serialization's JSON reader recurses once per level of nesting, and `NagiosApi` caught only `SerializationException`. About 100 KB of `[` threw `StackOverflowError`, an `Error`, which escaped the request and took the app down. Any hostile or broken server, or anything between the phone and Nagios, could do it; the 8 MiB size limit was no protection.

It was found by a hand-made awkward input in the replay test, before the fuzzer had run. The fuzzer then ran for a combined 12 minutes across the three targets on the final code and found nothing further.

Fix: bodies nested over 32 levels are refused before parsing. Nagios writes about 6.

## Deviations from the plan

- **The planned production change was a little larger than "make interpretation callable".** The depth guard is new behaviour, so it has its own tests and is documented in `docs/design.md`.
- **libFuzzer's resident-size limit is off.** The JVM and Jazzer's native side sit near 2.7 GB before the first input, so the limit blamed the JVM for something the code did not do. The heap cap does that job and turns a genuine runaway into an `OutOfMemoryError`.
- **Jazzer's reproducers are directed to the build directory.** Left alone it wrote a `Crash_*.java` into the module.

## What the verification taught

- **A fuzzer that finds nothing proves nothing until it has been shown to find something.** A crash planted behind a string comparison no fixture reaches was found in about 15 seconds, and saving its input made the replay test fail while the bug was present. This is now written down as the check to repeat when the harness changes.
- **A surviving mutant was a real gap in a test.** The "brackets inside strings" test had nothing structural after the string, so miscounting was invisible. Mutating the guard found it.
- **A mutation can be unreachable.** One of mine put the changed branch after the one that always matched; "caught" and "not caught" would have meant nothing. Checking that a mutation changes behaviour is part of the check.

## Known limits

- `ResilientListFetcher` is not fuzzed (#32).
- The corpus is not persisted between CI runs, so each run starts from the fixtures and the hand-made inputs. The weekly run is longer for that reason.
- Whether Scorecard closes its Fuzzing alert depends on it recognising the targets and on GitHub counting the repository as containing Java. Neither can be checked before merge.
- 32 is a judgement. If a future Nagios feature nests deeper, responses would be refused as "not Nagios".
