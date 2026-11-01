# IMAP MCP Server: Specification

Status: draft v0.1. Audience: implementation lead and team, who break this down into work items.

## 1. Purpose and scope

An MCP server that gives an LLM client access to IMAP mailboxes: search, read, and (in later phases) organize mail.

- **In scope:** IMAP only.
- **Out of scope for now:** SMTP/sending, drafts, contacts, calendars. Design decisions optimize for IMAP and ignore later SMTP needs.

## 2. Runtime modes

One codebase and one shaded jar, with the same tool code in every mode. Tools never know which mode they run in; they receive an `AccountContext`.

| Mode | Transport | Identity | Auth to server | Persistence |
|---|---|---|---|---|
| `stdio` | stdio, client spawns the process | fixed single tenant | none (process boundary) | config file + secrets from env / mounted file / OS keyring |
| `http-local` | Streamable HTTP, stateless, loopback, usually in Docker | fixed single tenant | static bearer token (random on first start if unset) | as `stdio` |
| `http-public` | Streamable HTTP, stateless, behind TLS reverse proxy | per request, from validated OAuth token | OAuth 2.1 resource server | Postgres |

`http-public` is what a Claude connector needs. It is not part of v1 (see section 3).

## 3. Phasing

| Phase | Content |
|---|---|
| **v1** | Modes `stdio` and `http-local`. Read-only tools. Generic IMAP with password / app password. |
| **v2** | Reversible change tools (flags, move, copy, create folder). |
| **v3** | Gated permanent deletion. Mode `http-public` (multi-tenant, OAuth, account linking, Postgres). Provider matrix: generic IMAP first; Gmail/Microsoft 365 upstream OAuth only after a compliance review. |
| **Later** | SMTP, drafts. |

The multi-tenant design (section 5.2) is applied from v1; only its implementations arrive later.

## 4. Tools

All tools require an account handle (from `list_accounts`) except `list_accounts` itself. All message references are signed handles (section 5.4). Every tool sets MCP annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`) and returns structured output.

### v1: read-only

| Tool | Notes |
|---|---|
| `list_accounts` | Linked mailboxes and capability summary. |
| `list_folders` | Includes special-use role (`\Trash`, `\Sent`, ...), unread and total counts. |
| `search_messages` | Paged summaries (sender, subject, date, flags, size, has-attachment). Criteria: from, to, subject, text, date range, `since`, flags, folder. Gmail `X-GM-RAW` when supported. Signed cursor. |
| `get_message` | Text body by default (HTML converted to text), attachment list, optional headers. Character budget with `truncated` flag and continuation offset. Body returned as clearly labeled untrusted data. |
| `list_attachments` | Metadata only (name, type, size) in v1. |

### v2: reversible changes

| Tool | Notes |
|---|---|
| `set_flags` | Add/remove flags on a list of handles (read/unread, flagged, ...). |
| `move_messages` | List of handles, target folder. Returns new handles. |
| `copy_messages` | As above. |
| `delete_messages` | Moves handles to the `\Trash` folder. Not a permanent delete. |
| `create_folder` | |

### v3: gated

| Tool | Notes |
|---|---|
| `purge_messages` | Permanent removal of explicit handles via UID EXPUNGE. Off by default; enabled by config (per tenant in `http-public`). Refused when the server lacks UIDPLUS. |
| `download_attachment` | stdio/`http-local`: write to a configured directory with sanitized file names. `http-public`: embedded resource or resource link with a size cap. |

### Tool rules

- Mutating tools take explicit lists of handles, never "all matching".
- Bulk size is capped.
- Errors are actionable: stale reference ("re-run search"), account needs re-linking, server busy (retry).
- No push or IDLE. "What's new" is a search with `since`.
- Dropped from the original idea list: delete folder, quota, save draft.

## 5. Architecture

### 5.1 Stack

- Java 25 (virtual threads without pinning on blocking I/O in `synchronized`).
- Official MCP Java SDK, Jakarta Mail (Angus Mail, incl. `gimap` provider).
- No Jakarta EE app server. Embedded Jetty 12 (ee10 servlet) only in HTTP modes, using the SDK's servlet transport.
- Manual wiring or a small DI library. No JPA.
- Nimbus JOSE+JWT for token validation (`http-public`). Maven multi-module build, one shaded jar with two entry points.

### 5.2 Modules

| Module | Responsibility |
|---|---|
| `imap-mcp-core` | Tool definitions and logic, IMAP access, pooling, handles/cursors, `AccountContext`, `CredentialStore` and `AccountRepository` interfaces. No transport or Jetty dependency. |
| `imap-mcp-store` | `CredentialStore` / `AccountRepository` implementations: file-based (v1), JDBC + Flyway (v3). |
| `imap-mcp-transport-stdio` | stdio transport setup, stdout hygiene. |
| `imap-mcp-transport-http` | Jetty, Streamable HTTP, bearer-token auth (local), Origin/Host validation; OAuth resource server (v3). |
| `imap-mcp-app` | Entry points, config loading, wiring, shaded jar, Docker image. |
| `imap-mcp-it` | End-to-end and integration tests. |

Jetty must stay off the classpath of the stdio entry point.

### 5.3 Tenancy and security

- `AccountContext` is derived only from the validated principal (fixed in `stdio` / `http-local`). Tools receive opaque account handles resolved via a tenant-scoped repository (`findByTenantAndHandle`). Pool keys are internal `(tenantId, accountId)`, never client-supplied.
- Prompt injection: email content is untrusted. Return it as structured, labeled data; destructive tools are off by default.
- SSRF (multi-tenant): resolve the IMAP host once, block loopback, link-local, RFC1918 and metadata addresses, pin the resolved IP, allow only 993 (implicit TLS) or 143 with mandatory STARTTLS (`mail.imaps.starttls.required`). Provider allowlist for the public deployment.
- `http-local`: bind to loopback only, validate `Origin` and `Host`, constant-time bearer token comparison.
- Credentials: never in the image. Env, Docker secret, mounted file, or keyring.
- `http-public` only (v3):
  - Account linking is a separate onboarding flow. Claude's token identifies the user, not the mailbox. No token passthrough.
  - Authorization server is delegated (Keycloak, Zitadel, Auth0 or Entra); this server is a resource server only (RFC 9728 metadata, `401` with `WWW-Authenticate`, JWT checks incl. `aud`, RFC 8707). Must verify the chosen IdP supports client registration for Claude and sets the audience.
  - Credentials encrypted at rest: AES-256-GCM envelope encryption, key wrapped by KMS/Vault, ciphertext bound to `tenant_id||account_id` as AAD.
  - Audit log of mutating calls (tenant, tool, handles, result; never content).

### 5.4 IMAP semantics

- Message handle = HMAC-signed `(account, folder full name, UIDVALIDITY, UID)`; the tenant is inside the signed data. Never use message sequence numbers. UIDVALIDITY mismatch is an error ("stale reference"). Do not keep `Message` objects between calls.
- Never issue a plain `EXPUNGE`. Use `UID EXPUNGE` for exact UIDs only.
- Move: MOVE if available, else COPY + `\Deleted` + UID EXPUNGE of those UIDs. Return new UIDs (COPYUID/APPENDUID). A retried move that finds the source gone reports success with a note.
- Paging: signed cursor (UIDVALIDITY, last UID, query hash), default page size 25, max 100. One `FetchProfile` round-trip per page. Report total count.
- Fetch with PEEK only; open folders read-only for read tools; close with `folder.close(false)`.
- Body output: pick the text part via BODYSTRUCTURE, partial fetch, default budget of 20k characters, always with `truncated` and a continuation offset.
- Resolve folders by SPECIAL-USE attributes, never by name (Gmail localizes names). Detect capabilities at connect time (MOVE, UIDPLUS, SORT, SPECIAL-USE, `X-GM-EXT-1`), cached per account with a TTL, and gate features on them.

### 5.5 Connection pooling

- Commons Pool 2 `GenericKeyedObjectPool`, keyed by `(tenant, account)`. Pool `IMAPStore` instances, leased exclusively per tool call. Never share a Store or Folder across concurrent calls.
- Set `mail.imaps.connectionpoolsize=1` so one Store means one socket. Use one `Session` per account, never `getDefaultInstance`.
- Property prefix must match the protocol: `mail.imaps.*` for `imaps` (`mail.imap.*` is ignored).
- Defaults (all configurable):

| Setting | Default |
|---|---|
| Idle eviction | 5 min |
| Validate (NOOP) before reuse if idle longer than | 60 s |
| Max connection lifetime | 30 min |
| Max connections per mailbox | 3 (1–2 when running several replicas) |
| Acquire timeout | 10 s |
| Connect / read / write timeout | 10 s / 60 s / 60 s |
| Overall per-tool deadline | 90 s |

- Per-tenant semaphore and global socket cap. Fail fast with "busy, retry" instead of queueing.
- On timeout, cancellation, `FolderClosedException` or `StoreClosedException`: discard the connection, never return it to the pool. MCP cancellation closes the leased Store.
- XOAUTH2 (v3): cache the access token, refresh about 5 min early, single-flight per account, one retry on `AUTHENTICATIONFAILED`, mark the account "needs re-link" when the refresh token is revoked.
- Virtual threads for request handling. Bound IMAP concurrency with semaphores, not thread pools.

### 5.6 Logging and observability

- Logback to file only. Capture the real stdout for the protocol, then `System.setOut(System.err)`. Pass the captured stream explicitly to the SDK's stdio transport. Logback `NopStatusListener`. Bridge JUL to SLF4J.
- Jakarta Mail debug output routed to the logger with `LOGIN`/`AUTHENTICATE` lines redacted.
- Never log message bodies, credentials, subjects, or addresses. Structured logs carry request ID, hashed tenant, tool name.
- Metrics per hashed tenant: pool active/idle/wait, IMAP command latency, auth failures. Health endpoint in HTTP modes.

### 5.7 Persistence

- v1: no database. Config file (accounts, hosts, policies) and secrets from env / mounted file / keyring, behind the `CredentialStore` and `AccountRepository` interfaces.
- v3: Postgres via plain JDBC or jOOQ with Flyway. Tables: `tenant` (IdP `iss` + `sub`), `mailbox_account`, `credential` (encrypted blob, key id, version).

### 5.8 Testing

- GreenMail for fast tests. Dovecot via Testcontainers for protocol fidelity (MOVE, UIDPLUS, SPECIAL-USE, SORT, UIDVALIDITY change). Toxiproxy for timeouts and dropped connections (pool eviction and discard paths).
- End-to-end with the SDK's `McpClient`: stdio via the shaded jar as a subprocess, asserting every stdout line is valid JSON-RPC; Streamable HTTP with real tokens (v3: Keycloak container; wrong audience, expired, other tenant).
- Tenant-isolation suite: swapped/forged account handles, tampered or replayed cursors and handles, concurrent load from one tenant not affecting another's pool, credential copied between tenants fails to decrypt.
- Periodic manual or nightly smoke test against real Gmail and Microsoft 365 accounts.

## 6. Open decisions

- Final package and groupId naming (currently `de.alexanderlindhorst.imapmcp`, placeholder).
- Config file format and location conventions.
- Which IdP for `http-public`, and whether Gmail/Microsoft 365 upstream OAuth is in the first public release.
