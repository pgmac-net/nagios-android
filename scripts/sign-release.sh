#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Signs a release APK and proves it was signed with the right key.
#
#   scripts/sign-release.sh <unsigned.apk> <signed.apk>
#
# Run by the release workflow, in the one job that holds the key. It uses the
# Android SDK's own zipalign and apksigner and nothing else: no Gradle, no
# plugin, no dependency of the app runs while the key can be read.
# See docs/adr/0011 and "Releasing" in docs/development.md.
#
# The key comes from the environment, never from an argument (arguments show
# in the process list):
#   KEYSTORE_FILE      path to the PKCS12 keystore
#   KEYSTORE_PASSWORD  its password (a PKCS12 key has the same one)
#   KEY_ALIAS          the key's alias
#   ANDROID_HOME       the Android SDK
#
# It fails unless the signed APK carries exactly one certificate, and that
# certificate is the one whose SHA-256 is committed in
# config/release-signing-cert.sha256. An APK signed with any other key would
# install for nobody who has the app already, so it must never be published.
set -euo pipefail

usage() {
  echo "usage: $0 <unsigned.apk> <signed.apk>" >&2
  exit 2
}

[[ $# -eq 2 ]] || usage
unsigned="$1"
signed="$2"

repo="$(cd "$(dirname "$0")/.." && pwd)"
fingerprint_file="${FINGERPRINT_FILE:-$repo/config/release-signing-cert.sha256}"

: "${KEYSTORE_FILE:?KEYSTORE_FILE is not set}"
: "${KEYSTORE_PASSWORD:?KEYSTORE_PASSWORD is not set}"
: "${KEY_ALIAS:?KEY_ALIAS is not set}"
: "${ANDROID_HOME:?ANDROID_HOME is not set}"

[[ -s "$unsigned" ]] || { echo "error: no APK at $unsigned" >&2; exit 1; }
[[ -s "$KEYSTORE_FILE" ]] || { echo "error: no keystore at $KEYSTORE_FILE (is the secret set?)" >&2; exit 1; }
[[ -r "$fingerprint_file" ]] || { echo "error: cannot read $fingerprint_file" >&2; exit 1; }

# The committed fingerprint: comments and blank lines aside, one line of hex.
# Colons and capitals are accepted, because that is how keytool prints it.
expected="$(grep -v -E '^[[:space:]]*(#|$)' "$fingerprint_file" | tr -d ':[:space:]' | tr '[:upper:]' '[:lower:]' || true)"
if [[ ! "$expected" =~ ^[0-9a-f]{64}$ ]]; then
  echo "error: $fingerprint_file does not hold one SHA-256 fingerprint." >&2
  echo "       Nothing is signed until the release key's fingerprint is committed there." >&2
  exit 1
fi

# The newest build-tools the SDK has. zipalign and apksigner are stable across versions.
build_tools="$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
[[ -x "$build_tools/apksigner" && -x "$build_tools/zipalign" ]] || {
  echo "error: no apksigner and zipalign under $ANDROID_HOME/build-tools" >&2
  exit 1
}
echo "build-tools: $(basename "$build_tools")"

# Signing comes after alignment: aligning a signed APK would break its signature.
# The build already aligns, so this only checks.
"$build_tools/zipalign" -c 4 "$unsigned" || {
  echo "error: $unsigned is not aligned" >&2
  exit 1
}

# minSdk is 26, so the v1 (JAR) scheme, which does not cover the whole file, is not needed.
"$build_tools/apksigner" sign \
  --ks "$KEYSTORE_FILE" \
  --ks-type PKCS12 \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass env:KEYSTORE_PASSWORD \
  --key-pass env:KEYSTORE_PASSWORD \
  --v1-signing-enabled false \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$signed" \
  "$unsigned"

# Verified as the oldest Android the app installs on would verify it. Depending on its version
# apksigner names a signer "Signer #1" or "V3.0 Signer", and may list one once for each scheme.
report="$("$build_tools/apksigner" verify --min-sdk-version 26 --print-certs "$signed")"
actual="$(sed -n 's/^.*Signer.* certificate SHA-256 digest: //p' <<<"$report" | sort -u)"

if [[ "$(grep -c . <<<"$actual")" -ne 1 ]]; then
  rm -f "$signed"
  echo "error: expected one signing certificate, found:" >&2
  echo "$report" >&2
  exit 1
fi

if [[ "$actual" != "$expected" ]]; then
  rm -f "$signed"
  echo "error: signed with the wrong key." >&2
  echo "       certificate: $actual" >&2
  echo "       expected:    $expected ($fingerprint_file)" >&2
  exit 1
fi

echo "signed: $signed"
echo "certificate SHA-256: $actual"
