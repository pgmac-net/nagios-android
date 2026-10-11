#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Stops and removes the throwaway test Nagios started by start.sh, and its configuration volume.
set -euo pipefail
if docker rm -f nagwatch-test-nagios >/dev/null 2>&1; then
  echo "test Nagios stopped"
else
  echo "test Nagios was not running"
fi
docker volume rm -f nagwatch-test-nagios-etc >/dev/null 2>&1 || true
