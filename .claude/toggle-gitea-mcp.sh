#!/usr/bin/env bash
# Toggle the "gitea" MCP server on/off for this project by moving it between
# enabledMcpjsonServers and disabledMcpjsonServers in settings.local.json.
# Usage: .claude/toggle-gitea-mcp.sh [on|off]   (no arg = flip current state)
set -euo pipefail

SETTINGS="$(dirname "$0")/settings.local.json"
STATE="${1:-}"

current_enabled=$(jq '(.enabledMcpjsonServers // []) | index("gitea") != null' "$SETTINGS")

if [[ -z "$STATE" ]]; then
  if [[ "$current_enabled" == "true" ]]; then STATE="off"; else STATE="on"; fi
fi

if [[ "$STATE" == "on" ]]; then
  jq '.enabledMcpjsonServers = ((.enabledMcpjsonServers // []) + ["gitea"] | unique)
      | .disabledMcpjsonServers = ((.disabledMcpjsonServers // []) - ["gitea"])' \
    "$SETTINGS" > "$SETTINGS.tmp" && mv "$SETTINGS.tmp" "$SETTINGS"
  echo "gitea MCP server: enabled"
elif [[ "$STATE" == "off" ]]; then
  jq '.disabledMcpjsonServers = ((.disabledMcpjsonServers // []) + ["gitea"] | unique)
      | .enabledMcpjsonServers = ((.enabledMcpjsonServers // []) - ["gitea"])' \
    "$SETTINGS" > "$SETTINGS.tmp" && mv "$SETTINGS.tmp" "$SETTINGS"
  echo "gitea MCP server: disabled"
else
  echo "usage: $0 [on|off]" >&2
  exit 1
fi
