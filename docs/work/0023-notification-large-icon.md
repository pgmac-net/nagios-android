# Notification large icon

Ticket: pgmac-net/nagios-android#91. Follows the emoji sets (#81, work record 0022).

## What was built

- `LargeIcon.render`: a circle in the state's colour with a vector glyph tinted `onState`, drawn into a bitmap when the notification is posted (about 64dp at the screen's density).
- On problems (new, worse, updated, button redraws), recoveries and the outage. Not the summary or the group's entry.
- Outage: the `pending` grey circle with a new glyph, `ic_no_signal.xml`, a signal with a slash.
- A **Coloured picture** switch on the Settings screen under the emoji sets, stored as `notification_large_icon`, default on, read when posting.
- Debug `SampleNotificationsReceiver`: `--ez large false`, and a sample outage so every kind can be seen.
- Tests: with the setting on, which have one and which do not; off; the drawn pixel (native graphics); the setting's default and round trip; the switch.

## Decisions

| Decision | Outcome | Why |
|---|---|---|
| Look | Circle plus the app's glyph | Same shapes as the small icon, so it reads as Nagwatch |
| Default | On | On the Pixel 8a the tint never shows, so this is the colour signal |
| Glyph colour | `onState` | White on the light colours, black on the dark ones, as the chips do |
| Outage | Grey circle, new glyph | No state colour is true of it; matches the 📡 in the emoji sets |
| Rendering | At post time from vectors | Follows the theme; nothing shipped |

## Deviations from the plan

- None.

## What was found, and by what

- On the Pixel 8a (Android 17) a lone notification draws the picture at the right, and a notification inside a group draws it at the left in place of the app icon. The spike for #81 had seen only the second. Seen in light and dark mode, and with the setting off (no picture).
- lint's `UseKtx` asked for `createBitmap` from KTX.

## Follow-ups

- Wear OS look is not tried.
- A Custom emoji set is still open (see 0022).
