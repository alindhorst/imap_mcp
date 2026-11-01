# IMAP MCP

An [MCP](https://modelcontextprotocol.io) server that lets an LLM client search and read (later also organize) mail in IMAP mailboxes. Java 25, official MCP Java SDK, Jakarta Mail (Angus Mail). IMAP only; sending mail is out of scope for now.

> **Status:** phase v1 (read-only tools; `stdio` and `http-local`). `http-public` and the later phases are not implemented; see "Scope by phase".

## Documentation

- [`doc/SPEC.md`](doc/SPEC.md): scope, tools, phasing, architecture. Start here.
- [`CLAUDE.md`](CLAUDE.md): rules for AI-assisted development in this repo.

## Project layout

```
imap-mcp-core             tools, IMAP access, pooling, handles, tenancy seam (no transport deps)
imap-mcp-store            credential/account storage (file; JDBC later)
imap-mcp-transport-stdio  stdio transport
imap-mcp-transport-http   Streamable HTTP via embedded Jetty
imap-mcp-app              entry points, config, wiring, shaded jar, Docker image
imap-mcp-it               end-to-end / container-based tests
doc/                      specification
```

## Conventions

- Maven multi-module, group `de.alexanderlindhorst.imapmcp` (placeholder, see SPEC section 6).
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

## Running

The same jar runs in two modes in v1 (see SPEC section 2):

| Mode | Use | How clients connect |
|---|---|---|
| `stdio` (dev, single user) | Claude Code / Desktop spawn the process | MCP client config with `java -jar ... --mode stdio` |
| `http-local` (single user, container) | Long-running local server, warm JVM | `http://127.0.0.1:<port>/mcp` with a bearer token |
| `http-public` (production, multi-tenant) | Not implemented; planned for phase v3 | OAuth 2.1 |

The mode comes from `--mode stdio|http-local` (fallback env `IMAP_MCP_MODE`). Without a mode the jar prints a usage text to stderr and exits with 2.

### Configuration

Accounts and options live in a YAML config file. Unknown keys are rejected. Passwords are never literals: use a secret reference, `env:NAME`, `file:/absolute/path` or `keyring:<service>/<account>` (literal passwords are rejected).

```yaml
accounts:
  - id: work
    host: imap.example.com
    username: work@example.com
    password: env:WORK_IMAP_PASSWORD
```

Optional per-account keys: `label`, `address`, `port` (default 993, or 143 with `starttls`), `security` (`implicit-tls` default, or `starttls`), `flavor` (`auto` default, `generic`, `gmail`). Every other option is optional; keys not listed below are rejected as unknown.

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
| `pool.acquireTimeout` | `10s` | wait for a free connection |
| `pool.capabilityTtl` | `10m` | cache time for server capabilities |
| `tools.deadline` | `90s` | time limit per tool call |
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

The reference is resolved once at startup, so unlock the keyring first. Headless or SSH sessions without a D-Bus session bus cannot use `secret-tool`. On macOS only ASCII passwords are supported in this version.

### IMAP protocol trace

`imap.trace: true` in the config switches on the Jakarta Mail protocol trace. It is a diagnostic opt-in, off by default. WARNING: with the trace on, the log file may contain message content (subjects, addresses, bodies); credentials stay redacted. Enable it only to diagnose a problem and disable it after diagnosis.

### Notes

- New-mail search (`since` / `as_of`) assumes the clocks of the MCP host and the IMAP server agree within 5 minutes.
- Security: publish on loopback only; `Host`/`Origin` checks and the bearer token apply; credentials are never in the image; literal passwords in the config are rejected; email content is untrusted input (possible prompt injection) and is returned as labeled data.

### http-public

Planned for phase v3 (multi-tenant, OAuth resource server, account linking, Postgres). Not available yet. See SPEC sections 3 and 5.3.

## Scope by phase

v1 read-only tools, v2 reversible changes (flags, move), v3 gated deletion and public multi-tenant mode. Details in SPEC sections 3 and 4.
