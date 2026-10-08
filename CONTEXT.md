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
A problem that is not acknowledged and not in scheduled downtime. This is the headline number everywhere (badge, widget, notifications).
_Avoid_: Open problem, active alert

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

**Unreachable (instance)**:
The last poll failed to get a valid response from the profile's Nagios. Distinct from OK and from stale.
_Avoid_: Offline, down (down = host state)

**Widget tier**:
One of four responsive widget layouts: Badge, Counts, Short list, Full list.
_Avoid_: Size, mode

**Access token**:
A Cloudflare Access service token (Client ID and Client Secret) sent as headers on every request to a profile.
_Avoid_: API key, login
