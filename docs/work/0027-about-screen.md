# About screen

Ticket: pgmac-net/nagios-android#103.

## What was built

An **About** item in the profile menu, under Settings, opening three screens:

- **About**: icon, name, version (versionName and versionCode, "debug build" on a debug build), `© 2026 Paul Macdonnell and contributors`, the GPL-3.0-or-later and no-warranty notice, a privacy line, the Nagios trademark line, links to the source, the issues and the security policy, Nagwatch's own licence, and the list of open-source licences with a count.
- **Open-source licences**: every library in the APK, sorted by name, with its coordinates, version and licence. Tap one to read the licence.
- **Licence text**: the full text, scrollable and selectable.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Where it lives | Profile menu, under Settings | The menu is shown with no profile too, so About is always reachable |
| Where the library list comes from | Licensee's report, bundled into the APK (`bundleAndroidAsset`) | Always matches what shipped; no new dependency; already gated by the allowlist |
| Licence texts | Apache-2.0, BSD-3-Clause and GPL-3.0 bundled in full; a unit test fails when a licence in the report has none | Works offline, and meets Apache section 4(a) and BSD's reproduce-the-notice term properly |
| Extra content | GPL notice and source link, Nagios trademark line, privacy line, issue and security links | GPL asks interactive programs to say this; the trademark and privacy lines are already in the README and design |
| Copyright line | `© 2026 Paul Macdonnell and contributors`; not added to source headers | The repo had no holder named anywhere |
| Screens | About, list, text. No search or grouping | 155 rows in two licences; not worth the code |

## Deviations from the plan

- The plan said to read the report with kotlinx.serialization types. The serialization compiler plugin is not applied in this module (the rest of the app reads `JsonElement`s), so `Libraries` does the same.
- The dependency report is read from `app/cash/licensee/artifacts.json` in the assets, the path Licensee uses.

## Verification

The three screens were checked on a phone (debug build): menu entry, About, the licences list, and the Apache and GPL texts. That showed licence text wrapping badly, so it now keeps its lines and scrolls sideways. They are also covered by Robolectric Compose tests, and the release APK was checked to hold the report and the three licence texts after R8 and resource shrinking.
