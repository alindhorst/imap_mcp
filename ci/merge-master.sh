#!/usr/bin/env bash
# #462/#475: makes a CI run equivalent to the merge gate's "PR head merged with current master" (SPEC 5.8). The
# base is origin's master, else origin's main (GitHub's default branch, the owner's copy of master). Unless the
# checked-out ref is the base itself, the base is merged into the head when it is not already an ancestor; a
# conflict fails the run. Writes "head=<sha> master=<sha|absent> merged=<yes|no> basis=<merged|contains|self|
# head-only> base=<master|main|none>" to the step summary and to stderr; master= is the SHA the build is based
# on. Without any base the line is written and the run fails. Never pushes; the commit identity is throwaway.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

branch="$(git symbolic-ref --short -q HEAD || true)"
branch="${branch:-${GITHUB_REF_NAME:-}}"
head="$(git rev-parse HEAD)"
master="absent"
merged="no"
basis="head-only"
base="none"
failure=""

if [ "$branch" = "master" ]; then
    master="$head"; basis="self"; base="master"
else
    if git ls-remote --exit-code --heads origin refs/heads/master >/dev/null 2>&1; then
        base="master"
    elif git ls-remote --exit-code --heads origin refs/heads/main >/dev/null 2>&1; then
        base="main"
    fi
    if [ "$base" = "main" ] && [ "$branch" = "main" ]; then
        master="$head"; basis="self"
    elif [ "$base" != "none" ]; then
        git fetch --quiet origin "refs/heads/$base"
        master="$(git rev-parse FETCH_HEAD)"
        if git merge-base --is-ancestor "$master" HEAD; then
            basis="contains"
        else
            git -c user.name="ci-merge" -c user.email="ci-merge@invalid" \
                merge --no-ff --no-edit "$master" >&2
            merged="yes"
            basis="merged"
        fi
    else
        failure="no origin master or main: cannot build the head merged with master"
    fi
fi

line="head=$head master=$master merged=$merged basis=$basis base=$base"
echo "$line" >&2
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "$line" >>"$GITHUB_STEP_SUMMARY"
fi
if [ -n "$failure" ]; then
    echo "$failure" >&2
    exit 1
fi
