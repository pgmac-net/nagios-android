#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Starts a throwaway Nagios Core in Docker, for developing and testing commands
# (acknowledge, downtime, recheck, ...). Commands change a Nagios, so they are never
# tried against a real one: this is what they are tried against.
#
#   scripts/test-nagios/start.sh [--date-format us|euro|iso8601|strict-iso8601]
#                                [--timezone Area/City] [--lan] [--port N]
#   scripts/test-nagios/stop.sh
#
# Everything in it is made up: two hosts, four services with fixed states, and three users.
# The passwords below are NOT secrets. They protect nothing; they exist because cmd.cgi
# refuses to work without authentication.
#
#   operator / operator-pw   may send any command
#   viewer   / viewer-pw     read-only
#   limited  / limited-pw    may only command web01 and its services
#
# It listens on 127.0.0.1 unless --lan is given. --lan exposes it to the local network,
# over plain http, so that a phone can reach it; stop it when the check is done.
#
# The image defaults to the public jasonrivers/nagios; set NAGWATCH_TEST_IMAGE to use another
# build of Nagios Core 4.x with the same layout (/opt/nagios, /orig/etc).
#
# Its configuration lives in a Docker volume, not in files on the host: the image changes
# the ownership of its config directory, and a host directory would be left undeletable.
set -euo pipefail

IMAGE="${NAGWATCH_TEST_IMAGE:-jasonrivers/nagios:latest}"
NAME=nagwatch-test-nagios
VOLUME=nagwatch-test-nagios-etc
DATE_FORMAT=us
TIMEZONE=UTC
BIND=127.0.0.1
PORT=8089

while [ $# -gt 0 ]; do
  case "$1" in
    --date-format) DATE_FORMAT=$2; shift 2 ;;
    --timezone) TIMEZONE=$2; shift 2 ;;
    --lan) BIND=0.0.0.0; shift ;;
    --port) PORT=$2; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done
case "$DATE_FORMAT" in us|euro|iso8601|strict-iso8601) ;; *) echo "bad --date-format: $DATE_FORMAT" >&2; exit 2 ;; esac
case "$TIMEZONE" in *[!A-Za-z0-9_/+-]*|"") echo "bad --timezone: $TIMEZONE" >&2; exit 2 ;; esac
case "$PORT" in *[!0-9]*|"") echo "bad --port: $PORT" >&2; exit 2 ;; esac

HERE=$(cd "$(dirname "$0")" && pwd)

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker volume rm -f "$VOLUME" >/dev/null 2>&1 || true
docker volume create "$VOLUME" >/dev/null

# Prepared inside the image, so nothing but Docker is needed on the host: the image's own
# example config, our objects on top, who may do what, and the three users.
docker run --rm --entrypoint sh \
  -v "$VOLUME:/work" -v "$HERE/objects.cfg:/objects.cfg:ro" \
  -e "DATE_FORMAT=$DATE_FORMAT" -e "TIMEZONE=$TIMEZONE" \
  "$IMAGE" -ec '
    cp -Rp /orig/etc/. /work/
    cp /objects.cfg /work/conf.d/nagwatch-test.cfg

    # Only our objects: the example localhost would add checks that really run.
    sed -i -E "s@^(cfg_file=.*/objects/(localhost|printer|switch|windows)\.cfg)@#\1@" /work/nagios.cfg
    sed -i -E "s@^date_format=.*@date_format=$DATE_FORMAT@; s@^use_timezone=.*@use_timezone=$TIMEZONE@" /work/nagios.cfg
    grep -q "^use_timezone=" /work/nagios.cfg || echo "use_timezone=$TIMEZONE" >>/work/nagios.cfg

    # Everyone sees everything; what differs is who may command.
    set_cgi() {
      sed -i -E "s@^#?$1=.*@$1=$2@" /work/cgi.cfg
      grep -q "^$1=" /work/cgi.cfg || echo "$1=$2" >>/work/cgi.cfg
    }
    set_cgi authorized_for_all_hosts "nagiosadmin,operator,viewer,limited"
    set_cgi authorized_for_all_services "nagiosadmin,operator,viewer,limited"
    set_cgi authorized_for_all_host_commands "nagiosadmin,operator"
    set_cgi authorized_for_all_service_commands "nagiosadmin,operator"
    set_cgi authorized_for_read_only "viewer"

    # Not secrets: see the note at the top of start.sh.
    htpasswd -c -b -s /work/htpasswd.users operator operator-pw >/dev/null 2>&1
    htpasswd -b -s /work/htpasswd.users viewer viewer-pw >/dev/null 2>&1
    htpasswd -b -s /work/htpasswd.users limited limited-pw >/dev/null 2>&1
    htpasswd -b -s /work/htpasswd.users nagiosadmin nagiosadmin-pw >/dev/null 2>&1
    chown -R nagios:nagios /work
  '

docker run -d --name "$NAME" \
  -p "$BIND:$PORT:80" \
  -e "NAGIOS_TIMEZONE=$TIMEZONE" -e "TZ=$TIMEZONE" \
  -v "$VOLUME:/opt/nagios/etc" \
  "$IMAGE" >/dev/null

# Ready when the JSON CGI answers and the first made-up checks have run.
url="http://127.0.0.1:$PORT"
for _ in $(seq 1 60); do
  if curl -fsS -m 3 -u operator:operator-pw \
    "$url/nagios/cgi-bin/statusjson.cgi?query=servicelist&formatoptions=enumerate" 2>/dev/null | grep -q '"critical"'; then
    echo "test Nagios ready at $url/nagios  (date_format=$DATE_FORMAT, timezone=$TIMEZONE, bound to $BIND)"
    exit 0
  fi
  sleep 2
done
echo "test Nagios did not become ready; see: docker logs $NAME" >&2
exit 1
