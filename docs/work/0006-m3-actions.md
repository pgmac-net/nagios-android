# M3: actions (acknowledge, comment, recheck, downtime)

Ticket: pgmac-net/nagios-android#5. Delivered as three planned PRs: #34 (the command layer), #36 (actions on the detail screens) and the downtime PR. Found on the way and filed: #35.

## What was built

The app can now change things on a Nagios, and says what became of each change.

1. **The command layer (#34).** One `CommandClient` through which every write goes: acknowledge, remove acknowledgement, add comment, force a check, schedule a fixed downtime, cancel a downtime. Nothing called it yet.
2. **Actions on the detail screens (#36).** Acknowledge, remove acknowledgement, comment and recheck, with a line under the buttons that tells accepted apart from done. Read-only users are found out in advance.
3. **Downtime.** Schedule (a length from now, or a chosen start and end), a read-back of every downtime against the window asked for, cancel, and a question about date order for the one case where the app cannot work it out.

## The rules it is built around

- **A command is sent at most once.** Its own HTTP client, with retries off and no connection reuse. A send that has begun is finished even if the screen is left.
- **Success is only what Nagios called success**, and accepted is not done. "Acknowledged." and the like are said only after the object was read again and showed the effect.
- **A time is never guessed** (ADR 0006). The server's date format and offset are read from the form Nagios itself serves, set beside its JSON clock.
- **Every downtime is read back.** A window written wrongly is not refused by Nagios; it lands somewhere else.
- **No command was sent to any real Nagios** while building this. Everything was tried against a throwaway instance (`scripts/test-nagios`).

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Where to try commands | A throwaway Nagios in Docker with made-up hosts and three users | Commands change a server; the maintainer's is not for experiments |
| Dates | Learned from the server's own form and JSON clock; the format remembered on the profile; every downtime read back; ask once when it cannot be worked out | `cmd.cgi` takes text in the server's format and zone, and reports neither. ADR 0006 |
| Scope | The ticket's five actions, plus cancel downtime; not delete comment | A downtime the app can make but not undo is a trap |
| Reporting | Sent, then confirmed by reading again, or "not confirmed yet"; failure only when Nagios said so | Effects take 3 to 14 seconds, and some commands are dropped without a word |
| Read-only users | Found out once per profile per run with a request that changes nothing; buttons disabled with the reason; unknown means enabled | A missing button looks like a missing feature, and a wrong "cannot" is worse than a refusal |
| Defaults | Sticky off with a warning, notify on, persistent off, nothing remembered; confirm only removing an acknowledgement and cancelling a downtime | A sticky acknowledgement once hid a failed drive |
| Device check | Against the test Nagios on the LAN, the maintainer adding the profile | |
| Delivery | Three sequential PRs | As for M1 and M2 |

## Deviations from the plan

- **The forms open in the page, not in dialogs.** Under Robolectric a Compose text field inside any dialog window never lets a test go idle, so a dialog form could not be tested. In the page it also stays clear of the keyboard. Confirmations, which have no text field, are still dialogs.
- **A forced check is types 7 and 96 with `force_check`**, not 54 and 98 as the design had it. `cmd.cgi` does not accept those.
- **The wait for a command's effect is about 25 seconds**, not the 15 in the plan: effects took 9 to 14 on the test instance.
- **The comment is cleaned before it is sent.** Nagios drops `<`, `>` and turns `;` into a space; the app does the same first so it can recognise its own comment, and shows what will be stored.
- **A mismatch is shown, not corrected.** The plan's default, kept: both windows and an offer to cancel. Sending again with an adjustment would be a second write made on a guess.

## What testing against a real server turned up

- A wrong order of day and month is accepted in silence: asked for 3 February, scheduled 2 March.
- A window on the far side of a daylight-saving change comes back an hour out. Seen with servers in Berlin and New York, and on the phone: asked 19:22 to 21:22, scheduled 20:22 to 22:22, reported as that, and cancelled from the report.
- "Not authorized" is also Nagios' answer for an object that does not exist.
- An acknowledgement of something that is not a problem is accepted and then dropped, as is a downtime wholly in the past.
- **On Android 17 the app could not reach a Nagios on the local network at all** (#35): it does not declare `ACCESS_LOCAL_NETWORK`. Found because the test Nagios was on the LAN; the maintainer's own is reached through a proxy. Device checks used a scratch build with that one line added. Not fixed in this ticket.

## How it was checked

- Unit and screen tests for every outcome, with the important ones proven by breaking the code on purpose: 12 properties for the command layer, 24 for the actions, 22 for downtime. Each round found tests that passed for the wrong reason (no retry, a send cut off part-way, a second tap, a window with only its start out) and those tests were fixed.
- Live runs through the real client and repository against the test Nagios in four date formats and four timezones.
- A profile database from version 1 is migrated in a test built from the exported schema, and was migrated for real on the phone.
- On a phone, for each PR that had something to see.

## Not done, and known limits

- Only Nagios 4.5 was tried.
- `lock_author_names` switched off has not been tried; the app always sends the user name as author for that case.
- The date and time pickers have no automated test; they were exercised on the phone.
- The date-order question could not be triggered live (it needs a day whose number equals its month and a server never seen before); it is covered by unit and screen tests.
- Three of the result pages in the fixtures are a captured page with the message replaced by the wording in `cmd.c`, because producing them needs a Nagios configured to refuse.
