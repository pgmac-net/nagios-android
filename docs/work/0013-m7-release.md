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

## The first release, `v0.1.0` (2026-10-14)

When this was merged, nothing downstream of the approval had run. `v0.1.0` ran all of it, once, and it passed first time ([run 38411682224](https://github.com/pgmac-net/nagios-android/actions/runs/38411682224)). Each part was then checked from outside the workflow, against the release's own files:

- **The tag check and the approval.** The tag was on `main` and matched; the `release` job waited for the maintainer.
- **The signature.** `apksigner verify` on the downloaded APK: v2 and v3, one signer, RSA 4096, and the certificate is the committed fingerprint.
- **Checksums and attestation.** `sha256sum -c` passes; `gh attestation verify` passes for the APK and the SBOM and names `release.yml` at `refs/tags/v0.1.0`.
- **Dependency-Track.** The upload was accepted, with a processing token and a project id. The upload-only key cannot read the project back, so that it was processed is the maintainer's to see.
- **On a phone** (Pixel 8a, Android 17), by the maintainer: a profile behind Cloudflare Access, Problems, browsing, acknowledging, graphs, and the widget at 1x1, 2x1, 2x2 and 4x2 to 4x4, tapping through to a service. No crash and no error in the log; the process that was started is the one still running at the end.

Two things did not go as the runbook says:

- **It was published before it had been on a phone.** The draft could not be installed: the phone has Google's Advanced Protection on, which refuses any app from outside Google Play, from the browser and from Obtainium alike. It was installed after publishing, with `adb install`, which Advanced Protection allows. Nothing was wrong with it. The runbook and the README now say that `adb` is the way in on such a phone.
- **A first release cannot prove an update.** The runbook's real test is installing over the previous release. There was none. `0.1.1` will be the first time the key and `versionCode` are proven that way.

Profiles made in the debug build are not in the release app. They are two apps (`net.pgmac.nagwatch.debug` and `net.pgmac.nagwatch`) with a database each, by design.

## What the merge turned up

Dependency submission on `main` raised four alerts within a minute of the merge, all high: two each on `jackson-core` and `jackson-databind` 2.22.2, which the CycloneDX plugin brings to the build classpath. This was the one thing listed as not known when the pull request was opened. Neither library is in the APK. They are raised to 2.22.3 with a security floor, with the two `jackson-dataformat` modules in use, since Jackson is released as a family.

## Follow-ups

- Users with Advanced Protection can only install with `adb`. F-Droid (M8) does not change that, and there is to be no Play listing. Worth saying plainly wherever the app is described.
- `0.1.1`, whenever it comes, is the first real test of an update.
