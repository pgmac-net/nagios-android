# Debug build: in-app indicator

Ticket: pgmac-net/nagios-android#105.

## What was built

A debug build now looks like one inside the app, in the debug icon's amber (`#FF8F00`):

- a **3 dp frame** round the whole window, drawn over everything, taking no touch and moving no content;
- the **status bar** painted amber, with dark icons (white on amber is about 2.2:1; black about 9:1);
- every **top bar** amber with black title, icons and buttons, in light and dark theme, scrolled or not.

The signed release is unchanged.

## How

`DebugChrome` exists twice: in `src/debug` (the amber) and in a new `src/release` (does nothing). `main` calls it from `MainActivity` and `WidgetConfigActivity` (frame, status-bar icons) and through a new `NagwatchTopAppBar` that all 11 top bars now use. No `BuildConfig.DEBUG` branches in `main`, and the release APK holds none of the drawing code (checked in its dex).

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Border | Overlay, not inset | Nothing moves, so debug and release screens line up |
| Status bar | The overlay paints it; dark icons forced; navigation bar left alone | Does not depend on each top bar; the frame already marks the bottom |
| Top bar | One amber for light and dark; black content; Home's profile button too | Matches the icon and stays readable |
| Keeping release clean | Two `DebugChrome` copies and a release test | The release build carries none of it, rather than relying on R8 |

## Deviations from the plan

- `NagwatchTopAppBar` also wraps the bar so buttons in it (Cancel, the profile name) are black: they take the theme's primary colour, which is hard to read on amber. The plan's separate title-colour helper was not needed.
- The release unit test task is off by default in this Android Gradle Plugin version. It is switched on in `app/build.gradle.kts` and filtered to `DebugChromeTest`, because the rest of the suite is written against debug (the Compose test activity is a debug-only library) and 249 of its tests fail on release. The release test registers that activity itself so the release manifest does not gain it.

## Verification

- `./gradlew check` passes, including `testReleaseUnitTest` for the release chrome test; CI runs it too.
- Debug build on a phone in light and dark theme: Home and About show the frame, amber status bar with dark icons, and amber top bar.
- Release APK's dex has no `DebugChrome` or `debug_chrome` strings.
- Not checked: the widget configuration screen on a phone, and the graph viewer's full-screen mode.
