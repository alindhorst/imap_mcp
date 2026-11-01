#!/usr/bin/env bash
# SubagentStop hook for the "lead-java-developer" subagent.
# Blocks the subagent from finishing until it has SendMessage'd
# java-developer at least once this stop cycle (dispatch, corrections or
# final acceptance) - see .claude/agents/lead-java-developer.md.
set -euo pipefail

input="$(cat)"

subagent_type="$(jq -r '.subagent_type // .agent_type // .agent_name // empty' <<<"$input")"
if [[ -n "$subagent_type" && "$subagent_type" != "lead-java-developer" ]]; then
  exit 0
fi

session_id="$(jq -r '.session_id // "unknown"' <<<"$input")"
transcript_path="$(jq -r '.transcript_path // empty' <<<"$input")"
stop_hook_active="$(jq -r '.stop_hook_active // false' <<<"$input")"
sentinel="/tmp/claude-lead-java-developer-handoff-check-${session_id}"

# Already nudged once this stop cycle - don't loop forever.
if [[ "$stop_hook_active" == "true" || -f "$sentinel" ]]; then
  exit 0
fi

# No transcript to inspect - fail open rather than block on infra we can't verify.
if [[ -z "$transcript_path" || ! -f "$transcript_path" ]]; then
  exit 0
fi

if jq -e '
  select(.type == "assistant")
  | .message.content[]?
  | select(.type == "tool_use" and .name == "SendMessage")
  | select(.input.to == "java-developer")
' "$transcript_path" >/dev/null 2>&1; then
  exit 0
fi

touch "$sentinel"

cat <<'EOF'
{
  "decision": "block",
  "reason": "No SendMessage to java-developer was found this session. Per .claude/agents/lead-java-developer.md, every run ends with a hand-off to java-developer (work dispatch, corrections, or final acceptance). Send it now.",
  "continue": false,
  "stopReason": "Handoff to java-developer not detected - send it before stopping.",
  "hookSpecificOutput": {
    "hookEventName": "SubagentStop",
    "additionalContext": "Reminder: you must SendMessage java-developer (work items, corrections, or acceptance) before ending your turn. No such message was found in this transcript yet."
  }
}
EOF
