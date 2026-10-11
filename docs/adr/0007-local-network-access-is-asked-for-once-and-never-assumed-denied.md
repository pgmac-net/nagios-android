# Local network access is asked for once, when it is first needed, and a refusal never stops a request

On Android 17 an app that targets SDK 37 needs the `ACCESS_LOCAL_NETWORK` permission ("Nearby devices") to reach an address on the local network. Nagwatch asks for it the first time a profile whose address looks local is tested or loaded, explains itself first, asks only once by itself, and carries on trying whatever the answer. When a connection then times out and this is the likely cause, it says so in words of its own and offers to ask again.

Without the permission Android drops the connection and nothing tells the app: a TCP connect to a local address simply times out. Before this change every request to a Nagios on the LAN failed that way on Android 17 and the app said "The server did not answer in time", which sends people to check a network that is fine (pgmac-net/nagios-android#35). It went unnoticed because a Nagios reached through a public address, behind Cloudflare Access for instance, is not on the local network as far as Android is concerned; it was found with a test Nagios on the LAN.

What Android treats as local, from its documentation: traffic over a broadcast-capable interface (Wi-Fi, Ethernet, but **not** cellular and **not a VPN**) to `10/8`, `172.16/12`, `192.168/16`, `169.254/16`, `100.64/10`, the IPv6 link-local and unique-local ranges, multicast, and `.local` names. A DNS lookup is exempt. The app can see the address; it cannot see which interface a request will take.

## Considered options

- **Ask at first launch.** One prompt, simple. Asks everyone for "Nearby devices" before they have typed a URL, including people who will never reach a local address. For an app whose pitch is that it talks to the address you gave it and nothing else, that is the wrong first impression.
- **Ask only after a failure.** Never asks unnecessarily, but every LAN user's first experience is a ten-second timeout.
- **Ask when needed, with the app's own sentence first (chosen).**
- **Fail fast when the permission is missing and the address looks local.** Quicker and clearer for the common case. It breaks the person who reaches a `10.x` Nagios over WireGuard or Tailscale: Android does not treat that as local, the request would have worked, and the app would refuse to try. The app cannot tell the two apart.
- **Ask every time it is needed until Android stops showing the prompt.** Maximises grants and nags someone who said no on purpose (the VPN user, for one).
- **Use the system device picker, which needs no permission.** Android offers it for discovering devices by service name. Nagwatch connects to an address the person typed; there is nothing to discover.

## Decision

- **When:** "Test connection" and a profile's first load, before the request, if the address looks local, the permission is not held, and the app has not explained yet on this install. The flag is for the whole install, not per profile: the permission belongs to the app.
- **How:** the app's own dialog first, saying what it is for and what it does not do (it does not scan), with "Continue" and "Not now". Android's prompt only follows "Continue". "Not now" exists so that someone can decline without spending one of the two refusals Android allows.
- **Never stop a request.** Whatever the answer, the request goes ahead.
- **Say it when it is probably Android.** A timeout to an address that looks local, with the permission not held, is reported as its own error (`LocalNetworkBlocked`), worded "may be blocking", with a button. The button raises Android's prompt, or opens the app's page in system settings once Android has stopped showing the prompt (known from `shouldShowRequestPermissionRationale` turning false after a refusal). After a grant the request is tried again.
- **Once only, by itself.** After the explanation the prompt is reached only from the button.

## Consequences

- **The error is an inference and says so.** A powered-off server on the LAN gives the same timeout and the same message. A block on an address the app did not think was local gives the ordinary timeout. The ranges are Android's own, which keeps the second rare.
- **A person who refused waits out the connect timeout (10 seconds) every time** before the explanation appears. Accepted: it is the price of not breaking VPN users.
- **A command that times out for this reason is still "unknown", not "not sent".** The classification of a command's outcome does not change because of a guess; only the wording of the reason does.
- **Background work has no screen to ask from.** Polling (M4) and the widget (M5) will receive `LocalNetworkBlocked` like any other error, and have to show it in a way that sends the person to the app. Nothing asks from the background.
- **A lookup happens before the first load of a profile** (to decide whether the address looks local). It is exempt from the protection. A name that does not resolve does not look local: the lookup failure is the more useful thing to show.
- **The permission is declared for every Android version.** Older Android ignores it, and the app never asks for it there. The app's manifest now asks Android for two permissions, and a test fails if that changes without a decision.
- **The button goes where the error is met.** Someone whose profile worked before Android 17, or who revoked the permission, still has the earlier result on screen, so the failure is a banner, not the full-screen error. The button is on both, and on all three tabs. This was missed in the first version and found on a phone.
- **"Looks local" is not "blocked", and the app can show the explanation to someone who does not need it.** On the maintainer's own phone the profile used every day looks local to the app and loads without the permission (the request does not take the local network). They are asked once, and everything works whatever they answer. That is the cost of deciding from the address alone, and the reason a refusal never stops a request.
- **Android's second refusal is final.** Seen on Android 17: the first "Don't allow" leaves the prompt available; the second marks the permission user-fixed and Android shows no more prompts. The button then opens the app's settings page, which is the only way back.
- **Android's prompt wording is alarming** ("find, connect to, and determine the relative position of nearby devices"). The app's sentence comes first to say what it is actually for.
