# Graphs are fetched by the app's own client, and judged by the picture

A nagiosgraph graph is a PNG from `showgraph.cgi`. The app fetches it with the client it uses for everything else, decides for itself whether what came back is a graph, decodes it only after its header has been read and its size checked, and keeps it in memory and nowhere else. No image library is involved (pgmac-net/nagios-android#8).

The design (section 9, as first written) had Coil sharing the OkHttp client, a per-profile URL template defaulting to `{base}/cgi-bin/`, and "a non-image response or error image collapses the section". Three things found before any code was written changed that.

## What was found

- **"No graph" is not an HTTP error.** With nothing to draw, `showgraph.cgi` answers 200 and `image/png`: a picture of the error text 608 x 23, a blank one 5 x 5, or headers and no body. A service with no performance data, a service that does not exist and a host that does not exist all look the same. (Measured on nagiosgraph 1.5.2 on the test Nagios; the answers are committed in `app/src/test/resources/graphs`.)
- **The server draws whatever size it is asked for.** `geom=20000x20000` returned 5 MB. And it takes `rrdopts`, which is passed to `rrdtool graph` as it stands.
- **A real graph is always larger than the plot asked for**, because the plot is inside it: 81 pixels wider for the axis, and 55 and up taller for the title and a legend that grows with what is plotted.

## Considered options

- **Coil, as designed.** It would fetch, cache and decode. Its strengths (lists of images, placeholders, a disk cache, transformations) are not used by one picture on one screen, and three of its defaults are wrong here: a disk cache of pictures of someone's infrastructure, no ceiling on bytes that is ours, and a decoder handed whatever arrived. Each can be configured away, at which point little of the library is left and the configuration is the thing to get right.
- **Coil for display only**, with the bytes fetched by the app. The cost of a dependency for the part that is ten lines.
- **Decide "no graph" from the status' performance data.** Fewer requests. But a service that is timing out reports none, which is exactly when its history is wanted, and performance data that nagiosgraph has no rule for still produces the error picture.
- **Decide it by reading nagiosgraph's HTML page first.** A second request and a parser for a page that themes change.
- **The app's own client, and a rule about the picture (chosen).**

## Decision

- **One path for credentials.** `NagiosApi.image` is one more kind of GET through `ConnectionInterceptor`: the same origin rule, the same refusal of redirects and of cleartext, the same classification of failures. Nothing new knows a password.
- **A graph is a PNG at least as large as the plot asked for, and not much larger.** `GraphRules.judge` reads the first 24 bytes (`PngHeader`) and compares. It needs no threshold of its own: the app never asks for a plot shorter than 120 pixels, and an error picture is 15 pixels a line. The upper bound (the plot plus 200 across and 2,000 down) is what stops a few bytes that claim to be enormous from reaching a decoder.
- **At most 2 MiB is read**, and the app asks only for its own sizes: a plot sized in dp for the room there is, in steps of 60, and one larger for the full-screen viewer. In dp and not device pixels because rrdtool draws text at a fixed pixel size; asked for at a phone's resolution, the labels cannot be read.
- **Only `host`, `service`, `period` and `geom` are sent.** Never `rrdopts`.
- **The graph path is a path**, on the profile's own server, and blank by default, meaning the CGI directory the app already found for Nagios. Not a URL and not a `{base}` template: credentials go to one origin, so a setting that could name another would either leak them or be refused at the moment of use. `GraphPath` allows unreserved characters only, so there is nothing to decode and nothing to decode twice.
- **Three answers, remembered for the session only.** *No graph* for a service, so it is not asked about on every visit. *Not there* for a profile (404, 403, or a 200 that is not a PNG), so one request settles it for every service. A failure that may pass is not remembered. A pull to refresh asks again about all of it: a server can be fixed.
- **Nothing is written to storage.** A few pictures are held in memory, dropped when the profile's address, path or user changes and when the profile is deleted.
- **Graphs are on by default**, for new and existing profiles. A server without nagiosgraph costs one request a session and shows nothing.

## Consequences

- **A graph that cannot be had is never an error on screen.** Every cause comes out as "no graph". That is right for the person, and it means a misconfigured graph path is silent: the editor's hint and the README have to carry that.
- **Service graphs only.** nagiosgraph files a host's own check under whatever name the administrator put in Nagios' host-perfdata template; there is no name the app could ask for that would be right on two servers. A host has no graph section until someone needs one and says what theirs is called.
- **A server set to draw unusually large legends** (more than 2,000 pixels of them) would have its graphs refused. Nothing like it has been seen; the number is in one place.
- **An error picture of eight lines or more, at a narrow plot width, would pass the rule** and be shown as a graph: a picture of text, which is harmless and would be noticed. nagiosgraph's messages are one line.
- **The decoder is still Android's.** The header check bounds what it is asked to do; it does not make a malformed PNG safe to decode. That is the platform's job, as it is for every image an app shows.
- **Pinch-zoom, the placeholder and the cache are ours to write.** About two hundred lines, and tested.
