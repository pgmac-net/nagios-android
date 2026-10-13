# M7: the release pipeline

Ticket: pgmac-net/nagios-android#9. One pull request, #56. The environment and the tag rule are pgmac-net/terraform-github#25. The first release itself is the maintainer's, after both.

## What was built

- **`.github/workflows/release.yml`**: a `v*` tag builds an unsigned APK and an SBOM in a job with no secrets, then, after the maintainer approves the `release` environment, signs the APK, attests it, drafts a GitHub Release and sends the SBOM to Dependency-Track.
- **`scripts/sign-release.sh`** and **`config/release-signing-cert.sha256`**: the signing step, and the fingerprint it will not sign without.
- **The SBOM**: the CycloneDX Gradle plugin, `releaseRuntimeClasspath` only.
- **`dependenciesInfo` off**, so the APK carries no dependency block that only Google can read.
- **Docs**: README Install, a Releasing runbook, SECURITY.md, ADR 0011, the name and id lookup in ADR 0003.

## Decisions (from the grilling session)

| Decision | Outcome | Why |
|---|---|---|
| Where the version comes from | Committed; the tag must match it | Source, CI and F-Droid all read one version from one commit |
| Protecting the key | A `release` environment: `v*` tags only, the maintainer as required reviewer | No other workflow or branch can read it, and nothing touches it without a click |
| Where signing happens | A separate job, with `apksigner`, after Gradle has finished | The key is never in the same process as a plugin or a dependency. ADR 0011 |
| Publishing | A draft; the maintainer installs it, then publishes | An update that installs is the only whole proof of the key |
| Provenance | Fingerprint in the repo and README; build attestation; `SHA256SUMS` | Who signed it, how it was built, and that the download is intact, each checked a different way |
| Dependency-Track | Pushed to `sbom.pgmac.net.au` with the organisation's upload-only key; never fatal | It is already public through a Cloudflare tunnel; the ticket's "if reachable" was answered by looking |
| Environment and tag rule | Terraform, in its own issue | Configuration stays in `terraform-github`; this ticket stays in one repo |
| First release | `v0.1.0`, tagged by the maintainer after merge | The assistant never pushes a tag or sees the key |

## Deviations from the plan

- **The release job checks out two files**, the signing script and the fingerprint, where the plan said it would check out nothing. A step written inline in YAML could not have been tested by hand; a script can.
- **A `pull_request` trigger for `release.yml` alone.** `workflow_dispatch` cannot start a workflow that is not on `main` yet, so the planned rehearsal from the branch was not possible. A pull request that changes the file now rehearses the `build` job.
- **The tag must also be on `main`**, and the release job checks the build job's reported version against the tag a second time. Neither was in the plan.
- **The Gradle cache is off for this workflow.** Not in the plan; a release should not be built from what another run left behind.

## What was found, and by what

- **Dependency-Track was reachable all along.** The ticket, and the M0 notes, assumed `dtrack.int.pgmac.net` and so a self-hosted runner. `sbom.pgmac.net.au` is the same server through a tunnel, with no Cloudflare Access gate in front of the API (its Access entry is a bookmark), found by asking the maintainer and then `curl`. The organisation's `DT_HOST` secret is from 2024 and presumably holds the internal name, so this repository overrides it.
- **`apksigner` words its output differently between versions.** Build-tools 36 prints `Signer #1 certificate SHA-256 digest`, 37 with `--verbose` prints `V3.0 Signer: certificate ...`. The first draft of the script matched only the first and failed a correctly signed APK. Found by running it; the script now accepts either and is run without `--verbose`.
- **A task that prints `versionName` cannot hold a reference to the build script** under the configuration cache. The value is read inside the task's configuration instead.
- **`./gradlew tasks --all` fails** on the `fuzz` task (`configure` called in the wrong context). It predates this work and is not fixed here.
- **The system JDK 21 on the development machine has no `javac`**; the build needs `JAVA_HOME` pointed at the Temurin one from mise.

## Not verified

Nothing downstream of the approval has run: the environment gate, the attestation, the draft Release, an authenticated Dependency-Track upload. Each is exercised for the first time by `v0.1.0`. The runbook says what each should look like and what to do when it does not.

Also not known: whether any library the CycloneDX plugin brings to the build classpath has an open advisory. The existing floors hold. Dependency submission on `main` will say after merge.

## Follow-ups

- pgmac-net/terraform-github#25, which blocks the first tag.
- The release key's fingerprint, into `config/release-signing-cert.sha256` and the README, before #56 leaves draft.
- `v0.1.0`: tag, approve, install the draft, publish.
