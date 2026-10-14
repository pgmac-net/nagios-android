# Swipe between tabs, and quick actions

Tickets: pgmac-net/nagios-android#63 and #64, from the ideas in pgmac-net/homelabia#210. One pull request, four commits: the pager, the row menu, a fix to it, the notification buttons. The reasoning for the actions is ADR 0012.

## What was built

- **#63, the tabs are pages.** A swipe moves between Problems, Hosts and Services and the bar follows. One pull to refresh over all three.
- **#64, press and hold a row.** A menu with Acknowledge, Remove acknowledgement, Recheck and Open, on all three lists. An acknowledgement is sent at once with the profile's comment (or "Acknowledged from Nagwatch"); a snackbar says when Nagios has confirmed it and offers Undo.
- **#64, a profile's "Acknowledgement comment"**, under a new "Quick actions" part of the editor. `profiles.db` is at version 5.
- **#64, buttons on a notification.** Acknowledge and Recheck on a problem's own notification. The notification says what became of the press.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Who owns a sideways drag | Tabs swipe; rows are pressed and held | A swipe is easy to do by accident, and an acknowledgement is seen by everyone and stops Nagios notifying |
| The comment | Sent at once with a default, with Undo | A quick action that stops to ask is the form again. An undo costs nothing when the action was meant |
| Where the default lives | On the profile | A team can have its own; there is no app-wide settings screen |
| Notification buttons | Acknowledge and Recheck, on problems only | A recovery, a summary and an outage have nothing to acknowledge |
| Delivery | One pull request | The maintainer's choice. Three were recommended; the commits are in an order that can be split |

## Deviations from the plan

- **The menu has no "Comment…"**, which an early sketch showed. It was dropped in the plan: a comment needs words. Listed here because it was in what the maintainer first saw.
- **Leaving a tab drops its keyboard focus.** Not planned. Found by a test: a search box with the keyboard kept its page alive off screen, and would have kept the keyboard up over a tab that was not it.
- **Before Android 12 the notification's job is not expedited.** The plan said "expedited, like the poll". An expedited job on older Android has to be a foreground service with a notification of its own; it is an ordinary job there, and the answer can take a while.
- **The notification's buttons carry the notification's words.** Not in the plan. The answer has to be drawn where the notification was, and by then it may have been swiped away.

## What was found, and by what

- **An undo went to whichever profile was on screen.** The commit security review found it in the second commit: Undo took the profile from the screen at the moment it was tapped. The profile can be switched while the snackbar is up, and two Nagios servers can each have a `web01`, so an undo could have removed an acknowledgement on the wrong server. The view model now remembers where a command went and an undo takes no profile. What a user may do was kept the same wrong way (one answer for every profile) and is now per profile. Both have tests. The notification buttons, written afterwards, take their profile from the notification only.
- **Two updates where there should have been one.** A test for "read-only is remembered" failed one run in several: the refusal was published, and what it taught a moment later. Anything watching could see one without the other. They are now one update.
- **A menu in a pop-up can be tested here.** A text field in a dialog cannot, with this project's tools (the detail screen's forms are in the page for that reason), so this was tried before relying on it. Dropdown menus and their items are found and clicked by Robolectric.
- **A clickable row and a pager do not fight** when the row has no horizontal gesture of its own, which is the design. A long press on a row inside the pager works; a swipe that starts on a row changes tab.

## Not verified

- **None of this has been on a phone.** It was not connected when the build was ready. The unit suite, lint, detekt, ktlint, the licence checks and both builds pass; R8 keeps the new receiver and worker by the same rules as the poll's, but that is reasoning and not a look.
- **Wanted on a phone:** the swipe and how it feels against pull to refresh; the menu; a **Recheck** against a real Nagios (it changes nothing); the notification buttons, using `SampleNotificationsReceiver`, whose profile does not exist, so a press should take its notifications down.
- **Not to be tried against a real Nagios without the maintainer saying so: Acknowledge.** It is visible to everyone and stops notifications. The local test Nagios (`scripts/test-nagios`) is the place for it.
- **A paired watch** showing the buttons is Android's behaviour and was not seen.

## Follow-ups

- #67 (Wear OS): the cheap route is this. Whether more is wanted can be judged once it has been used.
- A typed comment from a notification (Android's inline reply), if the default proves too thin. See ADR 0012.
