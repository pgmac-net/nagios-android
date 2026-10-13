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
| Network | OkHttp + kotlinx.serialization; interceptors for basic auth and Access/custom headers. Graph PNGs come through the same client and are decoded by the platform: no image library (ADR 0010) |
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
- **Graphs** (nagiosgraph): a switch, on by default, and an optional graph path. Blank means the CGI directory the app found for Nagios; otherwise a path on the same server, such as `/nagiosgraph/cgi-bin/`. Never a URL (ADR 0010)
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
| Timeout to an address that looks local, without Android's local network permission | `LocalNetworkBlocked` | "Android may be blocking Nagwatch from your local network", with a button, on the full-screen error and on the banner above an earlier result (ADR 0007) |
| 404, HTML, or JSON without a Nagios `result` block | `NotNagios` | Not a Nagios JSON CGI (wrong base URL?) |
| 200 with `result.type_code` not 0 | `Api` | Nagios error, its own message shown |
| `http://` without the opt-in | `CleartextRefused` | Unencrypted HTTP is not enabled for this profile |
| `http://` with Access credentials | `AccessOverCleartext` | Access credentials are never sent unencrypted |
| Any other redirect | `Redirected` | The server redirected to X; use that URL |
| A response over 8 MiB, or a list that never ends | `ResponseTooLarge` | The server sent more than the app will accept |
| Other HTTP status, typically 5xx | `Http` | Server error |

When a host has several addresses, every connection attempt is considered and a TLS failure takes precedence over "refused": it means something did answer.

## 5. Nagios API contract

All reads go through `statusjson.cgi` / `objectjson.cgi` (JSON). All writes go through `cmd.cgi` (HTML form POST; no JSON). The acting Nagios user needs command authorisation for writes; the app can tell a read-only user in advance and disables the action buttons, and reports any other refusal when it happens.

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

#### A bell or a vertical tab in plugin output

Nagios Core's JSON writer escapes a bell as `\a` and a vertical tab as `\v`. Neither is a JSON escape, so one check printing either makes the whole response unparseable (HTTP 200, not JSON), and with it the whole poll: not one record, which is what the non-ASCII case above degrades to. Measured on Nagios Core 4.5.3 by passing every control character through a check: only these two are written invalidly; the others get a valid escape or are legal as they stand.

The body is therefore repaired before it is parsed (`repairInvalidEscapes`, applied in `interpretResponseBody`): `\a` becomes `\u0007` and `\v` becomes `\u000b`. Escapes are read left to right, a backslash with the character after it, because that is how JSON reads them. Nagios writes a real backslash as `\\`, so a real backslash before an `a` is `\\a` and is left alone, and a real backslash before a bell is `\\\a`: an escaped backslash, then the invalid escape. A body with neither is returned untouched.

- **There is no fallback to the isolation search above.** A body that is still not JSON after the repair is "not Nagios", as before. Probing for a bad record in a response that is not Nagios' (a captive portal's page, say) would only cost requests.
- **The characters are left in the text** once parsed, like every other control character. Showing them tidily is a separate question.
- **The repair has an allowance of its own.** The size limit applies to what the server sent, before the repair, and a repair makes a body four characters longer per escape. The first 65,536 are exact; past that a bell or vertical tab is written as the nearest valid two-character escape (`\\b`, `\\f`), which does not grow the text. So growth is at most 256 KiB whatever the body, and **the poll is never refused for it**: failing the whole poll for one check's output is the bug this section is about. A real check prints a bell now and then, not tens of thousands. The fuzz target asserts the bound.

How much is fetched is the server's decision, so it is bounded rather than trusted: a single response is capped at 8 MiB, and a list is cut off at 50,000 records (a server that never sends a short page would otherwise be followed forever). Both report `ResponseTooLarge`.

### Writes (`cmd.cgi`)

Read from `cgi/cmd.c` of Nagios Core 4.5.9 and then checked by sending each command to a Nagios Core 4.5.3 kept for the purpose (`scripts/test-nagios`). Pages captured from it are in `app/src/test/resources/commandpages/`.

`cmd_typ` values:

| Action | Host | Service | Also sent |
|---|---|---|---|
| Acknowledge problem | 33 | 34 | `com_data`; `sticky_ack`, `send_notification`, `persistent` only when on |
| Remove acknowledgement | 51 | 52 | |
| Schedule fixed downtime | 55 | 56 | `com_data`, `start_time`, `end_time`, `fixed=1`, `trigger=0` |
| Cancel downtime | 78 | 79 | `down_id` only |
| Force recheck | 96 | 7 | `start_time`, `force_check` |
| Add comment | 1 | 3 | `com_data`, `persistent` |

A commit is a form POST with `cmd_mod=2`; without it `cmd.cgi` only shows the form.

What the source and the test instance showed, some of it against what this document used to say:

- **A forced check is the ordinary "schedule a check" (7, 96) with `force_check` set.** `cmd.cgi` does not accept the separate forced types 54 and 98: it shows no form for them and answers a commit with "not authorized". An earlier version of this table had them.
- **Times are text, in the server's `date_format` and local timezone**, and a wrong order of day and month is not rejected. Neither setting is in the JSON API. How the app deals with that is [ADR 0006](adr/0006-times-are-learned-from-nagios-and-read-back.md).
- **A read-only user is told so on a plain request for a form**, before anything is committed. That is how the app knows without trying to change anything.
- **Whether a user may command a particular object is only said at commit.** There is nothing to ask first.
- **"Not authorized" is also what Nagios says for an object that does not exist**, a downtime id it does not know, and a command type it does not offer.
- **The author is normally forced to the logged-in user** (`lock_author_names`), using the contact's alias if it has one. What the app sends as author is ignored.
- **A comment is required** for an acknowledgement, a downtime and a comment, and **is altered**: `<` and `>` are removed and `;` becomes a space. The app does the same before sending (`storedCommentText`), so what it sends is what is stored.
- **There is no CSRF token.** The form id is only enforced when the request carries Nagios' cookie, and the app sends none.
- **"Successfully submitted" means written to the command file.** Nagios acts on it later; on the test instance the effect showed in the JSON API between 3 and 10 seconds afterwards. Some commands are then dropped without a word: an acknowledgement of something that is no longer a problem, a downtime wholly in the past.
- **Every answer is HTTP 200.** What happened is in the page.

Nagios answers a commit with one of these, each with fixed wording, and the app tells them apart:

| Page | The app's name for it |
|---|---|
| "Your command request was successfully submitted" | accepted |
| "do not have permission to submit the command" | read-only user |
| "not authorized to commit the specified command" | not authorised for this object (or it does not exist) |
| any other message, such as "Comment was not entered" | rejected, with Nagios' reason |
| "not checking for external commands" | commands disabled |
| "error occurred while attempting to commit" | Nagios could not write its command file |
| anything else | **unknown**, never success |

Reading that HTML is the most fragile thing in the app. It is all in one file (`CommandPage`), tested against the captured pages, and fuzzed. A page that is not recognised is never treated as success, so a future Nagios that rewords a message costs a clear "unknown" and a one-file fix, not a false "done".

Three rules for sending:

- **A command is sent at most once.** No retry. If the connection fails after the request may have left, the outcome is *unknown* and the app says so.
- **Nothing is queued.** With no connection an action is refused there and then, not sent later when it may no longer be wanted.
- **The same connection rules as reads** ([ADR 0004](adr/0004-cleartext-permitted-in-manifest-enforced-in-app.md)).

After any command the app re-reads the affected object and reports what it sees, not what the page said (section 6, M3).

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
- **Stale means older than twice the profile's interval** (30 minutes at the default; two hours for a profile checked hourly), and is labelled with its age. Once is not enough: Android runs a poll about every interval, never exactly, so data one interval old is what a healthy poll looks like just before the next; twice means one was missed. A fixed half hour would call an hourly profile stale half the time. The screens and the widget take the rule from one place (`Staleness`). A failed refresh keeps the last good list and says it may be out of date, so a network error never blanks a screen that was showing real problems.
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

Host detail is the same minus the service name and without a graph: nagiosgraph has no fixed name for a host's own check (section 9).

How M2 implements the detail screens (graphs are M6, so not here yet; actions are described after this list):

- **Opening one shows something at once.** In order of preference: what was saved the last time it was opened, then what the last poll carried (every host, and every service with a problem). It is labelled "As of" with its date, and with its age once that passes 30 minutes. Then the record, its comments and its downtimes are fetched, and each is saved as it arrives.
- **A service that was fine and has never been opened** has no details anywhere until Nagios answers. It shows the state the last poll gave it, and "Loading".
- **Header:** state, HARD or SOFT, attempt n of m, how long in this state, last and next check, active or passive.
- **Output**, selectable. Long output and performance data are folded away until asked for; performance data is shown raw.
- **Status:** acknowledged, in scheduled downtime, active checks, notifications, flapping, each spelled out.
- **A service links to its host; a host lists its services**, worst first, each opening that service.
- **Comments**, newest first: the first 20, then "Show all". **Scheduled downtime:** who, the window, fixed or flexible, whether in effect.
- **Comments and downtimes fail on their own.** Each is its own request. If one cannot be fetched, its section says so and why, keeps whatever was saved earlier, and the rest of the screen is unaffected. This is also how an older Nagios, or a user without the right to read them, is handled.
- **When Nagios cannot be reached at all**, the saved record stays with a banner giving the reason, and Nagios is asked once, not three times.
- **A record Nagios cannot serialise** (section 5) shows its state and says details are unavailable; it does not show empty output as if the check had said nothing.
- **Open in Nagios** hands the object's page in the web interface to the browser. The link is built from the profile's address alone: no credentials are ever put in a URL passed to another app.

How M3 adds the actions:

- **Actions** sits under the header: Acknowledge (only for a problem that is not acknowledged; Nagios drops an acknowledgement of anything else), Remove acknowledgement (only when acknowledged), Recheck, Comment.
- **One command at a time.** While one is on its way the buttons are off, so a second tap cannot send a second command.
- **What became of it is said in words, under the buttons**, and the words keep four things apart:
  - *Sending*, then *Nagios accepted it. Waiting to see it take effect*.
  - **Confirmed** ("Acknowledged.", "Comment added.", ...): only after the object was read again and showed the effect. The app looks six times over about 25 seconds; on the test Nagios effects took 3 to 10.
  - **Not confirmed**: accepted, but not seen in that time. Not shown as an error and not shown as done.
  - **Refused**, with which refusal it was; **not sent** ("Nothing was changed"); and **unknown** ("may or may not have reached Nagios. Look at what it shows now before trying again").
- **How each is confirmed:** an acknowledgement by the object saying it is acknowledged; a removal by it saying it is not; a recheck by a last-check time later than the one read just before sending; a comment by a comment whose text is what Nagios keeps of what was typed.
- **A read-only user** is found out with one request that changes nothing (the form for a command, section 5), once per profile for as long as the app runs. The buttons are then shown disabled with "This Nagios user is read-only, so actions are unavailable." If it could not be found out, the buttons work, and a refusal says so when it comes. "Test connection" in the profile editor says the same.
- **Not being authorised for one object cannot be known in advance** (section 5), so it is reported when Nagios refuses.
- **A send that has begun is finished** even if the screen is left: a request cut off part-way is the one case where nobody could say whether it arrived.
- **Nothing is retried and nothing is queued.** A command that cannot be sent now is reported as not sent.
- After a confirmed command the screen and the lists behind it are refreshed.

### Acknowledge form

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

This opens in the page, in place of the action buttons, not as a dialog: it stays clear of the keyboard, and a text field in a dialog window cannot be exercised by the project's screen tests (see `docs/development.md`). The comment form is the same without the three options. Removing an acknowledgement asks first, in a dialog; a recheck does not ask.

Notify starts on and persistent off, as in Nagios' own web interface; nothing chosen is remembered. If Nagios would alter the comment (section 5), the form shows what it will store before it is sent.

Sticky is **off by default** and shows the warning when ticked. Rationale: a sticky acknowledgement hid a failed drive on 2026-10-02 in the maintainer's homelab.

### Downtime form

Fixed downtime only. Like the acknowledge form it opens in the page. A required comment; a length starting now (30 min, 1 h, 2 h, 4 h, 8 h, 24 h; two hours is selected to begin with, as in Nagios' own form) or **Custom**, which gives a start and an end, each changed with a date picker and then a time picker in the phone's own timezone.

- **The window is shown twice before it is sent:** in the phone's time, and as text exactly as the server will be told it, with the server's offset from UTC. The second line is there so that a surprise about timezones is had before the downtime exists. It is left out when it cannot be written without guessing (see the date-order question).
- **A length starts when it is sent**, not when the form was opened.
- **A window that ends before it starts, or is already over, cannot be sent**, and the form says why. Nagios would accept the second and silently drop it.
- **Every downtime is read back.** After Nagios accepts one, the app reads the object's downtimes and looks for one that was not there before and carries the comment as Nagios keeps it. Its window is then compared with the one asked for, to the second.
  - Same window: "Downtime scheduled, for the window asked for."
  - **A different window** (a mismatch): both windows are shown, with a button to cancel the one Nagios made. This is not sent again with a correction: the app would be guessing at why it moved, and a second downtime is worse than one in the wrong place that the person has been told about.
  - Not found in about 25 seconds: not confirmed.
- **The date-order question.** On a day whose number equals its month, for a server whose date format has never been seen, the window cannot be written safely (section 5, ADR 0006). Nothing is sent. The app shows the server's own "now" and asks: month first or day first? The answer is kept on the profile and the downtime is then sent. Declining sends nothing.
- **What a server showed of its date format is kept on the profile** every time a downtime is scheduled, and forgotten if the profile's address changes.
- **Cancelling:** each listed downtime has "Cancel downtime", which asks first, naming the window and the comment. It is confirmed when the downtime is no longer listed; a list that could not be read is not taken to mean it is gone.

### Settings

Profile list/editor (section 4), notification toggles per profile, theme, about (version, licence, "not affiliated with Nagios Enterprises").

## 7. Background polling and notifications

- Notifications are off until switched on, per profile.
- **A profile is polled in the background while it has notifications on or a widget on the home screen**, and at no other time. A widget is a reason to poll and never a reason to notify ([ADR 0009](adr/0009-a-widget-is-a-reason-to-poll-and-never-a-reason-to-notify.md)).
- One WorkManager periodic job per polled profile, every 15 minutes (Android's floor), 30 minutes or an hour.
- Each poll writes to Room; the widget and the app read the cache. One network cost serves all three consumers.
- Expected alert latency is up to about 15 minutes ([ADR 0002](adr/0002-no-backend-direct-polling.md)). The README says so plainly.

What a poll is worth saying is decided in one place (`Announcer`), and why is in [ADR 0008](adr/0008-what-is-announced-and-where-that-is-remembered.md):

- **Only HARD problems**, whether or not Nagios itself would notify for them.
- **New, and again when worse** (WARNING to CRITICAL). Better without recovering updates the notification quietly.
- **The first successful poll is one summary**, and so is any poll that would raise more than five at once.
- **Unhandled only by default.** A per-profile switch includes acknowledged problems and those in downtime. A problem that becomes handled loses its notification without a word.
- **A recovery replaces the problem's own notification**, silently, and clears after an hour. Something that was only in a summary recovers silently.
- **A failed poll never produces "all OK"** and never changes what is known. A failure that may pass by itself raises one notification after three polls in a row, unless the profile's "Tell me when this Nagios can't be reached" is off. One that will not (a rejected password or Access token, unreadable credentials) raises it at once, regardless. The next good poll removes it.
- One notification per problem, grouped per profile, with a channel per kind so classes can be muted in system settings. No buttons: a tap opens the host, the service or Problems.

How it is switched on:

- The profile editor has a "Background checks" part, always shown, with the interval (15 minutes, 30 minutes, 1 hour). It is not inside Notifications because notifications are not the only reason a profile is polled: a widget is another (M5), and an interval hidden behind a switch that is off would be one nobody could see or change.
- Below it, the "Notifications" section: the switch, "Only unhandled problems" and "Tell me when this Nagios can't be reached". The last two show only while the switch is on.
- Switching it on asks Android for permission to show notifications, there and then, if the app does not have it. If Android says no, the switch stays off and the section says why, with a button that asks again, or opens the app's notification settings once Android has stopped asking.
- After a profile has loaded successfully the app offers notifications for it, once. "Not now" is remembered. Profiles from before this existed are offered once too; none is switched on by an update.

How it is shown (`AndroidNotifier`):

- **One notification per problem**, found again by a tag made from the profile and the object, so worse, better and recovered all replace it. A profile's notifications share a group.
- **Six channels**: Down and critical (high importance: sound and heads-up), Warning, Unknown, Recovered (low: silent), Can't reach Nagios, Summaries. The person tunes them in Android's settings; the app sets an importance only when it creates a channel.
- **Text**: the state and the object ("CRITICAL: Disk / on web01"), the first line of the plugin's output, and the profile's name. Nothing that the Problems list does not already show. Lock-screen visibility is left to the system setting.
- **A recovery** takes the problem's place, silently, and clears itself after an hour. A problem's notification stays until it is dealt with or dismissed.
- **A tap** opens the host or the service, or the profile's Problems list for a summary or an outage, and selects that profile. No buttons: acknowledging from a notification is left for later.
- **Without Android's permission** nothing is posted, the background checks continue, and the profile's editor says notifications are blocked.
- **When a profile stops being watched** (notifications switched off, or the profile deleted) everything it has showing is taken down and what was announced for it is forgotten, so switching back on is a first poll: one summary, then changes. On the first look after the app starts, anything showing for a profile that does not have notifications on is swept up as well, which also removes what an earlier version stranded.
- **A tap for a profile that no longer exists** opens the app as the launcher would and goes nowhere.

## 8. Widget

Built with Glance `SizeMode.Responsive`; the system supplies size ranges in dp, so tiers are breakpoints rather than exact cell counts.

| Tier | Approx. cells | Content |
|---|---|---|
| **Badge** | 1x1 | Fill colour = worst unhandled state; one number = unhandled problems. Green tick at zero. Grey + icon when stale or unreachable |
| **Counts** | 2x1 to 4x1 | Chips: CRIT / WARN / UNKN / hosts DOWN (unhandled; acknowledged dimmed if room). In one row from about 270 dp wide (four cells), with the last-update time; two by two below that, so that none is cut off |
| **Short list** | 2x2 to 4x2 | Counts row + top 2-4 problems, worst then newest: host, service, duration. No plugin output |
| **Full list** | 3x3 to 4x6 | Counts row + scrollable list: host, service, state colour, duration, one truncated line of plugin output. Refresh button, profile name, last-update stamp |

Behaviour for all tiers:

- Tap a count or badge: open the Problems view. Tap a list row: open that service's detail.
- **Stale** (last good poll older than 2x the interval) and **unreachable** (the most recent poll failed) are shown distinctly. Old green is never shown as current. Both **keep the last known numbers**, drawn greyed, with "as of" and the reason: a host that was down twenty minutes ago probably still is. A profile never polled says "no data yet", which is not the same as nothing wrong.
- What a widget shows is decided in one plain function (`WidgetModel`), from what is on disk: the last good poll, what the most recent attempt came to, and the profile's interval. The four layouts each draw some of it and decide nothing.
- One widget instance = one profile, chosen in a configuration screen that Android opens when the widget is placed, and again from the widget's menu (Android 12 and later). Multiple widgets allowed. The screen says what a widget costs before anything is chosen: the profile is checked in the background for as long as the widget is there, and that does not turn notifications on.
- A widget with no profile, or whose profile was deleted, says so, and a tap opens the configuration screen.
- **Refresh** (Full list only) polls the widget's profile once, now, whether or not its notifications are on.
- Widgets are redrawn when a poll finishes, whoever asked for it, and by Android about every half hour, which is what makes one turn "out of date" when no poll can run.
- Home screen only; not offered for the lock screen. A widget puts host and service names on the home screen.
- Taps come in by the same non-exported name a notification's tap uses, so another app cannot forge one.
- Follows system light/dark and Material You colour. State colours (red/amber/green) are fixed so they keep their meaning. Below Android 12 there is no Material You, so the background and text use Glance's fixed scheme; **the widget is untested on Android 8 to 11** (section 13).
- The tiers are breakpoints in dp, not cell counts: under 110 dp wide is a Badge; under 110 dp high is Counts; from 250 dp high and 180 dp wide is the Full list; anything between is the Short list, with two to four problems by height. Under 270 dp wide the counts are two rows in every layout. A launcher that reports an odd size gets the nearest layout.

## 9. nagiosgraph

Why it is built this way is ADR 0010.

- Service detail fetches `showgraph.cgi?host=H&service=S&period=P&geom=WxH` with the profile's credentials and headers, through the app's own client. Periods: day, week, month, year. Nothing else is ever sent, and never `rrdopts`.
- **Where**: the CGI directory the app found for Nagios, unless the profile gives a graph path, which is a path on the same server and not a URL.
- **Size**: the plot is asked for in dp, not device pixels, in a few steps of width, and a larger one for the full-screen viewer. rrdtool draws text at a fixed pixel size, so a picture asked for at a phone's resolution has labels that cannot be read.
- **Whether there is a graph is decided from the picture.** nagiosgraph answers 200 and `image/png` when it has nothing to draw: a small picture of the error text, a blank one, or nothing. A graph is a PNG at least as large as the plot asked for and not much larger. Anything else leaves the Graph section out, silently; there is no error spam.
- **Remembered for the session, never stored**: the services with no graph, the profiles with no nagiosgraph at all (404, 403, or a 200 that is not a PNG), and a few pictures. A pull to refresh asks again.
- **Hosts have no graph.** nagiosgraph files a host's own check under whatever name the administrator gave it, so there is nothing to ask for that would be right on more than one server.
- **On screen**: a Graph section on service detail, under the actions, with a chip for each period. It draws nothing at all when there is no graph. A tap opens a viewer with the screen to itself, turned on its side while it is open, with its own larger picture, pinch (to four times), drag (to the picture's edges) and double-tap. The viewer is the one place that says "there is no graph for this period", because there a graph was asked for.
- The picture is shown as nagiosgraph drew it, on white in either theme.
- "Open in browser" opens `show.cgi?host=H&service=S`. It is secondary: the external browser has none of the app's credentials, so it works only where the browser can already reach and authenticate to Nagios.

## 10. Data model (Room)

- `profiles` (database `profiles.db`, since M1): name, base URL, remembered CGI directory, username, cleartext opt-in, Access client ID, and three secret columns holding ciphertext only: `password_enc`, `access_client_secret_enc`, and the values inside `custom_headers`. Secrets are AES-256-GCM under a non-exportable Android Keystore key (`SecretCipher`). The UI never reads a secret back: it shows "saved" and offers Replace. If the key is lost (device restore), the profile survives and its credentials have to be entered again.

Since M4 a profile also carries its notification choices: `notify` (off by default), `poll_minutes`, `notify_unhandled_only`, `notify_unreachable`, and `notify_offered`, which records that the app has made its one offer for that profile.

Status is cached in a second database, `status.db`, which is disposable where `profiles.db` is not ([ADR 0005](adr/0005-status-cache-in-a-separate-disposable-database.md)). There is no history in v1.

- `service_state`: every service's name and state, replaced wholesale by each poll.
- `object_detail`: a detail record per host or service. A row is there because the latest poll included it (every host, and services in a problem state), or because the user opened it, or both; a row that is neither is deleted.
- `comment`, `downtime`: for opened records only, and removed with them.
- `poll_meta`: when each profile was last polled successfully.
- `poll_attempt`: what the most recent poll of each profile came to, whether or not it worked: when, and why it failed if it did. A widget is drawn in whatever process Android has, often one that never saw the poll, and has to tell "could not be reached" from "nothing is wrong".

A poll is written in one transaction, so a killed process never leaves half of one. Everything is bounded per profile, because how much Nagios sends is Nagios' decision: lists cannot accumulate, opened records are limited to the 200 most recent, and comments and downtimes to 20 per object.

On opening, the app shows the cached result at once, labelled with its age and marked stale past twice the profile's interval, then refreshes. Data is never hidden for being old. The chosen profile is remembered across restarts (DataStore).

What has been announced is kept there too (`announced`, `announce_meta`): each problem the person has been told about or that was there at the first poll, and whether an outage has been announced. It cannot be fetched again, but it can be rebuilt: if it is lost the next poll is treated as a first poll and summarised ([ADR 0008](adr/0008-what-is-announced-and-where-that-is-remembered.md)).

Schema versions of both databases are exported to `app/schemas` and committed. `profiles.db` has no destructive-migration fallback: every change to it ships with a migration and a test. `status.db` is the opposite by design.

## 11. Security notes

- Credentials and tokens never logged. Debug logging redacts headers.
- No analytics, no crash reporter, no third-party network calls; the only traffic is to the user's profile hosts.
- What a server sends back is untrusted input and cannot crash the app: bodies are capped in size (8 MiB) and in nesting (32 levels) before they are parsed, and a response is only ever a result or a `NagiosError`. The parsers are fuzzed on every pull request (`docs/development.md`, "Fuzzing").
- Backups exclude secrets. User-installed CAs are trusted (section 4); the trade-off is that a CA the user or a device-management profile installed can also inspect this app's traffic, as with any browser on the device.
- Cloudflare Access service tokens are long-lived static secrets. The README recommends a dedicated token with a short expiry and a dedicated Nagios user with only the command permissions the user wants the phone to have.
- **Android 17 local network access** (ADR 0007). The app declares `ACCESS_LOCAL_NETWORK` and asks for it once, with an explanation of its own first, the first time a profile that looks local is tested or loaded. A refusal never stops a request, because a Nagios reached over a VPN needs no permission and the app cannot tell the paths apart. A timeout to a local-looking address without the permission is reported as `LocalNetworkBlocked`, worded as an inference. Polling (M4) and the widget (M5) have no screen to ask from and must show that error in a way that sends the person to the app.
- **A notification's tap cannot be forged by another app.** The main activity is exported, as a launcher activity must be. The intent a notification carries is addressed to a second, non-exported name for it, and the app acts on a "go to this host" request only when it arrived by that name. Pending intents are immutable.
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

- ~~Whether Nagios Core versions older than 4.4 return the fields the detail screen needs.~~ **Closed in M2, by not depending on the answer.** Only 4.5 could be tested, and no field-level changelog for the JSON CGIs was found, so the question cannot be answered from here. Instead: the claim is "tested on 4.5; 4.0.7 and later expected to work, not tested"; nothing is refused on a version number; the parsers tolerate missing fields; comments and downtimes each degrade to "unavailable" on their own; and Test connection adds a note below 4.4. The minimum version is not raised. Reports from other versions are asked for in the README.
- ~~Whether Android 8-11 devices need a different widget fallback, since dynamic colour and some Glance behaviour need Android 12.~~ **Closed in M5, without evidence either way.** One widget for every version: Glance works back to Android 8, uses a fixed colour scheme where there is no Material You, and picks the nearest declared layout where the system reports size less precisely. It could only be seen on Android 17 (no emulator image for older versions here), so the claim is "expected to work on 8 to 11, not tested", and the README asks for reports. A separate layout for versions nobody could run would have been more code nobody had seen working.
