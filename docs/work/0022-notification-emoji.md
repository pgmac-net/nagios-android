# Notification emoji

Ticket: pgmac-net/nagios-android#81. Large icon split to #91.

## What was built

- A leading emoji in the title of problem, recovery and outage notifications, from a set chosen in a new **Settings** screen (profile menu, next to Manage profiles). App-wide, kept in the settings DataStore as `notification_emoji_set`, read when a notification is posted, so a change shows on the next one.
- Sets: None, Coloured circles (default), Signs, Coloured squares, Faces. Defined in `res/values/emoji_sets.xml` (`translatable="false"`), read by `EmojiSet`/`EmojiTable`; no emoji in Kotlin.
- Summary and group entry left plain. Button redraws keep the emoji because the request carries the finished title.
- A set that has none adds no prefix and no stray space; an unknown stored id is the default.
- Debug `SampleNotificationsReceiver` takes `--es set <id>` to draw the samples with a set.
- Tests: set mappings, None, recovery/outage, settings round trip and unknown id, the screen (offers every set with a preview, pressing chooses).

## Decisions

| Decision | Outcome | Why |
|---|---|---|
| Where the choice lives | App-wide, new Settings screen | How a notification looks is the person's, as the sounds are; design.md had reserved a Settings tab for this |
| Emoji location | Resource table, not Kotlin | Owner asked for it not hardcoded; one place to add a set |
| Default | Coloured circles | Matches the app's state colours |
| Signs set | Differ by shape | Reads without telling colours apart |
| Outage | 📡 in every set but None | No state colour is true of it |
| Custom set (type your own) | Not built | Offered, not chosen |
| Large icon and its setting | #91 | Separate signal, separate setting |

## Deviations from the plan

- First plan (#90 as first pushed) hardcoded circles; replaced by this on request, same branch and PR.
- The first version of the screen was not scrollable; fixed when a test found the last set off-screen on a small display.

## Spike results (Pixel 8a, Android 17)

| Option | Result |
|---|---|
| Emoji in the title | Drawn in colour, alone and grouped. Shipped |
| `ForegroundColorSpan` on the title | **Colour stripped**: normal text colour. Bold survived |
| `setColorized(true)` with `DecoratedCustomViewStyle` | **Ignored**: no tinted background |
| `setLargeIcon` | **Drawn**, full colour, untinted, in place of the launcher icon. Becomes #91 |

## Follow-ups

- #91: large icon per state, with its own setting on the Settings screen.
- A Custom set (one emoji per state typed in) if the five are not enough.
- Look at the Settings screen and each set in the shade on the phone (the phone was locked for the last check).
