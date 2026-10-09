#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Run the real Nagios client against a real instance. Local only, never CI.
#
#   scripts/live-smoke-test.sh https://nagios.example.org/nagios
#
# Credentials: ~/.config/nagwatch/dev.env with USER= and PASS= lines (and
# optionally ACCESS_CLIENT_ID= / ACCESS_CLIENT_SECRET=). A read-only Nagios
# user is enough. Prints counts only, never host or service names.
set -euo pipefail

cd "$(dirname "$0")/.."

export NAGWATCH_URL="${1:?usage: live-smoke-test.sh NAGIOS_BASE_URL}"

log="$(mktemp)"
trap 'rm -f "$log"' EXIT
status=0
./gradlew --console=plain :app:testDebugUnitTest \
  --tests 'net.pgmac.nagwatch.nagios.LiveSmokeTest' \
  -PnagwatchLive=true --rerun >"$log" 2>&1 || status=$?

grep -E 'live:|LiveSmokeTest > .* (FAILED|SKIPPED)|AssertionError|^BUILD' "$log" | sed 's/^ *//' || true
exit "$status"
