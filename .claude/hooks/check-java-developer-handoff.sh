#!/usr/bin/env bash
# SubagentStop hook for the "java-developer" subagent.
# Blocks the subagent from finishing until it has SendMessage'd
# lead-java-developer at least once this stop cycle (handoff or
# clarification question) - see .claude/agents/java-developer.md.
set -euo pipefail

input="$(cat)"

subagent_type="$(jq -r '.subagent_type // .agent_type // .agent_name // empty' <<<"$input")"
if [[ -n "$subagent_type" && "$subagent_type" != "java-developer" ]]; then
  exit 0
fi

session_id="$(jq -r '.session_id // "unknown"' <<<"$input")"
transcript_path="$(jq -r '.transcript_path // empty' <<<"$input")"
stop_hook_active="$(jq -r '.stop_hook_active // false' <<<"$input")"
sentinel="/tmp/claude-java-developer-handoff-check-${session_id}"

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
  | select(.input.to == "lead-java-developer")
' "$transcript_path" >/dev/null 2>&1; then
  exit 0
fi

touch "$sentinel"

cat <<'EOF'
{
  "decision": "block",
  "reason": "No SendMessage to lead-java-developer was found this session. Per .claude/agents/java-developer.md, hand off (implementation done, or a clarification question) to lead-java-developer via SendMessage before finishing. Send it now.",
  "continue": false,
  "stopReason": "Handoff to lead-java-developer not detected - send it before stopping.",
  "hookSpecificOutput": {
    "hookEventName": "SubagentStop",
    "additionalContext": "Reminder: you must SendMessage lead-java-developer (item summary, or a clarification question) before ending your turn. No such message was found in this transcript yet."
  }
}
EOF
