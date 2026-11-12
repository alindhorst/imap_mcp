# CI: the Gitea `unit` and the GitHub `verify` workflows

This document is an instruction for the owner. SPEC 5.8 (v0.26) splits CI into two workflows with two contexts that must never be confused.

| Context | Where | Files | What it runs | Merge gate |
| --- | --- | --- | --- | --- |
| `unit` | Gitea Actions | `.gitea/workflows/unit.yml`, `ci/unit.sh` | `mvn -B test` | no merge-gate evidence |
| `verify` | GitHub Actions | `.github/workflows/verify.yml`, `ci/verify.sh` | the full `mvn -B verify` | the same build the gate records |

- **`unit`** gives fast feedback on the owner's own (ARM) host. It runs no failsafe tests, no deployed IT and no test-completeness gate, so a green `unit` is no merge-gate evidence and must never be read as one.
- **`verify`** is the same build the merge gate records, run on the PR head merged with the current master (`ci/merge-master.sh`). The head and master SHAs it tested are written to the job summary (`head=<sha> master=<sha|absent> merged=<yes|no> basis=<merged|contains|self|head-only> base=<master|main|none>`). `master=` is exactly the SHA the build was based on: origin's `master`, else origin's `main` (`base=`). `basis=` says how: `merged` (a merge commit was built), `contains` (the base was already an ancestor of the head), `self` (the ref is the base) or `head-only` (no base: the line is written and the job fails with "no origin master or main: cannot build the head merged with master").

Both workflows use no secrets. The JDK and Maven are never taken from the runner: the scripts fetch them and verify them against pinned checksums (see "Pins" below).

## The Gitea `unit` runner (optional, the owner's choice)

`unit` needs a Gitea runner. Without one, the `unit` status stays pending, so it must not be a required check until a runner exists and has produced a green run.

### Why the first workflow never ran

The first Gitea workflow (#438) asked for a runner label `imap-mcp-host`, which the owner's runner does not have, so it never ran: Gitea showed the jobs as Waiting with "No matching online runner with label: imap-mcp-host", and not one task was ever picked up. It also assumed host mode and an x64 JDK. That design is gone. The `unit` job runs in container mode on the runner's default label.

### How the job runs

- **Label and image:** `runs-on: ubuntu-latest`, the act_runner default label. The job container image is pinned in the workflow by a multi-arch index digest (`linux/arm64` and `linux/amd64`), so the same workflow runs on an ARM and on an x86_64 host.
- **Root and the privilege drop:** jobs run as root in the job container. Several file-permission tests behave differently as root, so `ci/unit.sh` refuses uid 0 unless the workflow sets `IMAP_MCP_CI_DROP_ROOT=1`. With the flag it creates the unprivileged user `ci` (home `/home/ci`), hands the checkout to it and re-executes itself as `ci` without the flag, so it cannot loop. As a non-root user it runs directly.
- **No Docker:** no unit test needs Docker, and the job uses no secret. Testcontainers is used only by the failsafe tests of `imap-mcp-it`, which `unit` does not run.
- **JDK and Maven:** `ci/install-jdk.sh` picks the Temurin tarball by `uname -m` (x86_64 or aarch64, each with its own pinned sha256); `ci/install-maven.sh` installs Maven 3.9.x. Both land under `/home/ci`, which the workflow caches with a key that includes the runner architecture.
- **Runtime:** unit-only, measured on x86_64: 728 s (about 12 min) with system Maven on a 16-core workstation, and 13 min 05 s in the pinned job image under Podman. On the owner's ARM runner `mvn -B test` takes about 24 to 27 min (27:12 in run 32); the deadline and timing tests are the most likely to flake on a slow host. The job has `timeout-minutes: 40`.
- **Reports:** the unit job uploads no artifact. A failure is read from the job log, where Maven prints the failing tests; locally the reports stay in `*/target/surefire-reports`. Why: `actions/upload-artifact` v4 fails on Gitea with `GHESNotSupportedError` (the client treats every server host other than github.com as GHES), although Gitea has had a v4 artifact backend since 1.22. The GitHub `verify` workflow still uploads its reports.

### Prerequisites

- **Runner:** a containerised or native `act_runner` with the default labels, x86_64 or aarch64 (check with `uname -m`). Everything the job needs is in the pinned job image: Node.js (the `actions/*` steps are JavaScript), bash, git, curl, tar, sha256sum, sha512sum, openssl, useradd and setpriv.
- **Network:** outbound access to github.com (the actions and the JDK), archive.apache.org (Maven), Maven Central and Docker Hub (the job image).
- **Repository:** Actions must be enabled in the repository Settings.

### Runner `config.yaml`

Set these in the runner's `config.yaml` and restart the runner afterwards:

- `container.network`: the real Docker name of the network of the Gitea compose project. docker compose prefixes the network key with the project name (for example `gitea-ci_cd_gitea_net`); find the name with `docker network ls`. A wrong name makes the job fail with "network ... not found". Without the option, job containers cannot resolve the Gitea instance URL, and checkout fails.
- `container.docker_host: "-"`: the runner keeps using the daemon, but job containers no longer get the Docker socket mounted. With the default every job container receives `/var/run/docker.sock`, and a mounted `docker.sock` is root-equivalent control of the host. The unit job needs no Docker.
- `container.privileged: false` and `container.valid_volumes: []`: no privileged job containers, no host volumes.
- `cache.host`: only if the cache step warns that it cannot reach the cache server; set it to an address reachable from the job network. Without it the job just runs without the cache.

### The registration token

The registration token (Settings, Actions, Runners, "Create new runner" at repository, user or site level) belongs neither in this repository nor in a compose file under version control. Put it in an environment file with mode 0600 that the runner container reads, or paste it at the prompt of the interactive `act_runner register`; never on the command line, where every local user sees it in the process list and the shell history keeps it. If a token was ever exposed (committed, pasted into a ticket or a shared file), revoke or rotate it.

### Labels and the "Waiting" state

- **Where to see the labels:** repository, user and site-admin Settings, Actions, Runners list each runner with its status (Idle, Active or Offline), its labels and its scope. In `config.yaml` they are `runner.labels`; an empty list means the defaults (`ubuntu-latest` and similar, each mapped to a Docker image).
- **Changing them:** edit `runner.labels` in `config.yaml` and restart the runner, which declares its labels at start-up; or re-register with the interactive `act_runner register`.
- **Troubleshooting:** a job that stays Waiting with "No matching online runner with label: ..." means a label or scope mismatch, or an offline runner. Compare the `runs-on` value of the workflow with the labels in the Runners list, and check that the runner's scope covers this repository. A runner with only custom labels never picks up a job that asks for `ubuntu-latest`.

### Try it, then require it

1. Push a branch or open a pull request and check that the `unit` run starts and ends green. Note the wall time.
2. Only after a green run, make `unit` a required status check: Gitea repository Settings, Branches, the rule for `master`, "Enable status check", and select the context shown for the job `unit`.
3. Cancel stale Waiting runs of the old workflow on the Actions page; new pushes no longer create them.

## The GitHub `verify` workflow

`.github/workflows/verify.yml` runs `ci/merge-master.sh`, `ci/prepull-images.sh` and then `ci/verify.sh`, the full `mvn -B verify` with the compose-based integration tests and the test-completeness gate.

- **Runner:** `ubuntu-24.04`, GitHub-hosted, x64, with native Docker; on a public repository 4 vCPU and 16 GB. It has everything installed; nothing needs to be installed by hand.
- **Permissions and secrets:** `permissions: contents: read`, no secrets.
- **Triggers:** `push` and `workflow_dispatch` only. There is no `pull_request` or `pull_request_target` trigger, so a fork PR runs nothing. PRs live on Gitea; a PR branch reaches GitHub by a push (see the next section).
- **Artifacts:** the surefire/failsafe reports and, on failure, the compose logs of the deployed IT are uploaded for 7 days. On a public repository they are public; they hold only throwaway test credentials.
- **Runtime:** measured in run 43304462127 (2026-11-12, cold Maven cache): job wall time 33 min 25 s, of which `ci/verify.sh` 30 min 06 s, "Free disk space" 2 min 29 s and `ci/prepull-images.sh` 36 s. A warm-cache run is not measured yet. The sums of the test-class times are about 29 min (1733.5 s), the largest shares in `imap-mcp-it` failsafe (774.8 s default execution, 268.7 s `deployed-it`) and `imap-mcp-core` (383.6 s). The job keeps `timeout-minutes: 120`.
- **Private repository:** it would get 2 vCPU and 7 GB, roughly twice the time, and the minutes count against the plan quota. Not recommended without a larger runner.

## How commits reach GitHub (owner decision)

GitHub only runs `verify` for commits it has. Three options, each with its consequence for the merge gate:

**(a) A Gitea push mirror over SSH with "sync on commit" (recommended).** Steps:

1. Change the GitHub default branch to `master`, because the mirror prunes `main`.
2. Create the push mirror in the Gitea repository settings, and add the public key Gitea generates as a GitHub deploy key with write access.
3. Check the log of the first sync for rejected refs.

Consequence: every Gitea branch becomes public at once, including unreviewed ticket branches. A secret committed by mistake must be rotated, because GitHub keeps orphaned commits. Check the commit author e-mail addresses in the history too: they become public with every push.

**(b) The owner pushes the PR branch to GitHub by hand before the gate.** No automatic publication, one manual step per PR.

**(c) Master only, after the merge.** `verify` then runs after the fact: a post-merge signal with no effect on the gate.

## Using the result

Gitea branch protection cannot see GitHub checks. The `verify` result is therefore enforced, if at all, by the owner's local merge script, not by Gitea. A check there should do the following:

- query `GET https://api.github.com/repos/alindhorst/imap_mcp/commits/<sha>/check-runs` (unauthenticated, 60 requests per hour) for the PR head;
- require a check-run named `verify` with `conclusion: success`;
- require that it comes from the workflow at `.github/workflows/verify.yml`, on the event `push`, in that repository and not in a fork;
- require that `head=` and `master=` of its job summary match the PR head and the current master. The script cannot know Gitea's master: `base=main` is only a stand-in (GitHub's default branch), and this `master=` comparison is what guards it. With option (b) the owner keeps GitHub's master or main current, since the base is whatever GitHub has; once the default branch is `master` (option a) the `main` fallback is unused.

Gitea branch protection may additionally require `unit` once a Gitea runner exists (see above). `unit` alone never replaces `verify`.

## Pins and how to update them

- **Actions** are pinned by commit SHA with the tag in a comment: checkout and cache in both workflows, upload-artifact only in `verify`. To update one, look up the tag's commit with `git ls-remote --tags <repo-url> <tag>` and replace the SHA and the comment.
- **JDK:** version and sha256 per architecture in `ci/install-jdk.sh`; the source is the checksum file of the Adoptium release named in the script.
- **Maven:** version and sha512 in `ci/install-maven.sh`; the source is the `.sha512` file next to the binary on archive.apache.org.
- **Job image of `unit`:** the multi-arch index digest in `.gitea/workflows/unit.yml`. Update it by looking up the digest of the new tag and checking that the index has `linux/arm64` and `linux/amd64`.
- **Runner image of `verify`:** `ubuntu-24.04` in `.github/workflows/verify.yml`. GitHub retires old images; move to the next LTS image deliberately and re-measure.
