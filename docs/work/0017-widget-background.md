# Widget background from transparent to opaque

Ticket: pgmac-net/nagios-android#65, from the ideas in pgmac-net/homelabia#210.

## What was built

- **Each widget has a background opacity**, 0 to 100 percent, kept beside its profile. New and existing widgets start at 100%.
- **The background is the theme's surface, washed with the worst state's colour**, at that opacity. Green when all is well, grey when stale, no wash when nothing is known.
- **The 1x1 badge's colour fades the same way.** Below 40% its number and icon take the state's colour instead of white.
- **A stale or unreachable widget is never drawn below 60%.**
- **The setup screen** now selects a profile instead of finishing on the tap, adds a Background slider (in fives) with a preview, and keeps both with Add widget (new) or Save (reconfiguring).

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| What the slider controls | Tinted surface, one opacity slider | A solid state colour behind red and amber words cannot be read; two sliders is two things to understand |
| Per widget or for all | Per widget, on its setup screen | A widget on a busy wallpaper and one on a plain page can differ; the app has no settings page |
| Readability and stale | No floor; stale held at 60% grey | What was asked for, with the one case where fading would hide something that matters |
| The badge | Its fill takes the opacity; faint ink switches colour | One setting means the same at every size |

## Deviations from the plan

- **Implemented on Opus 5.5**, not Sonnet as the plan recorded: the maintainer chose to continue.
- **The outer box is now transparent.** Not in the plan. It and each layout both drew the background, which at anything below 100% would have stacked (50% over 50% is 75%).
- **The preview is the background only**, over stripes, with a line of sample text, and not a drawing of the widget: a phone screen cannot draw a Glance widget.

## What was found, and by what

- **Eight-bit alpha.** A test expecting 0.5 got 0.502: a colour's alpha is kept in eight bits. The tests allow for it.
- **The widget's own surface is the system's**, Material You on Android 12 and later, so it cannot be a fixed colour here. It is read back for light and for dark from the context, then washed.

## Not verified

- **Not seen on a phone yet** at the time of writing; see the pull request for what was looked at.
- **Android 11 and older**, where there is no Material You and no reconfigure.
