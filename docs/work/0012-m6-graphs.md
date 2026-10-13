# M6: nagiosgraph graphs in the app

Ticket: pgmac-net/nagios-android#8. Two pull requests: #53 (what a graph needs) and #55 (the graph on screen), the second stacked on the first. Host graphs are a follow-up, #54.

## What was built

- **A Graph section on service detail**: a chip for each of day, week, month and year, nagiosgraph's own picture on white, and "Open graphs in browser". With no graph it draws nothing at all.
- **A viewer with the screen to itself**, on its side while it is open, with its own larger picture. Pinch to four times and double-tap, both about the fingers; drag to the picture's edges and no further.
- **Two settings on a profile**: "Graphs", on by default, and an optional graph path. `profiles.db` went from 3 to 4.
- **Underneath** (#53): `NagiosApi.image`, the rule that says whether bytes are a graph (`GraphRules`, `PngHeader`), the path rule (`GraphPath`), `GraphClient`, and `GraphRepository` with a memory that lasts the session and is never written anywhere.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Coil, or fetch it ourselves | No Coil. The app's own client, its own byte cap, the platform's decoder after the header is checked | One path for credentials; no disk cache of pictures of someone's infrastructure; no dependency for one picture on one screen |
| How "no graph" is decided | From the picture: at least as large as the plot asked for, and not much larger | nagiosgraph answers 200 `image/png` with a picture of the error text when it has nothing to draw |
| The profile settings | On by default; path blank (the CGI directory already found) or a path on the same server. Never a URL | A server without nagiosgraph costs one request a session. Credentials go to one origin |
| What is asked for and shown | Four periods; a plot sized in dp; inline and a larger one for the viewer; the server's colours | rrdtool draws text at a fixed pixel size, so a picture at a phone's resolution cannot be read |
| Hosts | No graph. Follow-up #54 | nagiosgraph files a host's own check under whatever name the administrator chose |
| The phone check | The test profile only; I drive inside the app; the maintainer does the gestures adb cannot | A graph of a real server has real names in it |

## Deviations from the plan

- All three of the ticket's particulars changed, each by decision: no Coil, no `{base}` template, no host graph. ADR 0010 has the reasons.
- The graph is fetched alongside the status, not after it. Neither waits for the other, which was the point of "after".
- **Added on the phone**: the screen is stood up again when the viewer closes; the period is the profile's on every screen; a failure that may pass leaves the picture where it is; the picture is asked for a sixth wider than the room; zooming is about the fingers (asked for by the maintainer during the check).

## What was found, and by what

| Found by | What |
|---|---|
| Reading nagiosgraph's source, then measuring | "No graph" is a 200 with a picture; the server draws any size asked for; `rrdopts` goes to rrdtool as it stands |
| The fuzz target, in its first second | A graph path accepted as typed (200 characters) and refused as stored (201, with its last slash): a saved profile that could not be saved again |
| The commit security review | A user and password typed into a profile's address would have been carried into the link handed to a browser |
| Running every live test on the changed test Nagios | The test Nagios said "ready" while checks were pending; one live test had been passing by the clock |
| A layout test | The viewer's period chips, in the app bar, covered Back in a narrow window |
| CI | Three view-model tests that waited on a `StateFlow` with a condition that also read a counter outside it. Passed on a fast machine, timed out on a slow one. Reproduced by running on one CPU core |
| Mutation runs | Tests that asserted "nothing happened" before anything could have; a picture kept after its service was ruled out |
| The phone | The five things below |

## What the phone found

Everything here had passed every test.

1. **The screen stayed on its side after the viewer closed.** The app had let go of its request correctly. A phone lying flat gives Android no tilt to turn it back by. The maintainer turned it back by hand, which is how it was confirmed. Now a screen that was upright is asked to be upright, then let go.
2. **A period chosen in the viewer was not the detail screen's.** Two view models, one remembered period, and only one of them told. Seen in the test server's access log: the detail screen asked for its old period.
3. **With the server stopped, choosing a period not yet fetched emptied the whole section**, chips and all, and left the profile on a period that could not be shown.
4. **The legend was cut off.** At exactly the room's width a phone got a 240-pixel plot, and rrdtool cut the legend part-way through its four numbers. A sixth wider and scaled down, it fits and is sharper.
5. **The viewer's title said only the service**, and its chips, in a row of their own, cost a fifth of the picture on a phone on its side. They share the bar where it is wide.

## How it was checked

- 1,048 unit tests (25 of them live, skipped without a Nagios), `./gradlew check`, both builds, the FOSS self-test.
- **Live**: the app's own client against nagiosgraph on the test Nagios, and every other live test re-run on the changed test Nagios.
- **Deliberate breaks**: 60 for #53; 35 and then 26 more for the phone-check changes for #55. All caught in the end; the ones that were not at first got tests, and are listed in the pull requests.
- **Fuzzing**: a fifth target, 21 million runs clean after its one finding.
- **On a phone** (Pixel 8a, Android 17, the test profile against the test Nagios):

| Check | Result |
|---|---|
| `profiles.db` 3 to 4 on a phone with real profiles; cold start | Pass, no crash at any point |
| A graph on service detail; a name with a space and a slash; three things plotted | Pass |
| Each period, nothing moving while one loads | Pass |
| The viewer: on its side, a larger picture, Back | Pass |
| Back stands the screen up again | Pass, after fix 1 |
| The viewer's period is the detail screen's | Pass, after fix 2 |
| A host: no graph section | Pass |
| A service with no performance data: no section, asked once, not again | Pass (the server's log) |
| Only `host`, `service`, `period` and `geom` are sent | Pass (the server's log) |
| The server stopped: the status says so, the graph stays | Pass |
| The server stopped and another period chosen | Pass, after fix 3 |
| Pinch, drag, double-tap, Back (the maintainer) | Pass |
| Zoom lands where the fingers are (the maintainer) | Pass |
| Graphs switched off: no section; on again: it is back (the maintainer) | Pass |

## Not checked, and said so

- **A busy graph.** The test Nagios has minutes of history, so its graphs are real pictures of the real size with little in them. How a year of real data reads at phone size has not been seen.
- **A real nagiosgraph other than 1.5.2**, or one with its own colours, geometries or map rules.
- **A graph path that is not blank** on a phone: live-tested from the laptop, not typed into the editor on the device.
- **Android 8 to 11**, and large screens, which may ignore the request to turn.
- **"Open graphs in browser"**: the link is tested; it was not tapped, by agreement (it leaves the app).
- **No request made with graphs off** was not read from the server's log: the maintainer saw the section go and come back.

## Follow-ups

- #54: graphs for hosts.
