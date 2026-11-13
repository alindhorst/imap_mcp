# Getting started

A walk-through from a fresh checkout to a working `imap-mcp` server in Claude Code. Reference material (all options, defaults, environment variables) is in the [README](../README.md); scope and architecture are in [SPEC.md](SPEC.md). Placeholders such as `<version>`, `<user>` and `imap.example.com` must be replaced.

## Which mode do you need?

The server runs in one of three modes. Pick first, then read only the matching section.

| You want | Mode | Users | Whose mailboxes | Read |
|---|---|---|---|---|
| Claude Code or Desktop on your own machine, simplest | `stdio` | one (you) | the accounts in your config file | sections 1 to 4 |
| A long-running local server (warm JVM, container) for yourself | `http-local` | one (you) | the accounts in your config file | sections 1 to 3, then 5 |
| A hosted server for several people, for example as a Claude connector | `http-public` | many | each user links their own accounts; nobody sees another user's mail | section 6, then [DEPLOYMENT.md](DEPLOYMENT.md) |

**Tenants and mailboxes.** In `stdio` and `http-local` there is exactly one tenant (internally `local`), and every account in the config file belongs to it. Only `http-public` has real multi-tenancy: each user (identified by the identity provider's token) is a tenant with a private list of IMAP accounts. Tenants do not share a mailbox and there is no shared account; if two people want the same mailbox, each links it separately. In `http-public` the config file contains no accounts at all.

Sections 2 and 3 (config file, keyring) apply to `stdio` and `http-local` only.

## 1. Build

Requirements: JDK 25 or newer (the project is built and tested with JDK 26) and Maven 3.9.6+. Set `JAVA_HOME` explicitly, because the default `mvn` may run another JDK:

```
export JAVA_HOME=/usr/lib/jvm/java-26
mvn -pl imap-mcp-app -am -DskipTests package
```

The runnable jar is `imap-mcp-app/target/imap-mcp-app-<version>-all.jar`. Use its absolute path in the commands below. A plain `mvn package` builds all modules as well; it also runs the unit tests, so it takes longer. Note that `-DskipTests` does not skip the integration tests under `mvn verify`, and any of `-DskipTests`, `-DskipITs` and `-Dmaven.test.skip` makes `mvn verify` fail unless you also pass `-Dimapmcp.partialBuild=true` (`package` never reaches that check or the integration tests).

## 2. Config file

The server reads one YAML file. The location is, in this order: `--config <file>`, then the environment variable `IMAP_MCP_CONFIG`, then `$XDG_CONFIG_HOME/imap-mcp/config.yaml` (when absolute), then `~/.config/imap-mcp/config.yaml`.

```yaml
accounts:
  - id: private-alexander-lindhorst
    host: imap.example.com
    username: me@example.com
    password: keyring:imap-mcp/me@example.com
```

Things to get right:

- **The account `id` must match `[a-z0-9-]{1,32}`**: lowercase letters, digits and hyphens only, at most 32 characters. An underscore is the classic mistake:

```yaml invalid-id
accounts:
  - id: private-alexander_lindhorst
    host: imap.example.com
    username: me@example.com
    password: keyring:imap-mcp/me@example.com
```

  The server then refuses to start with `accounts[0].id: must match [a-z0-9-]{1,32}`. Use hyphens, as in the first example.
- Replace the placeholder host `imap.example.com` and the user name with your own.
- Unknown keys are rejected, and a literal password is rejected. The password is always a reference: `keyring:<service>/<account>`, `env:NAME` or `file:/absolute/path`.

## 3. Password from the Linux keyring

For `keyring:<service>/<account>` the server runs exactly `secret-tool lookup service <service> account <account>` (no shell). The reference above therefore finds an entry with the attributes `service imap-mcp` and `account me@example.com`, and nothing else. Entries stored by other applications use other attributes (for example Evolution uses `xdg:schema org.gnome.Evolution.Data.Source e-source-uid ...`) and are not found.

Store a new entry; `secret-tool` prompts for the password, so it never lands in your shell history:

```
secret-tool store --label='imap-mcp: work' service imap-mcp account me@example.com
```

Copy an existing entry without ever displaying it. Replace `<their attributes>` with the attribute pairs the other application used (list them with `secret-tool search --all <attribute> <value>`); the secret travels through the pipe only:

```
secret-tool lookup <their attributes> | secret-tool store --label='imap-mcp: work' service imap-mcp account me@example.com
```

Check that the entry is found, without printing it:

```
secret-tool lookup service imap-mcp account me@example.com >/dev/null && echo found
```

The matching config line is `password: keyring:imap-mcp/me@example.com`.

Pitfalls:

- The keyring must be unlocked when the server starts.
- A D-Bus session is required. A plain SSH or other headless session usually has none, and `secret-tool` fails.
- The reference is resolved once at startup (eagerly, and held in memory), with a timeout of 5 seconds by default (`secrets.keyringTimeout`). A locked keyring or an unanswered unlock prompt therefore shows up as a startup error, not on a later tool call.
- It does not work in Docker: the image has neither D-Bus nor `secret-tool` (error: `keyring not available here (no secret-tool/security); use env:, a Docker secret or file:`).
- Alternatives: `env:NAME` (the variable must be in the server's environment) and `file:/absolute/path`; both are read when a connection is made. macOS has its own `security` command, see the README.

An `env:` example:

```yaml
accounts:
  - id: work
    host: imap.example.com
    username: work@example.com
    password: env:WORK_IMAP_PASSWORD
```

## 4. stdio mode (Claude Code, Claude Desktop)

In stdio mode the client starts the jar itself and talks to it over stdin/stdout. Claude Code:

```
claude mcp add imap-mcp -s user -- java -Xlog:disable -Xlog:all=warning:stderr -jar /path/to/imap-mcp-app-<version>-all.jar --mode stdio --config /path/to/config.yaml
```

- The two `-Xlog` flags keep JVM logging off stdout, which carries the protocol. Without them a JVM message can corrupt the stream.
- `-s user` makes the server available in all your projects (the default scope is `local`, the current project only).
- `--config` can be left out if the file is in the default place.
- Secrets referenced as `env:NAME` must reach the server process. Pass them with `-e NAME=<value>`. `-e` takes several values, so keep it after the server name and before `--`:

```
claude mcp add imap-mcp -s user -e WORK_IMAP_PASSWORD=<app password> -- java -Xlog:disable -Xlog:all=warning:stderr -jar /path/to/imap-mcp-app-<version>-all.jar --mode stdio --config /path/to/config.yaml
```

  The value is stored in Claude's own configuration; keyring references avoid that.
- `keyring:` references need the keyring of the user session that starts Claude Code.

Claude Desktop (`claude_desktop_config.json`):

```json
{
  "mcpServers": {
    "imap-mcp": {
      "command": "java",
      "args": ["-Xlog:disable", "-Xlog:all=warning:stderr", "-jar", "/path/to/imap-mcp-app-<version>-all.jar", "--mode", "stdio", "--config", "/path/to/config.yaml"]
    }
  }
}
```

## 5. HTTP mode (`http-local`)

A long-running local server on `127.0.0.1:8080`, secured by a bearer token. It serves one user: all accounts of the config file belong to the single local tenant, and anyone holding the token sees all of them. Use `http-public` (section 6) if several people need separate mailboxes.

Without Docker, start the jar yourself:

```
java -jar /path/to/imap-mcp-app-<version>-all.jar --mode http-local --config /path/to/config.yaml
```

The token is generated on first start and written to `<state>/http-token` (default `~/.local/state/imap-mcp/http-token`), unless `http.token` is configured as a secret reference. Here `keyring:` works, because the process runs in your session.

With Docker, build the image with `mvn -B -Pdocker -pl imap-mcp-app -am package -DskipTests`, put `config.yaml` into the `/config` volume and run:

```
docker run -d --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -v imap-mcp-config:/config -v imap-mcp-logs:/logs \
  imap-mcp
docker exec <container> cat /config/state/http-token
```

There is no keyring in the container: use `file:` (Docker secrets, mounted files) or `env:`. Publish the port on host loopback only.

Three things regularly go wrong on a first Docker start (full detail in the README, "Passing `env:` secrets to the container" and "Host directories: ownership and SELinux"):

- **`environment variable NAME is not set or empty`** with `password: env:NAME`. The variable must reach the container: `docker run -e NAME ...` (or `--env-file`). `-e NAME` copies the value from the shell that runs `docker`, so it must be exported there, and `sudo docker run -e NAME` loses it because `sudo` resets the environment (also for root). Run `docker` without `sudo`, or use `--env-file /root-owned/file` (mode 0600, outside `/config`). Check with `docker inspect -f '{{.Config.Env}}' <container>`.
- **`Cannot read or create the bearer token at /config/state/http-token`.** The container runs as uid 10001 and cannot write to your host directory. Run `mkdir -p <config>/state && chown 10001 <config>/state`, and give `/logs` a dedicated directory owned by 10001 (not `/var/log` itself).
- **"Permission denied" on a bind mount although ownership is right** (SELinux enforcing, the default on Fedora and RHEL). Add `:Z` to the mount: `-v /etc/imap-mcp:/config:Z -v /var/log/imap-mcp:/logs:Z`. Use it only on directories dedicated to the container, never on `/var/log`, `/etc` or a home directory.

A failed container restarts in a loop with `--restart unless-stopped`; `docker rm -f` it before retrying.

Add the server to Claude Code with the token:

```
claude mcp add --transport http imap-mcp http://127.0.0.1:8080/mcp --header "Authorization: Bearer <token>"
```

## 6. Public multi-tenant mode (`http-public`)

This is the mode for several users. Each user logs in through an identity provider and links their own IMAP accounts in a small web page; the server keeps the (encrypted) credentials in Postgres. Nothing from sections 2 and 3 applies: there is no `accounts:` list (a config with one fails startup) and no keyring.

### How tenants and mailboxes map

- **Tenant = one user.** The tenant is identified by the issuer and subject claim of the user's token. A tenant is created at the user's first login at `https://<host>/accounts`, and only if the user has the IdP role named by `oauth.requiredRole`. A request to `/mcp` never creates one; a user without a tenant sees an empty `list_accounts` plus a `link_url`.
- **Mailbox = an account owned by exactly one tenant.** The user links accounts (label, IMAP host, port, user name, password) at `/accounts`, by default up to 10. Each account has its own credentials and pooled connections. The IMAP host must be on the operator's `ssrf.allowedHosts` list.
- **No sharing.** A tenant can only address its own accounts. Account handles and message handles of another tenant are refused (`ACCOUNT_NOT_FOUND` and `INVALID_HANDLE` respectively).
- **Per account switches** are set by the user while linking: `read_only` (default on) and `allow_purge` (default off, and only possible when the operator enabled `purge.enabled`).

### What you need

| Part | Role |
|---|---|
| This server (`--mode http-public`) | OAuth 2.1 resource server; serves `/mcp` and the linking pages `/accounts` |
| Postgres | tenants, accounts, encrypted credentials, web sessions, audit rows |
| Identity provider (Keycloak is the tested one) | logs users in and issues access tokens whose `aud` is the server's `/mcp` URL |
| TLS reverse proxy | public https in front of the server, forwarding `Host` unchanged |
| Key material | handle signing keys (`security.handleKeys`) and a key-wrapping key (`kms`: local KEK or Vault Transit) |

### Steps

1. **Easiest path, a ready test stack:** `dist/docker` contains Postgres, a Keycloak realm, the app and an optional Caddy proxy with Let's Encrypt. Follow [`dist/docker/README.md`](../dist/docker/README.md) (commands) and [DEPLOYMENT.md](DEPLOYMENT.md) (what to provide, checks in order). `setup.sh` generates the keys and the config for you.
2. **Own deployment:** write the config as in the README section [http-public](../README.md#http-public) (`db`, `oauth`, `public.baseUrl`, `security.handleKeys`, `ssrf.allowedHosts`, `kms`), configure Keycloak as in [Keycloak setup for the Claude connector](../README.md#keycloak-setup-for-the-claude-connector) (two clients: the public one of the MCP client, the confidential web client of the linking page), then start `java -jar imap-mcp-app-<version>-all.jar --mode http-public --config /path/to/config.yaml`. Startup fails with one line naming the offending config key, never a value.
3. **Give users the role** `oauth.requiredRole` (for example `imap-mcp-user`) in the identity provider.
4. **Each user links a mailbox:** open `https://<host>/accounts`, log in, add the account (leave `read_only` on at first).
5. **Connect the client:** in Claude, add a custom connector with the URL exactly `oauth.resource` (ends in `/mcp`, no trailing slash). Claude Code is not supported in this mode. Grant both scopes (`imap-mcp:read`, `imap-mcp:write`) at the first authorization.
6. **Check:** ask "List my mail accounts"; you should see only the accounts you linked.

Differences from the single-user modes: no bearer token file, no `--config` accounts, `imap.trace` and `tools.download.dir` are refused, `download_attachment` returns the file inside the tool result, and `purge_messages` stays off until the operator enables `purge.enabled` together with `http.stateful`.

## 7. After adding

1. Restart Claude Code (or reconnect the server), then run `/mcp` and check that `imap-mcp` is connected.
2. Try a first prompt, for example: "List my mail accounts, then show the folders of the first one."
3. If it does not connect, look at the log file `<state>/logs/imap-mcp.log` (default `~/.local/state/imap-mcp/logs/imap-mcp.log`; `IMAP_MCP_STATE_DIR` and `IMAP_MCP_LOG_DIR` move it). A startup failure (bad config, unreadable password, locked keyring) is written as one line `imap-mcp: startup failed: ...` to stderr and, as soon as logging is up, also to that log file. In stdio mode stdout carries only the protocol, so the log file is the reliable place to look; the stderr line shows up in the client's MCP log if it keeps one. The logs never contain passwords, message bodies, subjects or addresses.
4. `imap.trace: true` switches on the IMAP protocol trace. It can write message content (subjects, addresses, bodies) to the log; credentials stay redacted. It is off by default; enable it only to diagnose a problem and disable it afterwards.

## 8. Write tools and read-only accounts

Besides the read tools the server offers five write tools: `set_flags`, `move_messages`, `copy_messages`, `delete_messages` and `create_folder`. They are on by default for every account. The switch is `readOnly: true` on an account; there is no other setting and no per-tool selection. This example has one writable and one read-only account:

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

Check the result after a restart:

- `tools/list` shows the five write tools. With `readOnly: true` on every account they are not listed at all.
- `list_accounts` shows `read_only` for each account.
- A write call on the read-only account (`archive` above) is refused with `PERMISSION_DENIED`; `details.reason` is `account_read_only`. Do not retry.

Write tools lengthen a call, so `tools.deadline` must be at least 30 seconds unless every account is read-only (the default is 90 seconds). A shorter value with a writable account is refused at startup:

```yaml invalid-deadline
tools:
  deadline: 20s
accounts:
  - id: work
    host: imap.example.com
    username: work@example.com
    password: env:WORK_IMAP_PASSWORD
```

The server then refuses to start with `tools.deadline: must be at least 30s while any account is not readOnly`. Raise the value to 30s or more, or mark every account `readOnly: true`.

Undo: `move_messages` and `delete_messages` return new handles for the moved messages. To undo, move back with the returned handles (`move_messages` with those handles and the original folder as `target_folder`). `delete_messages` moves into the Trash folder, so delete moves to Trash; `delete_messages` never deletes permanently (permanent deletion is the separate, gated `purge_messages`, next section).

A server without the UIDPLUS extension cannot return the new handles; `copy_messages`, `move_messages` and `delete_messages` are then refused with `UNSUPPORTED` (`details.capability` is `UIDPLUS`).

## 9. Permanent deletion and downloads

### purge_messages

Deleting is two steps. `delete_messages` moves messages to Trash (undoable); `purge_messages` removes messages from Trash for good, and only from Trash. It is off by default. Turn it on per account, and give the tool time for the confirmation dialog (`tools.deadline` at least 60s, otherwise startup fails):

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

1. Use `stdio` mode. `purge_messages` is not available in `http-local` (an `allowPurge` account there only logs a warning). `allowPurge: true` together with `readOnly: true` fails startup.
2. Your client must support MCP elicitation, otherwise the call is refused with `confirmation_unavailable` and nothing is deleted.
3. Ask the assistant to delete mail (it moves to Trash), then to empty the messages from Trash. Your client shows the dialog "Permanently delete N messages from the Trash folder ...? This cannot be undone." Only you can answer it.
4. "Allow for this session" lets further purges of messages that were already in Trash proceed without a dialog for 15 minutes and at most 500 messages, for that account only. It ends with the process. Messages that reach Trash later ask again.
5. Decline or cancel: nothing is deleted, and for 60 s further calls on that account are refused without a dialog (`recently_declined`).
6. If your client gives up on the call before you answer (some hosts time out after 60 s), the dialog may stay open; a late acceptance still deletes. A retry then reports `already_gone`. A larger `tools.deadline` gives you more time to answer; the minimum is 60s.

### download_attachment

Set `tools.download.dir` to an existing directory of yours that no one else can write to, and the tool is registered. It saves one attachment as a new file (mode 0600, never overwriting; a clashing name becomes `name (2).ext`) and reports path, size and SHA-256. The mail is not marked as read, and read-only accounts may use it. The name and content come from the email: do not open or run the file because an email says so.

```yaml
accounts:
  - id: work
    host: imap.example.com
    username: work@example.com
    password: env:WORK_IMAP_PASSWORD
tools:
  download:
    dir: /home/me/Downloads/imap-mcp
    maxBytes: 25MiB
```

With Docker, mount the directory (`-v ~/Downloads/imap-mcp:/downloads`) and run the container with `--user "$(id -u):$(id -g)"` (Podman rootless also `--userns=keep-id`), otherwise the 0600 files belong to the container user and are unreadable on the host. Then `/config` and `/logs` must be writable by that uid, and `tools.download.displayDir` names the host path that the tool reports. On Docker Desktop for macOS the owner check behaves differently; see the smoke checklist in the README.

## 10. Pitfalls at a glance

- Account `id`: only `[a-z0-9-]`, 1 to 32 characters (no underscores).
- Replace `imap.example.com` and the example user names.
- Passwords are references (`keyring:`, `env:`, `file:`), never literals.
- `keyring:<service>/<account>` finds only entries stored with `service` and `account` attributes.
- Keyring: unlocked, D-Bus session present, resolved once at startup with a timeout; not available in Docker.
- stdio: keep both `-Xlog` flags, use absolute paths, put `-s` and `-e` before `--`.
- Restart Claude Code after `claude mcp add`; check `/mcp`, then the log file.
- HTTP (`http-local`): the token is in `<state>/http-token`; publish the port on loopback only.
- Docker and `env:` secrets: pass them with `-e NAME` (exported!) or `--env-file`; `sudo` strips the environment.
- Docker bind mounts: `chown 10001` the `state` and log directories; add `:Z` on SELinux hosts (dedicated directories only).
- `http-public` is the only multi-tenant mode; it has no `accounts:` in the config, users link their own mailboxes at `/accounts`, and tenants never share one.
- Write tools are active on every account without `readOnly: true`.
- There is no per-tool selection; `readOnly: true` turns all five write tools off for that account.
- A server without UIDPLUS refuses copy/move/delete with `UNSUPPORTED`.
- `tools.deadline` below 30s fails at startup while any account is writable.
- `purge_messages` (permanent deletion) needs `allowPurge` set to true, `stdio` mode, a client with elicitation and `tools.deadline` of 60s or more; it works from Trash only.
- `download_attachment` exists only with `tools.download.dir`; in Docker run with `--user` or the files are unreadable on the host.
