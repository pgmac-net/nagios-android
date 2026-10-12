# Responses that are not valid JSON

Real responses from the test Nagios in `scripts/test-nagios`, kept apart from `fixtures/`
because that directory is parsed strictly (by `FixtureLeakTest`) and these are not parseable
as they stand: that is the point of them.

They were captured from the throwaway instance's "Control chars" service, whose check prints a
bell, a vertical tab, a real backslash before an `a` and a real backslash before a bell. Nagios
Core 4.5.3 writes the first two as `\a` and `\v`, which JSON does not have
(pgmac-net/nagios-android#23). Everything in them is made up: the hosts, services and plugin
output come from `scripts/test-nagios/objects.cfg`, and the user is the test instance's
`operator`, whose password is not a secret.

| File | Request |
|---|---|
| `service_control_chars.json` | `query=service` for `web01` / `Control chars` |
| `servicelist_details_control_chars.json` | `query=servicelist&details=true&formatoptions=enumerate` |

To capture them again: start the test Nagios, then fetch the two requests with the user
`operator`. Do not edit them by hand; they are a record of what a server wrote.
