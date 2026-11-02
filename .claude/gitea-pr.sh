#!/usr/bin/env bash
# PR gate for lindhrst/imap_mcp: verify, approve and merge with a recorded `mvn verify` of the merge result.
#   gitea-pr.sh verify  <n>                       build PR head + current master, write approval body (path printed)
#   gitea-pr.sh approve <n> <body-file> [text]    APPROVED review pinned to the PR head; needs a matching SUCCESS record
#   gitea-pr.sh merge   <n>                       real merge; needs a matching approval by the merge account
#   gitea-pr.sh whoami                            read-only: login of the merge account
# The token comes from the merge-token file (override: GITEA_MERGE_TOKEN_FILE) and reaches curl only through
# a file descriptor (process substitution); it is never on any argv and never printed.
set -euo pipefail

BASE="${GITEA_URL:-http://spacedock:3000}/api/v1/repos/lindhrst/imap_mcp"
API="${GITEA_URL:-http://spacedock:3000}/api/v1"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
TOKEN_FILE="${GITEA_MERGE_TOKEN_FILE:-$SCRIPT_DIR/gitea-merge-token}"
HEX40='[0-9a-f]{40}'

die() { echo "gitea-pr: $*" >&2; exit 1; }

[[ -r "$TOKEN_FILE" ]] || die "token file not readable: $TOKEN_FILE"
mode=$(stat -c %a "$TOKEN_FILE")
[[ "$mode" == "600" ]] || echo "gitea-pr: warning: token file mode is $mode, expected 600 (owner action: chmod 600)" >&2

# curl wrapper: authorization header from a /dev/fd pipe. printf is a builtin, so the token is not in any argv.
api() { # api <method> <url> [json-body-file]
  local method="$1" url="$2" data="${3:-}"
  local args=(-sS -o "$RESP" -w '%{http_code}' -X "$method")
  [[ -n "$data" ]] && args+=(-H 'Content-Type: application/json' --data-binary "@$data")
  # the process substitution must sit in the curl command itself, or bash closes its fd before curl runs
  curl -K <(printf 'header = "Authorization: token %s"\n' "$(head -1 "$TOKEN_FILE")") "${args[@]}" "$url"
}

TMP=$(mktemp -d "${TMPDIR:-/tmp}/gitea-pr.XXXXXX")
RESP="$TMP/resp.json"
WT=""
cleanup() {
  if [[ -n "$WT" ]]; then git -C "$REPO" worktree remove --force "$WT" >/dev/null 2>&1 || true; fi
  # keep the temp dir when it holds the approval body / build log of a verify run
  [[ -n "${KEEP_TMP:-}" ]] || rm -rf "$TMP"
}
trap cleanup EXIT

get() { # get <url> -> body in $RESP, dies unless 200
  local code; code=$(api GET "$1")
  [[ "$code" == "200" ]] || die "GET $1 -> HTTP $code"
}

pr_info() { # sets HEAD_SHA MASTER_SHA after validating the PR
  local n="$1"
  get "$BASE/pulls/$n"
  local state base; state=$(jq -r .state "$RESP"); base=$(jq -r .base.ref "$RESP"); HEAD_SHA=$(jq -r .head.sha "$RESP")
  [[ "$state" == "open" ]] || die "PR #$n is not open (state=$state)"
  [[ "$base" == "master" ]] || die "PR #$n does not target master (base=$base)"
  [[ "$HEAD_SHA" =~ ^$HEX40$ ]] || die "unexpected head sha"
  get "$BASE/branches/master"
  MASTER_SHA=$(jq -r .commit.id "$RESP")
  [[ "$MASTER_SHA" =~ ^$HEX40$ ]] || die "unexpected master sha"
}

# check_record <body-file-or-text-source> <head> <master>: the first verify line must be a SUCCESS record for exactly these shas
check_record() {
  local text="$1" head="$2" master="$3" line
  line=$(printf '%s\n' "$text" | grep -m1 -E '^verify: head=' || true)
  [[ -n "$line" ]] || die "no 'verify: head=... master=... result=...' line"
  [[ "$line" =~ ^verify:\ head=($HEX40)\ master=($HEX40)\ result=(SUCCESS|FAILURE)$ ]] || die "malformed verify line"
  [[ "${BASH_REMATCH[3]}" == "SUCCESS" ]] || die "verify record says ${BASH_REMATCH[3]}"
  [[ "${BASH_REMATCH[1]}" == "$head" ]] || die "verify record is for another PR head (stale: re-run verify)"
  [[ "${BASH_REMATCH[2]}" == "$master" ]] || die "verify record is for another master (master moved: re-run verify)"
}

cmd_verify() {
  local n="${1:?usage: verify <n>}"
  pr_info "$n"
  KEEP_TMP=1
  git -C "$REPO" fetch -q origin
  local master; master=$(git -C "$REPO" rev-parse origin/master)
  [[ "$master" == "$MASTER_SHA" ]] || die "local origin/master ($master) differs from the API master ($MASTER_SHA)"
  git -C "$REPO" cat-file -e "$HEAD_SHA^{commit}" 2>/dev/null || git -C "$REPO" fetch -q origin "refs/pull/$n/head"
  WT="$TMP/wt"
  git -C "$REPO" worktree add -q --detach "$WT" "$HEAD_SHA"
  local log="$TMP/verify.log" result=SUCCESS jdk="${JAVA_HOME:-/usr/lib/jvm/java-26}"
  if ! git -C "$WT" merge -q --no-edit "$master" >"$TMP/merge.log" 2>&1; then
    echo "gitea-pr: merge of origin/master into the PR head conflicts; resolve it on the branch, then re-run" >&2
    exit 2
  fi
  echo "gitea-pr: running mvn -B verify (log: $log)" >&2
  (cd "$WT" && JAVA_HOME="$jdk" mvn -B verify) >"$log" 2>&1 || result=FAILURE
  # totals: surefire and failsafe both print "Tests run: N, Failures: F, Errors: E, Skipped: S" summaries without a class suffix
  local totals
  totals=$(grep -E '^\[(INFO|WARNING|ERROR)\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$' "$log" \
    | sed -E 's/^\[[A-Z]+\] Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+)$/\1 \2 \3 \4/' \
    | awk '{r+=$1;f+=$2;e+=$3;s+=$4} END {printf "run=%d failures=%d errors=%d skipped=%d", r, f, e, s}')
  local body="$TMP/approval-body-$n.txt"
  {
    echo "verify: head=$HEAD_SHA master=$master result=$result"
    echo "command: mvn -B verify (PR head merged with origin/master in a temporary worktree)"
    echo "jdk: $jdk"
    echo "tests: $totals"
  } >"$body"
  echo "gitea-pr: result=$result $totals" >&2
  echo "$body"
  [[ "$result" == "SUCCESS" ]] || { echo "gitea-pr: build log: $log" >&2; exit 1; }
}

cmd_approve() {
  local n="${1:?usage: approve <n> <body-file> [text]}" file="${2:?body file required}"; shift 2
  [[ -r "$file" ]] || die "body file not readable: $file"
  pr_info "$n"
  local text; text=$(cat "$file")
  check_record "$text" "$HEAD_SHA" "$MASTER_SHA"
  [[ $# -gt 0 ]] && text="$text"$'\n\n'"$*"
  local payload="$TMP/review.json" code
  jq -n --arg body "$text" --arg commit "$HEAD_SHA" '{event:"APPROVED", body:$body, commit_id:$commit}' >"$payload"
  code=$(api POST "$BASE/pulls/$n/reviews" "$payload")
  [[ "$code" == "200" || "$code" == "201" ]] || die "POST review -> HTTP $code"
  echo "gitea-pr: approved PR #$n at head $HEAD_SHA (HTTP $code)"
}

cmd_merge() {
  local n="${1:?usage: merge <n>}"
  pr_info "$n"
  get "$API/user"
  local me; me=$(jq -r .login "$RESP")
  get "$BASE/pulls/$n/reviews"
  local text
  text=$(jq -r --arg me "$me" --arg head "$HEAD_SHA" '
      [.[] | select(.user.login == $me and .state == "APPROVED" and (.dismissed | not) and (.stale | not) and .commit_id == $head)]
      | last | .body // empty' "$RESP")
  [[ -n "$text" ]] || die "no APPROVED, non-dismissed, non-stale review by $me on head $HEAD_SHA"
  check_record "$text" "$HEAD_SHA" "$MASTER_SHA"
  local payload="$TMP/merge.json" code
  jq -n --arg head "$HEAD_SHA" '{Do:"merge", delete_branch_after_merge:true, head_commit_id:$head}' >"$payload"
  code=$(api POST "$BASE/pulls/$n/merge" "$payload")
  [[ "$code" == "200" ]] || die "POST merge -> HTTP $code"
  echo "gitea-pr: merged PR #$n at head $HEAD_SHA (HTTP $code)"
}

case "${1:-}" in
  verify)  shift; cmd_verify "$@" ;;
  approve) shift; cmd_approve "$@" ;;
  merge)   shift; cmd_merge "$@" ;;
  whoami)  get "$API/user"; echo "merge account: $(jq -r .login "$RESP")" ;; # read-only check of the token
  *) echo "usage: $0 verify <n> | approve <n> <body-file> [text] | merge <n> | whoami" >&2; exit 1 ;;
esac
