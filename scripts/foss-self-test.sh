#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Proves the FOSS dependency checks still bite.
#
# -PfossSelfTest=true makes the build add a proprietary, denylisted dependency
# (see FossCheckPlugin.SELF_TEST_DEPENDENCY). Each check must then FAIL, and
# fail for the right reason. A check that passes here is broken, so this
# script exits non-zero.
set -uo pipefail

cd "$(dirname "$0")/.."

status=0

expect_failure() {
  local name="$1" pattern="$2"
  shift 2
  local log
  log="$(mktemp)"

  echo "::group::self-test: $name"
  if ./gradlew --console=plain -PfossSelfTest=true "$@" >"$log" 2>&1; then
    cat "$log"
    echo "::endgroup::"
    echo "FAIL  $name: build succeeded with a banned dependency present"
    status=1
  elif ! grep -qF -- "$pattern" "$log"; then
    cat "$log"
    echo "::endgroup::"
    echo "FAIL  $name: build failed, but not with the expected message: $pattern"
    status=1
  else
    grep -F -- "$pattern" "$log" | head -3
    echo "::endgroup::"
    echo "ok    $name: rejected the banned dependency"
  fi
  rm -f "$log"
}

expect_failure "denylist" "FOSS check failed:" :app:checkFossDependencies
expect_failure "licensee" "com.google.android.gms:play-services-base" :app:licenseeAndroidRelease

exit "$status"
