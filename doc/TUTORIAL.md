# Getting started

A walk-through from a fresh checkout to a working `imap-mcp` server in Claude Code. Reference material (all options, defaults, environment variables) is in the [README](../README.md); scope and architecture are in [SPEC.md](SPEC.md). Placeholders such as `<version>`, `<user>` and `imap.example.com` must be replaced.

## 1. Build

Requirements: JDK 25 or newer (the project is built and tested with JDK 26) and Maven 3.9.6+. Set `JAVA_HOME` explicitly, because the default `mvn` may run another JDK:

```
export JAVA_HOME=/usr/lib/jvm/java-26
mvn -pl imap-mcp-app -am -DskipTests package
```

The runnable jar is `imap-mcp-app/target/imap-mcp-app-<version>-all.jar`. Use its absolute path in the commands below. A plain `mvn package` (all modules) also works once PR #162 is merged; it runs all tests, so it takes longer.

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

A long-running local server on `127.0.0.1:8080`, secured by a bearer token.

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

Add the server to Claude Code with the token:

```
claude mcp add --transport http imap-mcp http://127.0.0.1:8080/mcp --header "Authorization: Bearer <token>"
```

## 6. After adding

1. Restart Claude Code (or reconnect the server), then run `/mcp` and check that `imap-mcp` is connected.
2. Try a first prompt, for example: "List my mail accounts, then show the folders of the first one."
3. If it does not connect, look at the log file `<state>/logs/imap-mcp.log` (default `~/.local/state/imap-mcp/logs/imap-mcp.log`; `IMAP_MCP_STATE_DIR` and `IMAP_MCP_LOG_DIR` move it). A startup failure (bad config, unreadable password, locked keyring) is written as one line `imap-mcp: startup failed: ...` to stderr and, as soon as logging is up, also to that log file. In stdio mode stdout carries only the protocol, so the log file is the reliable place to look; the stderr line shows up in the client's MCP log if it keeps one. The logs never contain passwords, message bodies, subjects or addresses.
4. `imap.trace: true` switches on the IMAP protocol trace. It can write message content (subjects, addresses, bodies) to the log; credentials stay redacted. It is off by default; enable it only to diagnose a problem and disable it afterwards.

## 7. Pitfalls at a glance

- Account `id`: only `[a-z0-9-]`, 1 to 32 characters (no underscores).
- Replace `imap.example.com` and the example user names.
- Passwords are references (`keyring:`, `env:`, `file:`), never literals.
- `keyring:<service>/<account>` finds only entries stored with `service` and `account` attributes.
- Keyring: unlocked, D-Bus session present, resolved once at startup with a timeout; not available in Docker.
- stdio: keep both `-Xlog` flags, use absolute paths, put `-s` and `-e` before `--`.
- Restart Claude Code after `claude mcp add`; check `/mcp`, then the log file.
- HTTP: the token is in `<state>/http-token`; publish the port on loopback only.
