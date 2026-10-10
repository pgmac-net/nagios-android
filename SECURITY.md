# Security policy

Nagwatch holds the credentials for your Nagios, and is planned to send commands to it. Reports of security problems are welcome and are taken seriously.

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Report it privately through GitHub: open the repository's [Security tab](https://github.com/pgmac-net/nagios-android/security) and choose **Report a vulnerability**, or go straight to the [private report form](https://github.com/pgmac-net/nagios-android/security/advisories/new). Only the maintainer can see the report.

It helps to include:

- what you found and what an attacker could do with it;
- the version of Nagwatch (the APK's version name, or the commit it was built from) and of Android;
- steps to reproduce, or a proof of concept;
- whether it is already public anywhere.

Please leave out real hostnames, addresses and credentials. They are not needed to understand a report.

## What to expect

Nagwatch is maintained by one person in their own time, so these are intentions, not a service level:

- an acknowledgement within 7 days;
- an assessment, and a plan if it is confirmed, within 30 days;
- a fix released as soon as it is ready, with you credited in the advisory unless you would rather not be.

Please allow up to 90 days from your report before disclosing publicly, or until a fix is released if that is sooner. If a problem is being actively exploited, say so: that changes the timetable.

## Supported versions

Only the latest release receives security fixes. Before the first release, that means the `main` branch.

## Scope

In scope:

- the app in this repository: how it stores credentials, what it sends and to where, and how it handles what a Nagios server sends back;
- this repository's build and release process.

Out of scope:

- vulnerabilities in Nagios Core itself, or in a proxy in front of it. Report those to their own projects;
- a Nagios server that the user has chosen to reach over unencrypted `http://`. The app warns about this and requires a per-profile opt-in;
- an attacker who already has the unlocked device, or root on it.

## What the app promises

These are the properties a report is most likely to be about. Each is enforced in code and covered by tests; the reasoning is in [`docs/design.md`](docs/design.md) section 11 and [`docs/adr`](docs/adr).

- Credentials are sent only to the server a profile names, and redirects are never followed.
- Unencrypted HTTP is refused unless the profile opts in, and Cloudflare Access credentials are never sent over it.
- Stored secrets are encrypted with a key held in the Android Keystore, and the app never shows a stored secret again.
- There is no analytics, no crash reporting, and no network traffic to anyone but the servers in your profiles.
- What a server sends back cannot crash the app: responses are bounded in size and nesting before they are parsed, and the parsers are fuzzed on every pull request.
