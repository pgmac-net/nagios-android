# M2: browse, filter, detail and the status cache

Ticket: pgmac-net/nagios-android#4. Delivered as three planned PRs and one unplanned fix: #21 (cache and data), #22 (fix), #24 (navigation and lists), and the detail screens. Follow-up filed: #23.

## What was built

The app can now be used to look around a Nagios instance, not only to see what is wrong, and it keeps working without a network.

1. **Cache and data (#21).** `status.db`, a second, disposable Room database: the last poll of each profile and the detail of objects the user has opened, bounded per profile. The chosen profile is remembered across restarts. Each poll also lists every service by name and state, cheaply. `NagiosClient` learned to fetch one host or service, and an object's comments and downtimes.
2. **Navigation and lists (#24).** A bottom bar with Problems, Hosts and Services. Hosts and Services are searchable, with state chips that carry counts and a Handled chip.
3. **Detail screens.** State, attempts, duration, check times, output, long output, performance data, status flags, comments, scheduled downtime, the host of a service, the services of a host, and a link to the object in Nagios' own web interface.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Data strategy | Cheap state-only list of every service per poll; full detail fetched when an object is opened, and cached | Every service with details is about 40 times the size, and one record in it crashes the CGI |
| Cache storage | A separate `status.db` with destructive migrations; `profiles.db` keeps strict ones | Everything in the cache can be fetched again; nothing in the profiles can. ADR 0005 |
| Cache behaviour | Show what is saved at once, labelled with its age; never hide data because it is old; bounded per profile | A screen with yesterday's data and a label beats an empty one |
| Navigation | Bottom bar: Problems, Hosts, Services. No Settings tab until M4 | An empty tab was not worth a place in the bar |
| Detail content | Read-only: no actions (M3), no graphs (M6) | Keeps the milestone to looking |
| Version claim | "Tested on 4.5; 4.0.7 and later expected, not tested." Comments and downtimes each degrade on their own | Only 4.5 could be tested; an honest claim plus graceful degradation beats a guessed minimum |
| Delivery | Three sequential PRs | As for M1 |
| Verification | Tests, plus on-device checks driven over `adb` before each PR | See below: it earned its keep |

## Decisions made in review

- **Hosts and Services are sorted worst first**, with handled problems at the end of their severity. They were first built sorted by name.
- **The lists open on what is wrong**: Hosts with DOWN and UNREACH switched on, Services with WARN and CRIT.
- **Chips are tinted with the colour of their state**, with a tick on the ones that are on.

## Deviations from the plan

- **An unplanned fix PR (#22) between PR 1 and PR 2.** The first on-device check of M2 found two bugs already on `main`: a cold start with a saved profile stayed on "Loading" for ever, and the count chips overflowed on a phone. The cold-start bug dated from M1. Its on-device check had passed because it arrived at the screen from the profile editor, never from a cold start.
- **Detail routes were stubbed in PR 2** with a screen that said so, as planned, and replaced in PR 3.
- **Downtime parsing rests on Nagios' source, not a capture.** No downtime existed on the test instance. The fixture is hand-written from `json_status_downtime_details` in `cgi/statusjson.c`, which is also where the unit of `duration` (seconds) comes from. A first version guessed the unit and would have read two hours as 83 days.
- **PENDING has a chip only while something is pending**, to keep the row of chips short. The plan listed it as permanent.

## What the on-device checks taught

Recorded because each changed how the rest of the work was tested.

- **A cold start is its own case.** What a screen needs on arrival is now loaded by the view model when the data exists, not requested by the screen as it starts, and is tested from a newly built view model.
- **Layout tests need real fonts.** Robolectric's stub fonts made an overflowing row of chips fit. Such tests now run with native graphics at phone width, and are checked to fail against the broken layout first.
- **A long-lived collector in a view model needs stopping in tests.** The cold-start fix introduced one, and a test that outlived its own teardown could touch `Dispatchers.Main` while the next test replaced it.
- **A test can be wrong about time.** A test pressed Back in the same frame as a tab tap, before the screen had redrawn, and failed against correct code.

## Verification

- Unit and Robolectric tests for every layer; the total is in the pull request.
- Behaviours broken on purpose, one at a time, to confirm a test catches each. One such check in PR 3 found a gap (nothing tested that a comments-only failure keeps the comments saved earlier), which was then covered.
- A live test against a real Nagios Core 4.5.9 for the client calls (#21).
- On the maintainer's phone, against the real instance, for each PR. What was and was not checked there is listed in each pull request.

## Known limits

- Older Nagios versions are untested.
- Downtime display has never been seen against a real downtime.
- Lists are built and filtered on the main thread: fine for hundreds of services, untested for the tens of thousands the fetch limit allows.
- Nagios emits `\a` and `\v`, which are not valid JSON escapes; a plugin output containing either would make a response unparseable (#23).
