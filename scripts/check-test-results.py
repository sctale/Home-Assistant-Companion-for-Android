#!/usr/bin/env python3
r"""
Decide whether a Gradle test run is acceptable, separating real failures from machine-dependent ones.

Why this exists instead of a plain `gradlew test`: one test cannot pass on this class of machine, and
a gate that is permanently red is a gate that ends up being ignored.

  Wildcard proxy DNS. `UrlUtilTest` asserts that a made-up domain fails to resolve. Local proxy clients
  in TUN mode (Clash / sing-box) answer every query with an address in 198.18.0.0/15, so on those
  machines the assertion measures the network, not the code. It is matched on the exact assertion, so a
  different failure inside the same test is still reported.

Everything else is unexpected and exits non-zero. The failures previously tolerated here - Robolectric
navigation tests dying on SQLITE_CANTOPEN - were not excused but fixed: their per-test database path
ran past the Windows MAX_PATH limit, so test JVMs are now given a short temp root (see the `Test` task
configuration in the Android common convention plugin).
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# Not identifiable from its exception (a plain assertion failure), so it is matched on the test itself.
# Kept deliberately narrow: this exact assertion, and nothing else.
DNS_ASSERTION_TEST = "Given unresolved TLD when checking isPubliclyAccessible then returns false"


def environmental_reason(name: str, text: str) -> str | None:
    if DNS_ASSERTION_TEST in name and "expected: <false>" in text:
        return "local DNS answers every name (proxy running in TUN mode)"
    return None


def main(results_dirs) -> int:
    total = failures = skipped = tolerated = 0
    unexpected = []

    for directory in results_dirs:
        for path in sorted(Path(directory).rglob("TEST-*.xml")):
            try:
                root = ET.parse(path).getroot()
            except ET.ParseError:
                continue
            for case in root.iter("testcase"):
                total += 1
                node = case.find("failure")
                if node is None:
                    node = case.find("error")
                if node is None:
                    if case.find("skipped") is not None:
                        skipped += 1
                    continue
                failures += 1
                name = case.get("name", "")
                message = node.get("message") or ""
                text = f"{message}\n{(node.text or '')[:2000]}"
                reason = environmental_reason(name, text)
                if reason:
                    tolerated += 1
                    print(f"    environment-dependent failure [{reason}]")
                    print(f"      {path.name} :: {name}")
                else:
                    unexpected.append((path.name, name, text[:220]))

    print(
        f"    tests: {total}, failures: {failures}, tolerated: {tolerated}, "
        f"unexpected: {len(unexpected)}, skipped: {skipped}"
    )
    if unexpected:
        print("    UNEXPECTED TEST FAILURES:")
        for suite, name, message in unexpected:
            print(f"      {suite} :: {name}")
            print(f"        {message}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or [
        "app/build/test-results",
        "common/build/test-results",
        "lint/build/test-results",
        "wear/build/test-results",
        "automotive/build/test-results",
    ]))
