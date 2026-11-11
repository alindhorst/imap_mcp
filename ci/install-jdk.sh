#!/usr/bin/env bash
# Installs the pinned Temurin JDK 26 for the CI job under $HOME and prints its home directory on stdout.
# The tarball is verified against a pinned sha256 (source: the checksum file of the Adoptium release
# https://github.com/adoptium/temurin26-binaries/releases/tag/jdk-26.0.2.1%2B1). Nothing but the path goes to stdout.
set -euo pipefail

JDK_VERSION="26.0.2.1"
JDK_BUILD="1"
JDK_SHA256="451c12e68747bcfa2fb5a2c16b00483fedb9fa6d77bc962d30957f76ac17044d"
JDK_FILE="OpenJDK26U-jdk_x64_linux_hotspot_${JDK_VERSION}_${JDK_BUILD}.tar.gz"
JDK_URL="https://github.com/adoptium/temurin26-binaries/releases/download/jdk-${JDK_VERSION}%2B${JDK_BUILD}/${JDK_FILE}"

BASE="${IMAP_MCP_CI_TOOLS:-$HOME/.cache/imap-mcp-ci}"
TARGET="$BASE/jdk-${JDK_VERSION}+${JDK_BUILD}"

if [ ! -x "$TARGET/bin/java" ]; then
    mkdir -p "$BASE"
    work="$(mktemp -d "$BASE/dl.XXXXXX")"
    trap 'rm -rf "$work"' EXIT
    curl -fsSL --retry 3 -o "$work/$JDK_FILE" "$JDK_URL" >&2
    echo "$JDK_SHA256  $work/$JDK_FILE" | sha256sum --check --status >&2
    mkdir "$work/x"
    tar -xzf "$work/$JDK_FILE" -C "$work/x" --strip-components=1
    rm -rf "$TARGET"
    mv "$work/x" "$TARGET"
fi
echo "$TARGET"
