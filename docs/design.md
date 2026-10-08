# Nagwatch v1 design

Status: **locked for v1** (2026-10-08). Source: pgmac-net/homelabia#210. Change by PR to this file; record hard-to-reverse changes as ADRs. Terms are defined in [`../CONTEXT.md`](../CONTEXT.md).

## 1. Goals and non-goals

**Goal.** A modern Android client for Nagios Core that does what aNag did (see problems, act on them, get notified) with a current UI, plus a responsive home-screen widget and in-app nagiosgraph graphs.

**Non-goals for v1:**

- Per-object enable/disable of notifications or checks; cancel downtime
- Host/service group views; event log and history; config viewing
- Tablet-specific layouts; Wear OS
- Other backends (Nagios XI, Icinga, Thruk, Livestatus)
- Push via FCM or any backend/relay service ([ADR 0002](adr/0002-no-backend-direct-polling.md))
- Widget actions; widget spanning several profiles; lock-screen widget
- Native charts from RRD data; graphs in widget or notifications
- Interactive SSO login to Cloudflare Access

## 2. Decisions

| Area | Decision |
|---|---|
| Scope of #210 | Bootstrap only: repo, this design, milestone backlog |
| Repo | `pgmac-net/nagios-android`, `opensource` module, `/pgmac` PR bypass |
| Name | App **Nagwatch**; id `net.pgmac.nagwatch` ([ADR 0003](adr/0003-application-id-and-name.md)) |
| License | GPL-3.0-or-later ([ADR 0001](adr/0001-gpl-3-or-later-license.md)) |
| Connectivity | Direct client, no backend ([ADR 0002](adr/0002-no-backend-direct-polling.md)) |
| Distribution | Signed APK on GitHub Releases; FOSS-only deps (CI-enforced); F-Droid later; no Play |
| Nagios version | Nagios Core 4.0.7+ (JSON CGIs) |
| minSdk | 26 (Android 8.0); target/compile latest stable |

## 3. Stack

| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 (dynamic colour on Android 12+), single activity, Navigation Compose |
| Widget | Jetpack Glance, `SizeMode.Responsive` |
| Architecture | MVVM, unidirectional state, repository layer, coroutines/Flow |
| DI | Hilt |
| Network | OkHttp + kotlinx.serialization; interceptors for basic auth and Access/custom headers; Coil for graph PNGs sharing the OkHttp client |
| Storage | Room (cached status; offline view; widget reads cache), DataStore (settings); secrets encrypted with an Android Keystore key |
| Background | WorkManager periodic poll feeding notifications and widget |
| Build | Gradle Kotlin DSL, version catalog, single `:app` module until it hurts |
| Quality | ktlint, detekt, Android Lint; JUnit + Turbine; MockWebServer fixtures captured from real `statusjson.cgi`; Compose UI tests for key screens |
| CI | GitHub-hosted `ubuntu-latest` only (never self-hosted on a public repo); reuse `pg-actions` sbom/scorecard/slack workflows; Renovate |
| Dependency rule | FOSS-only: no Play Services, proprietary SDKs, analytics or crash reporters. Enforced by a CI check |

## 4. Connection profile

A profile holds:

- Display name, base URL (e.g. `https://nagios.example.org/nagios`; CGI path derived as `{base}/cgi-bin/`, overridable)
- Username and password (HTTP basic auth)
- **Cloudflare Access** section: Client ID, Client Secret. Sent as `CF-Access-Client-Id` and `CF-Access-Client-Secret` on every request
- **Custom headers**: list of name/value pairs for other proxies
- nagiosgraph: enabled toggle, base URL template (default `{base}/cgi-bin/`)
- Polling: enabled, interval (15 min default and minimum), unhandled-only filter
- Custom CA certificate (optional, for private CAs)

Secrets (password, Client Secret, header values) are encrypted at rest with a Keystore-held key and excluded from Android backup. Cleartext `http://` is refused unless the user explicitly enables it per profile, with a warning.

**Error classification** (shown distinctly, never a generic failure):

| Condition | Message |
|---|---|
| Redirect to `*.cloudflareaccess.com`, or 403 with Access markers | Cloudflare Access rejected the credentials |
| 401 | Nagios rejected the username/password |
| TLS error | Certificate problem (with detail) |
| Timeout / DNS / no route | Instance unreachable |
| 200 with unparseable body | Not a Nagios JSON CGI (wrong base URL or CGI path?) |
| `result.type_text` not Success | Nagios error, message shown |

## 5. Nagios API contract

All reads go through `statusjson.cgi` / `objectjson.cgi` (JSON). All writes go through `cmd.cgi` (HTML form POST; no JSON). The acting Nagios user needs command authorisation for writes; the app detects a lack of it and disables action buttons.

### Reads

| Need | Call |
|---|---|
| Version / health check on connect | `statusjson.cgi?query=programstatus` |
| Counts for badge/widget | `statusjson.cgi?query=hostcount` and `query=servicecount` |
| Problem lists | `statusjson.cgi?query=hostlist&details=true` and `query=servicelist&details=true`, filtered by status |
| Detail | `query=host` / `query=service` with `hostname` (and `servicedescription`) |
| Comments / downtimes | `query=commentlist`, `query=downtimelist` |

Exact filter parameters (`hoststatus`, `servicestatus`, `hostprops`, `serviceprops` for acknowledged/downtime) must be validated against a live instance in M1. M1 captures real responses as MockWebServer fixtures; this section is then updated to match.

### Writes (`cmd.cgi`)

`cmd_typ` values, verified against the Nagios 4 `common.h` and the existing `cmd_typ=33/34` links in the pgmac notification scripts:

| Action | Host | Service |
|---|---|---|
| Acknowledge problem | 33 | 34 |
| Remove acknowledgement | 51 | 52 |
| Schedule downtime | 55 | 56 |
| Force recheck | 98 (`SCHEDULE_FORCED_HOST_CHECK`) | 54 (`SCHEDULE_FORCED_SVC_CHECK`) |
| Add comment | 1 | 3 |

Success is detected by scraping the HTML response (Nagios prints "Your command request was successfully submitted"). This is the most fragile part of the app: M3 isolates it behind one `CommandClient` interface with fixture tests against captured pages, so a Nagios wording change is a one-file fix. After any command the app re-polls the affected object and shows the new state rather than trusting the HTML alone.

## 6. Screens (text wireframes)

### Navigation

```
 Profile switcher (top bar) | Problems | Hosts | Services | Settings
```

### Problems (start screen)

```
+--------------------------------------+
| macro (home)  v        updated 14:02 |
| 3 CRIT  2 WARN  1 UNKN  0 DOWN       |   <- count chips, tap = filter
|--------------------------------------|
| CRITICAL  k8s01 / jiva-volumes   2h  |
|   HARD WARNING: duplicate bind mount |
| CRITICAL  pve2 / raid-state     41m  |
| WARNING   hal / nfs-mounts      12m  |
| ...                                  |
| (acknowledged / downtime: collapsed) |
+--------------------------------------+
```

Pull to refresh. Rows sorted worst state first, then newest. Acknowledged and in-downtime problems collapsed under a footer by default. A banner shows when the profile is stale or unreachable.

### Hosts / Services

Searchable lists with state-filter chips (OK, WARN, CRIT, UNKN, PENDING; UP, DOWN, UNREACH) and a "show handled" toggle.

### Service detail

```
+--------------------------------------+
| < k8s01 / jiva-volumes               |
| CRITICAL   HARD   attempt 3/3        |
| for 2h 04m   last check 14:01        |
|--------------------------------------|
| HARD WARNING: duplicate bind mount.. |   <- plugin output, long output expandable
|--------------------------------------|
| [Acknowledge] [Downtime] [Recheck]   |
| [Comment]                            |
|--------------------------------------|
| Graph      day | week | month | year  |
| +----------------------------------+ |
| |            (PNG)                 | |   <- tap: fullscreen landscape
| +----------------------------------+ |
| Comments / downtimes                 |
| overflow: Open in browser            |
+--------------------------------------+
```

Host detail is the same minus the service name, with the host check graph if one exists.

### Acknowledge dialog

```
+--------------------------------------+
| Acknowledge k8s01 / jiva-volumes     |
| Comment  [__________________________]|
| [ ] Notify contacts                  |
| [ ] Persistent comment               |
| [ ] Sticky  (off by default)         |
|     Sticky hides later worsening --  |
|     it clears only on recovery.      |
|            [Cancel]  [Acknowledge]   |
+--------------------------------------+
```

Sticky is **off by default** and shows the warning when ticked. Rationale: a sticky acknowledgement hid a failed drive on 2026-10-02 in the maintainer's homelab.

### Downtime dialog

Fixed downtime only. Duration presets (30 min, 1 h, 2 h, 4 h, 8 h, 24 h) plus a custom start/end picker, and a required comment.

### Settings

Profile list/editor (section 4), notification toggles per profile, theme, about (version, licence, "not affiliated with Nagios Enterprises").

## 7. Background polling and notifications

- One WorkManager periodic job per enabled profile (15 min minimum, Android's floor).
- Each poll writes to Room; the widget and the app read the cache. One network cost serves all three consumers.
- Notify on: a new unhandled problem; a problem that worsens (WARN to CRIT); a recovery of something previously notified. Grouped per profile, with a notification channel per state class so the user can mute classes in system settings.
- Unhandled-only by default: acknowledged and in-downtime problems do not notify.
- A failed poll never produces "all OK". Three consecutive failures raise one "instance unreachable" notification, cleared on the next success.
- Expected alert latency is up to about 15 minutes ([ADR 0002](adr/0002-no-backend-direct-polling.md)). The README says so plainly.

## 8. Widget

Built with Glance `SizeMode.Responsive`; the system supplies size ranges in dp, so tiers are breakpoints rather than exact cell counts.

| Tier | Approx. cells | Content |
|---|---|---|
| **Badge** | 1x1 | Fill colour = worst unhandled state; one number = unhandled problems. Green tick at zero. Grey + icon when stale or unreachable |
| **Counts** | 2x1 to 4x1 | Chips: CRIT / WARN / UNKN / hosts DOWN (unhandled; acknowledged dimmed if room). Last-update time from 3 wide |
| **Short list** | 2x2 to 4x2 | Counts row + top 2-4 problems, worst then newest: host, service, duration. No plugin output |
| **Full list** | 3x3 to 4x6 | Counts row + scrollable list: host, service, state colour, duration, one truncated line of plugin output. Refresh button, profile name, last-update stamp |

Behaviour for all tiers:

- Tap a count or badge: open the Problems view. Tap a list row: open that service's detail.
- **Stale** (last good poll older than 2x the interval) and **unreachable** are shown distinctly. Old green is never shown as current.
- One widget instance = one profile, chosen in a configuration screen. Multiple widgets allowed.
- Follows system light/dark and Material You colour. State colours (red/amber/green) are fixed so they keep their meaning.

## 9. nagiosgraph

- Service and host detail fetch `showgraph.cgi?host=H&service=S&period=P` with the profile's credentials and headers, via the shared OkHttp client and Coil. Periods: day, week, month, year.
- The graph path is a per-profile template with default `{base}/cgi-bin/`, because upstream installs often use `/nagiosgraph/cgi-bin/`.
- Not every service has performance data. A non-image response or error image collapses the Graph section silently; there is no error spam.
- "Open in browser" opens `show.cgi?host=H&service=S`. It is secondary: the external browser has none of the app's credentials, so it works only where the browser can already reach and authenticate to Nagios.

## 10. Data model (Room)

- `Profile` (settings; secrets held separately, Keystore-encrypted)
- `HostStatus`, `ServiceStatus`: profile id, names, state, state type, output, last check, duration, attempts, acknowledged, in-downtime, fetched-at
- `PollResult`: profile id, time, success/failure class, counts
- `NotifiedState`: profile id, object key, last notified state (for de-duplication and recovery notices)

Cache is replaced per poll for a profile; there is no history in v1.

## 11. Security notes

- Credentials and tokens never logged. Debug logging redacts headers.
- No analytics, no crash reporter, no third-party network calls; the only traffic is to the user's profile hosts.
- Backups exclude secrets. Optional custom CA trusted per profile, not globally.
- Cloudflare Access service tokens are long-lived static secrets. The README recommends a dedicated token with a short expiry and a dedicated Nagios user with only the command permissions the user wants the phone to have.
- Release signing key is held by the maintainer outside the repo, backed up, and supplied to CI as a GitHub Actions secret. Losing it means installs cannot be upgraded.

## 12. Roadmap (milestone issues)

| # | Milestone |
|---|---|
| M0 | Project scaffold, CI, lint, FOSS-dependency check |
| M1 | Profiles, auth (basic + Access + custom headers), connect, Problems view; capture fixtures; finalise section 5 filters |
| M2 | Browse, filter, host/service detail |
| M3 | Actions via `cmd.cgi` |
| M4 | Background poll and notifications |
| M5 | Widget (four tiers) |
| M6 | nagiosgraph in-app graphs |
| M7 | Release pipeline, signing key, first GitHub Release; re-check name/id collisions |
| M8 | F-Droid submission |

Build iteratively: each milestone is a runnable app, so feasibility is shown early and direction can change.

Outside this repo: a follow-up in `pgmac-net/homelabia` to publish the maintainer's Nagios through the Cloudflare tunnel behind an Access policy with a service token, which is what makes the app usable off the home network.

## 13. Open questions

- Exact `statusjson.cgi` filter parameters for "unhandled" (M1, against a live instance).
- Whether Nagios Core versions older than 4.4 return the fields the detail screen needs (M1 fixtures; raise the minimum version if not).
- Whether Android 8-11 devices need a different widget fallback, since dynamic colour and some Glance behaviour need Android 12 (M5).
