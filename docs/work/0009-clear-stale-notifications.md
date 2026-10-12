# Take a profile's notifications down when it stops being watched

Ticket: pgmac-net/nagios-android#46 (a bug found while finishing the M4 phone check, #6). One pull request: #47.

## What was wrong

Switching a profile's notifications off, or deleting the profile, cancelled its background job and nothing else. Whatever it had showing stayed on the phone with nothing left to update or remove it. A "CRITICAL" could sit there long after the problem recovered, and a tap on a deleted profile's notification opened a profile that no longer exists.

## What was built

- `PollSchedule`, which already noticed a profile leaving background polling, now also takes down everything that profile has showing (`Notifier.clear`) and forgets what was announced for it (`AnnouncedStore.forget`).
- Switching back on is a first poll: one summary of what is wrong now, then changes only.
- On the first look after the app starts, anything showing for a profile without notifications on is swept (`Notifier.sweep`), and the memory of those profiles is forgotten. That removes what earlier versions stranded and what an app killed half way left.
- The summary, the outage and the group's entry are cancelled by known identity as well as by lookup, so Android's lagging list of what is showing cannot leave them behind.
- A tap for a profile that no longer exists opens the app as the launcher would and goes nowhere (`TapTargets`).

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| What happens to what was announced when notifications go off | Forgotten; switching on is a first poll | Keeping it would announce what happened meanwhile one by one, and "recover" things the person was told about days ago |
| When to clear | At the moment of change, and a sweep at app start | The sweep catches what is already stranded, an app killed half way, and a poll in flight when its profile went |
| A tap for a profile that is gone | Open the app, go nowhere | Cheap, and no dead-end screen |
| Checking | A phone check, driven by the assistant; deletion by unit tests only | The bug was one only a phone could show, and deleting one of the maintainer's profiles is not the assistant's to do |

## Not done, on purpose

- Re-checking after a poll's fetch that the profile still wants notifications before posting. The window is seconds wide, and the next start sweeps up after it.
- A message for a tap on a profile that is gone.

## Found on the phone (Pixel 8a, Android 17)

- **The sweep removed what was already stranded**, with no action in the app: notifications left from the M4 check, and their group entry, were gone after the new build's first start.
- **Switching on again** gave a first-poll summary.
- **Switching off with four showing** left none. Android's log has the summary and the group entry cancelled by the app, and the two problem notifications removed along with their group (Android does that itself when a group's entry goes).
- **Aside, from the M4 timing checks**: Android ran the scheduled job by itself, and a recovery's one-hour timeout fired exactly on time.

Not tried on the phone: deleting a profile (unit tests only).

## How it was checked

- 782 unit tests (19 of them the live tests, skipped without a Nagios), `./gradlew check`, both builds, the FOSS self-test.
- 28 deliberate breaks, all caught. One survived the first run (a bare number read as a group key), and two "catches" were only compile errors and were redone with valid mutants.
- CI: build, lint, FOSS, CodeQL and four fuzz jobs.
