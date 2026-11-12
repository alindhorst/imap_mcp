#!/usr/bin/env bash
# #462: makes a CI run equivalent to the merge gate's "PR head merged with current master" (SPEC 5.8). On any ref
# other than master/main, fetches origin's master (when it exists) and merges it into the checked-out head. A
# conflict fails the run. Writes "head=<sha> master=<sha|absent> merged=<yes|no>" to the step summary and to
# stderr. Never pushes; the commit identity is a throwaway one.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

branch="$(git symbolic-ref --short -q HEAD || true)"
branch="${branch:-${GITHUB_REF_NAME:-}}"
head="$(git rev-parse HEAD)"
master="absent"
merged="no"

if [ "$branch" != "master" ] && [ "$branch" != "main" ]; then
    if git ls-remote --exit-code --heads origin refs/heads/master >/dev/null 2>&1; then
        git fetch --quiet origin refs/heads/master
        master="$(git rev-parse FETCH_HEAD)"
        if ! git merge-base --is-ancestor "$master" HEAD; then
            git -c user.name="ci-merge" -c user.email="ci-merge@invalid" \
                merge --no-ff --no-edit "$master" >&2
            merged="yes"
        fi
    fi
fi

line="head=$head master=$master merged=$merged"
echo "$line" >&2
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "$line" >>"$GITHUB_STEP_SUMMARY"
fi
