# Android 17: local network access

Ticket: pgmac-net/nagios-android#35 (a bug found during the M3 device check). One pull request: #38.

## What was wrong

On Android 17 an app that targets SDK 37 needs `ACCESS_LOCAL_NETWORK` ("Nearby devices") to reach an address on the local network. Nagwatch declared only `INTERNET`, so every request to a Nagios on the LAN timed out, and the app said "The server did not answer in time". Nothing told the app why: Android drops the connection.

## What was built

- The permission is declared. The app asks for it the first time a profile whose address *looks local* is tested or loaded, with an explanation of its own first (**Continue** / **Not now**). It asks by itself once per install.
- A refusal never stops a request: a Nagios reached over a VPN needs no permission, and the app cannot tell the paths apart.
- A timeout to a local-looking address without the permission is reported as its own error, worded "Android may be blocking Nagwatch…", with a button: Android's prompt, or the app's settings page once Android has stopped showing it. After a grant the request is tried again.
- "Looks local" is Android's published list of ranges plus `.local` names, in one pure function tested at the edges of every range.
- Every fetch of a profile goes through one function, so none can get ahead of the question.

Reasoning and alternatives: ADR 0007.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| When to ask | When needed, from a screen, with the app's own sentence first | Launch-time asks everyone; after-failure makes every LAN user's first experience a timeout |
| After a refusal | Try anyway; explain only if it fails | Fail-fast would break the VPN user |
| How often | Once per install by itself, then only the button; "Not now" does not spend one of Android's two refusals | Android's prompt wording is alarming and it stops after two refusals |
| Device check | The maintainer taps Android's prompt; everything else over `adb`, on the real build | A scratch manifest line hides what the feature is about |

## Found on the phone (Pixel 8a, Android 17)

The unit tests run as Android 15, so this was the only place the real behaviour could be seen.

- **First time, Allow:** the sentence over "Loading…" with nothing fetched behind it, then Android's prompt, then the profile loaded. A second start asked nothing.
- **Don't allow, once:** the request still went ahead. The maintainer's own profile (which looks local) loaded anyway, so it does not take the local network; they were asked once for nothing. The LAN test Nagios timed out, as it did for the app's own user from the shell, and the error appeared.
- **The error had no button when earlier data was on screen.** The failure was a banner, and the button was only on the full-screen error. A profile that worked before Android 17, or after the permission was revoked, shows the banner. Fixed in this PR, with tests, on Problems, Hosts and Services.
- **Don't allow, twice:** the second refusal is final (the permission becomes user-fixed). The button then opened the system Settings app; I confirmed it from the foreground activity and did not look at the page.
- **"Not now":** no prompt, the profile loaded, and nothing was asked again.
- **Allow from the button, after an error:** the profile refreshed by itself and the banner went.
- **The editor:** "Test connection" with Android refusing gave the same wording and the button.

## How it was checked

- Unit and screen tests for every range edge, every decision and every screen state; a test that the manifest asks Android for `INTERNET` and `ACCESS_LOCAL_NETWORK` and nothing else.
- 36 deliberate breakages of the main code: 30 caught at once; 3 survived (1 dead code, removed; 2 gaps in my tests, now covered); 3 did not apply because the search text missed, and were re-run. Then 4 more for the banner button, all caught. One "catch" was a compile error, not a test failure, and was redone.
- CI found a real race in my own test (DataStore releases its file asynchronously); fixed.

## Not verified, and known limits

- **Once, the LAN profile reported "Nothing is accepting connections" (a refusal, not a timeout)** on its first request after reinstalling the app, with the server up. Later attempts timed out as expected. The app only blames Android for a timeout, so that one attempt gave the ordinary refused text. The cause is not known.
- **The editor's first-time explanation** was not seen on the phone: the home screen's question always comes first, because the maintainer's own profile also looks local. It is covered by tests.
- **A profile on a public address**, which should never ask, was not tried on the phone for the same reason.
- Android 17's own count (two refusals) is as observed on one phone.
- No Android below 17 was tried: there the permission is never asked for.

## Docs

ADR 0007; README (what the app asks Android for); `docs/design.md`; `docs/development.md` ("Local network access", including how to reset the debug app on a phone and read Android's own log of which button was pressed); `CONTEXT.md`.
