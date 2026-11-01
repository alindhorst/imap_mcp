# IMAP MCP

An [MCP](https://modelcontextprotocol.io) server that lets an LLM client search and read (later also organize) mail in IMAP mailboxes. Java 25, official MCP Java SDK, Jakarta Mail (Angus Mail). IMAP only; sending mail is out of scope for now.

> **Status:** specification phase. The module skeleton exists; no code yet. Commands and configuration names under "Running" describe the target behavior from `doc/SPEC.md` and will be confirmed as the implementation lands.

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

Requirements: JDK 25, Maven 3.9+, Docker (integration tests and container mode only).

```
mvn verify                          # build and test everything
mvn -pl imap-mcp-core -am test      # one module and what it depends on
```

The runnable shaded jar is produced by `imap-mcp-app`.

## Running

The same jar runs in three modes (see SPEC section 2):

| Mode | Use | How clients connect |
|---|---|---|
| `stdio` (dev, single user) | Claude Code / Desktop spawn the process | MCP client config with `java -jar ... --mode stdio` |
| `http-local` (single user, container) | Long-running local server, warm JVM | `http://127.0.0.1:<port>/mcp` with a bearer token |
| `http-public` (production, multi-tenant, later phase) | Claude connector behind a public URL | OAuth 2.1; needs TLS reverse proxy, IdP, Postgres |

### stdio

Configure the MCP client to launch the jar. Account settings come from a config file; secrets from environment variables, a mounted file, or the OS keyring (never from the image).

### http-local (Docker)

```
docker run -d --restart unless-stopped \
  -p 127.0.0.1:8080:8080 \
  -v imap-mcp-config:/config -v imap-mcp-logs:/logs \
  imap-mcp
```

Publish the port on loopback only. The server generates a bearer token on first start if none is configured, and validates `Origin`/`Host`. The mailbox is reachable only with that token. Claude Code can add it with `claude mcp add --transport http`.

### http-public

Planned for phase v3 (multi-tenant, OAuth resource server, account linking, Postgres). Not available yet. See SPEC sections 3 and 5.3.

## Scope by phase

v1 read-only tools, v2 reversible changes (flags, move), v3 gated deletion and public multi-tenant mode. Details in SPEC sections 3 and 4.
