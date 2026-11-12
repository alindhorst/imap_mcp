#!/usr/bin/env bash
# #462: pre-pulls every digest-pinned image (name:tag@sha256:<64 hex>) of the deployed IT, with retries, to ride
# out sporadic registry rate limits on shared runner IPs. The list is derived at run time from compose.yaml, Dockerfile and
# ItImages.java. A failed pre-pull only warns: the build's own pull decides. No login, no token.
# "--list" prints the images and pulls nothing.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

images="$(grep -ohE '[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}' \
    dist/docker/compose.yaml dist/docker/Dockerfile \
    imap-mcp-it/src/test/java/de/alexanderlindhorst/imapmcp/it/ItImages.java | sort -u)"

if [ "${1:-}" = "--list" ]; then
    echo "$images"
    exit 0
fi

for image in $images; do
    ok=no
    for attempt in 1 2 3; do
        if docker pull --quiet "$image" >/dev/null; then
            ok=yes
            break
        fi
        sleep $((attempt * 10))
    done
    if [ "$ok" = no ]; then
        echo "ci/prepull-images.sh: warning: could not pre-pull $image" >&2
    fi
done
