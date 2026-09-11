#!/usr/bin/env bash
# Everything CI will run, before pushing. Same order, so it fails where CI would.
#
# The two-JDK matrix is the part worth having locally: it has already caught a class of bug that no
# in-process check can see, and finding that out from a red pull request is a slow way to find out.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() { printf '\n\033[31mFAILED: %s\033[0m\n' "$1" >&2; exit 1; }
step() { printf '\n\033[1m==> %s\033[0m\n' "$1"; }

step "build and test on the default JDK"
mvn -B -ntp verify || fail "verify"

step "coverage"
mvn -B -ntp verify -Dcoverage -q || fail "coverage"
for csv in $(find . -name jacoco.csv); do
  awk -F, -v f="$csv" 'NR>1 {mi+=$4; ci+=$5} END {
    if (mi+ci > 0) printf "  %-50s %6.1f%% lines\n", f, 100*ci/(mi+ci)
  }' "$csv"
done

step "a wider soak sweep than CI runs on every commit"
mvn -B -ntp install -DskipTests -q
mvn -B -ntp test -pl cairn-testkit -am -Dtest=SoakTest -Dcairn.sim.seeds=500 \
    -Dsurefire.failIfNoSpecifiedTests=false -q || fail "soak"

step "the second JDK"
# Every JDK the CI matrix uses. A lint that exists in one and not the other is exactly the kind of
# thing this catches.
for version in 21 25; do
  home="$(/usr/libexec/java_home -v "$version" 2>/dev/null || true)"
  if [ -z "$home" ]; then
    printf '  JDK %s is not installed; CI will still check it\n' "$version"
    continue
  fi
  printf '  JDK %s\n' "$version"
  JAVA_HOME="$home" mvn -B -ntp verify -q || fail "verify on JDK $version"
done

step "a clean clone must build with nothing but a JDK and Maven"
rm -rf /tmp/cairn-fresh-repo
mvn -B -ntp -Dmaven.repo.local=/tmp/cairn-fresh-repo package -DskipTests -q \
    || fail "clean-clone build"

printf '\n\033[32mpreflight passed\033[0m\n'
