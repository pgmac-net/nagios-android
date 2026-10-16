# Nagios inventory

Ticket: pgmac-net/nagios-android#82. Map split to #95.

## What was built

Three pull requests:

1. **#96, groups.** The app's first use of `objectjson.cgi`: host and service groups. Hosts and Services gain an All / Groups switch. Groups in alias order, "Not in a group" last, an object in several groups under each, a group with an unhandled problem starts open, a failed read says why with Try again. Configuration is held in memory per profile (`InventoryRepository`) and read again on a pull to refresh.
2. **#97, parents.** A Parents view on Hosts: a tree from each host's `parent_hosts` (`HostTree`). A host is under every parent ("Also under …"); unknown parents ignored; loops become roots; unpicked ancestors drawn faded as the way down.
3. **This PR, links.** Notes, Open notes and Open actions on the detail screens, under a narrow rule for what is opened (`NagiosLinks`).

Hosts → Services was already there (a host's detail lists its services).

## Decisions

| Decision | Outcome | Why |
|---|---|---|
| Delivery | Split by data, three PRs; map its own ticket (#95) | Each PR is one kind of data and one view |
| Where | A view switch in the existing tabs | Bar stays three tabs |
| Chips in a grouped view | Open on every state, kept apart from All's | Found on the phone: a healthy Nagios showed an empty Groups |
| Multi-parent | Under every parent | The tree is true from any branch |
| Links | `http`/`https` only, macros filled URL-encoded, relative read against the profile, credentials stripped and never added | Addresses come from the server and go to another app |
| Configuration storage | Memory only, per profile | It changes when someone edits it; the cache is for status |

## Deviations from the plan

- Groups and Parents open on every state rather than All's chips (agreed during #96).
- The live smoke test runs against the maintainer's Nagios through the base URL they gave; the public address needs a Cloudflare Access token in `dev.env`.

## What was found, and by what

- **Phone:** the empty Groups on a healthy Nagios; the tree's arrow pushed rows out of line.
- **Security reviews (background, after each push of #97):** server-chosen names could repeat a LazyColumn key (Compose throws) — keys are now length-prefixed; crossing parents multiplied tree lines without limit and a long chain overflowed the stack — the walk is iterative, one frame per level, children worked out once, capped at 2,000 lines and 32 levels; merging same-named groups by dropping one lost its members from every section (a down host could vanish) — now merged. Two findings of the first review were not described in the notification and were not addressed.
- **Lint:** the Parents failure text was not used (the view would have said "groups"); `URLEncoder.encode(String, Charset)` needs Android 13 and would have crashed older phones on a link with a macro.
- **Live:** 48 hosts, 5 roots, 45 parent links; 12 host groups, 8 service groups; 12 notes/action addresses, all opened as web addresses.

## Follow-ups

- #95, the drawn map.
- Group-level notes and links are not shown.
- The lint step sometimes fails on a missing generated file and passes on a rerun.
