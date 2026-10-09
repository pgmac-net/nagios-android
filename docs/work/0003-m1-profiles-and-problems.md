# M1: profiles, auth and the Problems view

Ticket: pgmac-net/nagios-android#3. Delivered as three PRs: #17 (data layer), #19 (profiles), and this one (Problems view). Related: pgmac-net/homelabia#211 (Cloudflare Access service token), nagios-config#63, nagios-android#18.

## What was built

The app connects to a real Nagios Core and shows what is currently wrong.

1. **Data layer (#17).** `NagiosClient` with CGI-directory detection; a distinct `NagiosError` for every failure class; one interceptor (`ConnectionInterceptor`) enforcing what may leave the device; `ResilientListFetcher`, which survives a record Nagios cannot serialise; `ProblemClassifier`, which computes "unhandled" client-side; sanitised fixtures with a leak check; an opt-in live test.
2. **Profiles (#19).** Room storage with AES-256-GCM secrets under an Android Keystore key; write-only secrets in the editor; validation that explains the connection rules before the network layer has to refuse; Test connection with a message per error class.
3. **Problems view (this PR).** Count chips that filter, a severity-sorted list, services rolled up under their down host, a collapsed handled section, pull-to-refresh, stale / failed / degraded banners, a profile switcher.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Delivery | Three sequential PRs | One PR would have been thousands of lines |
| Fixtures | Sanitised captures only, with a leak test | The repo is public and raw captures map the homelab |
| "Unhandled" | Not acknowledged, not in downtime (own or host's), and not a service on a down host. Soft, checks-off and notifications-off still count, marked | Matches Nagios' own UI where it matters; hiding a failing check because it has not paged anyone yet would delay noticing it |
| HTTP 500 on a list | Isolate and degrade, bounded | One broken check must not blind the app |
| Cleartext HTTP | Per-profile opt-in, enforced in app code; never for Access credentials | Android cannot allow cleartext per runtime host; ADR 0004 |
| Storage | Profiles in Room; secrets encrypted in the row; status in memory only | A status cache is designed in M2, once detail and widget needs are known |
| Private CAs | Trust user-installed CAs; no in-app import | Covers private-CA users with no certificate code (follow-up #18) |
| Verification | Fixture tests, opt-in live test, Robolectric UI tests, phone check | CI has no credentials and must not |
| Cloudflare Access | Done between PR 2 and PR 3 (homelabia#211) so the off-LAN path could be tested for real | Without it the Access success path was unproven |

## Deviations from the plan

- The 500 search runs on every poll rather than being cached; the request budget is 40, not "about 25".
- Three automated security findings were fixed after the first push of PR 1: unbounded server-driven fetches (lists capped at 50,000 records, responses at 8 MiB), a certificate error hidden on dual-stack hosts, and bounds checked per response before records are kept.
- homelabia#211 changed shape along the way: the Nagios instance was already published behind Cloudflare Access (the bootstrap wrongly said it was internal-only), so the work was a Service Auth policy, not a tunnel route. Its token IDs are pinned in Terraform after a lookup by name failed.

## Defects found by tests or review rather than by the author

- "Everything is OK" was shown when the only remaining problems were acknowledged. A critical that is merely acknowledged is not OK. Fixed, with a test.
- A refresh clears its running marker slightly after publishing its result, so a refresh requested in that instant was dropped. The marker and the flag now change together under a lock.
- A certificate failure on a later address of a dual-stack host was reported as "unreachable" because the first failure won. Every attempt is now considered.
- Lint objected to "1 items" and to `%d` followed by words; the chips and banner now use plurals or label-then-count.
- The live test sent Access credentials over the LAN `http://` URL once they appeared in `dev.env`; the client rightly refused. The test now applies them only to `https://`.

## Verification

- 188 unit tests: 186 pass, 2 live tests skipped by design. The timing-sensitive ones were run three times in a row.
- A planted leak in a fixture fails `FixtureLeakTest`.
- Full uncached `check`, debug and release builds; FOSS self-test.
- Live, from the dev machine against a real Nagios Core 4.5.9: LAN over `http://` and the public URL through Cloudflare Access with a service token. Connect, path detection, a full poll, classification, and 221 services fetched in full with the one record that crashes the CGI returned degraded.
- Without a token the public URL reports "Access rejected".

## Not verified by automation

- `KeystoreKeySource`, the code that talks to the Android Keystore, cannot run on the JVM.
- The navigation wiring and the real screens on a device; the stateless screens are tested under Robolectric.
- The minified release build at runtime.
- A real-device launch and the phone-to-Cloudflare path with the phone's own token.

## Known limits

- Foreground refresh only; background polling and notifications are M4.
- Every host is fetched on each refresh. Fine at homelab scale; a very large install may want a narrower query.
- The profile list does not flag a profile whose credentials have become unreadable; that shows on Test connection and on the Problems screen.

## Follow-ups

- nagios-android#18: per-profile CA import and pinning.
- nagios-config#63: the check whose output crashes `statusjson.cgi`.
- A Nagios user with command permissions for the phone, from M3.
