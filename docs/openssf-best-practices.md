# OpenSSF Best Practices: answer sheet

Answers for the [OpenSSF Best Practices](https://www.bestpractices.dev/) badge (**Passing**), written from what this repository does and shows, so they can be pasted into the form. Evidence links point at `main`.

Criteria and their wording are those of the badge project's `criteria.yml` and English locale file, fetched 2026-10-15. If bestpractices.dev has changed them, trust the form and update this sheet.

**How to use it.** Register the repository at <https://www.bestpractices.dev/> (sign in with GitHub, add `https://github.com/pgmac-net/nagios-android`), go section by section, and for each criterion choose the answer in the table and paste the justification and URL. The form's autofill will answer some of these itself.

**Keep it true.** The badge is a claim about the project, so this sheet is revisited on each release, and whenever a process in it changes (for example, a different CI, a second maintainer, or a new way to report problems).

**Three answers are the maintainer's to confirm**, because they are about people and not files: `know_secure_design`, `know_common_errors` and `dynamic_analysis_fixed`.

Project page: <https://www.bestpractices.dev/projects/15514> (project ID 15514). The README badge links here.

## Passing


### Basics


**Basic project website content**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `description_good` | MUST | Met | The README opens with what Nagwatch is: a client for Nagios Core to see what is broken, acknowledge it, schedule downtime and view graphs. | [README.md](https://github.com/pgmac-net/nagios-android/blob/main/README.md) |
| `interact` | MUST | Met | README covers how to obtain it (Install), how to report problems (Security, issues) and how to contribute. | [README.md#install](https://github.com/pgmac-net/nagios-android/blob/main/README.md#install); [CONTRIBUTING.md](https://github.com/pgmac-net/nagios-android/blob/main/CONTRIBUTING.md) |
| `contribution` | MUST | Met | CONTRIBUTING.md states the process: comment on the issue first, then a pull request. | [CONTRIBUTING.md](https://github.com/pgmac-net/nagios-android/blob/main/CONTRIBUTING.md) |
| `contribution_requirements` | SHOULD | Met | Licence and SPDX headers, signed commits, scope, no telemetry, FOSS-only dependencies, strict static analysis and tests are all stated. | [CONTRIBUTING.md](https://github.com/pgmac-net/nagios-android/blob/main/CONTRIBUTING.md) |

**FLOSS license**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `floss_license` | MUST | Met | GPL-3.0-or-later. | [LICENSE](https://github.com/pgmac-net/nagios-android/blob/main/LICENSE) |
| `floss_license_osi` | SUGGESTED | Met | GPL-3.0 is OSI-approved. | [LICENSE](https://github.com/pgmac-net/nagios-android/blob/main/LICENSE) |
| `license_location` | MUST | Met | Top-level `LICENSE`; every Kotlin source and build script carries an SPDX header and the build checks it. | [LICENSE](https://github.com/pgmac-net/nagios-android/blob/main/LICENSE); [docs/development.md#what-check-enforces](https://github.com/pgmac-net/nagios-android/blob/main/docs/development.md#what-check-enforces) |

**Documentation**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `documentation_basics` | MUST | Met | README covers install, first use and the notifications/widget/quick-actions behaviour, and the security notes say what the app does with credentials and traffic. | [README.md](https://github.com/pgmac-net/nagios-android/blob/main/README.md); [docs/design.md](https://github.com/pgmac-net/nagios-android/blob/main/docs/design.md) |
| `documentation_interface` | MUST | Met | The app has no API; its external interface is the screens, notifications and widget, described in the README and the design document. The only network interface it uses is Nagios's own CGIs, documented in the design. | [README.md](https://github.com/pgmac-net/nagios-android/blob/main/README.md); [docs/design.md](https://github.com/pgmac-net/nagios-android/blob/main/docs/design.md) |

**Other**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `sites_https` | MUST | Met | Repository, releases and downloads are all on github.com over HTTPS. | [repository](https://github.com/pgmac-net/nagios-android/) |
| `discussion` | MUST | Met | GitHub issues and pull requests: searchable, addressable by URL, open to anyone with a GitHub account, no proprietary client. | [issues](https://github.com/pgmac-net/nagios-android/issues?q=is%3Aissue) |
| `english` | SHOULD | Met | All documentation, issues and code comments are in English. | [README.md](https://github.com/pgmac-net/nagios-android/blob/main/README.md) |
| `maintained` | MUST | Met | Active: 46 commits in the last six months; two releases (v0.1.0, v0.1.1) in October 2026; the roadmap is tracked as milestone issues. | [commits](https://github.com/pgmac-net/nagios-android/commits/main) |

### Change Control


**Public version-controlled source repository**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `repo_public` | MUST | Met | Public GitHub repository. | [repository](https://github.com/pgmac-net/nagios-android/) |
| `repo_track` | MUST | Met | Git records what, who and when. | [commits](https://github.com/pgmac-net/nagios-android/commits/main) |
| `repo_interim` | MUST | Met | Every merged pull request and commit is public, not only releases. | [commits](https://github.com/pgmac-net/nagios-android/commits/main) |
| `repo_distributed` | SUGGESTED | Met | Git. | [repository](https://github.com/pgmac-net/nagios-android/) |

**Unique version numbering**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `version_unique` | MUST | Met | Each release has a unique version: `versionName` and `versionCode`, tag `vX.Y.Z`. | [v0.1.1 release](https://github.com/pgmac-net/nagios-android/releases/tag/v0.1.1) |
| `version_semver` | SUGGESTED | Met | SemVer (0.1.0, 0.1.1); the release tag must equal `v` + `versionName`, checked by the release workflow. | [docs/development.md#releasing](https://github.com/pgmac-net/nagios-android/blob/main/docs/development.md#releasing) |
| `version_tags` | SUGGESTED | Met | Each release has a signed tag (`git tag -s vX.Y.Z`). | [tags](https://github.com/pgmac-net/nagios-android/tags) |

**Release notes**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `release_notes` | MUST | Met | Each release has notes listing what changed, one line per pull request, written as summaries and not as a commit log; the full list sits beside the comparison link. | [v0.1.1 release](https://github.com/pgmac-net/nagios-android/releases/tag/v0.1.1) |
| `release_notes_vulns` | MUST | N/A | No release has fixed a publicly known (CVE-assigned) vulnerability in Nagwatch's own code. | [v0.1.1 release](https://github.com/pgmac-net/nagios-android/releases/tag/v0.1.1) |

### Reporting


**Bug-reporting process**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `report_process` | MUST | Met | GitHub issues; README and CONTRIBUTING point to them, and SECURITY.md covers private reports. | [CONTRIBUTING.md](https://github.com/pgmac-net/nagios-android/blob/main/CONTRIBUTING.md) |
| `report_tracker` | SHOULD | Met | GitHub issue tracker. | [issues](https://github.com/pgmac-net/nagios-android/issues?q=is%3Aissue) |
| `report_responses` | MUST | Met | All 38 issues to date were opened by the maintainer, so no outside bug report has gone unanswered. | [issues](https://github.com/pgmac-net/nagios-android/issues?q=is%3Aissue) |
| `enhancement_responses` | SHOULD | Met | As above: there have been no outside enhancement requests; the maintainer's own are tracked as milestone issues. | [issues](https://github.com/pgmac-net/nagios-android/issues?q=is%3Aissue) |
| `report_archive` | MUST | Met | GitHub issues are public and searchable. | [issues](https://github.com/pgmac-net/nagios-android/issues?q=is%3Aissue) |

**Vulnerability report process**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `vulnerability_report_process` | MUST | Met | Published in SECURITY.md, linked from the README, with GitHub private vulnerability reporting enabled. | [SECURITY.md](https://github.com/pgmac-net/nagios-android/blob/main/SECURITY.md) |
| `vulnerability_report_private` | MUST | Met | SECURITY.md says to use GitHub's private report form (HTTPS), visible only to the maintainer. | [SECURITY.md](https://github.com/pgmac-net/nagios-android/blob/main/SECURITY.md) |
| `vulnerability_report_response` | MUST | N/A | No vulnerability report has been received in the last six months. SECURITY.md commits to an acknowledgement within 7 days. | [SECURITY.md](https://github.com/pgmac-net/nagios-android/blob/main/SECURITY.md) |

### Quality


**Working build system**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `build` | MUST | Met | Gradle with a checked-in, checksum-validated wrapper; `./gradlew assembleDebug` rebuilds from source. | [README.md#building](https://github.com/pgmac-net/nagios-android/blob/main/README.md#building) |
| `build_common_tools` | SUGGESTED | Met | Gradle with the Android Gradle plugin, the standard Android build. | [docs/development.md](https://github.com/pgmac-net/nagios-android/blob/main/docs/development.md) |
| `build_floss_tools` | SHOULD | Met | Everything needed to build is FLOSS (JDK, Gradle, Android SDK command-line tools); the dependency checks forbid proprietary libraries. | [CONTRIBUTING.md](https://github.com/pgmac-net/nagios-android/blob/main/CONTRIBUTING.md) |

**Automated test suite**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `test` | MUST | Met | JUnit and Robolectric suites in `app/src/test` and `nagios/src/test`, run with `./gradlew testDebugUnitTest :nagios:test`, documented and run in CI. | [docs/development.md#tests](https://github.com/pgmac-net/nagios-android/blob/main/docs/development.md#tests); [ci.yml](https://github.com/pgmac-net/nagios-android/blob/main/.github/workflows/ci.yml) |
| `test_invocation` | SHOULD | Met | The standard Gradle `test` task and `./gradlew check`. | [docs/development.md#everyday-commands](https://github.com/pgmac-net/nagios-android/blob/main/docs/development.md#everyday-commands) |
| `test_most` | SUGGESTED | Met | 86% coverage on Codecov (91% of lines locally with Kover). | [Codecov](https://codecov.io/gh/pgmac-net/nagios-android) |
| `test_continuous_integration` | SUGGESTED | Met | CI builds and tests on every pull request and push to `main`. | [ci.yml](https://github.com/pgmac-net/nagios-android/blob/main/.github/workflows/ci.yml) |

**New functionality testing**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `test_policy` | MUST | Met | "Include tests for behaviour you add or change." | [CONTRIBUTING.md](https://github.com/pgmac-net/nagios-android/blob/main/CONTRIBUTING.md) |
| `tests_are_added` | MUST | Met | Each feature milestone pull request adds tests; the work records name them. | [work records](https://github.com/pgmac-net/nagios-android/tree/main/docs/work) |
| `tests_documented_added` | SUGGESTED | Met | The pull-request rules in CONTRIBUTING.md require tests. | [CONTRIBUTING.md](https://github.com/pgmac-net/nagios-android/blob/main/CONTRIBUTING.md) |

**Warning flags**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `warnings` | MUST | Met | Kotlin `allWarningsAsErrors`, Android lint `warningsAsErrors`, ktlint and detekt. | [KotlinQualityPlugin.kt](https://github.com/pgmac-net/nagios-android/blob/main/build-logic/src/main/kotlin/net/pgmac/nagwatch/buildlogic/KotlinQualityPlugin.kt), [app/build.gradle.kts](https://github.com/pgmac-net/nagios-android/blob/main/app/build.gradle.kts) |
| `warnings_fixed` | MUST | Met | Warnings are errors and there is no baseline, so none are carried. | [CONTRIBUTING.md](https://github.com/pgmac-net/nagios-android/blob/main/CONTRIBUTING.md) |
| `warnings_strict` | SUGGESTED | Met | Warnings are errors; suppressions must be narrow and explained. | [CONTRIBUTING.md](https://github.com/pgmac-net/nagios-android/blob/main/CONTRIBUTING.md) |

### Security


**Secure development knowledge**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `know_secure_design` | MUST | Met | A self-attestation. The design document's security notes and the ADRs show the principles applied: least privilege, a signing key kept outside the build, untrusted input bounded and fuzzed, no ignore-TLS switch. | [docs/design.md section 11](https://github.com/pgmac-net/nagios-android/blob/main/docs/design.md#11-security-notes) |
| `know_common_errors` | MUST | Met | A self-attestation. For this kind of software the common errors are untrusted input parsing, credential storage, TLS handling and intent spoofing; each has a mitigation in the design notes (size and nesting caps, fuzzing, Keystore AES-GCM, system trust store with no bypass, non-exported intent target). | [docs/design.md section 11](https://github.com/pgmac-net/nagios-android/blob/main/docs/design.md#11-security-notes) |

**Use basic good cryptographic practices**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `crypto_published` | MUST | Met | TLS through Android's platform provider via OkHttp; AES-256-GCM through the Android Keystore. Nothing home-grown. | [app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt](https://github.com/pgmac-net/nagios-android/blob/main/app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt) |
| `crypto_call` | SHOULD | Met | Calls the platform TLS stack and the Keystore; implements no cryptography itself. | [app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt](https://github.com/pgmac-net/nagios-android/blob/main/app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt) |
| `crypto_floss` | MUST | Met | Android's TLS and Keystore interfaces are open; Nagwatch's own use depends on no proprietary cryptographic service. | [config/foss-denylist.txt](https://github.com/pgmac-net/nagios-android/blob/main/config/foss-denylist.txt) |
| `crypto_keylength` | MUST | Met | AES-256 for stored secrets; TLS parameters come from the platform's defaults, which meet NIST minimums. | [app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt](https://github.com/pgmac-net/nagios-android/blob/main/app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt) |
| `crypto_working` | MUST | Met | No MD5, SHA-1, DES, RC4 or ECB in use; stored values are AES-GCM, authenticated. | [app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt](https://github.com/pgmac-net/nagios-android/blob/main/app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt) |
| `crypto_weaknesses` | SHOULD | Met | As above; no weak algorithms or modes. | [app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt](https://github.com/pgmac-net/nagios-android/blob/main/app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt) |
| `crypto_pfs` | SHOULD | Met | TLS 1.2 or later negotiated by the Android platform, which prefers ECDHE key agreement. | [docs/design.md section 4 and 11](https://github.com/pgmac-net/nagios-android/blob/main/docs/design.md#11-security-notes) |
| `crypto_password_storage` | MUST | N/A | Nagwatch does not authenticate external users. It stores the user's own Nagios credentials to present to Nagios, so they are encrypted (AES-256-GCM, Keystore key), not hashed. | [app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt](https://github.com/pgmac-net/nagios-android/blob/main/app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt) |
| `crypto_random` | MUST | Met | The Keystore generates the key, and each value gets a fresh random nonce from the platform cipher. | [app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt](https://github.com/pgmac-net/nagios-android/blob/main/app/src/main/kotlin/net/pgmac/nagwatch/profile/SecretCipher.kt) |

**Secured delivery against man-in-the-middle (MITM) attacks**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `delivery_mitm` | MUST | Met | Releases are served over HTTPS from GitHub, and each APK is signed with one key, with a build attestation. | [README.md#install](https://github.com/pgmac-net/nagios-android/blob/main/README.md#install) |
| `delivery_unsigned` | MUST | Met | `SHA256SUMS` comes from the same HTTPS release page, and the APK's signature is checked with `apksigner` against the fingerprint committed in the repository. | [README.md#install](https://github.com/pgmac-net/nagios-android/blob/main/README.md#install); [config/release-signing-cert.sha256](https://github.com/pgmac-net/nagios-android/blob/main/config/release-signing-cert.sha256) |

**Publicly known vulnerabilities fixed**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `vulnerabilities_fixed_60_days` | MUST | Met | Dependency-Track reports none for 0.1.1. The one Dependabot alert raised in October (freemarker, a build-classpath dependency, not in the APK) was opened 2026-10-15 and fixed the same day by [#87](https://github.com/pgmac-net/nagios-android/pull/87), which raised it to 2.3.35; the alert closed as fixed on the dependency-submission run after the merge. | [#85](https://github.com/pgmac-net/nagios-android/issues/85), [#87](https://github.com/pgmac-net/nagios-android/pull/87) |
| `vulnerabilities_critical_fixed` | SHOULD | Met | The only critical Dependabot alert raised against the project (freemarker, CVE-2026-84939) was opened and fixed on 2026-10-15, the same day. It was on the build classpath only and not in the shipped app. | [#85](https://github.com/pgmac-net/nagios-android/issues/85), [#87](https://github.com/pgmac-net/nagios-android/pull/87) |

**Other security issues**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `no_leaked_credentials` | MUST | Met | Credentials for the test Nagios live in `~/.config/nagwatch/dev.env`, outside the repository; fixtures are sanitised and a test fails if a real value appears. | [docs/development.md#what-check-enforces](https://github.com/pgmac-net/nagios-android/blob/main/docs/development.md#what-check-enforces) |

### Analysis


**Static code analysis**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `static_analysis` | MUST | Met | CodeQL (Java/Kotlin and Actions), detekt, ktlint and Android lint on every pull request. | [codeql.yml](https://github.com/pgmac-net/nagios-android/blob/main/.github/workflows/codeql.yml); [ci.yml](https://github.com/pgmac-net/nagios-android/blob/main/.github/workflows/ci.yml) |
| `static_analysis_common_vulnerabilities` | SUGGESTED | Met | CodeQL's security queries and Android lint's security checks. | [codeql.yml](https://github.com/pgmac-net/nagios-android/blob/main/.github/workflows/codeql.yml) |
| `static_analysis_fixed` | MUST | Met | No medium or higher finding is open; the only code-scanning alerts are Scorecard policy results. | [Scorecard result](https://scorecard.dev/viewer/?uri=github.com/pgmac-net/nagios-android) |
| `static_analysis_often` | SUGGESTED | Met | On every pull request and push, and weekly. | [codeql.yml](https://github.com/pgmac-net/nagios-android/blob/main/.github/workflows/codeql.yml) |

**Dynamic code analysis**

| Criterion | | Answer | Why | Evidence |
|---|---|---|---|---|
| `dynamic_analysis` | SUGGESTED | Met | Jazzer fuzzing of the parsers that read a Nagios server's responses, on every pull request and weekly for longer. | [fuzz.yml](https://github.com/pgmac-net/nagios-android/blob/main/.github/workflows/fuzz.yml); [docs/development.md#fuzzing](https://github.com/pgmac-net/nagios-android/blob/main/docs/development.md#fuzzing) |
| `dynamic_analysis_unsafe` | SUGGESTED | N/A | Kotlin on the JVM; no memory-unsafe code. | [app/build.gradle.kts](https://github.com/pgmac-net/nagios-android/blob/main/app/build.gradle.kts) |
| `dynamic_analysis_enable_assertions` | SUGGESTED | Met | Tests and fuzz targets run under Gradle's test and JVM defaults, which enable assertions, with Kotlin `require` and `check` throughout. | [docs/development.md#fuzzing](https://github.com/pgmac-net/nagios-android/blob/main/docs/development.md#fuzzing) |
| `dynamic_analysis_fixed` | MUST | Met | The documented process turns any fuzz crash into a fixed regression input. No confirmed exploitable finding is open. | [docs/development.md#fuzzing](https://github.com/pgmac-net/nagios-android/blob/main/docs/development.md#fuzzing) |

## Silver: what is not met

Not part of the Passing badge. Only the criteria that are not met today, with what meeting each would take. Criteria Nagwatch already meets (signed releases, coverage above 80%, coding standards enforced, TLS verified by default, dependencies listed and monitored) are left out.

| Criterion | What it would take | Needs another person? |
|---|---|---|
| `dco` | A DCO sign-off or a contributor licence statement for contributions | No |
| `code_of_conduct` | A `CODE_OF_CONDUCT.md` in a standard location | No |
| `governance` | A short GOVERNANCE.md: who decides, and how | No |
| `roles_responsibilities` | Name the roles (maintainer, contributor) and what each does | No |
| `documentation_roadmap` | A roadmap for the next year; the milestones stop at M8 | No |
| `assurance_case` | Threat model, trust boundaries and the argument that the security requirements hold, built from design section 11 and the ADRs | No |
| `build_repeatable` | A bit-for-bit reproducible APK; not assessed | No |
| `regression_tests_added50` | Count bugs fixed in six months against tests added; not assessed | No |
| `accessibility_best_practices`, `internationalization` | Not assessed; the app is English-only | No |
| `access_continuity` | Someone else able to build, sign and release if the maintainer cannot; the signing key is held by one person | **Yes** |
| `bus_factor` | A bus factor of 2 or more | **Yes** |
| `documentation_achievements` | Link the badge from the README: done in the second pull request | No |

Gold needs two-person review and a second maintainer, so it is out of reach for a solo project.

