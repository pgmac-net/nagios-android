# Repair the invalid JSON escapes Nagios writes

Ticket: pgmac-net/nagios-android#23. One pull request: #48.

## What was wrong

Nagios Core's JSON writer escapes a bell as `\a` and a vertical tab as `\v`, neither of which JSON has. One check whose output holds either made the whole `statusjson.cgi` response HTTP 200 and not JSON, so the **whole poll** failed, not one record, and the app told the person that something other than Nagios was answering.

The ticket had this from reading Nagios' source and said it had not been seen. It has now: the test Nagios gained a check that prints these characters, and every control character was passed through it. Only these two are written invalidly (Nagios Core 4.5.3); the rest get a valid escape or are legal as they stand.

## What was built

- `repairInvalidEscapes`, applied in `interpretResponseBody` before the body is parsed: `\a` becomes `\u0007`, `\v` becomes `\u000b`.
- Escapes are read left to right, a backslash with the character after it. A real backslash is written `\\`, so a real backslash before an `a` (`\\a`) is left alone and one before a bell (`\\\a`) is repaired after the pair.
- A body with nothing to repair comes back as the same string.
- A fifth service in the test Nagios, `web01` / `Control chars`, whose check prints these characters; two real responses from it committed in `app/src/test/resources/invalid_escapes`; a live test that runs the app's own client against it.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Repair, fall back, or both | Repair only | The two characters are the whole set; a fallback would cost requests and probe bodies that are not Nagios at all |
| What the test is built from | A real capture, and the check lives in the test Nagios | The ticket itself sat unverified because it was read from source |
| The character once parsed | Left as it arrives | Same as every other control character; tidy display is a separate question |

## Things the capture and the reviews found

- **Nagios eats one level of backslash from plugin output itself.** The first version of the test check printed one backslash before an `a` and Nagios wrote a bare `a`. To get a real backslash into the JSON, a plugin has to print two. This is why the capture is real: a hand-written fixture would have encoded what I believed.
- **Two passes of the commit security review pulled opposite ways on the same line.**
  1. The repair makes a body up to three times its size, and the size limit was applied before it. A bound that was a consequence, not a decision.
  2. A fix that refused a body past an allowance (failing closed) would let one check's output, or a plugin printing binary, blind the app to every other host. That is the bug being fixed.
  The reconciliation: the allowance is about **growth**, not acceptance. The first 65,536 repairs are exact; past that a bell or vertical tab is written as the nearest valid two-character escape (`\b`, `\f`), which does not grow the text. The body always parses, and growth is at most 256 KiB.
- **A test that could not tell two things apart.** "Valid escapes do not use the allowance up" had a body with no bell in it, so a mutant that counted valid escapes survived. It now has a bell after them.

## How it was checked

- 803 unit tests (20 of them live, skipped without a Nagios), `./gradlew check`, both builds, the FOSS self-test.
- **The live test fails at its assertion when the repair is removed**, so it proves the bug and not just the fix.
- 18 deliberate breaks, all caught. No phone check: nothing on screen changes except that a poll which failed now succeeds, and the live test covers that against a real server.
- CI: build, lint, FOSS, CodeQL and four fuzz jobs. The `ResponseBody` fuzz target also asserts idempotence and the growth bound on whatever it is given.
