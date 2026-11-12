#!/usr/bin/env bash
# Unit tests only (SPEC 5.8, v0.26), for a slow host: the test phase stops before verify, so the test-completeness
# gate and the failsafe integration tests do not run. This is not merge-gate evidence; ci/verify.sh is.
# Runs on the pinned JDK and Maven as a non-root user. Run it from anywhere; it changes to the repository root.
set -euo pipefail

JDK_VERSION="26.0.2.1"
cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$(pwd)"

echo "ci/unit.sh: unit-only run; this is no merge-gate evidence, the full build is ci/verify.sh"

if [ "$(id -u)" -eq 0 ]; then
    echo "ci/unit.sh: refusing to run as root (uid 0); use the unprivileged runner user" >&2
    exit 1
fi

JAVA_HOME="$("$ROOT/ci/install-jdk.sh")"
export JAVA_HOME
MAVEN_HOME="$("$ROOT/ci/install-maven.sh")"
export PATH="$MAVEN_HOME/bin:$JAVA_HOME/bin:$PATH"

actual="$(java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
if [ "$actual" != "$JDK_VERSION" ]; then
    echo "ci/unit.sh: expected JDK $JDK_VERSION, found '$actual'" >&2
    exit 1
fi
if ! mvn -v 2>/dev/null | grep -q "^Apache Maven 3\.9\."; then
    echo "ci/unit.sh: pinned Maven 3.9.x is not first on PATH" >&2
    exit 1
fi

collect() {
    rc=$?
    out="$ROOT/ci-artifacts"
    mkdir -p "$out"
    for dir in "$ROOT"/*/target/surefire-reports; do
        if [ -d "$dir" ]; then
            mod="$(basename "$(dirname "$(dirname "$dir")")")"
            mkdir -p "$out/$mod"
            cp -R "$dir" "$out/$mod/" || true
        fi
    done
    exit "$rc"
}
trap collect EXIT

mvn -B test
