# Cleartext HTTP is permitted in the manifest and enforced in the app

Many self-hosted Nagios instances are plain `http://` on a LAN, so Nagwatch supports that as a per-profile opt-in. Android cannot grant cleartext per host chosen at runtime: `usesCleartextTraffic` and the network security config are fixed at build time. Supporting a user-typed `http://` host therefore means permitting cleartext app-wide in the manifest and doing the refusing ourselves. The platform's protection is off; `ConnectionInterceptor` is the single choke point that replaces it, and `ConnectionPolicyTest` is what proves it holds.

The rules it enforces: `http://` only when that profile opted in; Cloudflare Access credentials never over `http://`, opt-in or not; credentials only to the origin the user configured, with redirects never followed. There is no "allow HTTP on private addresses automatically" heuristic: implicit rules are how passwords leak on a hotel network.

## Considered options

- **HTTPS only.** Cleanest, but excludes the common LAN setup, including the maintainer's own.
- **A second build flavour with cleartext enabled.** Two artifacts to ship, sign and explain, for the same code.

## Consequences

- Android Lint flags the manifest setting; the suppression there points at this ADR. A reader who sees `usesCleartextTraffic="true"` should end up here, not "fix" it.
- Any new HTTP client in the app (Coil for graphs in M6, for example) must be derived from the per-profile client so it passes through the interceptor. A client built from scratch would bypass every rule above.
