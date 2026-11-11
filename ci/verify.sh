#!/usr/bin/env bash
# The single CI entry point (SPEC 5.8): the same full build the merge gate records, on the pinned JDK, as a
# non-root user. Run it from anywhere; it changes to the repository root. Needs Docker and Maven on the host.
set -euo pipefail

JDK_VERSION="26.0.2.1"
cd "$(dirname "${BASH_SOURCE[0]}")/.."
ROOT="$(pwd)"

# The H2 allowlist admits the uid-0 skip of StateDirectoryTest for local runs; CI must not rely on it.
if [ "$(id -u)" -eq 0 ]; then
    echo "ci/verify.sh: refusing to run as root (uid 0); use the unprivileged runner user" >&2
    exit 1
fi

JAVA_HOME="$("$ROOT/ci/install-jdk.sh")"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

actual="$(java -version 2>&1 | sed -n '1s/.*version "\([^"]*\)".*/\1/p')"
if [ "$actual" != "$JDK_VERSION" ]; then
    echo "ci/verify.sh: expected JDK $JDK_VERSION, found '$actual'" >&2
    exit 1
fi

# Reports and, on failure, the compose logs of the deployed IT go to ci-artifacts/ (picked up by the workflow).
collect() {
    rc=$?
    out="$ROOT/ci-artifacts"
    mkdir -p "$out"
    for dir in "$ROOT"/*/target/surefire-reports "$ROOT"/*/target/failsafe-reports; do
        if [ -d "$dir" ]; then
            mod="$(basename "$(dirname "$(dirname "$dir")")")"
            mkdir -p "$out/$mod"
            cp -R "$dir" "$out/$mod/" || true
        fi
    done
    if [ "$rc" -ne 0 ]; then
        for logs in "$ROOT"/imap-mcp-it/target/it-compose/*/logs; do
            if [ -d "$logs" ]; then
                mkdir -p "$out/it-compose"
                cp -R "$logs" "$out/it-compose/$(basename "$(dirname "$logs")")" || true
            fi
        done
    fi
    exit "$rc"
}
trap collect EXIT

mvn -B verify
