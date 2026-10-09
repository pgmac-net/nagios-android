#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Capture raw Nagios JSON CGI responses for scripts/sanitise_fixtures.py.
#
# The output names real hosts and services. Write it somewhere OUTSIDE this
# repository and never commit it.
#
#   NAGWATCH_URL=https://nagios.example.org/nagios/cgi-bin \
#     scripts/capture-fixtures.sh /tmp/nagwatch-raw
#
# Credentials come from ~/.config/nagwatch/dev.env (USER= and PASS= lines).
# Use a read-only Nagios user: nothing here needs command authorisation.
set -euo pipefail

out="${1:?usage: capture-fixtures.sh RAW_DIR}"
url="${NAGWATCH_URL:?set NAGWATCH_URL to the CGI directory, e.g. https://host/nagios/cgi-bin}"
env_file="${NAGWATCH_ENV:-$HOME/.config/nagwatch/dev.env}"

repo="$(cd "$(dirname "$0")/.." && pwd -P)"
mkdir -p "$out"
chmod 700 "$out"
case "$(cd "$out" && pwd -P)/" in
  "$repo"/*) echo "refusing to capture into the repository: $out" >&2; exit 1 ;;
esac

value() { grep -E "^$1=" "$env_file" | head -1 | cut -d= -f2- | sed -e 's/^["'\'']//' -e 's/["'\'']$//'; }
user="$(value USER)"
pass="$(value PASS)"
[ -n "$user" ] && [ -n "$pass" ] || { echo "USER or PASS missing in $env_file" >&2; exit 1; }

config="$(mktemp)"
trap 'rm -f "$config"' EXIT
chmod 600 "$config"
# Passed by config file so the password never appears in the process list.
printf 'user = "%s:%s"\n' "${user//\"/\\\"}" "${pass//\"/\\\"}" >"$config"

fetch() {
  local name="$1" query="$2"
  curl --silent --show-error --fail --max-time 30 --config "$config" \
    --output "$out/$name" "$url/statusjson.cgi?$query&formatoptions=enumerate"
  echo "captured $name"
}

fetch programstatus.json "query=programstatus"
fetch hostlist_details.json "query=hostlist&details=true"
fetch hostlist_problems_details.json "query=hostlist&details=true&hoststatus=down+unreachable"
fetch hostlist_nodetails.json "query=hostlist"
fetch servicelist_problems_details.json "query=servicelist&details=true&servicestatus=warning+critical+unknown"
fetch servicelist_nodetails_single.json "query=servicelist&start=0&count=1"
fetch servicelist_beyond_end.json "query=servicelist&details=true&start=100000&count=10"
# Nagios reports this as HTTP 200 with a non-zero result.type_code.
fetch error_invalid_option.json "query=nonsense"
