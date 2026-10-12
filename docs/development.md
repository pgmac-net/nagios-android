# Development

## Requirements

- A full **JDK 21** (with `javac`; a JRE is not enough).
- The **Android SDK**. Either Android Studio, or the command-line tools with `ANDROID_HOME` set (or `sdk.dir` in an untracked `local.properties`). The build needs the platform and build-tools for the `compileSdk` in `app/build.gradle.kts`; with licences accepted, the Android Gradle Plugin fetches missing pieces itself.
- Nothing else. Gradle comes from the wrapper (`./gradlew`), pinned by checksum.

## Everyday commands

| Task | Command |
|---|---|
| Build a debug APK | `./gradlew assembleDebug` (output in `app/build/outputs/apk/debug/`) |
| Unit tests | `./gradlew testDebugUnitTest` |
| Everything CI runs | `./gradlew check assembleDebug assembleRelease` then `scripts/foss-self-test.sh` |
| Auto-format Kotlin | `./gradlew ktlintFormat` |
| Refresh the lockfile | `./gradlew :app:resolveAndLockAll --write-locks` |

The debug build installs as `net.pgmac.nagwatch.debug`, labelled "Nagwatch (debug)", so it sits beside a release build. Release builds are minified with R8 and **unsigned** until the release pipeline exists (M7).

## What `check` enforces

| Check | Task | Notes |
|---|---|---|
| Kotlin style | `ktlintCheck` | Settings in `.editorconfig` |
| Static analysis | `detekt` | `config/detekt/detekt.yml`; no baseline, warnings are errors |
| Android Lint | `lintDebug` | Warnings are errors. Version-nag checks are off; Renovate owns bumps |
| Compiler warnings | (compile) | `allWarningsAsErrors` |
| Licence allowlist | `licensee` | Allowed SPDX ids in `app/build.gradle.kts` |
| Dependency denylist | `checkFossDependencies` | Prefixes in `config/foss-denylist.txt` |
| SPDX headers | `checkSpdxHeaders` | First line of every `.kt` / `.kts` |
| Locked dependencies | (resolution) | Any dependency not in `app/gradle.lockfile` fails the build |
| No leaked fixtures | `testDebugUnitTest` (`FixtureLeakTest`) | Fixtures must be sanitised |

`scripts/foss-self-test.sh` adds a known-banned dependency (`-PfossSelfTest=true`) and asserts that the licence and denylist checks both **fail**. CI runs it on every PR, so a silently broken check turns CI red.

## Dependencies

- Versions live in `gradle/libs.versions.toml`. Add a library in the change that first uses it, not before.
- After any dependency change, refresh the lockfile and commit it.
- Kotlin, KSP and the Compose compiler plugin move together. AGP and the Gradle wrapper have a compatibility matrix. Renovate groups them for that reason.
- FOSS only. See [CONTRIBUTING.md](../CONTRIBUTING.md#dependencies).

### Security floors

Build tools bring libraries of their own, and a tool's latest release can still ask for a version with a known vulnerability. None of these is in the APK: they run on the machine doing the build. They are still raised, because an alert left open teaches everyone to ignore alerts.

- The versions are the `floor...` entries in `gradle/libs.versions.toml`, so Renovate keeps them moving.
- They are applied as **constraints** in three places, because Gradle has three separate classpaths here: the build script's (`build.gradle.kts`, for what the Android Gradle plugin brings), and the lint tool's and ktlint's (`app/build.gradle.kts`). A constraint adds nothing: it only applies where something already asks for that library.
- Where a library is one of a family released together (Bouncy Castle, logback), every member in use is raised, not only the one named in the advisory.
- `build-logic` does not use the `kotlin-dsl` plugin, because that brings the Kotlin Gradle plugin of whichever version Gradle embeds, which cannot be raised without a new Gradle. It uses the project's own Kotlin plugin instead. The cost: in `build-logic`, a lambda passed to a Gradle `Action` takes its argument as a parameter (`{ task -> task.dependsOn(...) }`), not as an implicit receiver.
- **When an alert appears:** find where the version comes from (`./gradlew buildEnvironment` for the build script, `./gradlew :app:dependencies` for everything else), add or raise a floor, refresh the lockfile, and run the full `check`: a raised library is being run by a tool that was not released against it.
- **When to remove one:** when the tool that needed it asks for a fixed version itself. A floor below what is already resolved does nothing and can go.

## Tests

JVM unit tests only, for now. Compose screens are tested under Robolectric (`app/src/test`), which is how CI stands in for "the app launches" without an emulator. Robolectric is pinned to an Android version in `app/src/test/resources/robolectric.properties`; it trails `targetSdk` deliberately.

A real-device launch is checked by hand: download the `nagwatch-debug-apk` artifact from a CI run and install it.

Two things the tests could not see, and what to do about each:

- **A test that measures layout needs real fonts.** Robolectric's default stub fonts make every piece of text a few dp wide, so a row of chips that overflows on a phone fits comfortably in the test. Annotate such a test with `@GraphicsMode(GraphicsMode.Mode.NATIVE)` and a phone-sized qualifier (`w360dp-h640dp-xhdpi`), then check it fails against the broken layout before trusting it.
- **A cold start is its own case.** Arriving at a screen from another screen and arriving from a fresh process differ in what has loaded by then. Anything a screen needs on arrival should be driven by the view model once the data exists, not requested by the screen at the moment it starts; test it from a newly built view model.

## Nagios fixtures

Parser and client tests replay responses captured from a real Nagios. This repository is public, so captures are **sanitised before they are committed**: hosts, services, plugin output and the user name are replaced with generic values; structure, states and timestamps are kept.

```
NAGWATCH_URL=https://nagios.example.org/nagios/cgi-bin scripts/capture-fixtures.sh /tmp/nagwatch-raw
scripts/sanitise_fixtures.py /tmp/nagwatch-raw app/src/test/resources/fixtures
```

- Raw captures name real hosts. Keep them outside the repository (the capture script refuses to write inside it) and delete them afterwards.
- Credentials come from `~/.config/nagwatch/dev.env` (`USER=` and `PASS=` lines). Use a read-only Nagios user.
- The detail and comment captures ask about one host and one of its services: set `NAGWATCH_HOST` and `NAGWATCH_SERVICE` to ones that have comments.
- Re-running the sanitiser regenerates **every** fixture from the new capture, and the tests assert on their contents. Regenerate only the files you mean to and update the tests that read them.
- `downtimelist_synthetic.json` is hand-written, not captured: the instance the others came from had no downtime. Its fields follow `json_status_downtime_details` in Nagios Core's `cgi/statusjson.c`.
- The sanitiser fails if any original name survives. `FixtureLeakTest` is the backstop in CI: it fails on host or service names that are not the sanitiser's generic ones, on private addresses and on anything that looks like a real domain.

## Testing against a real Nagios

```
scripts/live-smoke-test.sh https://nagios.example.org/nagios
```

Runs the real client (connect, CGI-path detection, a full poll, classification, and a full detailed service list) against a live instance, using the same credentials file. `ACCESS_CLIENT_ID` and `ACCESS_CLIENT_SECRET` in that file are used only for `https://` URLs, as Access credentials are never sent over `http://`. It prints counts only, never names. It is skipped in normal test runs and **never runs in CI**: CI has no credentials and must not have any.

## Profiles and stored secrets

Profiles live in a Room database (`profiles.db`). Passwords, the Cloudflare Access client secret and custom header values are stored only as `SecretCipher` output (AES-256-GCM, key in the Android Keystore).

- **Never add a way to read a secret back into the UI.** The editor shows "saved" and offers Replace; `SecretInput.Keep` means "leave what is stored".
- **Never log or `toString` a secret.** `ConnectionSettings`, `SecretInput.Replace`, `SecretField` and `SettingsResult.Ready` are deliberately not data classes, or override `toString`, for this reason. Tests assert on it.
- **Schema changes need a migration.** Raising `ProfileDatabase.VERSION` exports a new file under `app/schemas`; commit it, write the `Migration`, and add a `MigrationTestHelper` test. There is no destructive fallback, so a missing migration crashes on upgrade rather than silently deleting profiles.
- `SecretCipher` is unit-tested with a software key. `KeystoreKeySource`, the few lines that talk to the Android Keystore, cannot run on the JVM and is only exercised on a device.

## Status and the Problems view

`StatusRepository` polls a profile (`NagiosClient`, then `ProblemClassifier`) and holds the latest result in memory, per profile: the classified report for the Problems tab and the poll it was made from for the Hosts and Services lists. Both are also written to the status cache (below).

- A failed refresh keeps the last *good* report and records the error beside it; the next success clears the error.
- One refresh per profile at a time: a second request while one is running does nothing. The running marker and the `refreshing` flag change together under a lock, so seeing `refreshing = false` means a new refresh would be accepted.
- A cancelled refresh (leaving the screen mid-fetch) resets the flag in `finally`.
- The first poll of a profile finds the CGI directory and stores it on the profile; later polls skip the search.
- Screens are stateless composables fed by a `...UiState` and an actions interface, so they are tested without a view model.

## Navigation and the tabs

`HomeScreen` holds the three tabs. One `HomeViewModel` serves all of them: which profile is shown and its status are the same whichever tab is open.

- The current tab, and each tab's search, chips and scroll position, are saved UI state (`rememberSaveable` under a `SaveableStateHolder`, keyed by profile and tab), not view-model state. What is typed has to reach the text field in the same frame.
- Rows for the lists are built by `BrowseRows` and filtered by `BrowseFilter`, both plain functions with their own tests.
- `Routes` builds every destination. Host and service names are free text from someone's Nagios config, so they travel as encoded query arguments, never as path segments; `DetailRoutesTest` sends awkward names through the real graph.
- Icons are vector drawables drawn for the app (`res/drawable/ic_*.xml`). There is no icon library in the build, on purpose: one more dependency to license-check for a handful of shapes.

## Detail screens

`DetailRepository.open` is a flow: the cache first, then a "loading" state, then the record, the comments and the downtimes, emitted as each arrives. `DetailViewModel` collects it and joins it with the last poll (`detailUiState`) for the stand-in record, the host link and a host's service list.

- **Three requests, three outcomes.** The record's error is `ObjectDetail.error`; comments and downtimes each carry their own in an `AnnotationSection`. Do not fold them together: an unreadable comment list must not blank a screen.
- **A failure that is not about this record ends the refresh.** Unreachable, rejected credentials and the like would fail twice more the same way, and each failure can be a timeout. Only an API error or an HTTP error from Nagios lets the comments and downtimes be tried.
- **A refresh starts from what is on screen** (`from`), not from the cache, which keeps fewer comments than a fetch returns.
- **Comments and downtimes are saved only if the record is**: the cache hangs them off its row.
- The screen is a `LazyColumn` of rows, including one row per comment. A busy object can have a couple of hundred.
- The view model reads its arguments from `SavedStateHandle` under the names `Routes` uses; `detailDestinations` takes its content as a parameter so the routes can be tested without Hilt.

## Commands

Everything the app writes to a Nagios goes through `CommandClient` (`nagios/command`). The rules it is built around are in `docs/design.md` section 5; the reasoning for the date handling is ADR 0006.

- **`CommandPage` is the only code that reads `cmd.cgi`'s HTML.** If a Nagios release rewords a message, that is the file to change, and a new captured page in `app/src/test/resources/commandpages/` is how to prove it. An unrecognised page must stay "unknown": do not add a fallback that guesses.
- **`ServerClock` is the only code that turns a moment into text for Nagios.** Do not format a date for `cmd.cgi` anywhere else.
- **The command client is built on its own HTTP client** (`NagiosClientFactory.createCommands`): no retries and no connection reuse. OkHttp re-sends a request when a pooled connection turns out to be dead, which is right for a read and wrong for an acknowledgement.
- **Outcomes are four different things**, and the difference is the point: accepted, refused (Nagios said no), not sent (certain), unknown (not certain). Only "not sent" is safe to repeat without looking.
- `storedCommentText` is what Nagios will keep of a comment. Use it wherever the app has to recognise its own comment or show the user what will be stored.

### Sending a command from a screen

`CommandRepository` (`status/`) is what screens use; they never hold a `CommandClient`.

- `run(profileId, command)` is a flow of `ActionProgress`: `Sending`, then one of `Refused`, `NotSent`, `Unknown`, or `Sent` followed by `Confirmed` or `NotConfirmed`. **Only `Confirmed` may be worded as done.**
- Confirming is reading the object again and looking for the effect (`effectSeen`). A new command needs a new case there; a command with no case is never confirmed.
- The send itself runs in `NonCancellable`: leaving the screen must not cut a request off part-way. Nothing after it is protected, so the waiting stops when the screen goes.
- A downtime is confirmed by `scheduled`, which compares windows and can answer `Mismatch`. **Do not add the window to how the downtime is recognised**: the window is the thing being checked.
- `DateOrderNeeded` means nothing was sent. The view model asks, stores the answer with `rememberDateOrder`, and runs the same command again.
- The server's date format is a column on the profile (`date_format`, schema version 2). Profiles have no destructive fallback, so **every schema change needs a migration and a case in `ProfileMigrationTest`**, which builds the old database from the exported schema in `app/schemas`.
- `permission(profileId)` asks once whether the user is read-only and remembers the answer for the process, keyed by profile, address and user. A failure to find out is `UNKNOWN` and is not remembered. `UNKNOWN` leaves the buttons on.
- `DetailViewModel` allows one command in flight and ignores taps until it has an answer.

**Text fields do not go in dialogs.** Under Robolectric a Compose text field inside any dialog window never lets the test go idle (the test fails after a minute with "Compose did not get idle"; a bare `BasicTextField` in a plain `Dialog` is enough to show it). So the forms that take text open in the page. A dialog with only text and buttons, such as the one confirming removal of an acknowledgement, is fine.

### The test Nagios

Commands change a server, so they are never tried against a real one. `scripts/test-nagios/start.sh` starts a throwaway Nagios Core in Docker with made-up hosts and three users (`operator` may command anything, `viewer` is read-only, `limited` may only command `web01`), listening on this machine only.

```
scripts/test-nagios/start.sh --date-format euro --timezone Europe/Berlin
scripts/test-nagios/live-test.sh        # every date format, several timezones; needs Docker
scripts/test-nagios/stop.sh
```

- The passwords in `start.sh` are not secrets: they protect a made-up instance and exist because `cmd.cgi` refuses to work without authentication.
- **One of its five services prints control characters** (`web01` / `Control chars`, from `control-chars.sh`): a bell, a vertical tab and real backslashes beside an `a` and a bell. It is how pgmac-net/nagios-android#23 was seen for the first time, and `ControlCharactersLiveTest` runs the app's client against it. Nagios takes one level of backslash off a plugin's output itself, so the script prints two to get one into the JSON; if you change what it prints, look at what Nagios writes (`curl` the `query=service` response) before trusting a test.
- The two real responses it produces are in `app/src/test/resources/invalid_escapes`, apart from `fixtures/` because the leak check parses everything there strictly and these are not valid JSON. Their README says how they were captured.
- `CommandLiveTest` sends real commands. Before it sends anything it checks that the server is on this machine and is the made-up one, and refuses otherwise.
- `--lan` makes it reachable from a phone on the same network, over plain http. Stop it when the check is done.
- `NAGWATCH_TEST_IMAGE` selects the image (default `jasonrivers/nagios:latest`).
- Never run in CI: it needs Docker and takes minutes.

## The status cache

`status.db` holds the last poll of each profile and the detail of objects the user has opened (`StatusCache`, `DetailCache`). It exists so the app can show something before the network answers.

- **It is disposable.** Its migrations are destructive: change the schema, raise `StatusDatabase.VERSION`, and the cache is wiped and refilled. Do not put anything in it that cannot be fetched again. See [ADR 0005](adr/0005-status-cache-in-a-separate-disposable-database.md). The one exception is what has been announced, which can be rebuilt instead: raising the version costs every profile with notifications on one summary at its next poll (ADR 0008).
- **Every multi-row write is one transaction** (`StatusCacheDao`), so a crash never leaves half a poll.
- **Everything is bounded per profile.** Adding a table means deciding what stops it growing.
- **No foreign key to profiles.** Deleting a profile clears its cache through `ProfileCleanup`; `CacheJanitor` sweeps orphans at startup. A new store keyed by profile id needs both.
- **`poll_attempt` is separate from `poll_meta`.** The second only knows about the last poll that worked. The first is what the latest one came to, written by `StatusRepository.refresh` after every attempt; without it, "unreachable" exists only in the memory of the process that polled.
- **Stale is `Staleness`, nowhere else.** Twice the profile's interval, used by the screens (`ProfileStatus.staleAfter`, set whenever a profile is refreshed or shown from the cache) and by the widget. A change to a profile's interval reaches an open screen at its next refresh.
- `StatusRepository` serves the cached result first and keeps `refreshing` true until the fresh result is on disk: "not refreshing" has to mean a new refresh would be accepted.

## What gets announced

`Announcer.decide(state, outcome, settings)` (`notify/`) is the whole of what a background poll is worth saying: given what was announced before and what the poll found, it returns what to post, what to take down and what to remember. The rules and their reasons are [ADR 0008](adr/0008-what-is-announced-and-where-that-is-remembered.md).

- **It has no Android and no clock in it, and has to stay that way.** How a notice looks, its channel and what a tap does belong to whatever posts it. A rule about *whether* to say something belongs here, with a test.
- **Its tests build a real `ProblemReport`** with `ProblemClassifier`, so handled, rolled-up and SOFT mean in the tests what they mean in the app.
- **A failed poll never touches `known`.** That is what keeps a failure from announcing a recovery.
- `AnnouncedStore` keeps the state in `status.db`. Losing it is survivable by design: the next poll is a first poll. Severities and outage causes are stored by name, and a name this version does not know is dropped on load.
- Adding a `StatusError` or `NagiosError` will not compile until `OutageCause.of` says whether it may pass by itself. Decide that, don't default it.

## Background polling

A profile is polled in the background exactly while its notifications are on. The pieces, all in `notify/`:

- `PollSchedule` follows the stored profiles and tells a `PollScheduler` what to schedule and cancel. It is the only place that does: the editor and the offer just save the profile. It starts with the application.
- `WorkManagerPollScheduler` is the real scheduler: one unique periodic job per profile, named from the profile's id, needing a network, enqueued with `UPDATE` so that opening the app does not restart every profile's cycle.
- `PollWorker` is what WorkManager runs. WorkManager constructs it, so it finds `BackgroundPoller` through a Hilt entry point. That is deliberate: it avoids `hilt-work` and a custom WorkManager configuration, and WorkManager starts itself in the default way.
- `BackgroundPoller.poll(profileId)` is everything a run does: the same `StatusRepository.refresh` the screens use, then `Announcer.decide`, then save. A job whose profile is gone or has notifications off cancels itself.
- A failed poll returns success to WorkManager on purpose. Its retry would poll more often than the person asked for; the failure is counted by `Announcer` instead.

Things to know:

- **Permissions.** `AndroidLocalNetworkTest` pins the full list the merged manifest asks for, WorkManager's included, and the README repeats it. WorkManager also declares `FOREGROUND_SERVICE`; the manifest removes it because the app runs no foreground work. If a WorkManager upgrade adds another, that test fails and the README needs a decision.
- **The notification permission** is asked for from the editor's switch and from the offer, never at launch and never from the background. `NotificationAccess` is the seam; screens raise the prompt through `rememberNotificationRequest`, the same `PermissionRequest` local network access uses.
- **Tests.** `PollScheduleTest` uses a fake scheduler and a real profile database. `WorkManagerPollSchedulerTest` uses WorkManager's test helpers to read back what was enqueued. `PollWorkerTest` is the only test that runs in the real `NagwatchApplication`, to show the worker can find its dependencies.
- **The profiles database changed (version 3).** `ProfileDatabase.MIGRATIONS` is the one list of migrations; the app and `ProfileMigrationTest` both open the database with it, and a test fails if a version has no migration in it.
- **`PollSchedule` is where "no longer watched" is acted on.** A profile that leaves background polling is cleared (`Notifier.clear`) and its announced memory forgotten (`AnnouncedStore.forget`), whichever way it left. The first look after the app starts sweeps (`Notifier.sweep`) everything for profiles that are not being watched. Do not clear from the editor or the delete button: they would miss the other route, and the sweep would be doing the real work.
- **A decision is delivered before it is saved.** If the app dies between the two, the next run says the same thing again, which replaces the same notification. The other order would lose it.
- **On a phone**, Android decides when a job runs, and it cannot be hurried: `adb shell cmd jobscheduler run` starts the job, and WorkManager ends it within a tenth of a second, because it will not run periodic work before its interval is up. So debug builds have a way of their own, `PollNowReceiver` (`app/src/debug`), which queues the same worker once:
  ```
  adb shell am broadcast -n net.pgmac.nagwatch.debug/net.pgmac.nagwatch.debug.PollNowReceiver --el profile <profile id>
  ```
  It asks for the `DUMP` permission, which adb's shell holds and no ordinary app can, and it is not in a release build. It does what the scheduled job does: a profile with notifications off is not polled. The "Updated" time on the Problems tab moves when the run has finished. The scheduled job itself can be seen with `adb shell dumpsys jobscheduler | grep -A12 'PollWorker'`: it is ready when `TIMING_DELAY` is no longer among its unsatisfied constraints.
- **"Needs a network" means one Android has validated as having internet.** A Wi-Fi network with a Nagios on it and no way out does not count, and the job waits.

## The widget

A Glance widget (`widget/`), one profile per widget, four layouts by size. Why it polls is ADR 0009.

- **What it shows** is `WidgetModel.of(profile, snapshot, attempt, now)`: one of five states (current, stale, unreachable, no data, profile gone), the four counts, the rows in the Problems tab's order, the "as of" time and the reason for a failure. It has no Android and no clock in it.
- **Old green is never shown as current.** Stale and unreachable keep the last numbers and are marked `isDemoted`; the layouts must draw that, not decide it. `counts` is null when nothing is known, which is not zero, and nothing may print it as zero.
- **Which layout** is `WidgetTier.of(width, height)`, in dp. `StatusWidget.sizeMode` lists the smallest size of each layout, a wide one of each but the badge (wide is where the four counts fit in one row) and a tall one for the short list; Glance composes each and Android shows whichever fits.
- **A layout is told its declared size, not its real one.** Under `SizeMode.Responsive`, `LocalSize` is the entry of `sizeMode` that was chosen, whatever room the launcher gave. A short list two rows of cells high drew two problems and half a widget of nothing until a taller short list was declared. Anything that should change with size needs a declared size where it changes.
- **Anything a layout does not set, it inherits from whatever was drawn there before.** Android redraws a widget by applying the new layout to the views it already has. A widget is a badge for a moment while it is being placed, and a badge's background is the state colour: counts and lists that set no background of their own came out solid red. Every layout sets its own background on its outermost view (`surface()`), and the same goes for any property one layout sets and another does not.
- **Padding is inside a view's background.** A gap between two coloured things is a `Spacer`, not padding.
- **A row that does not fit is cut off, silently.** Four counts across need about 250dp; a widget two or three cells wide showed two or three of them and nothing to say the rest were missing. Below `StatusWidget.WIDE` the counts go two by two (`Chips`), in every layout. Check anything laid out in a row at the narrowest width it can be given.
- **Drawing** is `StatusWidgetContent`. It reads `WidgetView` and decides nothing. Rows and the widget itself open the app through `NotificationTarget`, the same non-exported entry notifications use.
- **Which widget shows which profile** is `WidgetBindings`, kept in the app's settings store under `widget_<id>`. `watched` is the set of profiles with at least one widget; `PollSchedule` polls those as well as the ones with notifications on.
- **Redrawing.** `WidgetRefresher` is a `PollObserver`: `StatusRepository` tells it when any poll finishes. It bumps a counter that a widget already on screen is collecting, and asks Glance to update, which starts one that is not. Both, every time: from there it cannot tell which case it is. Android also redraws about every half hour (`updatePeriodMillis`), which is what turns a widget "out of date" when no poll can run.
- **Glance only runs `provideGlance` once per session.** Data read there goes stale if it is not also read inside `provideContent`; that is what the counter and `produceState` are for. Reading the cache only at the top would look right in a test and be wrong on a phone.
- **The configuration activity is exported**, because the launcher starts it. It takes a widget's number from the intent and nothing else, and only if `AppWidgetManager` says that number is one of this app's widgets. Do not let it accept a profile, a host or a URL from an intent.
- **Removing a widget** reaches `StatusWidgetReceiver.onDeleted`, which forgets the binding. In case Android could not tell the app, `WidgetRefresher.start` forgets every widget Android no longer lists, before the schedule is looked at.
- **Tests.** `WidgetModelTest`, `WidgetTierTest` are plain. `StatusWidgetContentTest` draws each layout in each state with Glance's unit-test library (`runGlanceAppWidgetUnitTest`): it sees text, tags and click actions, not pixels. `WidgetInAppTest` runs in the real application, like `PollWorkerTest`, for the parts Android constructs itself.
- **What no unit test sees**: colours, clipping, how a launcher sizes the widget, and whether a redraw actually arrives. Those are for a phone, and the four rules above were all found on one. A redraw can also arrive a few seconds after the poll that caused it; look twice before calling a widget wrong. A widget's content cannot be read back over adb; `adb shell dumpsys appwidget` lists the widgets and their sizes, and a screenshot of the home screen is the only way to see one.
- **Lint and `check` together.** After a source change, run `check` and the `assemble` tasks separately: together, lint can read a generated file while the release build is rewriting it and report "unexpected failure during lint analysis", which is not a finding.

## Notifications

`AndroidNotifier` (`notify/Notifier.kt`) turns a `Decision` into Android notifications. It decides nothing: whether to say something is `Announcer`'s, with its own tests.

- **Identity.** A problem's notification is `(tag, ID_PROBLEM)`, the tag made from the profile id, the host and the service, separated by line breaks (which a Nagios name cannot contain). The outage, the summary and the group's entry use the profile id as tag with their own ids. Posting to the same pair replaces; that is how worse, better and recovered work.
- **Channels are set once.** `NotifyChannel` lists them. Android ignores a change of importance to a channel that exists, by design, so changing a default means a new channel id and deleting the old one. Adding a channel is safe.
- **The group's entry.** Android shows a group only if it has a summary notification of its own and does not remove that when the last member goes, so `tidyGroup` posts it while there are members and cancels it after. **Android's list of what is showing lags** behind a cancel or a post, so `tidyGroup` is told what was just taken down and whether anything was just posted, and does not trust the list for those. Robolectric does not lag; the tests that cover this hand the notifier a stale list on purpose.
- **Critical notifications are a heads-up**, over whatever app is open, and tend to be swiped away within seconds. When checking on a phone, use warnings for anything that has to stay put, and read how a notification went from the event log: `adb logcat -d -b events | grep notification_cancel` (the number after the key is the reason: 1 a tap, 2 a swipe, 8 the app).
- **Silence.** `setSilent(true)` on a member of a group works, on Android 8 and later, by leaving the sound to the group's entry, which itself leaves it to the members. The tests read that back as `groupAlertBehavior`.
- **Posting needs the permission; taking down does not.** `deliver` cancels first and posts only if `NotificationPermission.isHeld()`. It uses the platform `NotificationManager`, with that check, so there is one place the rule lives.
- **Taps come in by a name other apps cannot use.** `MainActivity` is exported, because the launcher starts it, so any app can send it an intent. A notification's intent is therefore addressed to the activity alias `.notify.FromNotification`, which is `exported="false"`, and `NotificationTarget.from` returns null for any intent whose component is not exactly this app's package and that name. It also returns null for an intent Android is replaying from the recent apps list, so a tap is acted on once. Do not read a target from an intent any other way, and do not export the alias: either lets another app make Nagwatch switch profile and look up a host of its choosing.
- **Taps.** `NotificationTarget` is what a tap carries: a profile id and, optionally, a host and a service. It builds an explicit intent for that alias (`MainActivity` is `singleTop`, so an open app gets `onNewIntent` and is not opened twice) with a data URI that makes each target a distinct `PendingIntent`. `MainActivity` selects the profile and hands the target to `NagwatchNavHost`, which navigates through the ordinary routes. Pending intents are immutable.
- **Wording** is in `strings.xml` (`notify_*`, `channel_*`). An outage has one sentence per `OutageCause`; a test fails if two causes share one.
- **On a phone**, the app's own notification records can be read as text, without opening the shade:
  ```
  adb shell dumpsys notification --noredact | grep -A12 'pkg=net.pgmac.nagwatch.debug'
  ```

## Fuzzing

What a Nagios server sends back is the app's main untrusted input, so it is fuzzed with [Jazzer](https://github.com/CodeIntelligenceTesting/jazzer).

**What is covered.** Three targets, in `app/src/test/java/net/pgmac/nagwatch/fuzz`:

| Target | Takes arbitrary bytes through |
|---|---|
| `ResponseBody` | the step that turns a response's text into JSON or a `NagiosError` (`interpretResponseBody`) |
| `StatusParser` | hosts, services, server info, and `ProblemClassifier` after them |
| `AnnotationParser` | comments and downtimes |

The contract under test is "a result or a `NagiosError`, nothing else". The `ResponseBody` target also asserts about `repairInvalidEscapes`, which runs on everything a server sends: repairing twice is repairing once, and a repair never grows a body by more than its allowance (65,536 exact repairs, 256 KiB; past that it writes a same-length escape instead). An exception of any kind, one input taking over 10 seconds, or the heap (capped at 512 MB) running out is a finding. `ResilientListFetcher` is not covered: it is a conversation of several requests, not a function of one body.

**The targets are Kotlin.** Each is a few lines in `FuzzTargets.kt` that hand the bytes to a function in `FuzzEntryPoints`. They were Java at first, to earn credit from OpenSSF Scorecard's Fuzzing check, which looks for Jazzer in `*.java` files. It does not earn it here: Scorecard only examines languages at least a quarter the size of the repository's average one, this repository is almost entirely Kotlin, and Scorecard has no Kotlin pattern. That alert is dismissed as "won't fix" with this reason (#29). Do not add Java or ClusterFuzzLite for the score alone.

**Two ways it runs:**

- **On every build**, `FuzzCorpusTest` replays every sanitised fixture, the hand-made corpus (`app/src/test/resources/fuzz/corpus`) and every input that ever crashed a fuzzer (`app/src/test/resources/fuzz/crashes`) through all three entry points. It takes seconds and is part of `testDebugUnitTest`.
- **The fuzzer itself**, `./gradlew :app:fuzz` (every target) or `:app:fuzz<Target>`, for `-PfuzzSeconds` each (default 60). It is not part of `check`. Its working files are under `app/build/fuzz/<Target>`. CI runs it in `fuzz.yml`: 60 seconds per target on every pull request, 10 minutes per target weekly, and by hand. **It is not a required check:** it is random, and must not be able to block a merge that did not cause what it found.

**When it finds something:**

1. The input is written to `app/build/fuzz/<Target>/crashes/` (in CI, uploaded as the `fuzz-crash-<Target>` artifact), beside a `Crash_*.java` stand-alone reproducer.
2. Replay it: `./gradlew :app:fuzz<Target> -PfuzzReplay=<path to the file>`. The stack trace is printed.
3. Fix the code, not the target.
4. Copy the input into `app/src/test/resources/fuzz/crashes/` under a name that says what it was (`json-nesting-100k.json`, not `crash-3fa9...`). `FuzzCorpusTest` now keeps it fixed. Inputs there must contain nothing from a real Nagios: if a crash came from a captured response, reduce it to the minimum that reproduces it.

**Things learned setting it up**

- The JSON reader recurses once per level of nesting, and a stack overflow is an `Error`, not an exception: `NagiosApi` did not catch it and a body of about 100 KB of `[` would have taken the app down. Bodies nested over 32 deep (Nagios writes about 6) are now refused before parsing (`nestsDeeperThan`). The first thing the fuzz corpus test found.
- libFuzzer's resident-size limit is switched off: the JVM and Jazzer's native side sit near 2.7 GB before the first input, so it blamed the JVM. The heap cap does that job.
- A fuzzer that finds nothing proves nothing until it has been shown to find something. When changing the harness, plant a crash behind a condition no fixture reaches (a string compare in `StatusParser.check` worked, found in about 15 seconds), check the fuzzer finds it and the replay test fails with its input saved, then take it out.

**Licence.** Jazzer is Apache-2.0 and test-only: it is on a `fuzzer` configuration of its own, outside every classpath the app or its tests use, so it is in no APK and is not seen by Licensee. The targets compile against nothing from it.

## Local network access

Android 17 gates the local network behind `ACCESS_LOCAL_NETWORK`; the reasoning is ADR 0007. The pieces:

- `LocalAddresses` (`localnet/`) is the one place that decides whether an address looks local. It has no Android in it and takes its resolver as a parameter. Android's own ranges are in its comments, with the link; every range is tested at both edges.
- `LocalNetwork` is the service: `shouldAsk(host)` before a first load or a test, `likelyBlocking(host)` after a timeout. The second is what `NagiosApi` asks through the `LocalNetworkGuard` seam, so the Nagios client does not know about Android.
- `AndroidLocalNetworkPermission` answers "held" without asking below Android 17. `DataStoreLocalNetworkHistory` keeps two flags for the whole install: the app has explained, and Android has stopped offering the prompt.
- Screens ask through `rememberLocalNetworkRequest`, because both the prompt and the settings page need an Activity. The view models decide which one; they are handed the request, not an Activity.
- **Every fetch of a profile goes through `HomeViewModel.load`**, so none can get ahead of the question. Do not call `StatusRepository.refresh` from elsewhere in that view model.
- The system prompt, the settings page and `checkSelfPermission` at Android 17 cannot be exercised by Robolectric here (the unit tests run as Android 15). They are covered by the device check in the work record, on a phone running Android 17.
- To try it on a phone running Android 17, put the debug app back to "never asked":
  ```
  adb shell am force-stop net.pgmac.nagwatch.debug
  adb shell pm revoke net.pgmac.nagwatch.debug android.permission.ACCESS_LOCAL_NETWORK
  adb shell pm clear-permission-flags net.pgmac.nagwatch.debug android.permission.ACCESS_LOCAL_NETWORK user-set user-fixed
  adb shell run-as net.pgmac.nagwatch.debug rm -f files/datastore/settings.preferences_pb
  ```
  The flags are Android's own refusal count. **Not `pm reset-permissions`**: that resets every app on the phone. Deleting the settings file forgets that the app has explained, and which profile was selected.
- Android's own log says which button was pressed on its prompt: `adb logcat -d | grep GrantPermissionsViewModel` (`clickedButton=1` is Allow, `8` Don't allow, `16` a final Don't allow).
- A build for a phone must be the real one. A scratch manifest line and `pm grant` hide exactly what this feature is about, and a grant survives a reinstall.

## Network rules

All traffic to Nagios goes through one OkHttp interceptor (`ConnectionInterceptor`) that refuses `http://` unless the profile opted in, never sends Cloudflare Access credentials over `http://`, and only sends credentials to the configured origin. Redirects are not followed. Any new HTTP client must be derived from the per-profile client so it inherits these rules; see [ADR 0004](adr/0004-cleartext-permitted-in-manifest-enforced-in-app.md).

## CI

`.github/workflows/ci.yml` has three jobs, `build`, `lint` and `foss`, on GitHub-hosted runners. **Those job names are required status checks** on `main`, set in `pgmac-net/terraform-github`. Rename one there first, or every merge blocks.

Actions are pinned by commit SHA and kept current by Renovate. The JDK that CI installs (`java-version`) must match `jvmToolchain()` in `app/build.gradle.kts`; Renovate is told not to bump its major, so moving to a newer JDK is one change to both. `dependency-submission.yml` runs on each push to `main`, and `scorecard.yml` weekly and on demand; neither is required. Their findings appear under the repository's Security tab. Scorecard is deliberately not run on push: it only accepts that the Gradle wrapper is verified if the newest commit already has a finished CI run, and on a push it starts alongside CI and looks too early, so the alert would reopen on every merge. To see its verdict on a change straight away, run it by hand once CI on `main` has finished (`gh workflow run scorecard.yml`).


Beside those three, and not required for a merge:

- **CodeQL** (`codeql.yml`) analyses the Kotlin, the workflows and the scripts on every pull request, on `main`, and weekly. Kotlin is analysed by watching a real compilation, so that job builds the app with the daemon and the build cache off: a compilation restored from a cache is one CodeQL never saw. Downloaded libraries and Gradle itself are still read from the cache. It is not a required check because CodeQL's support for a new Kotlin release can lag the release; if the Kotlin job fails after a Kotlin upgrade, that is the first thing to check.
- **The Gradle wrapper is verified** in the `build` job. `gradle-wrapper.jar` is a binary in the repository that runs on every build; it is checked against the checksums Gradle publishes before anything runs it.

## Layout

```
app/                     the application module
build-logic/             Gradle plugin with this repo's own checks (FOSS denylist, SPDX)
config/                  detekt config, FOSS denylist
gradle/libs.versions.toml  version catalog
scripts/                 CI helper scripts
docs/                    design, ADRs, this file
```
