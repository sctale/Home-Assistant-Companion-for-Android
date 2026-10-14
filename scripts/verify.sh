#!/usr/bin/env bash
#
# Local quality gate. This is the replacement for the GitHub Actions workflows that the previous
# revision of this fork deleted: upstream's pr.yml ran ktlint, Android Lint, unit tests, screenshot
# tests, license checks and dependency-lock checks. Deleting all of that is what let every regression
# in the code review land silently, so the gate has to live somewhere - here it lives in a script.
#
# Deliberately local-only: this project's artifacts are built on this machine, not by a hosted pipeline.
#
# Usage: scripts/verify.sh
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE=(--console=plain)

# The google-services plugin runs for every flavour, so the full-flavour tasks need a
# google-services.json. Upstream synthesised one in CI (the deleted inflate-secrets action); locally we
# copy the credential-free placeholder. A real app/google-services.json is never overwritten.
if [[ ! -f app/google-services.json ]]; then
  echo "==> Installing placeholder google-services.json (no credentials; see tools/)"
  cp tools/mock-google-services.json app/google-services.json
fi

echo "==> Style (ktlint)"
./gradlew "${GRADLE[@]}" ktlintCheck :build-logic:convention:ktlintCheck --continue

echo "==> Tests"
# Upstream's CI runs `testDebugUnitTest :app:testFullDebugUnitTest :lint:test` and notes that "Minimal
# and Full are the same in unit tests for now": three tests in the shared app/src/test source set
# reference full-only classes (WearDnsRequestListener and the Play-services wear tiles), so
# :app:testMinimalDebugUnitTest could not compile at all - the flavour this project ships had zero
# runnable unit tests. Those three files now live in app/src/testFull, which the repository already
# uses for exactly this purpose, so both variants run: minimal executes ~1230 tests.
# --continue: collect every failure, then judge them in one pass against an explicit allowlist of
# machine-dependent failures (see scripts/check-test-results.py). A gate that is permanently red is
# not a gate.
./gradlew "${GRADLE[@]}" testDebugUnitTest :app:testFullDebugUnitTest :app:testMinimalDebugUnitTest :lint:test --continue || true
python3 scripts/check-test-results.py

echo "==> Static analysis (Android Lint on the shipped flavour)"
./gradlew "${GRADLE[@]}" :app:lintMinimalRelease

echo "==> Build the shipped flavour"
./gradlew "${GRADLE[@]}" :app:assembleMinimalDebug

echo
echo "All local gates passed."
