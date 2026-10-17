# An unreachable host is shown and notified as unknown

A host in UNREACHABLE is drawn in the unknown colour (orange) everywhere, notifies on the Unknown channel, and is never a loud alert (pgmac-net/nagios-android#114). It still sorts above a critical service and is still one of the Problems tab's hosts.

## Why this needed deciding

The app disagreed with itself. Since M2 (#24) the screens (the Problems list, the Hosts list and its chips, a host's detail, the host map) drew UNREACHABLE in orange, as unknown. The notifications (#60) and the widget drew it red and filed it with down and critical, so it made a heads-up and, once loud alerts came in (ADR 0013), could wake someone through Do Not Disturb. Each place had its own mapping from state to colour, which is how they drifted apart. The refactor in #114 gives them one mapping, so one answer had to be chosen.

## What was decided

- **Unknown, not down.** Nagios calls a host unreachable when a parent of it is down or unreachable: it could not be checked, which says nothing about whether it is up. That is what unknown means for a service. The host behind it that *is* down is reported as down, and is what someone needs to act on.
- **Everywhere, in one place.** `StatePresentation.kt` (`StateColors.of`, `shortWord`) is the only mapping from a state to its colour and word. The screens, the widget and the notification tint and large icon all use it. The emoji follow: in Coloured circles unreachable is 🟠 and in Coloured squares 🟧. Signs (🔌) and Faces (😵) already had a shape of their own.
- **The Unknown channel, so not loud.** `NotifyChannel.of(HOST_UNREACHABLE)` is `UNKNOWN`: default importance, no heads-up. Loudness follows the channel (ADR 0013), so a newly unreachable host does not sound a loud alert, and a summary of only unreachable hosts does not stand in for a critical. That rule is kept rather than adding a second one.
- **The order of the lists is unchanged.** DOWN, then UNREACHABLE, then the services' CRITICAL and so on. Urgency in a list and the colour are separate questions.

## What it costs

- **Fewer interruptions for a real outage behind a router.** When a switch goes down, its children turn unreachable. Only the switch is loud now. The person sees the rest when they look, which is the point, but a parent that Nagios does not know about (no `parents` set) leaves its children unreachable and quiet.
- **A visible change** for anyone used to red in the shade and on the widget.
- **Channel settings do not move with it.** Someone who had given "Down and critical" a sound of their own will now hear the Unknown channel's sound for an unreachable host.
