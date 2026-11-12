#!/usr/bin/env bash
# Installs the pinned Apache Maven for the CI job under $HOME and prints its home directory on stdout.
# The archive is verified against a pinned sha512 (source: the .sha512 file next to the binary on
# https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/). Nothing but the path goes to stdout.
set -euo pipefail

MAVEN_VERSION="3.9.9"
MAVEN_SHA512="a555254d6b53d267965a3404ecb14e53c3827c09c3b94b5678835887ab404556bfaf78dcfe03ba76fa2508649dca8531c74bca4d5846513522404d48e8c4ac8b"
MAVEN_FILE="apache-maven-${MAVEN_VERSION}-bin.tar.gz"
MAVEN_URL="https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/${MAVEN_FILE}"

BASE="${IMAP_MCP_CI_TOOLS:-$HOME/.cache/imap-mcp-ci}"
TARGET="$BASE/maven-${MAVEN_VERSION}"

if [ ! -x "$TARGET/bin/mvn" ]; then
    mkdir -p "$BASE"
    work="$(mktemp -d "$BASE/dl.XXXXXX")"
    trap 'rm -rf "$work"' EXIT
    curl -fsSL --retry 3 -o "$work/$MAVEN_FILE" "$MAVEN_URL" >&2
    echo "$MAVEN_SHA512  $work/$MAVEN_FILE" | sha512sum --check --status >&2
    mkdir "$work/x"
    tar -xzf "$work/$MAVEN_FILE" -C "$work/x" --strip-components=1
    rm -rf "$TARGET"
    mv "$work/x" "$TARGET"
fi
echo "$TARGET"
