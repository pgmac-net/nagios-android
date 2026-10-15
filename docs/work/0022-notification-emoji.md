# Notification emoji

Ticket: pgmac-net/nagios-android#81.

## What was built

- A leading coloured circle in the title of problem, recovery and outage notifications (`Notifier.kt`): 🔴 down/unreachable/critical, 🟡 warning, 🟠 unknown, 🟢 recovered, 📡 outage.
- Summary and group entry left plain. Button redraws keep the emoji because the request carries the finished title.
- Tests: title assertions in `AndroidNotifierTest` and `NotificationButtonsTest`; one new test for warning/unknown and the plain summary and group entry.

## Decisions

| Decision | Outcome | Why |
|---|---|---|
| Emoji set | Circles matching `StateColors` | Same signal as chips and the tint; words still say the state |
| Outage | 📡 | No state colour is true of it, but a fixed symbol helps scanning |
| Where defined | Code constants, no setting | Language-neutral; a translation must not drop it |
| Position | Leading | Visible when the title truncates |

## Deviations from the plan

- The spike (coloured title text, coloured large icon) was **not run**: no phone or emulator was available to the session. Nothing was shipped for either.

## Follow-ups

- Run the spike on the Pixel 8a via `SampleNotificationsReceiver` if wanted; a large icon is the likelier of the two.
- Look at the emoji in the shade on the phone.
