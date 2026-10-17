# Answers from `showgraph.cgi`

Real responses from nagiosgraph 1.5.2 on the throwaway test Nagios (`scripts/test-nagios`),
asked as the `viewer` user. Everything in them is made up: the hosts are `web01` and `db01`.

**Every one of these was HTTP 200 with `Content-Type: image/png; charset=ISO-8859-1`**, which
is the point of keeping them: whether there is a graph cannot be told from the status or the
type, only from the picture.

| File | Asked for | What came back |
|---|---|---|
| `graph_day_360x120.png` | `web01` / `HTTP`, `period=day`, `geom=360x120` | A graph, 441 x 175 |
| `graph_three_series_360x120.png` | `web01` / `Load`, `period=day`, `geom=360x120` | A graph of three things, 441 x 245: the legend makes it taller |
| `graph_year_700x240.png` | `web01` / `HTTP`, `period=year`, `geom=700x240` | A graph, 781 x 295 |
| `no_data.png` | `db01` / `Replication`, which prints no performance data | A picture of the error text, 608 x 23 |
| `no_such_host.png` | a host that does not exist | A picture of the error text, 608 x 23 |
| `blank.png` | no `service` at all | A blank picture, 5 x 5 |

A graph is always larger than the plot asked for (`geom`): by 81 across, and by 55 and up
down, depending on the legend. That is the rule in `GraphRules`.

To take them again: `scripts/test-nagios/start.sh`, wait a few minutes for the checks to have
run and nagiosgraph to have written its files, then ask `showgraph.cgi` as above.

They are binary, so the fixture leak check does not read them. Do not add a capture from a
real installation: a graph is a picture of what a real server does, and its error pictures
print real paths.
