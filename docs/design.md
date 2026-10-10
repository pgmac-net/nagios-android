# Nagwatch v1 design

Status: **locked for v1** (2026-10-08; sections 4 and 5 revised 2026-10-09 from measurements in M1). Source: pgmac-net/homelabia#210. Change by PR to this file; record hard-to-reverse changes as ADRs. Terms are defined in [`../CONTEXT.md`](../CONTEXT.md).

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

- Display name, base URL (e.g. `https://nagios.example.org/nagios`). The CGI directory is found on connect by trying `{base}/cgi-bin/` then `{base}/nagios/cgi-bin/`; a pasted CGI directory is used as it is. The result is stored so later polls skip the search
- Username and password (HTTP basic auth)
- **Cloudflare Access** section: Client ID, Client Secret. Sent as `CF-Access-Client-Id` and `CF-Access-Client-Secret` on every request
- **Custom headers**: list of name/value pairs for other proxies
- nagiosgraph: enabled toggle, base URL template (default `{base}/cgi-bin/`)
- Polling: enabled, interval (15 min default and minimum), unhandled-only filter
- Private CAs: the app trusts system **and user-installed** CA certificates (network security config), so a private CA installed in Android Settings just works. There is no in-app CA import in v1 and there is never an "ignore TLS errors" switch

Secrets (password, Client Secret, header values) are encrypted at rest with a Keystore-held key and excluded from Android backup. Cleartext `http://` is refused unless the user explicitly enables it per profile, with a warning; Cloudflare Access credentials are never sent over `http://` at all ([ADR 0004](adr/0004-cleartext-permitted-in-manifest-enforced-in-app.md)). Redirects are never followed: credentials go only to the origin the user configured.

**Error classification** (shown distinctly, never a generic failure):

| Condition | Error class | Message |
|---|---|---|
| Redirect to `*.cloudflareaccess.com`; `WWW-Authenticate: Cloudflare-Access`; 403 from Cloudflare | `AccessRejected` | Cloudflare Access rejected the credentials |
| 401 | `BadCredentials` | Nagios rejected the username/password |
| 403 from the origin | `Forbidden` | This user is not allowed to see that |
| TLS failure | `Certificate` | Certificate problem (with detail; hint about installing a private CA) |
| DNS failure, connection refused, timeout | `Unreachable` (with reason) | Instance unreachable |
| 404, HTML, or JSON without a Nagios `result` block | `NotNagios` | Not a Nagios JSON CGI (wrong base URL?) |
| 200 with `result.type_code` not 0 | `Api` | Nagios error, its own message shown |
| `http://` without the opt-in | `CleartextRefused` | Unencrypted HTTP is not enabled for this profile |
| `http://` with Access credentials | `AccessOverCleartext` | Access credentials are never sent unencrypted |
| Any other redirect | `Redirected` | The server redirected to X; use that URL |
| A response over 8 MiB, or a list that never ends | `ResponseTooLarge` | The server sent more than the app will accept |
| Other HTTP status, typically 5xx | `Http` | Server error |

When a host has several addresses, every connection attempt is considered and a TLS failure takes precedence over "refused": it means something did answer.

## 5. Nagios API contract

All reads go through `statusjson.cgi` / `objectjson.cgi` (JSON). All writes go through `cmd.cgi` (HTML form POST; no JSON). The acting Nagios user needs command authorisation for writes; the app detects a lack of it and disables action buttons.

### Reads

Measured against Nagios Core 4.5.9 with a read-only user (2026-10-09). Sanitised captures are in `app/src/test/resources/fixtures/`.

| Need | Call |
|---|---|
| Connect check, version | `statusjson.cgi?query=programstatus` |
| Every host | `query=hostlist&details=true` |
| Services in a problem state | `query=servicelist&details=true&servicestatus=warning critical unknown` |
| Every service, name and state only | `query=servicelist` (no `details`) |
| One record, for a detail screen | `query=host` / `query=service` with `hostname` (and `servicedescription`) |
| Comments / downtimes for one object | `query=commentlist&details=true` / `query=downtimelist&details=true` with the same filters |

Every list request also sends `formatoptions=enumerate` and is paged with `start` / `count`.

What the measurements showed:

- **There is no server-side "unhandled" filter.** The JSON CGI has no `serviceprops` / `hostprops`. Unhandled is computed in the app (`ProblemClassifier`) from `problem_has_been_acknowledged` and `scheduled_downtime_depth`, which only exist with `details=true`. A service's own downtime depth does not reflect its host's downtime, which is why every host is fetched.
- **States are bitmask integers by default** (ok 2, warning 4, unknown 8, critical 16). `formatoptions=enumerate` returns words, which is what the app parses.
- **Times are epoch milliseconds**, with 0 meaning never.
- **Lists nest** host -> service -> detail. Hosts with no matching service are still emitted, as empty objects.
- **Errors arrive as HTTP 200** with a non-zero `result.type_code`. The body is always checked.
- **List order is stable**: the same `start` / `count` window selects the same records with and without details.
- **The CGI directory varies by install**; `/nagios/cgi-bin/` and `/cgi-bin/` were both seen.

More measurements, from M2:

- **A single record has exactly the fields of a list entry** (41 for a service, 39 for a host), so a detail screen needs no parser of its own.
- **State-only lists are cheap**: about 2 KB for 48 hosts and 9 KB for 221 services, against roughly 370 KB for every service with details. So each poll lists every service by name and state, and fetches details only for hosts and for services in a problem state. Any other service's detail is fetched when its screen is opened.
- **The host filter on comments and downtimes is not exact.** Asking for a host's comments returns its own **and** every comment on its services, mixed. The app filters again to the one object asked about.
- **Comments accumulate.** Acknowledgement comments are persistent and are not cleaned up; one instance held three years of them. What is fetched is capped at the newest 200 per object, and what is cached at the newest 20.
- **A downtime's `duration` is in seconds**, unlike the timestamps, which are milliseconds. This comes from Nagios Core's source (`json_status_downtime_details` in `cgi/statusjson.c`), not from a capture: the instance measured had no downtime scheduled, so the downtime fixture is hand-written to that function's fields.
- **Percent signs arrive single.** Nagios doubles them internally because the string passes through `printf`, which turns them back.

#### One bad record can crash the CGI

A service whose plugin output contains non-ASCII text makes `statusjson.cgi` answer **HTTP 500** for any `details=true` window that includes it. The same record without details is fine. One broken check must not blind the app, least of all when that check is the one alarming, so list fetches go through `ResilientListFetcher`:

1. Page through the list with details. A healthy server costs nothing extra.
2. On a 5xx, first confirm the same window works without details. If it does not, the server is failing in general: report the error and stop.
3. Otherwise halve the window until the failing record is alone, and fetch that one without details.

That record is returned **degraded** (name and state only), shown as "details unavailable", and counted as unhandled because nothing proves otherwise. The search is bounded (40 extra requests, 5 degraded records per list); past that the server error is reported. The search is repeated on each poll: list positions shift as states change, so a remembered position cannot be trusted.

How much is fetched is the server's decision, so it is bounded rather than trusted: a single response is capped at 8 MiB, and a list is cut off at 50,000 records (a server that never sends a short page would otherwise be followed forever). Both report `ResponseTooLarge`.

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

As built in M2: a bottom navigation bar with **Problems, Hosts, Services**, and the profile switcher in the top bar on every tab. **There is no Settings tab yet.** It arrives in M4 with the first thing there is to set (polling and notifications); an empty tab was not worth a place in the bar. Back from Hosts or Services returns to Problems, where the app opens. A host or service opens over the bar, on its own screen.

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

How M1 implements this screen, where it differs from the wireframe:

- **Refresh is foreground only.** The app fetches when the screen opens or is returned to (if what it holds is over a minute old) and on pull-to-refresh. Background polling and notifications are M4.
- **Stale means older than 30 minutes**, twice the 15-minute poll interval the design allows, and is labelled with its age. A failed refresh keeps the last good list and says it may be out of date, so a network error never blanks a screen that was showing real problems.
- **Chips** read "Critical (3)", "Warning (1)", "Unknown (0)" and "2 hosts down", and tapping one filters the list to it; several can be on at once.
- **Services on a down host** appear under that host and are not counted separately.
- **Handled problems** (acknowledged, in downtime) are listed below under a "Show handled (N)" toggle, collapsed by default, each saying why it is handled. "Everything is OK" is only shown when nothing is handled either: an acknowledged critical is still a critical.
- **Profile switching** is a menu on the title, with Edit and Manage profiles beneath the list. The selection is remembered across restarts (since M2; M1 kept it only through rotation and process death).
- **First run** shows an explanation and an "Add profile" button.
- Every host is fetched on every refresh (needed for host state and host downtime).

### Hosts / Services

Searchable lists with state-filter chips (OK, WARN, CRIT, UNKN, PENDING; UP, DOWN, UNREACH) and a "show handled" toggle.

How M2 implements them:

- **Sorted worst first**: services CRITICAL, WARNING, UNKNOWN, then PENDING and OK; hosts DOWN, UNREACHABLE, then PENDING and UP. Within a severity the unhandled come before the handled, so an acknowledged critical sits below the criticals nobody has dealt with and above every warning. Then by name, ignoring case.
- **Search** is a plain, case-insensitive substring match, taken literally. On Services it matches the host name as well as the check name.
- **The lists open on what is wrong.** Hosts starts with DOWN and UNREACH switched on, Services with WARN and CRIT. Everything else is one tap away, and its chip says how many there are. The Problems tab starts with no chip on, which there means everything. When nothing is in the states that are on, the list says that, not "no hosts"; a search that only matches a state that is off says to look at the chips.
- **Chips are tinted with the colour of their state**, on all three tabs: faintly when off, more strongly when on, with a tick on the ones that are on so the strength of a tint is never the only sign. The label stays in the normal text colour. The Handled chip is not about a state and keeps the plain look.
- **Chips** carry a count ("CRIT (3)") and several can be on at once. The count follows the search and the handled switch but not the other chips, so a chip always says what tapping it would show. PENDING only has a chip while something is pending.
- **Handled problems are shown by default** here, the opposite of the Problems tab, at the end of their severity, and a "Handled (N)" chip switches them off. A host that vanished from the list when someone acknowledged it would look like a host that is not monitored. The chip only appears when there is something handled to hide.
- **A service that is OK shows its name and state and nothing else.** Each poll lists every service by name and state and fetches details only for those with a problem (section 5), so output and "how long for" are there for problems and for every host, and for other services only once opened.
- **Each tab keeps its own search, chips and scroll position** while another tab is shown. Switching profile, or starting the app again, returns them to the defaults.
- The time of the data and the stale, failed and "details unavailable" banners are the same on every tab.

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

- `profiles` (database `profiles.db`, since M1): name, base URL, remembered CGI directory, username, cleartext opt-in, Access client ID, and three secret columns holding ciphertext only: `password_enc`, `access_client_secret_enc`, and the values inside `custom_headers`. Secrets are AES-256-GCM under a non-exportable Android Keystore key (`SecretCipher`). The UI never reads a secret back: it shows "saved" and offers Replace. If the key is lost (device restore), the profile survives and its credentials have to be entered again.

Status is cached in a second database, `status.db`, which is disposable where `profiles.db` is not ([ADR 0005](adr/0005-status-cache-in-a-separate-disposable-database.md)). There is no history in v1.

- `service_state`: every service's name and state, replaced wholesale by each poll.
- `object_detail`: a detail record per host or service. A row is there because the latest poll included it (every host, and services in a problem state), or because the user opened it, or both; a row that is neither is deleted.
- `comment`, `downtime`: for opened records only, and removed with them.
- `poll_meta`: when each profile was last polled successfully.

A poll is written in one transaction, so a killed process never leaves half of one. Everything is bounded per profile, because how much Nagios sends is Nagios' decision: lists cannot accumulate, opened records are limited to the 200 most recent, and comments and downtimes to 20 per object.

On opening, the app shows the cached result at once, labelled with its age and marked stale past 30 minutes, then refreshes. Data is never hidden for being old. The chosen profile is remembered across restarts (DataStore).

What notifications need to remember (the last state notified per object, for de-duplication and recovery notices) is decided in M4.

Schema versions of both databases are exported to `app/schemas` and committed. `profiles.db` has no destructive-migration fallback: every change to it ships with a migration and a test. `status.db` is the opposite by design.

## 11. Security notes

- Credentials and tokens never logged. Debug logging redacts headers.
- No analytics, no crash reporter, no third-party network calls; the only traffic is to the user's profile hosts.
- Backups exclude secrets. User-installed CAs are trusted (section 4); the trade-off is that a CA the user or a device-management profile installed can also inspect this app's traffic, as with any browser on the device.
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

Outside this repo: pgmac-net/homelabia#211. The maintainer's Nagios is already published behind Cloudflare Access; what is missing for off-network use is a Service Auth policy and a service token on that Access application. (An earlier revision of this document said the instance was internal-only. That was wrong.)

## 13. Open questions

- Whether Nagios Core versions older than 4.4 return the fields the detail screen needs (M1 fixtures; raise the minimum version if not).
- Whether Android 8-11 devices need a different widget fallback, since dynamic colour and some Glance behaviour need Android 12 (M5).
