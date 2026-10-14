# A quick acknowledgement is sent at once, with a default comment

Acknowledging from a list or from a notification sends the command straight away, with the profile's own comment or "Acknowledged from Nagwatch", and offers an undo afterwards. It does not open the form. A row is acted on by pressing and holding it, never by swiping it (pgmac-net/nagios-android#63, #64).

## Why this needed deciding

The detail screen's form asks for a comment before anything is sent, starts with sticky off, and remembers nothing. That was deliberate: an acknowledgement is not private. It is visible to everyone who uses that Nagios, and it stops Nagios notifying about the problem. Two requests pulled against that: act on a problem without opening it, and act on it from a notification, where there is no screen to ask on.

There was also one gesture and two uses for it. Swiping between the three tabs and swiping a row to act on it are both a sideways drag.

## What was decided

- **Tabs own the sideways drag. Rows are pressed and held.** A swipe is quick and easy to do by accident with a thumb that is scrolling. A stray swipe now changes tab at worst. Changing Nagios takes a deliberate press and then a choice from a menu.
- **No form.** A quick action that stops to ask is the detail screen with one fewer step. The comment Nagios requires comes from the profile, where a team can set its own; with none set it is "Acknowledged from Nagwatch". The flags are the form's own defaults: not sticky, notify, not persistent.
- **An undo, in place of a confirmation.** Asking "are you sure?" before every acknowledgement slows the hundredth as much as the first. Saying what was done and offering to take it back costs nothing when it was meant. The undo is "remove acknowledgement", sent to the same Nagios.
- **The same command path.** A quick action is not a lighter kind of command. It goes through `CommandRepository`: accepted, then confirmed by reading the object back, never retried, one at a time. "Done" is still only said after the effect has been seen.
- **The profile comes from where the action started.** A menu's action carries the profile it was opened on, an undo remembers the profile its acknowledgement went to, and a notification's button carries its own. Nothing asks which profile happens to be on screen. Two Nagios servers can each have a `web01`, and the profile on screen can change while a command is on its way. (The first version of the undo did ask, and the commit review caught it.)
- **On a notification, the notification is the screen.** There is nowhere else to say what happened, so it says "Acknowledging…", then "Acknowledged" or the reason, in the problem's own place.
- **Not from a locked phone.** A notification and its buttons are shown on the lock screen. Whoever is holding a locked phone is not known to be its owner, and an acknowledgement silences a problem for everyone, so a button's press needs the phone unlocked: Android is asked to require it, and the receiver checks for itself on every version. A recheck is held to the same rule, since it is the same credentials either way. (The first version did not, and the commit review caught it.)
- **Comment and downtime stay behind the form.** They need words or a window, and a default for either would be a guess.

## What it costs

- **An acknowledgement can now be made with two touches, and one from a notification.** That is the point, and it is also the risk: it is easier to silence a problem for the whole team than it was. The undo is the answer in the app. From a notification there is no undo; the acknowledgement has to be removed in the app.
- **The default comment says less than a person would.** "Acknowledged from Nagwatch" tells a colleague who and how (Nagios records the user) but not why. The profile's setting, and the form, are there for more.
- **A row that is acknowledged leaves Hosts and Services**, since they hide handled problems until asked. The snackbar says where it went.
- **One more request in the background.** The poll asks once per run of the app what the user may do, so a read-only user is not shown buttons that cannot work.
- **The button's intent carries the notification's words**, so the answer can be drawn where the notification was even if it has been swiped away. They stay inside the app: the receiver is not exported.

## Not done

- **Swipe actions on a row.** Considered and left: see above. If they are wanted later they need the tabs to give up the gesture.
- **An undo on a notification.** A second button that appears for a minute is easy to miss and easy to press by mistake.
- **A typed comment from a notification** (Android's inline reply). It would make the comment a person's own, at the cost of a keyboard on a notification. Worth another look if the default proves too thin.
- **Sticky, or the other flags, as settings.** Sticky off is the safe default and the form is where to change it.
