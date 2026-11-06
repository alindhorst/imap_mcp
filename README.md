# IMAP MCP

An [MCP](https://modelcontextprotocol.io) server that lets an LLM client search, read and organize mail in IMAP mailboxes. Changes are reversible, except the gated permanent deletion from Trash that the user confirms each time. Java 25, official MCP Java SDK, Jakarta Mail (Angus Mail). IMAP only; sending mail is out of scope for now.

> **Status:** phase v3a: read-only tools, reversible write tools (enabled by default, turned off per account by `readOnly`), `download_attachment` (needs `tools.download.dir`) and `purge_messages` (needs `allowPurge`, `stdio` only), with `stdio` and `http-local`. `http-public` (v3b) is available as described below; the stateful transport for `purge_messages` and the linking surface follow; see "Scope by phase".

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
- `purge_messages`: permanently deletes up to 100 messages from the Trash folder, Trash only, and cannot be undone; only for accounts with `allowPurge`, in `stdio` only, and the user confirms each call (or allows it for the session).
- `download_attachment`: saves one attachment as a new file in `tools.download.dir`, never overwriting; registered only when `tools.download.dir` is set; allowed on `readOnly` accounts (it does not change the mailbox).

## Documentation

- [`doc/SPEC.md`](doc/SPEC.md): scope, tools, phasing, architecture. Start here.
- [`doc/TUTORIAL.md`](doc/TUTORIAL.md): getting started (build, config, keyring, stdio and HTTP setup, pitfalls).

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
| `IMAP_MCP_MODE` | `stdio` or `http-local` (if `--mode` is absent) |
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
- `purge_messages` is registered in `stdio` only and not available in `http-local` (it needs a server that can ask the client a question). An `allowPurge` account in `http-local` only gives a startup warning, so one config file serves both modes.
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

`imap.trace: true` in the config switches on the Jakarta Mail protocol trace. It is a diagnostic opt-in, off by default. WARNING: with the trace on, the log file may contain message content (subjects, addresses, bodies); credentials stay redacted. Enable it only to diagnose a problem and disable it after diagnosis.

### Safety and upgrading

- Email content is untrusted input (possible prompt injection) and is returned as labeled data.
- Write tools are enabled by default and make only reversible changes. `delete_messages` moves to Trash and never deletes permanently; on servers without MOVE the move is done as copy, then flag and expunge of exactly the copied messages in the source.
- Set `readOnly: true` on every account the agent must never change.
- Permanent deletion exists only as `purge_messages`: off unless an account has `allowPurge: true`, Trash only, `stdio` only, and the user confirms each call in the client.
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
| `ACCOUNT_NOT_FOUND` | no | unknown account handle |
| `INVALID_HANDLE` | no | unknown, malformed, tampered or foreign message handle (one identical payload for all of them) |
| `INVALID_CURSOR` | no | invalid cursor, or one used with other search criteria (`criteria_mismatch`) |
| `STALE_REFERENCE` | no | the folder changed since the handle was issued (`details.reason` `uidvalidity_changed` or `folder_missing`); re-run the search |
| `MESSAGE_NOT_FOUND` | no | the message no longer exists |
| `FOLDER_NOT_FOUND` | no | the folder (or the Trash role folder) does not exist |
| `AUTH_FAILED` | no | the IMAP server refused the login |
| `SERVER_BUSY` | yes | pool or server limit reached; wait `retry_after_ms` (2000) |
| `TIMEOUT` | yes | `tools.deadline` exceeded |
| `UPSTREAM_UNAVAILABLE` | yes | the IMAP server is unreachable or dropped the connection |
| `UPSTREAM_ERROR` | no | the IMAP server refused the command |
| `UNSUPPORTED` | no | a needed capability is missing (for example `UIDPLUS`, `X-GM-EXT-1`, UTF-8 search); `download_attachment`: `transfer_encoding` or `decode_failed` |
| `INTERNAL_ERROR` | no | unexpected server error |
| `PERMISSION_DENIED` | no | `details.reason` is `account_read_only`, `folder_read_only`, `server_denied`, or for `purge_messages` `purge_disabled`, `purge_scope`, `confirmation_unavailable` or `not_confirmed` (`details.elicitation` is `declined`, `cancelled`, `timeout`, `invalid_answer`, `error` or `recently_declined`) |
| `QUOTA_EXCEEDED` | no | the mailbox is over quota; nothing is deleted permanently as a fallback |
| `OUTCOME_UNKNOWN` | no | `copy_messages` and `download_attachment`: the call was cut off after the first mutating command, so the copy may exist; repeating it could duplicate |
| `TOO_LARGE` | no | `download_attachment`: the attachment exceeds `tools.download.maxBytes` (`details.size`, `details.max`) |
| `LOCAL_STORAGE_ERROR` | no | `download_attachment`: the file could not be saved; `details.reason` is `directory_unavailable`, `name_exhausted`, `no_space` or `io_error` |

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

### Notes

- Angus Mail enables `UTF8=ACCEPT` by itself for any server that advertises `UTF8=ACCEPT` or `UTF8=ONLY` (not only Gmail; not configurable). Folder names a tool returns or accepts are the server-visible full names and round-trip verbatim either way.
- New-mail search (`since` / `as_of`) assumes the clocks of the MCP host and the IMAP server agree within 5 minutes.
- Security: publish on loopback only; `Host`/`Origin` checks and the bearer token apply; credentials are never in the image; literal passwords in the config are rejected; email content is untrusted input (possible prompt injection) and is returned as labeled data.

### http-public

The multi-tenant mode for a hosted deployment (what a Claude connector needs): `java -jar imap-mcp.jar --mode http-public --config <file>`. This server is an OAuth 2.1 resource server only; the identity provider (for example Keycloak) is separate and must put the resource URI into the access token's `aud` claim (RFC 8707). Mail accounts are linked per user in the linking surface, not in the config file; a configured `accounts` list fails startup.

```yaml http-public
db:
  url: jdbc:postgresql://db.internal:5432/imapmcp   # no password in the URL
  username: imapmcp
  password: env:DB_PASSWORD
oauth:
  issuer: https://idp.example.com/realms/mail        # https; keys are discovered from <issuer>/.well-known/openid-configuration
  resource: https://mail.example.com/mcp             # the canonical resource URI, expected in the token's aud
  requiredRole: imap-mcp-user                        # IdP role needed to get a tenant (or allowWithoutRole: true for development)
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

- **Startup fails** (one line on stderr naming the config key, never a value) without a database, without an explicit key ring, with an empty `ssrf.allowedHosts` unless `ssrf.allowAnyPublicHost: true`, with a non-https issuer or resource, with `tools.download.dir`, `maxBytes` or `displayDir`, with `http.token`, with a password in `db.url`, and with unknown keys. It also logs one fingerprint (12 hex characters) per handle key id and per KEK id, so replicas can be compared; key material is never logged.
- **Reverse proxy:** terminate TLS there and forward `Host`. `http.allowedHosts` defaults to the host of `public.baseUrl`; bind to a non-loopback address only with `http.allowNonLoopbackBind: true` (or `IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND`). With `http.stateful: true` (required for `purge.enabled`) a load balancer needs affinity on the `Mcp-Session-Id` header: the user's answer to the purge confirmation is a POST that must reach the replica whose call is waiting, and there is no shared session registry, so a request routed elsewhere gets `404` and the client initializes again (session approvals are lost). The proxy's read timeout must exceed `tools.deadline`, because the call's event stream stays open while the user answers. `purge_messages` stays off (`purge.enabled: false`) until the owner's smoke test with the Claude connector has passed (see the v3b smoke checklist).
- **Discovery and checks:** `GET /.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp` serve the RFC 9728 metadata; a missing or invalid token gets `401` with a `WWW-Authenticate` challenge naming it. The scope `imap-mcp:read` is required for every request (`403 insufficient_scope` otherwise); `imap-mcp:write` is checked per call for the write tools. Tokens are accepted in the `Authorization` header only.
- **Tenants:** a user who is not yet known sees no accounts (`list_accounts` returns an empty list and the `link_url`); no MCP request creates a tenant. A tenant is created at the first login of the linking surface, and only for users with `oauth.requiredRole`.
- **Tools:** one fixed list for everybody (read tools, `download_attachment` with the file embedded in the result, and the five write tools). `purge_messages` is not registered. Restrictions are refusals at call time: `account_read_only`, `insufficient_scope`, and `relink_required` (an account whose password was refused three times in a row; `list_accounts` shows it as `needs_relink` with the `link_url`).
- **Audit and retention:** every call of a write tool leaves a row in the database (reference hashes only, never folder names, UIDs or message handles); rows are deleted after 180 days. If the audit row cannot be written, the call fails and nothing is changed.
- **Egress:** the server blocks private and loopback addresses and any host outside `ssrf.allowedHosts`, and only connects to port 993 (implicit TLS) or 143 (STARTTLS). Add an egress network policy as well.
- **Key rotation:** add a handle key id or KEK id, switch `signingKeyId` or `currentKekId`, restart every replica, and remove the old id only when nothing refers to it any more (SPEC 5.4 and 5.7).

## Scope by phase

v1 read-only tools, v2 reversible changes (set_flags, move_messages, copy_messages, delete_messages and create_folder; delete moves to Trash), v3a gated deletion from Trash (`purge_messages`, `stdio` only) and `download_attachment`, v3b public multi-tenant mode. Details in SPEC sections 3 and 4.

## Coverage

Unit tests (surefire) and integration tests (failsafe), including the shaded-jar subprocesses started by the ITs, are measured with JaCoCo and aggregated over all code modules:

```
mvn clean verify site
```

Report: `imap-mcp-coverage/target/site/jacoco-aggregate/index.html`. There are no coverage thresholds. The Docker image IT is not measured (the same server code is covered by the http-local subprocess ITs). `-Djacoco.skip=true` switches the agent off.
