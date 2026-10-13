# Notification icon colour and a way to the sounds

Tickets: pgmac-net/nagios-android#60 and #62, from the ideas in pgmac-net/homelabia#210. One pull request, a commit for both.

## What was built

- **#60, the small icon is tinted by state.** In the shade a problem's icon is red (down, unreachable, critical), amber (warning) or orange (unknown), and a recovery is green: the colours the screens and the widget already use, light or dark to match the phone. A summary, the group's entry and "Can't reach Nagios" are left untinted.
- **#62, a way to the sounds.** The profile editor's Notifications section has a "Sounds and importance" button, shown while notifications are on. It creates the six channels and opens Android's notification page for the app, where each kind has its own sound (from Android's own list), importance and vibration.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| #62: what the app adds | A link to Android's screen; no picker of its own | Android owns a channel's sound after creation. A picker means a new channel id on every change, which resets what the person set |
| #62: different built-in sounds | No: every channel keeps Android's default | Android's bundled sounds differ from phone to phone, and a fall-back is invisible and untestable |
| #60: shape | One shape, tinted; a tick for recovered | The title already says the state in words, so colour is a second signal, not the only one |
| Untinted ones | Summary, group entry, outage | No state colour is true of them. An outage is about the connection, not a Nagios state |
| Delivery | One pull request | Same file, same section, small |

## Deviations from the plan

None. The plan said the sounds row would be tested with a scroll in the UI test; the fixture is a plain column, so the test clicks the node directly.

## What was found, and by what

- **Channels do not exist until something is posted.** The channels are created by the first notification, so Android's page for a profile that has never notified would list nothing. The button creates them first; `NotifyChannel.createAll` is the code both it and the notifier use, and a test shows it is safe to repeat.
- **The status bar ignores the colour.** Android draws a small icon white and flat there, whatever is set. The tint is only for the shade. The README and the design say so, so nobody expects the colour in the bar.
- **A tint is not seen by a unit test.** The tests check the colour on the notification Android is handed. How it looks in the shade is a thing to look at.

## Not verified

- **Not looked at on a phone.** The phone was not connected when the debug build was ready to install. The behaviour is covered by Robolectric tests, the whole suite passes. Still to be seen: the tint in the shade for each kind; the button and Android's page it opens; and that the colours read on both a light and a dark shade.
- Seeing a notification of each severity needs the debug poll receiver (`PollNowReceiver`) against a Nagios with each kind of problem.

## Follow-ups

- An in-app sound picker, if the trip out to Android's page proves annoying (it needs new channel ids; see `docs/development.md`).
- Do Not Disturb breakthrough is #66's, as part of push alerts.
