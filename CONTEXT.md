# Nagwatch

Android client for Nagios Core. Reads status from the Nagios JSON CGIs, sends commands through `cmd.cgi`, and surfaces problems in an app, in notifications, and in a widget.

## Language

**Profile**:
One saved connection to a Nagios instance: base URL, credentials, optional Cloudflare Access token and custom headers, graph settings, polling settings.
_Avoid_: Server, account, connection, instance

**Problem**:
A host in DOWN or UNREACHABLE, or a service in WARNING, CRITICAL or UNKNOWN.
_Avoid_: Alert, incident, issue

**Unhandled problem**:
A problem that is not acknowledged, not in scheduled downtime (its own or its host's), and not a service on a host that is itself down. This is the headline number everywhere (badge, widget, notifications). Soft problems and problems with checks or notifications disabled are still unhandled; they are marked, not hidden.
_Avoid_: Open problem, active alert

**Rolled-up problem**:
A service problem on a host that is itself DOWN or UNREACHABLE. It is shown under the host problem and never counted on its own: the host problem stands in for it.
_Avoid_: Child problem, suppressed

**Soft problem**:
A problem Nagios is still retrying (attempt 2 of 3, say) and has not notified about yet. Shown and counted, with a marker.
_Avoid_: Pending problem (PENDING means never checked)

**Degraded record**:
A host or service Nagios could not serialise in full, so only its name and state are known. Shown as "details unavailable" and treated as unhandled.
_Avoid_: Broken service, partial record

**Acknowledgement**:
A Nagios record that someone has seen a problem. Stops repeat notifications.
_Avoid_: Ack (UI shorthand only), silence

**Sticky acknowledgement**:
An acknowledgement that survives state changes between non-OK states and clears only on recovery. Off by default in Nagwatch because it can hide a worsening fault.
_Avoid_: Persistent ack

**Downtime**:
A scheduled window in which a host or service is expected to be down. Problems inside it are handled.
_Avoid_: Maintenance, mute

**Poll**:
One background fetch of status for a profile. Feeds notifications, the cache and the widget.
_Avoid_: Sync, refresh (refresh = user-triggered)

**Stale**:
Cached data whose last successful poll is older than twice the poll interval. Always shown as stale, never as current.
_Avoid_: Old, outdated

**Announced**:
Known to the app as a problem the person has been told about, or that was there at the first poll. What stops a problem being notified again at every poll. Not the same as "showing": a notification swiped away is still announced.
_Avoid_: Notified (that is what Nagios does), seen, shown

**First poll**:
The first background poll of a profile that succeeds, or the first after what was announced has been lost. Told as one summary, not one notification per problem.
_Avoid_: Initial sync, baseline

**Summary (notification)**:
One notification that stands in for many: everything at the first poll, or more than five new or worse problems in one poll. Problems told only by a summary have no notification of their own and recover silently.
_Avoid_: Digest, group (the group is how Android stacks a profile's notifications)

**Outage**:
A profile's Nagios cannot be polled. One that may pass by itself (no answer, a captive portal) is told after three failed polls in a row; one that will not (a rejected password) is told at once.
_Avoid_: Downtime (that is scheduled, in Nagios), offline

**Channel**:
One kind of notification as Android's settings list it: down and critical, warning, unknown, recovered, can't reach Nagios, summaries. The person, not the app, decides how loud each is.
_Avoid_: Category, type

**Offer (of notifications)**:
The one time the app asks, for a profile that has just loaded, whether to switch notifications on. Whatever the answer it is not made again for that profile, and nothing is switched on without a yes.
_Avoid_: Prompt (that is Android's), onboarding

**Unreachable (instance)**:
The last poll failed to get a valid response from the profile's Nagios. Distinct from OK and from stale.
_Avoid_: Offline, down (down = host state)

**Command**:
Something the app asks Nagios to do: acknowledge, remove an acknowledgement, add a comment, force a check, schedule a downtime, cancel a downtime. The only writes the app makes.
_Avoid_: Action (the button), request

**Accepted**:
Nagios took a command and wrote it to its command file. Not the same as carried out: Nagios acts on it later, and may drop it.
_Avoid_: Done, succeeded, sent successfully

**Confirmed**:
The app re-read the object after a command and saw its effect. The only state reported as done.
_Avoid_: Successful

**Not confirmed**:
Nagios accepted a command and the app did not see its effect in the time it looks for (about 25 seconds). Not a failure: the effect may still arrive, or Nagios may have dropped the command.
_Avoid_: Failed, timed out

**Read-back**:
Reading a downtime again from Nagios after scheduling it, and comparing its window with the one asked for. What turns "accepted" into "confirmed" for a downtime, and what catches one that landed on another day or an hour out.
_Avoid_: Verification, check (a check is what Nagios runs)

**Mismatch**:
A downtime Nagios accepted and then scheduled for a different window from the one asked for. A real downtime, silencing real notifications; shown with both windows and an offer to cancel it.
_Avoid_: Error, failure

**Refused**:
Nagios answered a command and said no, with a reason the app can name.
_Avoid_: Failed (which also covers "never arrived" and "nobody knows")

**Unknown (outcome)**:
A command may or may not have reached Nagios, or the answer said neither yes nor no. Never shown as success or as failure; the thing to do is look.
_Avoid_: Error, timeout

**Read-only user**:
A Nagios user the CGIs will take no commands from, for any object. Knowable in advance, unlike not being authorised for one particular object.
_Avoid_: Viewer, guest

**Server clock**:
How one Nagios server writes and reads a time: its date format and its offset from UTC. Learned from the server each time, never assumed.
_Avoid_: Timezone setting, locale

**Status cache**:
The last poll of each profile and the detail of objects the user has opened, kept on disk so the app has something to show before the network answers and when there is none. Disposable: everything in it can be fetched again.
_Avoid_: Database (that is where profiles live), history

**Detail record**:
The full record of one host or service: output, attempts, check times, flags. Every host and every service in a problem state has one from each poll; any other service gets one when the user opens it.
_Avoid_: Full status, extended info

**Opened record**:
A detail record kept because the user opened that object's screen. The most recently opened are kept, per profile.
_Avoid_: Favourite, pinned

**Polled (profile)**:
Fetched in the background on a schedule. A profile is polled while it has notifications on or a widget on the home screen, and not otherwise.
_Avoid_: Monitored, synced

**Announcing (profile)**:
Having its notifications on, so that a poll may notify. A widget makes a profile polled; it never makes it announcing.
_Avoid_: Notifying (that is what Nagios does)

**Widget tier**:
One of four responsive widget layouts: Badge, Counts, Short list, Full list.
_Avoid_: Size, mode

**Local network access**:
Android 17's permission (`ACCESS_LOCAL_NETWORK`, shown to the user as "Nearby devices") for reaching an address on the local network. Without it Android drops the connection and the app sees only a timeout.
_Avoid_: Wi-Fi permission, network permission

**Looks local**:
An address in one of the ranges Android 17 treats as the local network, or a `.local` name. The app can tell this much and no more: whether a request will actually go over the local network or over a VPN is not known until it is made.
_Avoid_: Is local, private address

**Access token**:
A Cloudflare Access service token (Client ID and Client Secret) sent as headers on every request to a profile.
_Avoid_: API key, login
