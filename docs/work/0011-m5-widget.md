# M5: the home-screen widget

Ticket: pgmac-net/nagios-android#7. Two pull requests: #50 (what a widget needs to know) and #51 (the widget), the second stacked on the first.

## What was built

A home-screen widget for one profile. Resizing it changes what it is:

| Size | Shows |
|---|---|
| **Badge**, about one cell | A colour for the worst unhandled state and the number of unhandled problems. A tick when there are none |
| **Counts**, one row of cells | DOWN / CRIT / WARN / UNKN with their numbers: in one row from four cells wide, with the time; two by two below that |
| **Short list**, two rows or so | The counts and two to four problems: state, host / service, how long |
| **Full list**, three rows and up | The profile's name, Refresh, the counts, a scrolling list with a line of each plugin's output |

- **#50** added what none of that existed to draw from: when a profile was last polled and how it went (`poll_attempt` in the disposable status database), one rule for "stale" shared by the app's banners and the widget (`Staleness`: older than twice the profile's interval), and `WidgetModel`, which turns a profile, its cached snapshot and its last attempt into one of five states with nothing of Android in it. The interval moved to an always-visible "Background checks" part of the profile editor, because it is no longer only about notifications.
- **#51** added the widget (Glance), which widget shows which profile (`WidgetBindings`), the configuration screen, polling for a profile that has a widget, redrawing when any poll finishes, and Refresh.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| When a profile is polled in the background | Notifications on **or** a widget. A widget never turns notifications on | Placing a widget is asking for fresh numbers; it is not asking to be interrupted. ADR 0009 |
| Old or unconfirmed data | Kept, drawn grey, with "as of" and the reason. Never-loaded says "No data yet", never zero | The numbers are still the best anyone knows; what must not happen is old green read as current |
| Android below 12 | One widget for every version, a fixed colour scheme below 12, **untested on 8 to 11** and the README says so | No emulator image here; a second widget implementation for versions nobody can test would be worse |
| Where the interval lives | Its own part of the profile editor, always visible | It now governs widgets too |
| The phone check | The maintainer places and resizes widgets on an otherwise empty home-screen page and does the taps; I screenshot that page only, never tap the home screen, delete the screenshots after | A widget cannot be read back over adb, and a home screen is not the app's |

## Deviations from the plan

- `glance-material3` was planned and not added: the default theme already gives Material You from Android 12 and a fixed scheme below it.
- The glossary gained *polled* and *announcing*, not *watched*: the plan's one word was about to mean two things.
- **The counts are two by two below four cells wide.** The plan and the design had them in one row at every width. The phone showed that does not fit (below).
- **A list with nothing in it says "Nothing needs attention".** Not in the plan; four zeros over a blank space looked like a widget that had not loaded.

## What the phone found

Everything here passed every unit test first. Glance's test library sees text, tags and click actions, not pixels.

1. **Counts and both lists were solid red.** Android redraws a widget by applying the new layout to the views it already has, and a property the new layout does not set keeps its old value. A widget is a badge for a moment while it is being placed, and a badge's background is the state colour. Every layout now sets its own background. The rule: *anything a layout does not set, it inherits from whatever was drawn there before.*
2. **The count chips touched.** Padding is inside a view's background, so it only made each chip wider. Gaps are spacers.
3. **A short list two rows of cells high showed two problems and half a widget of nothing.** Under `SizeMode.Responsive` a layout is told the size it was declared for, not the room it has. A taller short list is now declared and draws four.
4. **Four counts across were cut off below four cells wide**, found by resizing: two cells showed two kinds, three showed three, with nothing to say the rest were missing. A count that is not drawn reads as a count of nothing. Two by two below 270dp.
5. A redraw can arrive a few seconds after the poll that caused it. Not a fault; worth knowing before calling a widget wrong.

## How it was checked

- 899 unit tests (20 of them live, skipped without a Nagios), `./gradlew check`, both builds, the FOSS self-test.
- Deliberate breaks, all caught in the end: 45 for #50, and for #51 51, then 8 and 10 for the phone fixes. Five were not caught at first and got tests: two counts under each other's names (the test data had one of each), the Refresh poll losing its profile, a pattern that had to be redone, and a taller size that could become a full list unnoticed.
- **On a phone** (Pixel 8a, Android 17, the test profile against the throwaway test Nagios), at 1x1, 2x1, 3x1, 4x1, 4x2 and 4x3:

| Check | Result |
|---|---|
| Problems, current | Pass at every size, after fixes 1 to 4 |
| Everything OK | Pass: tick on the badge, plain zeros, the all-clear line |
| Nagios unreachable | Pass: grey, numbers kept, "Can't reach Nagios · as of" |
| Back to current | Pass |
| Placing a widget on a profile with notifications off starts its polling | Pass: a job at the profile's interval, nothing announced |
| Tap on the badge | Pass: that profile's Problems list |
| Tap on a row | Pass: that service's detail |
| Refresh | Pass: one poll queued and run within a second, every widget redrawn |
| Resizing between tiers | Pass (badge to counts), and it is how finding 4 was made |
| The app replaced and restarted under the widgets | Pass, each time a fix was installed |
| Removing the last widget | Pass: the profile's job gone, the other profile's untouched |

## Not checked, and said so

- **Stale** ("Out of date") on a phone: it needs twice the interval to pass with no poll, at least half an hour. Unit tests only.
- **Android 8 to 11**: no emulator image, and the phone is Android 17.
- **A tap on a count** as its own step: the counts have no tap target of their own, the whole widget does, and that tap passed on the badge.
- **A widget whose profile was deleted**, and the configuration screen with no profiles: unit tests only.
- **Other launchers.** Cell sizes differ; the breakpoints are in dp and fall back to the layout that fits, but only the Pixel launcher was seen.
- **Two-digit counts at exactly two cells wide**: two chips of the widest kind may be tight there. Not seen; the test Nagios has three problems.

## Follow-ups

- Reports from Android 8 to 11 (the README asks for them).
