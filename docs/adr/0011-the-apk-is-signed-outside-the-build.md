# The APK is signed outside the build, behind an approval, and checked against a fingerprint in the repo

A release APK is built unsigned by a job that has no secrets. A second job, which runs only after the maintainer approves it, signs that APK with the Android SDK's `apksigner` and publishes nothing unless the certificate on it is the one whose SHA-256 is committed in `config/release-signing-cert.sha256`. Gradle has no `signingConfig` (pgmac-net/nagios-android#9).

## Why this needed deciding

The release key cannot be replaced. Android installs an update only if it is signed with the key the installed app was signed with, and there is no store account behind Nagwatch that could vouch for a new one. If the key is lost, or has to be abandoned because someone else has it, every user uninstalls and loses their profiles. So the question is not how to sign, but what is allowed to be running while the key can be read.

The usual answer is a `signingConfig` that reads the keystore from environment variables, so `assembleRelease` produces a signed APK. That puts the key in the same process as the whole build: the Android Gradle plugin, KSP, Hilt, Room's compiler, detekt, ktlint, Licensee, the CycloneDX plugin, and everything each of them brings. Any of those, or a compromised update to any of them, could read it. They are locked and floored, which makes that unlikely; it does not make it impossible, and the cost of being wrong is the whole user base.

## What was decided

- **Two jobs.** `build` runs Gradle and has no secrets and no environment. `release` has the key and runs `apksigner`, `gh` and `curl`. It checks out two files, the signing script and the fingerprint, and no build script.
- **The key lives in one GitHub Environment**, `release`, which only a `v*` tag can use and only after the maintainer approves that run. No other workflow, branch or job in the repository can read it. The environment and the rule on who may push a `v*` tag are in `pgmac-net/terraform-github`.
- **The certificate's fingerprint is in the repository**, and the signing script deletes what it signed and fails if the APK's certificate is not that one. A wrong keystore in the secrets (a test key, a regenerated key) therefore cannot produce a release. The fingerprint is public: it identifies the key and cannot sign with it. The same value is what a user, Obtainium or AppVerifier pins.
- **The version is the source's.** `versionName` and `versionCode` are committed. The tag must equal `v` plus `versionName` and must be on `main`, and the release job checks the version the build job reports against the tag again, because the build job ran Gradle and the tag is the one thing it could not have chosen.
- **The Release is a draft.** The maintainer installs that exact APK over the previous release before publishing. An update that installs is the only complete proof of the key and of `versionCode`.
- **APK signature schemes v2 and v3, not v1.** `minSdk` is 26, and v1 does not cover the whole file.
- **A build attestation** on the APK and the SBOM says which workflow run, from which tag, produced them. It is a second, independent check: the signature says who, the attestation says how.

## What it costs

- A signed release build cannot be made on a laptop with `./gradlew`. That is intended: a signed APK exists only if the workflow made it. `scripts/sign-release.sh` can be run by hand against a throwaway key to test the step.
- Two jobs and an artifact handed between them, where one job would have done.
- One click per release, and a release cannot be cut by anyone but the maintainer.
- The `build` job could, if compromised, hand over an APK that is not what the source says. The signature would not notice. Signing outside the build protects the key, not the contents; what protects the contents is the lockfile, the pinned actions, the cache being off for this workflow, and reproducible builds if F-Droid (M8) gets them.

## Not done

- **A key in a hardware token or a cloud KMS.** It would mean the key never reaches a runner at all. It also means a paid service or a release only from one machine, for one maintainer and one app. Worth revisiting if the project gains maintainers.
- **Google Play App Signing**, where Google holds the key. There is no Play listing.
- **Key rotation with the v3 scheme's proof-of-rotation.** It exists, and v3 is enabled so it stays possible. It needs the old key to sign for the new one, so it helps with a planned change and not with a lost key.
