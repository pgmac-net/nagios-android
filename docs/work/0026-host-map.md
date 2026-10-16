# Host map

Ticket: pgmac-net/nagios-android#95. Split out of #82 (work record 0025).

## What was built

- `HostMap`: a radial layout of hosts by parent, pure and tested. Each host once, under its first known parent; a line to every parent; roots on ring one around the Nagios server; slices in proportion to the hosts at the ends of each branch; loops and unknown parents handled; capped at 500 hosts and 2,000 lines.
- `HostMapScreen`: a full screen opened by **Show as a map** above the Parents tree. Compose `Canvas`; pinch, drag and double tap through `ui/graph/Zoom` (given a `max` so the map can go to 12×); dots in state colours, unhandled problems larger and ringed; names left out where they would overlap; tap opens the host.
- `HostMapViewModel` reads the parents and the status itself, so the screen works when opened after a restore.

## Decisions

| Decision | Outcome | Why |
|---|---|---|
| Layout | Radial only | The Parents view is already a tree; radial fits a phone and shows each host once |
| Where | Its own screen from Parents | The switch stays at three; a map wants the whole screen |
| Content | Every host once, a line to every parent | A map is a graph, not a list |
| Filters | Ignored | A map that changes shape with each chip is hard to read |
| Size | 500 hosts, 2,000 lines | Unreadable past that on a phone; bounded work against what a server sends |
| Drawing | Compose Canvas, no library | Nothing to licence-check for F-Droid; the layout is small |
| Zoom | Positions, not the picture | Dots and names stay the same size on screen |

## Deviations from the plan

- The 2,000-line cap was added before the PR: hosts were capped, but every host naming every other as a parent would have meant a quarter of a million lines a frame.
- `Zoom` gained a `max`, so the graph viewer keeps 4× and the map gets 12×.

## What was found, and by what

- Phone (Pixel 8a, the maintainer's Nagios): 48 hosts laid out round the centre, the long chain through pfmarvin to the Cloudflare checks, the big branch of `*.int.pgmac.net` hosts round the outside, acknowledged down hosts in red; double tap zooms and shows more names; a tap on k8s01 opened k8s01.
- A test of the tap needed the test clock moved past the double-tap wait: a single tap is only known once a second has not followed.

## Follow-ups

- Hand-set `x_2d`/`y_2d` coordinates are not used (the maintainer's Nagios sets none).
- Names near the centre can still sit across lines at the default zoom.
