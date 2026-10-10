#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs the command client against the throwaway test Nagios, once for each way a server can
# be set to write dates and in more than one timezone. Local only, never CI: it needs Docker.
#
#   scripts/test-nagios/live-test.sh                      every combination
#   scripts/test-nagios/live-test.sh euro Europe/Berlin   just one
#
# The tests send real commands, to a Nagios that exists for that purpose. They refuse to send
# anything to a server that is not on this machine or is not the made-up one.
set -uo pipefail

cd "$(dirname "$0")/../.."

if [ $# -eq 2 ]; then
  combos=("$1 $2")
else
  combos=("us UTC" "euro UTC" "iso8601 UTC" "strict-iso8601 UTC" "us Australia/Brisbane" "euro Europe/Berlin" "us America/New_York")
fi

failed=0
log="$(mktemp)"
trap 'rm -f "$log"; scripts/test-nagios/stop.sh >/dev/null 2>&1' EXIT
for combo in "${combos[@]}"; do
  # shellcheck disable=SC2086
  set -- $combo
  echo "== date_format=$1 timezone=$2"
  if ! scripts/test-nagios/start.sh --date-format "$1" --timezone "$2" >/dev/null; then
    echo "   could not start the test Nagios"
    failed=1
    continue
  fi
  status=0
  NAGWATCH_TEST_NAGIOS="http://127.0.0.1:8089/nagios" NAGWATCH_TEST_DATE_FORMAT="$1" \
    ./gradlew --console=plain :app:testDebugUnitTest \
    --tests 'net.pgmac.nagwatch.nagios.command.CommandLiveTest' -PnagwatchLive=true --rerun >"$log" 2>&1 || status=$?
  grep -E 'live:|CommandLiveTest > .* (FAILED|SKIPPED)|AssertionError|IllegalStateException|^BUILD' "$log" | sed 's/^ */   /' || true
  [ "$status" -eq 0 ] || failed=1
done
exit "$failed"
