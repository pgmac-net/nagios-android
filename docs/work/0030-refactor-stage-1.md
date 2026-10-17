# Refactor, stage 1: one state mapping, smaller files

Ticket: pgmac-net/nagios-android#114. Three stacked pull requests: #116, #117, #118. Decision recorded in [ADR 0014](../adr/0014-an-unreachable-host-is-shown-and-notified-as-unknown.md); the Problem entry in `CONTEXT.md` gained a sentence. Stage 2, splitting `:app` into Gradle modules, is pgmac-net/nagios-android#115.

## What was done

- **#116: one mapping from state to colour and word.** `ui/theme/StatePresentation.kt` (`StateColors.of(Severity|HostState|ServiceState)`, `shortWord()`) replaces five hand-written `when`s, in the Problems badge, `HostStyle`/`ServiceStyle`, the host map, the widget and the notification tint and large icon. `PollInterval.label()` has one copy.
- **UNREACHABLE is unknown.** The copies had drifted: the screens drew it orange, the notifications and widget red, on "Down and critical". Now it is orange everywhere, 🟠/🟧 in the circles and squares emoji sets, on the Unknown channel, and so never loud. A summary of only unreachable hosts is not "critical". The sort order and the hosts filter are unchanged.
- **#117: the four largest screens split** along their existing private composables. `DetailScreen` (669 lines) became 4 files, `ProfileEditorScreen` (559) 3, `BrowseScreens` (433) 4, and `HomeScreen` (454) 3. This is a pure move.
- **#118: Notifier and the widget content split.** `Notifier` (548) became the interface and channels (`Notifier.kt`), posting (`AndroidNotifier.kt`), building (`NotificationFactory.kt`) and wording (`NotificationWords.kt`). `StatusWidgetContent` (505) became layouts, `WidgetRows` and `WidgetStyle`, a pure move.

## Decisions (from the grilling session)

| Decision | Outcome |
|---|---|
| Scope | Both stages, staged: cleanup inside `:app` now, module split as a follow-up ticket (#115), grilled when picked up |
| UNREACHABLE colour | Orange (unknown) everywhere. The other choice, red everywhere as design.md said, was turned down |
| UNREACHABLE channel | Unknown, not Down and critical |
| Loudness | Stays tied to the channel (ADR 0013), so unreachable is no longer loud |
| Sort order | Unchanged: DOWN, UNREACHABLE, then the services |
| Notification titles | Kept apart from the screens' short words ("CRITICAL: %s" against "CRIT"): different messages, not a duplicate |
| Shipping | Three PRs, one per concern, merged in order |

## How the splits were checked

The splits in #117 and the widget part of #118 were made by a script that moves whole top-level declarations, makes moved `private` ones `internal`, and prunes imports. For each, the sorted non-import lines before and after were compared, ignoring `private`/`internal`. They were identical apart from ktlint wrapping one signature that had grown. The `Notifier` split moved members into a new class, so it relies on the existing `AndroidNotifierTest`, which is unchanged and passes.

## Deviations from the plan

- **The UNREACHABLE question was asked twice.** At first only the Problems badge looked orange, so the question was framed as "the Problems badge turns red". Then `HostStyle` turned out to draw it orange in every screen, and the question was asked again with that in front of the maintainer. The answer changed from red to orange, and the channel and loudness questions followed from it.
- **Extra files beyond the plan:** `BrowseTags.kt` (detekt `MatchingDeclarationName` fires once `BrowseTags` is the only class left in `BrowseScreens.kt`), and `ProfileSwitcher.kt` and `HomeEmptyStates.kt` as named files for Home's parts.
- **The widget's layout constants** (`PAD`, `GAP`, sizes and so on) and `text()`, `open()` are now `internal` to the widget package, where they were file-private, because the rows use them too.
- **The phone check** of the UNREACHABLE change on the Pixel 8a was not done: the phone was disconnected by then.
- **Implemented on Opus 5.5**, not Fable 5: the model switch was declined.

## Tests

- `StatePresentationTest`: every severity is drawn as its state, in both themes; unreachable is unknown.
- `AndroidNotifierTest`: unreachable's channel, tint and emoji; unreachable is never loud.
- `AnnouncerTest`: a summary of unreachable hosts is not critical.
- `./gradlew check assembleDebug` green for each PR.
