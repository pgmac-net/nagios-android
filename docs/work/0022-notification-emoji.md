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

- None. The spike was run afterwards, on the Pixel 8a (Android 17), once the phone was connected. Nothing was shipped for either alternative.

## Spike results (Pixel 8a, Android 17)

| Option | Result |
|---|---|
| Emoji in the title | Drawn in colour, alone and grouped. Shipped |
| `ForegroundColorSpan` on the title | **Colour stripped**: title drawn in the normal text colour. Bold from a `StyleSpan` survived |
| `setColorized(true)` with `DecoratedCustomViewStyle` | **Ignored**: no tinted background. Colorized is reserved for foreground-service and ongoing media-style notifications |
| `setLargeIcon` (red square, white dot) | **Drawn**, in full colour, untinted, in place of the launcher icon at the left. Works on this phone, where the small-icon tint does not |

A large icon is the one real alternative: it would need one drawn bitmap per state (or a tinted vector rendered to a bitmap), and it replaces the launcher icon in the shade for those notifications. Not built; the emoji already carries the signal.

## Follow-ups

- A large icon per state, if the emoji is not enough (own ticket).
- Seen on the phone: the emoji draw in colour in the shade, alone and in the group.
