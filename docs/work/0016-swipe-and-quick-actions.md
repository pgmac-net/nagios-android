# Swipe between tabs, and quick actions

Tickets: pgmac-net/nagios-android#63 and #64, from the ideas in pgmac-net/homelabia#210. One pull request: the pager, the row menu, a fix to it, the notification buttons, a fix to those, and the docs. The reasoning for the actions is ADR 0012.

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
- **The buttons worked on a locked phone.** The commit security review found this too, in the notification commit. A notification and its buttons are on the lock screen, so anyone holding the phone could have acknowledged a problem, silencing it on Nagios for everyone. Each button now needs authentication (Android asks for the unlock, from Android 12) and the receiver refuses a press from a locked phone on every version, saying so on the notification. A recheck is held to the same rule.
- **Two updates where there should have been one.** A test for "read-only is remembered" failed one run in several: the refusal was published, and what it taught a moment later. Anything watching could see one without the other. They are now one update.
- **A menu in a pop-up can be tested here.** A text field in a dialog cannot, with this project's tools (the detail screen's forms are in the page for that reason), so this was tried before relying on it. Dropdown menus and their items are found and clicked by Robolectric.
- **A clickable row and a pager do not fight** when the row has no horizontal gesture of its own, which is the design. A long press on a row inside the pager works; a swipe that starts on a row changes tab.

## Looked at on a phone

On a Pixel 8a with Android 17, in the debug build, against the maintainer's own Nagios. Every tap was aimed at something found on the screen first, after one early tap, aimed by coordinates at a notification, landed on a row of the app instead because the screen had dozed and the shade had closed. It opened a detail screen and sent nothing, but it was a tap on a live Nagios that was not meant.

- **Upgrade.** The build installed over a version 4 `profiles.db` and opened with all five profiles.
- **Swipe.** Left twice, past the end, right twice, past the start; a tap on the bar; Back. The selected tab was read from the screen each time and was right every time.
- **The menu.** On a problem nobody has acknowledged: Acknowledge, Recheck, Open. On an acknowledged one: Remove acknowledgement, Recheck, Open. The second was closed with Back and nothing was chosen.
- **A recheck from the menu**, on a real service: "Nagios accepted it. Waiting to see it take effect…", then "Checked again."
- **A notification's button with no profile behind it** (`SampleNotificationsReceiver`): the press reached the receiver, the job ran, and everything of that profile was taken down.
- **A notification's button against the real Nagios.** The debug poster was given a way to post a sample for a real profile and service. Recheck on it: the notification read "Asking for a check…" with no buttons and could not be swiped away, then "Checked again. The next background check will show what it found." with its buttons back. Then it was cleared.
- **The editor** shows "Quick actions" and "Acknowledgement comment". Left by Cancel.
- **A locked phone** (after the merge, by the maintainer, with the phone reporting itself locked). A sample notification was posted; on the lock screen it could be expanded and its text and buttons read. Acknowledge was pressed and **the phone asked for the unlock before anything happened**. The log agrees: the lock screen went at 12:54:51.655 and the button's job was queued at 12:54:51.680, after it and not before. So the press is held by Android until the phone is unlocked and then delivered. That is the first of the two checks (the button marked as needing authentication, Android 12 and later). The receiver's own refusal, for older Android, was not reached, because Android never delivered a press from a locked phone; it remains covered by unit tests only.
- **An acknowledgement, on a real problem** (after the merge, with the maintainer's say-so; `k8s03 / reboot`, a warning). From the menu: "Nagios accepted it. Waiting to see it take effect…", then "Acknowledged. It is with the handled problems now." with Undo, and the row had left the list. Undo: accepted, then "Acknowledgement removed.", and the row was back. From a notification's button, on a sample posted for the same service: "Acknowledging…" with no buttons, then "Acknowledged." with none, and the notification was gone within the minute. That acknowledgement was then removed from the menu of the row under "Show handled". The problem was left as it was found: unacknowledged, eight handled problems as before. Each acknowledgement told Nagios to notify, as the form's default does, so the server's contacts will have heard of two acknowledgements and their removal.
- No crash in the log from start to finish.

## Not verified

- **The text and the buttons can be read on the lock screen.** Android showed them once the notification was expanded, on this phone with its settings. That was already so before this work (lock-screen visibility is left to the system setting, design section 7); it is noted because a host name and a plugin's output are now accompanied by buttons, which do nothing until the unlock.
- **A read-only user** on a phone: the menu's switched-off items and a notification with no buttons.
- **A paired watch** showing the buttons.
- **The release build.** Everything above is the debug build. R8 keeping the new receiver and worker is reasoned from the poll's, not seen.

## Follow-ups

- #67 (Wear OS): the cheap route is this. Whether more is wanted can be judged once it has been used.
- A typed comment from a notification (Android's inline reply), if the default proves too thin. See ADR 0012.
