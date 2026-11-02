#!/bin/bash
# Wrapper script to launch gitea-mcp with token from file.
# The token goes via the environment, never via argv (visible in ps and /proc/<pid>/cmdline).
# GITEA_ISSUES_TOKEN_FILE overrides the default location (mainly for tests).
TOKEN_FILE="${GITEA_ISSUES_TOKEN_FILE:-$(dirname "$0")/gitea-issues-token}"
GITEA_ACCESS_TOKEN=$(head -1 "$TOKEN_FILE")
export GITEA_ACCESS_TOKEN
export GITEA_HOST="http://spacedock:3000"
exec gitea-mcp -t stdio
