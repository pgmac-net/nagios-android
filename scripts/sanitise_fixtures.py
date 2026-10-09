#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Turn raw Nagios JSON CGI captures into fixtures that are safe to publish.

Raw captures name real hosts, addresses and services and quote real plugin
output. None of that may reach this repository. This script keeps the exact
structure, field names, types, states and timestamps, which is what the
parser tests need, and replaces everything identifying:

- host names            -> host01, host02, ...
- service descriptions  -> generic names (PING, Disk /, ...)
- plugin output         -> generic text matching the state
- perf data             -> generic or empty
- the authenticated user -> nagwatch

Usage:
    scripts/capture-fixtures.sh RAW_DIR        # writes raw captures (never commit)
    scripts/sanitise_fixtures.py RAW_DIR OUT_DIR

Renaming is deterministic (first-seen order across the inputs, in the order
listed in FIXTURES), so re-running on the same captures gives the same output.
After writing, the script re-reads its output and fails if any original name
survived.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

# (raw capture, fixture name, maximum hosts to keep or None for all)
FIXTURES: list[tuple[str, str, int | None]] = [
    ("programstatus.json", "programstatus.json", None),
    ("hostlist_details.json", "hostlist_details.json", 8),
    ("hostlist_problems_details.json", "hostlist_problems_details.json", None),
    ("hostlist_nodetails.json", "hostlist_nodetails.json", 8),
    ("servicelist_problems_details.json", "servicelist_problems_details.json", None),
    ("servicelist_nodetails_single.json", "servicelist_nodetails_single.json", None),
    ("servicelist_beyond_end.json", "servicelist_beyond_end.json", None),
    ("error_invalid_option.json", "error_invalid_option.json", None),
]

SERVICE_NAMES = [
    "PING", "SSH", "HTTP", "Disk /", "Load", "Memory", "Swap", "NTP",
    "DNS", "Processes", "Users", "Uptime", "Disk /var", "HTTPS certificate",
]

HOST_OUTPUT = {
    "up": "PING OK - Packet loss = 0%, RTA = 0.42 ms",
    "down": "CRITICAL - Host Unreachable (192.0.2.10)",
    "unreachable": "CRITICAL - Network Unreachable (192.0.2.10)",
    "pending": "",
}
SERVICE_OUTPUT = {
    "ok": "OK - check passed",
    "warning": "WARNING - value 85 is above the warning threshold 80",
    "critical": "CRITICAL - value 97 is above the critical threshold 90",
    "unknown": "UNKNOWN - check could not be run",
    "pending": "",
}
SERVICE_PERF = {
    "ok": "value=42;80;90;0;100",
    "warning": "value=85;80;90;0;100",
    "critical": "value=97;80;90;0;100",
}


class Renamer:
    """Maps real names to generic ones, remembering every original it has seen."""

    def __init__(self) -> None:
        self.hosts: dict[str, str] = {}
        self.services: dict[str, str] = {}

    def host(self, name: str) -> str:
        """Return the generic name for a real host name."""
        if name not in self.hosts:
            self.hosts[name] = f"host{len(self.hosts) + 1:02d}"
        return self.hosts[name]

    def service(self, description: str) -> str:
        """Return the generic name for a real service description."""
        if description not in self.services:
            index = len(self.services)
            self.services[description] = (
                SERVICE_NAMES[index] if index < len(SERVICE_NAMES) else f"Service {index + 1:02d}"
            )
        return self.services[description]

    def originals(self) -> list[str]:
        """Real names that must not appear in any fixture."""
        generic = set(SERVICE_NAMES)
        hosts = list(self.hosts)
        # A real service that happens to share a generic name (e.g. "PING") is not a leak.
        services = [name for name in self.services if name not in generic]
        return [name for name in hosts + services if len(name) > 2]


def scrub_detail(detail: dict[str, Any], output: dict[str, str], perf: dict[str, str]) -> None:
    """Replace the free-text fields of one host or service detail record."""
    state = str(detail.get("status", "")).lower()
    detail["plugin_output"] = output.get(state, "")
    detail["long_plugin_output"] = ""
    detail["perf_data"] = perf.get(state, "")


def scrub_hostlist(hostlist: dict[str, Any], renamer: Renamer, limit: int | None) -> dict[str, Any]:
    """Rename hosts in a hostlist and scrub their details."""
    result: dict[str, Any] = {}
    for name, entry in list(hostlist.items())[:limit]:
        new_name = renamer.host(name)
        if isinstance(entry, dict):
            entry["name"] = new_name
            scrub_detail(entry, HOST_OUTPUT, {})
        result[new_name] = entry
    return result


def scrub_servicelist(servicelist: dict[str, Any], renamer: Renamer, limit: int | None) -> dict[str, Any]:
    """Rename hosts and services in a servicelist and scrub their details."""
    result: dict[str, Any] = {}
    for host, services in list(servicelist.items())[:limit]:
        new_host = renamer.host(host)
        scrubbed: dict[str, Any] = {}
        for description, entry in services.items():
            new_description = renamer.service(description)
            if isinstance(entry, dict):
                entry["host_name"] = new_host
                entry["description"] = new_description
                scrub_detail(entry, SERVICE_OUTPUT, SERVICE_PERF)
            scrubbed[new_description] = entry
        result[new_host] = scrubbed
    return result


def sanitise(document: dict[str, Any], renamer: Renamer, limit: int | None) -> dict[str, Any]:
    """Sanitise one whole CGI response."""
    result = document.get("result", {})
    if "user" in result:
        result["user"] = "nagwatch"
    data = document.get("data", {})
    if result.get("type_code", 0) != 0:
        # Error responses carry the full API help as data; the tests only need the result.
        document["data"] = {}
        return document
    if "hostlist" in data:
        data["hostlist"] = scrub_hostlist(data["hostlist"], renamer, limit)
    if "servicelist" in data:
        data["servicelist"] = scrub_servicelist(data["servicelist"], renamer, limit)
    selectors = data.get("selectors")
    if isinstance(selectors, dict):
        for key in ("hostname", "servicedescription"):
            selectors.pop(key, None)
    return document


def main(argv: list[str]) -> int:
    """Sanitise every capture listed in FIXTURES."""
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    raw_dir, out_dir = Path(argv[1]), Path(argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    renamer = Renamer()
    written: list[Path] = []

    for raw_name, fixture_name, limit in FIXTURES:
        source = raw_dir / raw_name
        if not source.exists():
            print(f"missing capture: {source}", file=sys.stderr)
            return 1
        document = sanitise(json.loads(source.read_text(encoding="utf-8")), renamer, limit)
        target = out_dir / fixture_name
        target.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
        written.append(target)

    leaks = [
        f"{path.name}: {name!r}"
        for path in written
        for name in renamer.originals()
        if name.lower() in path.read_text(encoding="utf-8").lower()
    ]
    if leaks:
        print("original names survived sanitising:", *leaks, sep="\n  ", file=sys.stderr)
        for path in written:
            path.unlink()
        return 1

    print(
        f"wrote {len(written)} fixtures: "
        f"{len(renamer.hosts)} hosts, {len(renamer.services)} services renamed"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
