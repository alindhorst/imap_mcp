# CI: the `verify` workflow

`.gitea/workflows/verify.yml` runs the same `mvn -B verify` that the merge gate records (SPEC 5.8), with no partial build, through the one entry point `ci/verify.sh`. This document is an instruction for the owner. At the time of writing there is no runner, and `verify` is not yet a required check or part of the merge gate; both are owner decisions (steps below).

What the job does:

- triggers on `pull_request` to `master` and on `push` to `master`; one run per ref (`concurrency`), `timeout-minutes: 60`;
- runs on the runner label `imap-mcp-host`;
- `ci/verify.sh` refuses to run as uid 0, installs the exact Temurin JDK 26.0.2.1+1 (`ci/install-jdk.sh`, tarball verified against a pinned sha256) under `~/.cache/imap-mcp-ci`, asserts `java -version`, then runs `mvn -B verify`. Surefire/failsafe reports go to the artifact `verify-reports`; on failure also the compose logs of the deployed IT (`imap-mcp-it/target/it-compose/*/logs`);
- the Maven repository cache is keyed on the hash of all `pom.xml`;
- no secrets are used or needed. Every action is pinned by commit SHA (checkout v4.3.1, cache v4.3.0, upload-artifact v4.6.2); update them by looking up the tag's commit with `git ls-remote --tags <repo-url> <tag>` and replace the SHA and the comment.

## Runner prerequisites

- A dedicated VM (not a workstation with other duties); Linux x86_64, about 6 GB RAM free for a build with the compose-based integration tests, and disk for the container images (plan 30 GB) plus the Maven cache.
- A **non-root** user (for example `ci-runner`) that runs the runner. `ci/verify.sh` fails for uid 0 on purpose: the test-completeness gate would otherwise see the root-only skip of `StateDirectoryTest` as a normal case.
- Docker for that user, either rootless Docker or membership in the `docker` group (the latter is root-equivalent on that VM, which is why the VM is dedicated), with the `docker compose` plugin installed in a pinned version (tested here: Docker Compose 5.1.2 with Docker 29.4.2; record the version you install).
- Maven 3.9.6 or newer, `git`, `curl`, `tar`, `sha256sum` and Node.js (the actions are JavaScript) on the PATH of the runner user. The JDK is not installed by hand; the job fetches and verifies it.
- Outgoing access to the Gitea host, github.com (JDK download), Maven Central and the container registries the compose files pull from.

## Runner mode

Use `act_runner` in **host mode**: the label maps to `host`, so jobs run directly on the VM. Not container mode (compose bind mounts are resolved on the Docker daemon host, so a job-container workspace breaks the deployed IT) and not Docker-in-Docker (privileged, no image cache). Testcontainers needs no `TESTCONTAINERS_HOST_OVERRIDE` in host mode, and Ryuk stays on. Set `capacity: 1` in the runner config: the integration tests are heavy and share the Docker daemon.

## Registration

The owner creates a registration token in Gitea (repository, organisation or instance Settings, Actions, Runners, "Create new runner"). The token is given to the runner once and is never committed, written into a file of this repository or put into an issue.

```
# as the non-root runner user, on the VM
act_runner generate-config > config.yaml          # set runner.capacity: 1
act_runner register                               # prompts, see below
act_runner daemon --config config.yaml            # run it as a systemd user service in practice
```

`act_runner register` asks interactively for the instance URL (`http://spacedock:3000`), the registration token (paste it at the prompt), the runner name (for example `imap-mcp-ci-1`) and the labels (`imap-mcp-host:host`). Use the interactive form: a token passed as a command-line argument is visible to every local user in the process list and ends up in the shell history. The runner label must equal `runs-on` in the workflow: `imap-mcp-host`.

## Try it before relying on it

Open a pull request (or push to `master`) and check that the `verify` run starts and finishes green. Until a runner with the label exists, the run stays queued. Locally, `ci/verify.sh` can be run by a non-root user with Docker; `act_runner exec -W .gitea/workflows/verify.yml` runs the workflow on a workstation that has `act_runner` installed.

## Making `verify` a required check (owner decision)

Once at least one successful run exists, the owner may require it: in Gitea, repository Settings, Branches, the rule for `master`, enable "Enable status check" and select the context of the job, `verify` (shown as `verify / verify (pull_request)` in the list, taken from the workflow and job name). Adding it to the owner's local merge gate is a separate decision. Until then a green run is evidence, not enforcement.
