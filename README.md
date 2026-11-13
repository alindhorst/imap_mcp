# IMAP MCP

[![verify](https://github.com/alindhorst/imap_mcp/actions/workflows/verify.yml/badge.svg?branch=main)](https://github.com/alindhorst/imap_mcp/actions/workflows/verify.yml)\
[![unit](http://spacedock:3000/lindhrst/imap_mcp/actions/workflows/unit.yml/badge.svg?branch=master)](http://spacedock:3000/lindhrst/imap_mcp/actions?workflow=unit.yml)

An [MCP](https://modelcontextprotocol.io) server that lets an LLM client search, read and organize mail in IMAP mailboxes. Changes are reversible, except the gated permanent deletion from Trash that the user confirms each time. Java 25, official MCP Java SDK, Jakarta Mail (Angus Mail). IMAP only; sending mail is out of scope for now.

> **Status:** phase v3a: read-only tools, reversible write tools (enabled by default, turned off per account by `readOnly`), `download_attachment` (needs `tools.download.dir`) and `purge_messages` (needs `allowPurge`; `stdio`, and `http-public` with `purge.enabled`), with `stdio` and `http-local`. `http-public` (v3b) is available as described below, including the linking surface at `/accounts`; see "Scope by phase".

## Tools

Twelve tools; the five v2 tools are write tools and are not listed when every account is read-only. `purge_messages` and `download_attachment` are registered only when configured (see their sections).

- `list_accounts`: lists the configured accounts with label, provider, status and `read_only`; read-only.
- `list_folders`: lists the folders of one account with role and attributes; read-only.
- `search_messages`: searches one folder and returns a page of message summaries with handles (`\Deleted` messages are not filtered out); read-only.
- `get_message`: reads one message (envelope, a window of the text body, attachment list); read-only.
- `list_attachments`: lists the attachments of one message (metadata only); read-only.
- `set_flags`: adds or removes flags and keywords on up to 100 messages; write; refused on `readOnly` accounts.
- `move_messages`: moves up to 100 messages into one folder and returns the new handles; write; refused on `readOnly` accounts.
- `copy_messages`: copies up to 100 messages into one folder and returns the new handles; write; refused on `readOnly` accounts.
- `delete_messages`: up to 100 messages; moves to Trash, not a permanent delete; write; refused on `readOnly` accounts.
- `create_folder`: creates a folder (an existing one is success); write; refused on `readOnly` accounts.
- `purge_messages`: permanently deletes up to 100 messages from the Trash folder, Trash only, and cannot be undone; only for accounts with `allowPurge`, in `stdio` (and in `http-public` when the operator enables `purge.enabled`, see below), and the user confirms each call (or allows it for the session).
- `download_attachment`: saves one attachment as a new file in `tools.download.dir`, never overwriting; registered only when `tools.download.dir` is set; allowed on `readOnly` accounts (it does not change the mailbox).

## Documentation

- [`doc/SPEC.md`](doc/SPEC.md): scope, tools, phasing, architecture. Start here.
- [`doc/TUTORIAL.md`](doc/TUTORIAL.md): getting started (build, config, keyring, stdio and HTTP setup, pitfalls).
- [`doc/DEPLOYMENT.md`](doc/DEPLOYMENT.md): operator guide for the http-public test deployment (what to provide, checks, open assumptions).

## Project layout

```
imap-mcp-core             tools, IMAP access, pooling, handles, tenancy seam (no transport deps)
imap-mcp-store            credential/account storage (file; JDBC later)
imap-mcp-transport-stdio  stdio transport
imap-mcp-transport-http   Streamable HTTP via embedded Jetty
imap-mcp-app              entry points, config, wiring, shaded jar, Docker image
imap-mcp-it               end-to-end / container-based tests
imap-mcp-coverage         build only: aggregated JaCoCo report (no sources)
doc/                      specification
```

## Conventions

- Maven multi-module, group `de.alexanderlindhorst.imapmcp` (final, see SPEC section 5.2).
- Dependency versions are pinned in the parent POM.
- Logging goes to a file via Logback. Nothing but protocol messages may reach stdout in stdio mode.
- Unit tests live in their module; slow/container tests only in `imap-mcp-it`.
- Never log mail content or credentials.

## Build

Requirements: JDK 25 or newer (bytecode target 25; the project is built and tested with JDK 26, e.g. `JAVA_HOME=/usr/lib/jvm/java-26`), Maven 3.9.6+, Docker (integration tests and the image build only).

```
mvn verify                          # build and test everything
mvn -pl imap-mcp-core -am test      # one module and what it depends on
mvn -B -Pdocker -pl imap-mcp-app -am package -DskipTests   # also builds the Docker image imap-mcp
```

Skipping tests: `-DskipTests` skips only the unit tests (Surefire); the Failsafe integration tests, such as `ShadedJarIT` in `imap-mcp-app`, still run in `verify`. `-DskipITs` skips only the integration tests. `mvn package` never reaches the integration-test phase, so `package -DskipTests` is enough there.

Deployed tests: the classes `*DeployedIT` in `imap-mcp-it` start the `dist/docker` stack from a copy (Docker or Podman needed) and exercise `http-public` through Caddy, Keycloak, Postgres and Dovecot. They run in their own failsafe execution `deployed-it`, in the default `mvn verify`; the execution `default` excludes them. To run only them, call that execution directly (the first goal builds the modules and hands the shaded jar to the tests; `-Dit.test` is needed only so that the failsafe runs in the other modules find nothing to do):

```
mvn -B -pl imap-mcp-it -am pre-integration-test failsafe:integration-test@deployed-it failsafe:verify@deployed-it -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='*DeployedIT' -Dfailsafe.failIfNoSpecifiedTests=false -Dimapmcp.partialBuild=true
```

Do not change files under `dist/docker` while it runs: one test checks that the source tree stays untouched. `-Dit.test` replaces the include and exclude patterns of every execution, so with a plain `mvn verify -Dit.test=...` the classes would run in both executions (twice, with two stacks); the command above avoids that. `-Dimapmcp.partialBuild=true` is needed because the run is partial (see the gate below). Evidence of a run is the failsafe summary `imap-mcp-it/target/failsafe-reports/failsafe-summary-deployed.xml`.

Test-completeness gate: every build that reaches `verify` ends with a check in `imap-mcp-coverage` (SPEC 5.8). It fails when a test is skipped or aborted outside the allowlist `imap-mcp-coverage/src/main/resources/allowed-skips.txt`, when an `*IT` class of `imap-mcp-it` has no report with tests, and when `-DskipTests`, `-DskipITs` or `-Dmaven.test.skip` is set. `-Dimapmcp.partialBuild=true` admits these flags and turns both report checks off, for example `mvn verify -DskipITs -Dimapmcp.partialBuild=true`; the profile `smoke` sets it. A partial build is no evidence for the merge gate.

CI: a fast unit-only workflow on Gitea and the full `verify` workflow on GitHub Actions, with the owner steps for runners, mirroring and the merge gate, are described in `doc/CI.md`. A green unit-only run is no merge-gate evidence.

`imap-mcp-app` produces the runnable shaded jar `imap-mcp-app/target/imap-mcp-app-<version>-all.jar`; the thin jar stays the main artifact.

## Release

Master is protected, so every step that changes it goes through a PR and the gate `.claude/gitea-pr.sh`.
1. Set the release version with `mvn -B versions:set -DnewVersion=<version> -DgenerateBackupPoms=false` (plugin version pinned on the command line) in a PR.
2. Run `mvn -B clean verify` on the merge commit; only if it is green, put the annotated tag `v<version>` on exactly that commit.
3. Attach `imap-mcp-app/target/imap-mcp-app-<version>-all.jar` and its SHA-256 checksum file (`.sha256`) to the Gitea release for the tag.
4. Set the next development (snapshot) version the same way, via PR.

## Running

The same jar runs in two modes in v2 (see SPEC section 2):

| Mode | Use | How clients connect |
|---|---|---|
| `stdio` (dev, single user) | Claude Code / Desktop spawn the process | MCP client config with `java -jar ... --mode stdio` |
| `http-local` (single user, container) | Long-running local server, warm JVM | `http://127.0.0.1:<port>/mcp` with a bearer token |
| `http-public` (production, multi-tenant) | Behind a TLS reverse proxy; accounts, credentials and tenants in Postgres | OAuth 2.1 resource server (JWT access tokens) |

The mode comes from `--mode stdio|http-local|http-public` (fallback env `IMAP_MCP_MODE`). Without a mode the jar prints a usage text to stderr and exits with 2.

#### Tenants and mailboxes: which mode do I need?

Short version: **`stdio` and `http-local` serve exactly one user (one fixed tenant, `local`); only `http-public` is multi-tenant.** Tenants never share a mailbox: in `http-public` every user is a tenant with their own, separately linked IMAP accounts.

| | `stdio` | `http-local` | `http-public` |
|---|---|---|---|
| Users | one (you) | one (you) | many, each a tenant |
| Who is the tenant | fixed tenant `local` | fixed tenant `local` | the user in the OAuth token (issuer + subject), created at the first login at `/accounts` |
| Where mailboxes are defined | `accounts:` in the YAML config | `accounts:` in the YAML config | not in the config (a configured `accounts` list fails startup); each user links their own at `<public.baseUrl>/accounts` |
| Which mailboxes a client sees | all accounts of the config | all accounts of the config | only the accounts of the token's own tenant |
| Passwords | secret references (`env:`, `file:`, `keyring:`) | secret references (`file:`, `env:`; no keyring in Docker) | entered by the user in the linking page, stored encrypted in Postgres |
| Client authentication | none (the client spawns the process) | static bearer token | OAuth 2.1 access token (JWT) from an identity provider such as Keycloak |
| Needs | the jar | the jar, optionally Docker | the jar, Postgres, an identity provider, a TLS reverse proxy, a key-wrapping key |
| Setup guide | [TUTORIAL 4](doc/TUTORIAL.md#4-stdio-mode-claude-code-claude-desktop) | [TUTORIAL 5](doc/TUTORIAL.md#5-http-mode-http-local) | [TUTORIAL 6](doc/TUTORIAL.md#6-public-multi-tenant-mode-http-public), then [DEPLOYMENT](doc/DEPLOYMENT.md) |

Within one tenant a user may have several accounts (several mailboxes, at most 10 by default in `http-public`); every tool call names the account it addresses. Two tenants can link the same IMAP login without noticing each other: their accounts, handles and pooled connections are separate.

### Configuration

Accounts and options live in a YAML config file. Unknown keys are rejected. Passwords are never literals: use a secret reference, `env:NAME`, `file:/absolute/path` or `keyring:<service>/<account>` (literal passwords are rejected).

```yaml
accounts:
  - id: work
    host: imap.example.com
    username: work@example.com
    password: env:WORK_IMAP_PASSWORD
```

Optional per-account keys: `label`, `address`, `port` (default 993, or 143 with `starttls`), `security` (`implicit-tls` default, or `starttls`), `flavor` (`auto` default, `generic`, `gmail`), `readOnly` (default `false`), `allowPurge` (default `false`, see "Permanent deletion"). Every other option is optional; keys not listed below are rejected as unknown.

`readOnly: true` makes the write tools refuse that account (error code `PERMISSION_DENIED` with `details.reason` `account_read_only`); `list_accounts` reports it as `read_only`. When every account is `readOnly: true`, the write tools are not listed at all. Example with one writable and one read-only account:

```yaml
accounts:
  - id: work
    host: imap.example.com
    username: work@example.com
    password: env:WORK_IMAP_PASSWORD
  - id: archive
    host: imap.example.com
    username: archive@example.com
    password: env:ARCHIVE_IMAP_PASSWORD
    readOnly: true
```

Deadline guard: while at least one account is not read-only, `tools.deadline` must be at least 30 s, otherwise the server refuses to start. If every account is read-only, the 30 s minimum does not apply; the normal bounds of `tools.deadline` still do. The bulk cap of the write tools (100 handles per call, 10 folders) is fixed and not configurable.

Options and defaults (durations: a number plus `ms`, `s`, `m` or `h`, e.g. `90s`; sizes: a number plus `B`, `KiB`, `MiB` or `GiB`):

| Key | Default | Meaning |
|---|---|---|
| `imap.connectTimeout` | `10s` | IMAP connect timeout |
| `imap.readTimeout` | `60s` | IMAP read timeout |
| `imap.writeTimeout` | `60s` | IMAP write timeout |
| `imap.trace` | `false` | protocol trace, see "IMAP protocol trace" |
| `pool.maxPerMailbox` | `3` | connections per account (1 to 1000) |
| `pool.maxPerTenant` | `8` | connections per tenant (1 to 10000) |
| `pool.maxTotal` | `64` | connections overall (1 to 100000) |
| `pool.idleEviction` | `5m` | close connections idle longer than this |
| `pool.validateAfterIdle` | `60s` | NOOP check before reuse when idle longer |
| `pool.maxLifetime` | `30m` | maximum connection age |
| `pool.acquireTimeout` | `10s` | time to get a ready connection: pool wait plus connect, TLS and LOGIN |
| `pool.capabilityTtl` | `10m` | cache time for server capabilities |
| `tools.deadline` | `90s` | time limit per tool call (at most 10m; at least 30s while a write tool is registered, at least 60s while `purge_messages` is registered) |
| `tools.maxCharsCap` | `100000` | upper bound for `max_chars` (configurable down only) |
| `tools.maxBodyPartBytes` | `4MiB` | per-part source limit when reading bodies |
| `tools.maxFolders` | `100` | folders listed at most |
| `tools.sinceMaxCandidates` | `10000` | search hits examined for a `since` filter |
| `secrets.keyringTimeout` | `5s` | timeout of the keyring CLI call |
| `http.bind` | `127.0.0.1` | `http-local` bind address |
| `http.port` | `8080` | `http-local` port |
| `http.allowNonLoopbackBind` | `false` | required `true` for a non-loopback bind |
| `http.token` | none | bearer token as a secret reference; generated into the state directory if unset (at least 32 characters) |
| `http.allowedHosts` | `["localhost:*", "127.0.0.1:*", "[::1]:*"]` | accepted `Host` header values |
| `http.allowedOrigins` | `[]` | accepted `Origin` header values |
| `http.maxRequestBytes` | `1MiB` | request body limit |
| `http.stateful` | `false` | `true` serves the stateful Streamable HTTP transport and registers `purge_messages` in `http-local` (see "Permanent deletion") |
| `security.handleKey` | none | handle signing key as a secret reference (base64url, 32 bytes); generated into the state directory if unset |
| `security.handleKeys` | `[]` | key ring: list of `{id, key}` (`id` 0 to 255, `key` a secret reference, base64url of 32 bytes); all ids verify handles and cursors |
| `security.signingKeyId` | `1` | id that signs new handles and cursors; must be in the ring |

Key rotation (SPEC 5.4, 5.7), one restart per step: add a key under a new id in `security.handleKeys`, then set `security.signingKeyId` to it, later remove the old id (its handles and cursors then fail as `INVALID_HANDLE` / `INVALID_CURSOR`). Id 1 is `security.handleKey`, else the `handleKeys` entry with id 1, else the generated `handle-key` state file (created only while id 1 signs, otherwise only read), so existing handles keep verifying after keys are added. Switching the signing id gives every message a new handle and changes the hashed ids in logs. With several replicas, each step must reach every replica before the next starts.

Locations (SPEC 5.7):

| What | Default | Overrides |
|---|---|---|
| Config file | `~/.config/imap-mcp/config.yaml` (or `$XDG_CONFIG_HOME/imap-mcp/config.yaml`) | `--config <file>`, then `IMAP_MCP_CONFIG` |
| State directory (bearer token, handle key) | `~/.local/state/imap-mcp/` (or `$XDG_STATE_HOME/imap-mcp`) | `IMAP_MCP_STATE_DIR` |
| Logs | `<state>/logs/imap-mcp.log` | `IMAP_MCP_LOG_DIR` |

Precedence for the state directory: `IMAP_MCP_STATE_DIR`, then `$XDG_STATE_HOME/imap-mcp` (only when absolute), then `~/.local/state/imap-mcp`. The Docker image sets `/config/config.yaml`, `/config/state` and `/logs` through these variables.

Environment variables (the only ones read; they override file values where applicable):

| Variable | Effect |
|---|---|
| `IMAP_MCP_MODE` | `stdio`, `http-local` or `http-public` (if `--mode` is absent) |
| `IMAP_MCP_CONFIG`, `IMAP_MCP_STATE_DIR`, `IMAP_MCP_LOG_DIR` | paths, see above |
| `XDG_CONFIG_HOME`, `XDG_STATE_HOME` | base directories, used only when absolute |
| `IMAP_MCP_LOG_LEVEL` | root log level: `TRACE`, `DEBUG`, `INFO` (default), `WARN`, `ERROR` |
| `IMAP_MCP_HTTP_BIND`, `IMAP_MCP_HTTP_PORT` | `http-local` bind address (default `127.0.0.1`) and port (default 8080) |
| `IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND` | `true` permits a non-loopback bind (set in the Docker image only) |

Secrets referenced as `env:NAME` use whatever variable names you choose.

Logs go to a rotating file only, never to the console. Message bodies, subjects, addresses and credentials are not logged (except in the opt-in IMAP protocol trace below; credentials stay redacted there too).

### stdio

Configure the MCP client to launch the jar:

```
java -Xlog:disable -Xlog:all=warning:stderr -jar imap-mcp-app-<version>-all.jar --mode stdio
```

The two `-Xlog` flags keep JVM logging off stdout, which carries the protocol in this mode. Claude Code:

```
claude mcp add imap-mcp -- java -Xlog:disable -Xlog:all=warning:stderr -jar /path/to/imap-mcp-app-<version>-all.jar --mode stdio
```

Claude Desktop (`claude_desktop_config.json`); the `env` block passes secrets referenced as `env:NAME`:

```json
{
  "mcpServers": {
    "imap-mcp": {
      "command": "java",
      "args": ["-Xlog:disable", "-Xlog:all=warning:stderr", "-jar", "/path/to/imap-mcp-app-<version>-all.jar", "--mode", "stdio"],
      "env": { "WORK_IMAP_PASSWORD": "<app password>" }
    }
  }
}
```

If the config file is not in the default place, add `--config /path/to/config.yaml` to the arguments.

### http-local (Docker)

```
docker run -d --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -v imap-mcp-config:/config -v imap-mcp-logs:/logs \
  imap-mcp
```

Build the image first with `-Pdocker` (see Build). Put `config.yaml` into the `/config` volume. Inside the container the server binds `0.0.0.0` (the image sets `IMAP_MCP_HTTP_BIND` and `IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND`); always publish the port on host loopback only, as above. The server validates `Origin` and `Host` and requires a bearer token. It generates the token on first start (unless `http.token` is configured, as a secret reference); read it with:

```
docker exec <container> cat /config/state/http-token
```

Add it to Claude Code:

```
claude mcp add --transport http imap-mcp http://127.0.0.1:8080/mcp --header "Authorization: Bearer <token>"
```

The image runs as uid 10001, contains no credentials or config, and has a health check (`/health`). Without Docker the same mode runs with `java -jar ... --mode http-local`; the token is then in `<state>/http-token`.

Keyring references do not work in the container (no D-Bus, no `secret-tool`). Use `file:` (Docker secrets or mounted files) or `env:`:

```yaml
accounts:
  - id: personal
    host: imap.example.com
    username: me@example.com
    password: file:/run/secrets/imap_password
```

**Passing `env:` secrets to the container.** `env:NAME` reads the variable from the server process inside the container, so it must be given to `docker run`; a variable in your shell is not enough. A missing one fails startup with `environment variable NAME is not set or empty`. Pitfalls:

- `-e NAME` (without a value) copies the variable from the environment of the `docker` command. It must be exported (`export NAME=...`; `set | grep NAME` also lists unexported variables, `env | grep NAME` does not).
- `sudo docker run -e NAME` loses the variable, even when you are already root: `sudo` resets the environment by default (`env_reset`). Run `docker` without `sudo` (as root, or as a member of the `docker` group), or pass the value explicitly (`-e NAME="$NAME"`, which exposes it in the process list), or use `--env-file`.
- Preferred on a server: `--env-file /path/to/env` with a root-owned file of mode 0600 (`NAME=value` lines, everything after the first `=` is literal), kept outside the directory mounted as `/config`; or a `file:` reference to a file inside the mounted config directory (owned by uid 10001, mode 0400).
- Check what the container received: `docker inspect -f '{{.Config.Env}}' <container>` (this shows the values too; the secrets are also visible to anyone who can run `docker inspect`).
- With `--restart unless-stopped` a container that fails at startup restarts in a loop; `docker rm -f` it before the next attempt.

**Host directories: ownership and SELinux.** The container user is uid 10001, so the mounted directories must let it in:

- `/config` is read for `config.yaml`; the server also creates `/config/state` (bearer token, handle key). Create `state` yourself and hand it over: `mkdir -p <config>/state && chown 10001 <config>/state && chmod 700 <config>/state`. Otherwise startup fails with `Cannot read or create the bearer token at /config/state/http-token; make the state directory writable or configure http.token`. `config.yaml` itself can stay root-owned, as long as uid 10001 can read it (mode 0644).
- `/logs` must be writable by uid 10001. Use a dedicated directory (`mkdir -p /var/log/imap-mcp && chown 10001 /var/log/imap-mcp`), never `chown` a shared directory such as `/var/log`.
- On hosts with SELinux enforcing (Fedora, RHEL and derivatives), a bind mount can be refused with "permission denied" although the ownership is right, because the files carry a label the container may not use. Add `:Z` to the mount, which relabels the host directory for this container only: `-v /etc/imap-mcp:/config:Z -v /var/log/imap-mcp:/logs:Z`. Use `:z` instead when several containers share the directory. Never use `:Z` or `:z` on a system directory such as `/var/log`, `/etc` or your home directory itself: it relabels everything below it. Mount a dedicated directory. Named volumes (`-v imap-mcp-config:/config`) need none of this.

Example for a host with SELinux, root-owned env file and dedicated directories:

```
docker run -d --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  --env-file /etc/imap-mcp.env \
  -v /etc/imap-mcp:/config:Z -v /var/log/imap-mcp:/logs:Z \
  imap-mcp
```

### Permanent deletion (purge_messages)

Permanent deletion is a two-step workflow: `delete_messages` moves messages from any folder to Trash (reversible), and `purge_messages` removes messages from Trash for good. `purge_messages` works only from the Trash folder (found by its SPECIAL-USE role, never by name); a handle from any other folder is refused with `purge_scope`, and it cannot be undone. Gmail: the message disappears from all labels.

Two switches must both agree. The operator turns it on per account, and the user confirms each call:

```yaml
accounts:
  - id: work
    host: imap.example.com
    username: work@example.com
    password: env:WORK_IMAP_PASSWORD
    allowPurge: true
tools:
  deadline: 120s
```

- `allowPurge: true` (default `false`) registers `purge_messages`. An account without it refuses the tool with `PERMISSION_DENIED` (`purge_disabled`). `allowPurge: true` together with `readOnly: true` fails startup. `list_accounts` shows the setting as `allow_purge`.
- `purge_messages` is registered in `stdio` and over the stateful HTTP transport (`http-local` with `http.stateful: true`; `http-public` only with `purge.enabled` and `http.stateful`). Without `http.stateful` it is not available in `http-local` (it needs a server that can ask the client a question), and an `allowPurge` account there only gives a startup warning, so one config file serves both modes.
- `http.stateful: true` (default `false`) lets `http-local` register `purge_messages` too: the server then runs the stateful Streamable HTTP transport (`GET`, `POST` and `DELETE` on `/mcp`, each through the same Host, Origin and token checks) and the confirmation works as in `stdio`, on the call's event stream. Every MCP session is bound to the principal it was issued to (here the one fixed local principal); a request with an unknown session id gets `404` and the client initializes again. At most 10 sessions per principal (the least recently used is replaced), 1000 per server (`503` with `Retry-After` beyond that), 30 minutes idle. A session approval belongs to its session and ends with it; the decline cooldown stays per account. A reverse proxy in front must keep its read timeout above `tools.deadline`, and `tools.deadline` must be at least 60 s while `purge_messages` is registered.
- Needs the server extension UIDPLUS (`UNSUPPORTED` otherwise). Only the given UIDs are expunged; other `\Deleted` messages in Trash are never touched. A repeat reports `already_gone`.
- Confirmation by MCP elicitation: the client shows the user a fixed dialog ("Permanently delete 2 messages from the Trash folder ... of account ...? This cannot be undone.") with a checkbox "Allow for this session". Only the user can answer it; no tool argument can. The dialog contains no subject, sender or file name. A client without elicitation support gets `confirmation_unavailable` and nothing is deleted. Decline, cancel, an unreadable answer, an error or no answer are `not_confirmed` with `details.elicitation` (`declined`, `cancelled`, `invalid_answer`, `error`, `timeout`); after a decline or cancel, further calls on that account are refused for 60 s without a dialog (`recently_declined`).
- Session approval: with "Allow for this session" the user allows further purges of messages that were already in that Trash folder when the approval was given, for 15 minutes and at most 500 messages, for that account only. It is kept in memory, ends with the process and has no configuration key. Messages that reach Trash later always ask again. Such calls report `confirmation` `session_approval`.
- Deadline trade-off: the user needs time to answer, so `tools.deadline` must be at least 60 s while `purge_messages` is registered (startup fails otherwise). The wait for the answer ends 20 s before the deadline. A longer deadline gives the user more time but also holds a connection and a worker longer.
- Host timeouts: some clients end a tool call after their own timeout (for example 60 s), which can be shorter than the wait. The dialog may then stay open on the client; a late acceptance still deletes (the user agreed), although the result reaches nobody. Retry then reports `already_gone`. If a call is cut off after the deletion started, `TIMEOUT` with `details.outcome` `unknown` is returned and a retry is safe.

### Downloading attachments (download_attachment)

`download_attachment` saves one attachment (or any other leaf part, or a whole `message/rfc822` part with its headers) as a new file. It is registered only when `tools.download.dir` is set. It reads the message with `BODY.PEEK` (the message is not marked as read) and changes nothing in the mailbox, so it is allowed on `readOnly` accounts. The output names the file and gives `bytes` and `sha256`; the file name and path come from the email and are untrusted.

```yaml
accounts:
  - id: work
    host: imap.example.com
    username: work@example.com
    password: env:WORK_IMAP_PASSWORD
tools:
  download:
    dir: /home/me/Downloads/imap-mcp
    displayDir: /home/me/Downloads/imap-mcp
    maxBytes: 25MiB
```

- `tools.download.dir`: absolute path, must exist and belong to the server's user, must not be writable by others, and must not be the state, log or config directory. The server never creates it.
- `tools.download.maxBytes`: limit of the decoded bytes per download, default `25MiB`, at most `100MiB` (`TOO_LARGE` beyond it).
- `tools.download.displayDir`: optional absolute path that the output reports instead of `dir` (the host path of a Docker volume). It is never accessed.
- Files are created with mode 0600, never executable, and the server never overwrites a file: a clashing name gets a number (`name (2).ext`). Names are sanitized; there is no file-type list, so never open or run a downloaded file because an email says so.
- Docker: mount the directory as a volume and run the container as the host user, because the files are 0600 and owned by the container user (`/config` and `/logs` must then be writable by that uid). Podman rootless additionally needs `--userns=keep-id`:

```
docker run -d --user "$(id -u):$(id -g)" \
  -p 127.0.0.1:8080:8080 \
  -v imap-mcp-config:/config -v imap-mcp-logs:/logs \
  -v ~/Downloads/imap-mcp:/downloads \
  imap-mcp
```

  With `tools.download.dir: /downloads` set `displayDir` to the host path. Docker Desktop on macOS maps volume ownership differently; the startup owner check there is covered by the smoke checklist below.
- Large attachments on slow servers may need a larger `tools.deadline`.

### Gmail

Use an app password (the account password is not accepted). With `flavor: auto` (the default) the hosts `imap.gmail.com` and `imap.googlemail.com` are detected and the `gimaps` protocol is used, which `gmail_raw` search needs. Gmail requires implicit TLS.

```yaml
accounts:
  - id: gmail
    host: imap.gmail.com
    username: me@example.com
    flavor: auto
    password: env:GMAIL_APP_PASSWORD
```

**Write tools on Gmail.** The write tools contain no Gmail-specific code; the label effects below come from the Gmail server and are tested against GreenMail and Dovecot only, not against real Gmail. On Gmail, folders are labels:

- A copy adds a label. If the message already has the label, no duplicate is made and the returned handle points to the existing message.
- A move replaces the source label with the target label. Moving into All Mail archives the message; moving out of Trash untrashes it. A message moved out of All Mail stays there, so repeating the call does not report `already_gone` and the old handle may stay valid.
- Moving or copying into Junk, Starred or Important has the effect of that label.
- `delete_messages` moves the message to the Trash role folder and removes all labels; Gmail purges Trash after 30 days. `purge_messages` (see its section) deletes from `[Gmail]/Trash` permanently, from all labels.
- Moving into Drafts or Sent may be refused by Gmail (`UPSTREAM_ERROR`).
- Flags and keywords apply to the message across labels.
- The Gmail extension items `X-GM-LABELS`, `X-GM-MSGID` and `X-GM-THRID` are not used.
- Gmail advertises `UTF8=ACCEPT`, which Angus Mail enables (see Notes).

Manual smoke checklist, to be run by the owner against a test Gmail account (use `example.com`-style test data, never a real mailbox):

**Status: pending, not yet run, because no test accounts are available.**

- COPYUID on labels: copy a message into a label and check the returned handle opens the message.
- Copy into a label the message already has: no duplicate, handle points to the existing message.
- Move into and out of All Mail: archives, and the message stays in All Mail after moving out.
- Move out of Trash: the message is untrashed.
- Junk, Starred and Important: move or copy into each and check the label effect.
- Delete from a label: the message ends in Trash with no other labels.
- Trash role under a localized UI: set the Gmail UI language to a non-English one and check `delete_messages` still finds Trash.
- Keyword persistence: set a keyword with `set_flags`, reopen the message from another label and check it is still set.
- Move into Drafts or Sent refused: the call reports `UPSTREAM_ERROR` and changes nothing.

### Smoke checklist for v3a

Manual checks for the owner, with test data only (the automated smoke run above does not cover them):

**Status: pending, not yet run, because no test accounts are available.**

- Gmail Trash purge: `delete_messages` a test message, then `purge_messages` it from `[Gmail]/Trash`; the message is gone from All Mail too.
- Microsoft 365 Deleted Items: purge a test message from Deleted Items (the Trash role there); it is gone from the folder.
- Real elicitation client: in at least one client that supports elicitation, check the dialog text, decline, accept, and "Allow for this session" (a second call without a dialog).
- Gmail attachment download: `download_attachment` of an attachment of a Gmail message; compare size and `sha256` with the original.
- Docker Desktop on macOS owner probe: start the container with `--user` and a mounted `tools.download.dir`; startup succeeds (or fails with the owner message) and a download is readable on the host.

### Smoke checklist for v3b

Manual checks for the owner, with test data only.

**Status: pending, not yet run.**

- Browser login flow of the linking surface: login at the identity provider, the callback page, `/accounts`, in Chrome, Firefox and Safari. The `SameSite` cookie behaviour cannot be automated with an HTTP client.
- Claude connector, purge over `http-public` (decision D8): `purge_messages` is off by default in `http-public` (`purge.enabled` false). Enable it on a test deployment only after this check: connect the Claude connector with the write permission, `delete_messages` a test message, then `purge_messages` it; the connector must show the form elicitation dialog (fixed text, no subject or sender), accept deletes only the addressed message, decline and cancel delete nothing, and a client that cannot answer form elicitation gets `confirmation_unavailable`. If the connector does not answer form elicitation over Streamable HTTP, `purge.enabled` stays false.

### OS keyring (Linux and macOS)

Passwords can come from the OS keyring through the platform CLI. Not available on Windows or in Docker. The commands prompt for the secret, so it never lands in shell history.

```
# Linux (libsecret)
secret-tool store --label='imap-mcp: work' service imap-mcp account work@example.com

# macOS (-w last, without a value, so it prompts; add -U to update an existing item)
security add-generic-password -s imap-mcp -a work@example.com -w
```

```yaml
accounts:
  - id: work
    host: imap.example.com
    username: work@example.com
    password: keyring:imap-mcp/work@example.com
```

The reference is resolved once at startup, so unlock the keyring first. Headless or SSH sessions without a D-Bus session bus cannot use `secret-tool`. On macOS a non-ASCII password also works: when `security -w` prints hex, the password is read via `security -g` and decoded as strict UTF-8; an ambiguous or non-UTF-8 item fails startup, naming only the reference.

### IMAP protocol trace

`imap.trace: true` in the config switches on the Jakarta Mail protocol trace. It is a diagnostic opt-in, off by default. WARNING: with the trace on, the log file may contain message content (subjects, addresses, bodies); credentials stay redacted. Enable it only to diagnose a problem and disable it after diagnosis. In `http-public` it is refused: `imap.trace: true` fails startup naming the config path, because the trace would write every tenant's mail content into the operator log (SPEC decision D15).

### Safety and upgrading

- Email content is untrusted input (possible prompt injection) and is returned as labeled data.
- Write tools are enabled by default and make only reversible changes. `delete_messages` moves to Trash and never deletes permanently; on servers without MOVE the move is done as copy, then flag and expunge of exactly the copied messages in the source.
- Set `readOnly: true` on every account the agent must never change.
- Permanent deletion exists only as `purge_messages`: off unless an account has `allowPurge: true`, Trash only, `stdio` or (`http-public` with `purge.enabled`), and the user confirms each call in the client.
- MCP hosts may ask for confirmation on tools that carry `destructiveHint`.
- Upgrade note: upgrading from 0.1.0 enables the write tools on every account without `readOnly: true`; a configured `tools.deadline` below 30 s then fails startup (see "Configuration").

### Errors

A failed tool call has `isError` true and carries the error object only in the text block, with no `structuredContent` (the output schema describes the success shape only):

```
{"error":{"code","message","retryable","hint","retry_after_ms"?,"details"?,"request_id"}}
```

Payloads carry no stack traces, host names or exception text. Codes:

| Code | Retryable | Meaning |
|---|---|---|
| `INVALID_ARGUMENT` | no | an argument is missing, malformed or out of bounds; see `details` (`download_attachment`: `part_not_found`, `not_a_leaf`) |
| `ACCOUNT_NOT_FOUND` | no | unknown, malformed or foreign account handle (one identical payload for all of them) |
| `INVALID_HANDLE` | no | unknown, malformed, tampered or foreign message handle (one identical payload for all of them) |
| `INVALID_CURSOR` | no | invalid cursor, or one used with other search criteria (`criteria_mismatch`) |
| `STALE_REFERENCE` | no | the folder changed since the handle was issued (`details.reason` `uidvalidity_changed` or `folder_missing`); re-run the search |
| `MESSAGE_NOT_FOUND` | no | the message no longer exists |
| `FOLDER_NOT_FOUND` | no | the folder (or the Trash role folder) does not exist |
| `AUTH_FAILED` | no | the IMAP server refused the login (`NO` without `[UNAVAILABLE]`); `details.reason` `relink_required` in `http-public`; for an upstream OAuth account (`http-public`) also `xoauth2_rejected` (the server refused the access token or does not offer XOAUTH2; does not count towards a re-link) |
| `SERVER_BUSY` | yes | pool or server limit reached; wait `retry_after_ms` (2000) |
| `TIMEOUT` | yes | `tools.deadline` exceeded |
| `UPSTREAM_UNAVAILABLE` | yes | the IMAP server is unreachable or dropped the connection or, for an upstream OAuth account (`http-public`), the provider's token endpoint failed (`details.reason` `token_refresh_unavailable`, wait `retry_after_ms`, at most 60000); also a LOGIN answered `NO [UNAVAILABLE]` (`details.reason` `login_unavailable`, hint "Retry in a minute or later."). **Changed in v0.23 (D14):** before, that LOGIN answer was `AUTH_FAILED`; a client that treated `AUTH_FAILED` as "wrong password, stop" now sees a retryable error. It does not count towards `needs_relink`, and `list_accounts` shows the account as `unreachable` |
| `UPSTREAM_ERROR` | no | the IMAP server refused the command; for an upstream OAuth account (`http-public`) `details.reason` `oauth_client_rejected` (the provider refused the server's OAuth client) or `provider_unconfigured` (the account's provider is no longer configured); both need the operator |
| `UNSUPPORTED` | no | a needed capability is missing (for example `UIDPLUS`, `X-GM-EXT-1`, UTF-8 search); `download_attachment`: `transfer_encoding` or `decode_failed` |
| `INTERNAL_ERROR` | no | unexpected server error |
| `PERMISSION_DENIED` | no | `details.reason` is `account_read_only`, `folder_read_only`, `server_denied`, or for `purge_messages` `purge_disabled`, `purge_scope`, `confirmation_unavailable` or `not_confirmed` (`details.elicitation` is `declined`, `cancelled`, `timeout`, `invalid_answer`, `error` or `recently_declined`) |
| `QUOTA_EXCEEDED` | no | the mailbox is over quota; nothing is deleted permanently as a fallback |
| `OUTCOME_UNKNOWN` | no | `copy_messages` and `download_attachment`: the call was cut off after the first mutating command, so the copy may exist; repeating it could duplicate |
| `TOO_LARGE` | no | `download_attachment`: the attachment exceeds `tools.download.maxBytes` (`details.size`, `details.max`) |
| `LOCAL_STORAGE_ERROR` | no | `download_attachment`: the file could not be saved; `details.reason` is `directory_unavailable`, `name_exhausted`, `no_space` or `io_error` |

`PERMISSION_DENIED` in `http-public` also carries `insufficient_scope` (`details.scope`), see "Discovery and challenges" below. HTTP-level refusals of `/mcp` in `http-public` are not tool errors and are listed under "HTTP answers of /mcp".

`details` fields: `details.field` names the offending argument (`handles[i]` for the first bad handle), `details.reason` the specific rule, `details.max` the bound that was exceeded. For a write call cut off after the first mutating command (deadline, cancel, unexpected failure), `details.outcome` is `unknown`: the change may or may not have happened; for an idempotent tool the call is retryable, for `copy_messages` it is `OUTCOME_UNKNOWN`.

### Smoke tests (opt-in)

Smoke tests run against real accounts and never as part of a normal build. Run them with:

```
mvn -Psmoke -pl imap-mcp-it -am verify
```

- `IMAP_MCP_SMOKE_GMAIL_USER` and `IMAP_MCP_SMOKE_GMAIL_PASSWORD`: Gmail account and app password.
- `IMAP_MCP_SMOKE_M365_USER` and `IMAP_MCP_SMOKE_M365_PASSWORD`: Microsoft 365 account and app password.
- `IMAP_MCP_SMOKE_<GMAIL|M365>_HOST` and `_PORT`: optional host and port overrides.
- `IMAP_MCP_SMOKE_WRITE=1`: also run the Gmail write checks.

Every test is skipped, with a reason, when its credentials are not set.

#### XOAUTH2 smoke (Gmail, Microsoft 365)

Status: **pending, not yet run.** The harness (`GmailOAuthLiveSmoke`, `M365OAuthLiveSmoke`) is prepared; the real-provider run is an owner task (#328) and needs the provider registrations of #325. The provider assumptions in SPEC 5.5 stay unverified until a run is recorded.

It drives the production `http-public` wiring against the real provider: refresh and the read tools with PEEK, one reversible write, purge from Trash, a forced refresh, and a revoked token giving `relink_required`. **Docker is needed** for the Postgres container of the stack (and only once the variables below are set); there is no IMAP or token stub.

- `IMAP_MCP_SMOKE_<GMAIL|M365>_USER`: the test address (the IMAP user).
- `IMAP_MCP_SMOKE_<GMAIL|M365>_OAUTH_CLIENT_ID`: the OAuth client id of your registration.
- `IMAP_MCP_SMOKE_<GMAIL|M365>_OAUTH_CLIENT_SECRET_REF`: a reference such as `env:MY_GOOGLE_SECRET` or `file:/path` (the secret itself is rejected); `env:` names a variable of the same environment.
- `IMAP_MCP_SMOKE_<GMAIL|M365>_OAUTH_REFRESH_TOKEN`: a refresh token you obtained (below).
- Optional: `_OAUTH_TENANT` (Microsoft: `common`, `organizations`, `consumers` or a GUID), `_OAUTH_REVOKED_REFRESH_TOKEN` (a token you revoked; otherwise a made-up one stands in).
- `IMAP_MCP_SMOKE_OAUTH_WRITE=1`: also set and clear the flagged flag on one INBOX message.
- `IMAP_MCP_SMOKE_OAUTH_PURGE_SUBJECT`: purge from Trash the messages with exactly this subject (at most 3). Put a throwaway message in Trash first. Without this variable nothing is purged.

Getting the refresh token: run the provider's authorization-code flow by hand (`access_type=offline` and `prompt=consent` for Google; the `offline_access` scope for Microsoft) and exchange the code at the token endpoint. The linking surface (`/accounts`) of a test `http-public` deployment with the provider configured (SPEC 5.3) can check your registration and redirect URI end to end, but it never exposes the token: it is stored envelope-encrypted and cannot be read back. Use a dedicated test account. Never put the token on a command line that is logged or committed.

### Notes

- Angus Mail enables `UTF8=ACCEPT` by itself for any server that advertises `UTF8=ACCEPT` or `UTF8=ONLY` (not only Gmail; not configurable). Folder names a tool returns or accepts are the server-visible full names and round-trip verbatim either way.
- New-mail search (`since` / `as_of`) assumes the clocks of the MCP host and the IMAP server agree within 5 minutes.
- Security: publish on loopback only; `Host`/`Origin` checks and the bearer token apply; credentials are never in the image; literal passwords in the config are rejected; email content is untrusted input (possible prompt injection) and is returned as labeled data.

### http-public

The multi-tenant mode for a hosted deployment (what a Claude connector needs): `java -jar imap-mcp.jar --mode http-public --config <file>`. Every user is a tenant with their own mail accounts; tenants never see each other's mailboxes (see "Tenants and mailboxes" above). A step-by-step overview is in [`doc/TUTORIAL.md`, section 6](doc/TUTORIAL.md#6-public-multi-tenant-mode-http-public). This server is an OAuth 2.1 resource server only; the identity provider (for example Keycloak) is separate and must put the resource URI into the access token's `aud` claim (RFC 8707). Mail accounts are linked per user in the linking surface, not in the config file; a configured `accounts` list fails startup.

A container-based test environment (Postgres, Keycloak with a ready realm, the app and an optional Caddy TLS proxy) for trying http-public on the public web, including with the Claude connector, is in [`dist/docker`](dist/docker/README.md).

```yaml http-public
db:
  url: jdbc:postgresql://db.internal:5432/imapmcp   # no password in the URL
  username: imapmcp
  password: env:DB_PASSWORD
oauth:
  issuer: https://idp.example.com/realms/mail        # https; keys are discovered from <issuer>/.well-known/openid-configuration
  resource: https://mail.example.com/mcp             # the canonical resource URI, expected in the token's aud; its path must end in /mcp
  requiredRole: imap-mcp-user                        # IdP role needed to get a tenant (or allowWithoutRole: true for development)
  webClientId: imap-mcp-web                          # the confidential OIDC client of the linking surface (required)
  webClientSecret: env:WEB_CLIENT_SECRET             # secret reference (required)
public:
  baseUrl: https://mail.example.com                  # the linking surface lives at <baseUrl>/accounts
security:
  handleKeys:                                        # explicit and identical on every replica; ids 0 to 255
    - id: 1
      key: env:HANDLE_KEY_1                          # base64url (no padding) of 32 random bytes
  signingKeyId: 1
ssrf:
  allowedHosts: ["imap.example.com", "*.mail.example.net"]   # IMAP hosts a user may link
kms:
  provider: local                                    # credentials are AES-256-GCM encrypted; the key is wrapped by a KEK
  local:
    currentKekId: kek-1
    keks:
      - id: kek-1
        key: env:KEK_1                               # base64url (no padding) of 32 random bytes
```

- **Startup fails** (one line on stderr naming the config key, never a value) without a database, without an explicit key ring, with an empty `ssrf.allowedHosts` unless `ssrf.allowAnyPublicHost: true`, without `oauth.webClientId` or `oauth.webClientSecret`, with a `public.baseUrl` that is not an https origin (no path, query or fragment), with a non-https issuer or resource, with an `oauth.resource` whose path does not end in `/mcp` (a trailing `/` included, so `https://mail.example.com` and `https://mail.example.com/` fail), with `imap.trace: true`, with `http.initializeRatePerMinute` outside 1 to 600, with an explicitly set `tools.download.inlineConcurrencyPerTenant` above `tools.download.inlineConcurrency`, with `tools.download.dir`, `maxBytes` or `displayDir`, with `http.token`, with a password in `db.url`, and with unknown keys. It also logs one fingerprint (12 hex characters) per handle key id and, with `kms.provider: local`, per KEK id, so replicas can be compared; key material is never logged.
- **Reverse proxy:** terminate TLS there and forward `Host`. `http.allowedHosts` defaults to the host of `public.baseUrl`; bind to a non-loopback address only with `http.allowNonLoopbackBind: true` (or `IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND`). With `http.stateful: true` (required for `purge.enabled`) a load balancer needs affinity on the `Mcp-Session-Id` header: the user's answer to the purge confirmation is a POST that must reach the replica whose call is waiting, and there is no shared session registry, so a request routed elsewhere gets `404` and the client initializes again (session approvals are lost). Requests are limited by Jetty's request header limit of 8 KiB, which applies before the token cap of 8192 characters; a larger `Authorization` header is refused by Jetty before the token is looked at. The proxy's read timeout must exceed `tools.deadline`, because the call's event stream stays open while the user answers. `purge_messages` stays off (`purge.enabled: false`) until the owner's smoke test with the Claude connector has passed (see the v3b smoke checklist).
- **Discovery and challenges (RFC 9728):** the metadata is served unauthenticated (`GET` and `HEAD`) at `/.well-known/oauth-protected-resource/<path of oauth.resource>` (for `https://mail.example.com/mcp`: `/.well-known/oauth-protected-resource/mcp`) and, as a deliberate fallback that clients probe second, at `/.well-known/oauth-protected-resource`; `resource` equals `oauth.resource` at both. **The connector URL you give Claude is exactly `oauth.resource`**, which ends in `/mcp` and has no trailing slash. Behind a proxy with a path prefix use `https://mail.example.com/x/mcp`: the proxy maps `/x/mcp` to `/mcp` and passes `/.well-known/oauth-protected-resource/x/mcp` through unchanged. With `S` = `imap-mcp:read imap-mcp:write` and `<URL>` the metadata URL, the answers are exactly: no `Authorization` header: `401` with `Bearer realm="imap-mcp", resource_metadata="<URL>", scope="S"`; a presented token that fails (scheme, more than one header, malformed, `typ` or `alg`, signature, claims, an unknown `kid` after a refresh): `401` with `Bearer realm="imap-mcp", error="invalid_token", resource_metadata="<URL>", scope="S"`; a valid token without `imap-mcp:read`: `403` with `Bearer realm="imap-mcp", error="insufficient_scope", resource_metadata="<URL>", scope="S"`. There is no `error_description`. Every other refusal (disabled tenant `403`, `400`, `404`, `429`, `503`) carries no challenge. `imap-mcp:write` is checked per call for the write tools and `purge_messages` and is a tool error (`PERMISSION_DENIED` `insufficient_scope`), which Claude never answers with re-authorization, so grant it at the first authorization (there is no step-up for it at HTTP level, D19). Tokens are accepted in the `Authorization` header only. The subject claim is `oauth.subjectClaim` (default `sub`; Microsoft Entra needs `oid`).
- **Signing keys and an identity provider outage (D16):** the keys come from the issuer's `jwks_uri`, read from `<issuer>/.well-known/openid-configuration` when the first token arrives (a failed discovery is retried at most every 10 s; the `jwks_uri` itself is not read again, so after the identity provider changes it, restart the replicas). The key set is kept per replica; a refresh is due 5 minutes after the last successful fetch, when a token names an unknown `kid`, or when the set reached the age ceiling; refreshes are started only by requests, run single-flight and start at most once every 30 s. An outage is not an invalid token, because Claude refreshes its token on every `401` and that fails too during the outage. So: after a failed refresh the last good key set stays usable until 60 minutes after its fetch (tokens whose `kid` is in it are validated normally). **Age ceiling (v0.24):** a key set older than 60 minutes since its successful fetch is never used, also when no refresh has failed (for example on a replica that saw no token for an hour): a request that finds it so waits for a refresh like one with an unknown `kid` (single-flight; up to 11 s, each fetch is bounded by 5 s) and is decided on the fresh set (`200`, or `401` when its `kid` is gone), and when the refresh fails or may not start yet, the set is dropped. When the ceiling is reached, or when discovery never succeeded, or when an unknown `kid` cannot be resolved, or the wait ends after 11 s, the answer is `503` with `Retry-After` (30 s, or the rest of the 30 s interval), the JSON-RPC body `-32003` and no `WWW-Authenticate`. Only a token that is actually invalid gets `401`. The linking callback answers `503` with a generic page under the same conditions. Logs: one WARN when an outage begins (not for a set already at the ceiling), one INFO when a fetch succeeds again, one ERROR when a set is dropped at the age ceiling, plus the 503 WARN at most once a minute; never token content. **To stop trusting a key during an outage restart the replica; with several replicas restart every one of them** (a restart drops the cached keys, and until a fetch succeeds every token gets `503`). The 60 minute window is fixed (confirmed by the owner on 2026-11-08).
- **HTTP answers of `/mcp`:** besides the challenges above: `initialize` over the rate limit gets `429` with `Retry-After` and JSON-RPC `-32003` (see below); an `initialize` that carries a session id gets `400` (`-32600`); a `POST` that carries a session id is decoded strictly as UTF-8 before the SDK sees it: a `charset` other than UTF-8 gets `415`, a body above `http.maxRequestBytes` `413`, bytes that are not valid UTF-8 `400` (each with an empty body, a foreign session id still gets `404` first); unavailable signing keys, the tenant lookup and the session cap get `503`. SDK-level error bodies carry fixed messages only, never exception text.
- **`initialize` rate limit (D12):** with `http.stateful: true`, a token bucket per principal and replica, refilled at `http.initializeRatePerMinute` (default 10, 1 to 600; the capacity is twice the rate); `http.initializeRatePerMinute` set while `http.stateful` is false has no effect and logs a WARN. The table is in memory (n replicas admit n times the rate; a restart resets it).
- **Embedded download:** `download_attachment` returns the usual text block (exactly the `structuredContent`) followed by one `resource` block with the file base64-encoded (`uri` `imap-mcp://attachment/<sha256>`; `mimeType` only for `application/pdf`, `image/png`, `image/jpeg`, `image/gif`, `image/webp`, `text/plain` and `text/csv`, otherwise `application/octet-stream`). `tools.download.inlineMaxBytes` (default `5MiB`, 1 byte to `10MiB`) caps the decoded bytes (`TOO_LARGE`), and `tools.download.inlineConcurrency` (default 4, per replica) caps the downloads running at once and `tools.download.inlineConcurrencyPerTenant` (default `min(2, inlineConcurrency)`, 1 to `inlineConcurrency`) caps those of one tenant, so that a single tenant cannot hold every permit (`SERVER_BUSY`, retryable; the busy scopes in the metrics are `download_tenant` and `download_replica`). The pre-fetch size check uses the encoded size from the server and is exact for 76-character base64 lines; it can over-count for others, so a part a few percent below the cap may be refused before the fetch, and the check while streaming is the authoritative one. Memory: each running download holds about 1.4 times `inlineMaxBytes` (the base64 text and its buffer), and the finished responses come on top, bounded by the concurrent HTTP requests. As a guideline give the JVM at least 512 MiB of heap at the largest cap.
- **Tenant disable (D13):** v3b has no admin path that changes the tenant status; the operator disables a tenant in the database, in one transaction that sets the status and writes the audit row (`:issuer` and `:subject` identify the user, as in `tenant`; `:change_ref` is the operator's own change reference, for example a ticket number, and is stored as the audit row's `request_id`; the row has the tenant id, no account and the result `ok`):

  ```sql
  BEGIN;
  UPDATE tenant SET status = 'disabled' WHERE issuer = :issuer AND subject = :subject;
  INSERT INTO audit_event (tenant_id, request_id, event_type, result)
    SELECT id, :change_ref, 'tenant_disabled', 'ok' FROM tenant WHERE issuer = :issuer AND subject = :subject;
  COMMIT;
  ```

  A replica caches the tenant lookup for 30 s, so the disable takes effect on every replica within 30 seconds (immediately on a replica whose cache is invalidated). A disabled tenant gets `403` without a challenge.
- **Tenants:** a user who is not yet known sees no accounts (`list_accounts` returns an empty list and the `link_url`); no MCP request creates a tenant. A tenant is created at the first login of the linking surface, and only for users with `oauth.requiredRole`.
- **Tools:** one fixed list for everybody (read tools, `download_attachment` with the file embedded in the result, and the five write tools). `purge_messages` is registered only with `purge.enabled` (which requires `http.stateful`). Restrictions are refusals at call time: `account_read_only`, `insufficient_scope`, and `relink_required` (an account whose password was refused three times in a row; `list_accounts` shows it as `needs_relink` with the `link_url`). The account is resolved before the scope check, so a call to such an account without the write scope gets `relink_required`, not `insufficient_scope`. Provider lockout: in the worst case about 2 plus `pool.maxPerMailbox` refused LOGINs per replica arrive in a burst before the account flips to `needs_relink`, and with several replicas that number is multiplied; keep it below the lockout threshold of your mail provider.
- **Purge over HTTP (decision D8):** `purge.enabled` (default `false`) and `http.stateful` (default `false`; `purge.enabled` requires it) turn on `purge_messages` in `http-public`. Enable `purge.enabled` only after the owner's smoke test with the Claude connector (see the v3b smoke checklist); the per-account `allowPurge` switch of the linking surface can only be set while `purge.enabled` is true. While it is false, `purge_messages` is not registered and every account refuses it as `purge_disabled`.
- **Audit and retention:** every call of a write tool leaves a row in the database (reference hashes only, never folder names, UIDs or message handles); rows are deleted after 180 days. If the audit row cannot be written, the call fails and nothing is changed. A write call that fails in the argument check (step 1) leaves no row, only the metric `write_argument_rejected` (D10). A call without a resolved account (unknown or foreign account handle, an error during the account lookup) leaves a row with the tenant and an empty (`NULL`) account id; a principal without a tenant row (owns nothing, so every write call fails) leaves no row at all: each such call increments the metric `write_without_tenant` (replica-wide, per tool), and the replica logs at most one WARN per minute with the tool, request id, error code and a hashed principal (D11, v0.24).
- **Metrics (internal, in the periodic log summary, hashed ids):** besides pool, latency and auth-failure figures, v0.23 adds `write_argument_rejected` (per tenant and tool), `initialize_rate_limited` (per tenant), `oauth_keys_unavailable` (replica-wide: requests answered `503` for the keys, including the linking callback's `503`), `oauth_keys_stale` (replica-wide: key decisions made with the stale key set) and the busy scopes `download_tenant` and `download_replica`. A LOGIN `NO [UNAVAILABLE]` is not an auth failure (D14).
- **Egress and SSRF:** the server blocks private, loopback, link-local, metadata and other special-purpose addresses (the SPEC 5.3 table, IPv4 and IPv6, including IPv4-mapped IPv6 and the NAT64 prefixes) and any host outside `ssrf.allowedHosts`, refuses IP literals as host names, and only connects to port 993 (implicit TLS) or 143 (STARTTLS). All addresses of the name are resolved by the server; if one of them is blocked the connect is refused, and only the first address (IPv4 first) is tried. The check runs on every TCP connection, folder connections included. Add an egress network policy as well. Operator notes:
  - **DNS64/NAT64:** behind a DNS64 resolver every IPv4-only host also gets a synthesized `64:ff9b::` address, so it is refused (`64:ff9b::/96` is blocked as a whole). With a network-specific NAT64 prefix, add that prefix to `ssrf.extraBlockedCidrs` as well.
  - **More ranges:** `ssrf.extraBlockedCidrs` (a list of CIDRs, default empty) adds ranges to the built-in table, for example `3fff::/20`, `5f00::/16` or `::ffff:0:0:0/96` if your network needs them.
  - **Suffix patterns:** `*.example.net` matches every name below `example.net`, so a pattern such as `*.co.uk` would allow a whole public suffix. Use patterns for domains you trust.
  - **Hosts:** names are stored as IDN A-labels in lower case; a refused connect is `UPSTREAM_ERROR` with `details.reason` `address_blocked` (never retryable), and `list_accounts` shows the account as `unreachable`.
- **Key wrapping (`kms.provider`):** `local` (shown above; the KEK comes from a reference, startup logs a WARN, for self-hosting and tests) or `vault` (HashiCorp Vault Transit, the production choice). With `kms.provider: vault` the keys are `kms.vault.address` (https; plain http only with `kms.vault.allowInsecureHttp: true`; the key `kms.vault.allowInsecureHttp` logs a WARN at startup when the address really is http, and is for tests), `kms.vault.transitMount` (default `transit`), `kms.vault.keyName`, `kms.vault.auth.method` (`token` with `kms.vault.auth.token`, or `approle` with `kms.vault.auth.roleId`, `secretId` and optionally `path`; all values are secret references), `kms.vault.caCert` (absolute path of a PEM bundle that replaces the JDK trust store for Vault) and `kms.vault.requestTimeout` (1s to 30s, default 5s). `kms.local` and `kms.vault` are mutually exclusive. The Transit key must exist with type `aes256-gcm96`; startup checks it and fails otherwise. The Vault policy needs `update` on `<transitMount>/encrypt/<keyName>` and `<transitMount>/decrypt/<keyName>` and `read` on `<transitMount>/keys/<keyName>` (without the read capability startup fails with `http_403`); it must not grant `create`, so that a mistyped key name cannot silently create a key. A credential row that names a key the policy does not cover gets `403` from Vault, one re-authentication, `403` again, and is then treated as an outage of the key service (a WARN line and the `credentials/key_service_unavailable` metric, `INTERNAL_ERROR` for the tool call, no LOGIN), not as a decrypt failure.
- **Key rotation:** for the handle keys, add a key under a new id in `security.handleKeys`, switch `signingKeyId`, restart every replica, and remove the old id only when nothing refers to it any more (SPEC 5.4). Rotation with `kms.provider: local`: add the KEK under a new id, switch `currentKekId`, restart every replica, and remove the old id only when no credential row carries it (SPEC 5.7). With `kms.provider: vault` no restart is needed: rotate the Transit key in Vault (`<transitMount>/keys/<keyName>/rotate`); new wraps use the latest version and older versions keep decrypting.

#### Further http-public keys

Beyond the example above (defaults in brackets): `oauth.subjectClaim` (`sub`), `oauth.roleClaim` (`realm_access.roles`), `oauth.allowWithoutRole` (`false`), `ssrf.allowAnyPublicHost` (`false`), `ssrf.extraBlockedCidrs` (empty), `public.maxAccountsPerTenant` (`10`, 1 to 1000), `tools.download.inlineMaxBytes` (`5MiB`), `tools.download.inlineConcurrency` (`4`), `tools.download.inlineConcurrencyPerTenant` (`min(2, inlineConcurrency)`), `http.initializeRatePerMinute` (`10`, 1 to 600, only with `http.stateful`), `http.stateful` (`false`), `purge.enabled` (`false`), `http.allowedHosts` (the host of `public.baseUrl`) and `http.allowedOrigins`. Unknown keys fail startup.

#### Keycloak setup for the Claude connector

The MCP client (the Claude connector) gets its access tokens from Keycloak; the server only validates them. The realm file `imap-mcp-it/src/test/resources/keycloak/imap-public-realm.json` is a working template (test data only; never use its users or secrets).

1. **Realm and signing:** an ordinary realm with asymmetric signing keys. Keycloak's default RS256 works; the server accepts RS256, PS256 and ES256 and refuses `none` and HMAC algorithms. The realm's issuer URL is `oauth.issuer` (https, exactly the `issuer` of the discovery document).
2. **The client of the connector:** either dynamic client registration (DCR) or a client you register by hand. Registered by hand it is a public client (client authentication off) with the standard flow on and PKCE method `S256`, and the redirect URIs that the connector documents. With DCR, enable the realm's client registration policies you want (for example Trusted Hosts) and make sure the policy "Allowed Client Scopes" lets registered clients use the scopes of step 4. This client is not the web client of the linking surface (see below).
3. **Audience:** the access token's `aud` must contain `oauth.resource` (for example `https://mail.example.com/mcp`). Create a client scope with an "Audience" mapper whose "Included Custom Audience" is exactly `oauth.resource`, "Add to access token" on, and assign the scope to the connector's client as a default scope; for DCR clients add it to the realm's default client scopes so that every registered client gets it. Without it every token is refused with `401` (`invalid_token`).
4. **Permissions as scopes:** create the client scopes `imap-mcp:read` and `imap-mcp:write` and assign them to the connector's client as optional scopes (for DCR, allow them in the "Allowed Client Scopes" policy). The read scope is required for every request. The first `401` challenge names both scopes, and Claude requests the scopes of that challenge at the first authorization, so the user grants both then; a write scope refused later is a tool error that Claude does not answer with re-authorization (D19). Observed with Keycloak 26.4 and the realm of `dist/docker`: besides the two `imap-mcp` scopes, `openid` is accepted, while `offline_access`, `profile` and `email` are refused with `invalid_scope`, so a client that adds one of them cannot authorize. A missing "Allowed Client Scopes" entry for the `imap-mcp` scopes has not been tested; check the issued token's `scope` claim.
5. **Who may create a tenant:** create the realm role named by `oauth.requiredRole` (for example `imap-mcp-user`) and give it to the users; the role is checked at the first login of the linking surface, not in the access token (see "Who gets a tenant" below for the ID token mapper).
6. **Web client of the linking surface:** the second, confidential client described under "Linking surface".

#### Decisions of v3b

Decided by the owner on 2026-11-06, as recommended: **D1** Keycloak, self-hosted, as the identity provider. **D2** a tenant is created at the first login of the linking surface, only with the required IdP role. **D3** scopes: `imap-mcp:read` in the filter, `imap-mcp:write` per call for the write tools and purge. **D4** server-rendered linking pages without JavaScript under `/accounts`. **D5** audit rows kept 180 days; a failed audit write fails the call (fail-closed). **D6** key wrapping with a local KEK or Vault Transit behind one interface. **D7** XOAUTH2 is not part of release 0.5.0; D7 was lifted on 2026-11-13: it is built after 0.5.0, on assumptions. **D8** purge over HTTP behind `http.stateful` and `purge.enabled`, off by default until the owner's connector smoke test. **D9** `download_attachment` returns an embedded resource with a cap, no `resource_link`.

Decided by the owner on 2026-11-07 (SPEC section 6): **D10** a write call that fails the argument check writes no audit row, only the metric `write_argument_rejected`. **D11** a call without a resolved account writes a row with the tenant and an empty account id; changed on 2026-11-08: a principal without a tenant row gets no row, only the metric `write_without_tenant` and a WARN at most once per minute with the hashed principal. **D12** `initialize` is rate-limited per principal and replica (`http.initializeRatePerMinute`, default 10, confirmed). **D13** a tenant disable takes effect within 30 s (confirmed) on every replica; no immediate path across replicas yet. **D14** a LOGIN `NO [UNAVAILABLE]` is retryable `UPSTREAM_UNAVAILABLE` (`login_unavailable`) instead of `AUTH_FAILED`: a contract change from v1. **D15** `imap.trace` is refused in `http-public`. **D16** a key-fetch outage is `503` with `Retry-After`, with a 60 minute window that is also a hard age ceiling of the key set since v0.24 (confirmed). **D17** embedded downloads are also capped per tenant (`tools.download.inlineConcurrencyPerTenant`, default `min(2, inlineConcurrency)`, confirmed). **D18** the link-probe limit stays per replica. The four defaults (D12, D13, D16 window, D17) were confirmed by the owner on 2026-11-08.

**D19** (decided on 2026-11-08): no step-up at HTTP level. A write tool call without `imap-mcp:write` stays a per-call tool error; every challenge names both scopes, so grant both at the first authorization.

#### Linking surface (`/accounts`)

Users link their mail accounts in a small server-rendered web application on the same port, under `<public.baseUrl>/accounts` (no JavaScript; the `link_url` in `list_accounts` and in re-link errors points there). It has its own filters and no bearer token; the `/mcp` chain is unchanged.

- **Keycloak web client:** create a second, confidential client (not the one the MCP client uses): client authentication on, standard flow on, direct access grants off, PKCE method `S256`, valid redirect URI exactly `<public.baseUrl>/accounts/callback` (for example `https://mail.example.com/accounts/callback`, no wildcard). Its id and secret are `oauth.webClientId` and `oauth.webClientSecret` (a reference: `env:`, `file:` or `keyring:`; the value is read again at each login, so a rotated secret needs no restart only with `file:` or `keyring:`, because an environment variable is fixed for the life of the process). Login uses the authorization code flow with PKCE, `state` and `nonce` against `oauth.issuer`; the ID token must carry `iat`, and `auth_time` for the re-authentication that turning on `allow_purge` needs.
- **Who gets a tenant:** the first login creates a tenant, but only when the ID token has `oauth.requiredRole` in the claim named by `oauth.roleClaim` (default `realm_access.roles`, Keycloak realm roles). Keycloak puts realm roles into the ID token only when the mapper says so: in the client scope that the web client uses, add a "User Realm Role" mapper with token claim name `realm_access.roles`, multivalued on, and "Add to ID token" on (`id.token.claim` true in the mapper's configuration; the default `roles` scope maps them to the access token only). Without it every login is refused with 403 and no tenant is created. `oauth.allowWithoutRole` set to `true` switches the check off for development and logs a WARN at startup. Removing the role at the IdP later does not affect an existing tenant; disable the tenant to withdraw access (effective on every replica within 30 seconds).
- **Other identity providers:** the linking surface requests the scope `openid` only. Microsoft Entra puts `oid` into ID tokens only with the scope `profile`, so Entra is not supported for the linking surface in v3b; Keycloak is the tested provider.
- **Session:** the cookie `__Host-session` (`Secure`, `HttpOnly`, `SameSite=Strict`) lives 30 minutes idle and 8 hours at most and is kept in the database, so any replica serves it. The callback answers with a page that continues to `/accounts` itself (a `SameSite=Strict` cookie is not sent on a redirect that began at the IdP). Every `POST` needs the session's CSRF token in the form body; the public origin from `public.baseUrl` must match the `Host` the proxy forwards, so forward `Host` unchanged.
- **After a successful action** (link, re-link, settings, unlink) the surface answers `303` to `/accounts` (Post/Redirect/Get), so a reload does not repeat it; a refused action is shown on the page with a `4xx` status.
- **Linking an account** asks for label, host, port with security (993 with implicit TLS or 143 with STARTTLS), user name and password, `read_only` (default on) and `allow_purge` (default off; it can be turned on only when `purge.enabled` is true, `read_only` is off, and the login is at most 5 minutes old). The SSRF checks (`ssrf.*`) run first, then a probe (connect, TLS, LOGIN, CAPABILITY, LOGOUT; no folder) against the server; only then is anything stored, encrypted with the configured `kms.provider`. A tenant has at most `public.maxAccountsPerTenant` accounts (default 10). Re-link replaces the password only, against the stored endpoint; a different host needs unlink and link.
- **Probe budget:** at most 10 probes per tenant per hour, counted in memory per replica (a global limit is a later, low-priority item, D18). With n replicas a tenant can reach 10 x n per hour, and a restart resets the count. The limiter tracks a bounded number of tenants; when its table is full of active tenants, a tenant that is not in it is refused until entries expire.
- **Unlink:** deletes the account and its credentials at once and its handles become unknown; idle pooled connections of the account end at the latest with the pool's idle eviction (they are unreachable meanwhile, because the handle is unknown).
- **Audit:** tenant creation, link, re-link, settings, unlink and every refusal leave an `audit_event` row (type, tenant, account reference, request id and result only, never host, user name, label or password). Expired web sessions and pending logins are deleted every 10 minutes.

#### Upstream OAuth (experimental, unverified)

For mail providers that no longer accept a password (Gmail, Microsoft 365), `http-public` can link an account through the provider's own sign-in (authorization code with PKCE) and log in to IMAP with XOAUTH2. This is `http-public` only and **experimental**: it is built on assumptions that no test or run against the real Google or Microsoft has verified (the list, with the symptom of each, is in section 4 of [`doc/DEPLOYMENT.md`](doc/DEPLOYMENT.md#4-assumptions-what-the-tests-observed-and-their-symptoms)). It is tested only against a fake provider.

- **Not in `stdio` and `http-local`:** `stdio` and `http-local` have no XOAUTH2; `oauth.upstream` and `auth: xoauth2` there fail startup. For Gmail use an app password there (see "Gmail" above). Microsoft 365 has no single-tenant option for now: it needs `http-public`.
- **Provider config:** one entry per provider under `oauth.upstream.providers` (the key is the provider id; it matches `[a-z0-9-]{1,32}`). `kind` is `google`, `microsoft` or `generic`. `clientId` and `clientSecret` are required; the secret is a reference (`env:` or `file:` only, resolved at startup and again at use, never logged). `label` is the name shown on the linking page (default the id). `google` and `microsoft` have fixed endpoints, scopes and IMAP host (`imap.gmail.com`, `outlook.office365.com`) and refuse `authorizationEndpoint`, `tokenEndpoint`, `scopes` and `imap`; `microsoft` takes `tenant` (`common`, `organizations`, `consumers` or a GUID; default `organizations`). `generic` needs `authorizationEndpoint` and `tokenEndpoint` (https, the token host a DNS name), `scopes` and `imap` (`host`, `security` `implicit-tls` or `starttls`, `port` 993 or 143 to match). The IMAP host of every provider must also match `ssrf.allowedHosts` (or `ssrf.allowAnyPublicHost`), or startup fails.

```yaml http-public-upstream
  upstream:
    providers:
      google:
        kind: google
        clientId: google-client-id-example
        clientSecret: env:GOOGLE_CLIENT_SECRET
      microsoft:
        kind: microsoft
        tenant: organizations
        clientId: microsoft-client-id-example
        clientSecret: file:/run/secrets/microsoft_client_secret
        label: Microsoft 365
```

  (This goes under `oauth:` of the example above; add `imap.gmail.com` and `outlook.office365.com` to `ssrf.allowedHosts`.)
- **Redirect URI:** register `<public.baseUrl>/accounts/oauth/<id>/callback` (for example `https://mail.example.com/accounts/oauth/google/callback`) with the provider. It is derived from `public.baseUrl` and the id and is not configurable.
- **Linking:** the user opens `/accounts`, picks the provider, gives a name, the mail address (only a hint for the sign-in) and read-only or read and write, and signs in at the provider. Nothing is saved unless the exchange and an IMAP XOAUTH2 login succeed. The refresh token is stored as a credential, encrypted like a password.
- **Startup WARN:** every `google` or `microsoft` provider logs `Upstream OAuth provider <id> is not verified against the real provider`; and if stored accounts use a provider that is no longer configured, one WARN gives their number.
- **Re-link:** when the provider refuses the grant (`invalid_grant`), the tool call fails with `AUTH_FAILED` `relink_required` and the `link_url`; on the account's page "Authorize again" repeats the sign-in for the same account (server and address cannot be changed; unlink and add it again for that). A commit that fails after the provider rotated the refresh token loses the grant, so a re-link is needed; it is counted in the metric `oauth_grant_lost`.
- **Operator errors:** `oauth_client_rejected` (the provider refused the server's OAuth client: check the registration and the secret), `provider_unconfigured` (the account's provider id is not in the config), `token_refresh_unavailable` (the token endpoint is down; retryable), `xoauth2_rejected` (the IMAP server refused or does not offer XOAUTH2). See "Errors".

## Scope by phase

v1 read-only tools, v2 reversible changes (set_flags, move_messages, copy_messages, delete_messages and create_folder; delete moves to Trash), v3a gated deletion from Trash (`purge_messages`, `stdio`; v3b: also `http-public` with `purge.enabled`) and `download_attachment`, v3b public multi-tenant mode. Details in SPEC sections 3 and 4.

## Coverage

Unit tests (surefire) and integration tests (failsafe), including the shaded-jar subprocesses started by the ITs, are measured with JaCoCo and aggregated over all code modules:

```
mvn clean verify site
```

Report: `imap-mcp-coverage/target/site/jacoco-aggregate/index.html`. There are no coverage thresholds. The Docker image IT is not measured (the same server code is covered by the http-local subprocess ITs). `-Djacoco.skip=true` switches the agent off.
