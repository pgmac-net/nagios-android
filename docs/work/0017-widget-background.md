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

## Looked at on a phone

Pixel 8a, Android 17, dark theme, debug build, two widgets placed by the maintainer through the new setup screen:

- **1x1 at 100%:** solid amber (the worst problem was a warning), the number 3 in the badge's own ink. As before this work.
- **2x1 at 50%:** the wallpaper plainly visible through it, a faint amber wash, all four counts readable.
- **Reconfigured from the home screen**, the setup screen opening on each widget's setting with Save.
- **1x1 at 20%:** a faint amber panel with the wallpaper through it, and the 3 drawn in amber instead of the badge's ink.
- **2x1 at 0%:** no background at all; the chips and the grey counts on the wallpaper, readable on this one.

## Not verified

- **A stale or unreachable widget** on a phone. A background check waits for a network rather than failing, so one could not be made without breaking the profile's connection. The 60% floor and the stale widget's other signs are covered by unit tests only.
- **The light theme**, and **Android 11 and older** (no Material You, no reconfigure).
- **Busy or bright wallpaper** at low settings: readability there is the owner's call, by design (no floor).

## Noticed, not changed

- At 2x1 the four counts sit two by two and the last is cut to "UNKN…". That is the existing narrow layout, not this work.
