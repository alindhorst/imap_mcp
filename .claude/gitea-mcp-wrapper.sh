#!/bin/bash
# Wrapper script to launch gitea-mcp with token from file
TOKEN=$(head -1 "$(dirname "$0")/gitea-issues-token")
exec gitea-mcp \
  -host "http://spacedock:3000" \
  -token "$TOKEN" \
  -t stdio
