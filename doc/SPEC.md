# IMAP MCP Server: Specification

Status: draft v0.5 (v1 implemented; records the v1 contract as built, plus the QA hardening decisions). Audience: implementation lead and team, who break this down into work items.

## 1. Purpose and scope

An MCP server that gives an LLM client access to IMAP mailboxes: search, read, and (in later phases) organize mail.

- **In scope:** IMAP only.
- **Out of scope for now:** SMTP/sending, drafts, contacts, calendars. Design decisions optimize for IMAP and ignore later SMTP needs.

## 2. Runtime modes

One codebase and one shaded jar, with the same tool code in every mode. Tools never know which mode they run in; they receive an `AccountContext`.

| Mode | Transport | Identity | Auth to server | Persistence |
|---|---|---|---|---|
| `stdio` | stdio, client spawns the process | fixed single tenant | none (process boundary) | config file + secrets from env / mounted file / OS keyring |
| `http-local` | Streamable HTTP, stateless, loopback, usually in Docker (bind rules in 5.3) | fixed single tenant | static bearer token (random on first start if unset) | as `stdio` |
| `http-public` | Streamable HTTP, stateless, behind TLS reverse proxy | per request, from validated OAuth token | OAuth 2.1 resource server | Postgres |

`http-public` is what a Claude connector needs. It is not part of v1 (see section 3).

The mode is selected with `--mode stdio|http-local` (fallback `IMAP_MCP_MODE`); `http-public` is refused in v1. The single-tenant modes use the fixed tenant id `local`.

## 3. Phasing

| Phase | Content |
|---|---|
| **v1** | Modes `stdio` and `http-local`. Read-only tools. Generic IMAP with password / app password. OS keyring secrets via platform CLI (Linux `secret-tool`, macOS `security`; no Windows, not available in Docker). |
| **v2** | Reversible change tools (flags, move, copy, create folder). |
| **v3** | Gated permanent deletion. Mode `http-public` (multi-tenant, OAuth, account linking, Postgres) together with its SSRF protections (address blocking, IP pinning, port allowlist, provider allowlist). Provider matrix: generic IMAP first; Gmail/Microsoft 365 upstream OAuth only after a compliance review. |
| **Later** | SMTP, drafts. |

The multi-tenant design (section 5.2) is applied from v1; only its implementations arrive later.

## 4. Tools

All tools require an account handle (from `list_accounts`) except `list_accounts` itself. All message references are signed handles (section 5.4). Every tool sets MCP annotations (`readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`) and returns structured output (`structuredContent` plus one text block carrying the same JSON).

### v1: read-only

| Tool | Notes |
|---|---|
| `list_accounts` | Linked mailboxes with `status` and capability summary with detection time. Missing or expired capabilities are refreshed by a probe connection (connect, TLS, LOGIN; no folder opened); a failing account never fails the call and keeps its stale capabilities if cached. `status`: `ok` (capabilities fresh or probe succeeded), `auth_failed` (`AUTH_FAILED`), `unreachable` (`UPSTREAM_UNAVAILABLE`, `TIMEOUT` or `UPSTREAM_ERROR`: network, TLS, acquire timeout, no answer or server refusal), `unknown` (probe not completed for a local reason: `SERVER_BUSY`, `INTERNAL_ERROR` or any other code). |
| `list_folders` | Includes special-use role (`\Trash`, `\Sent`, ...), unread and total counts. Capped at 100 folders by default (configurable) with a truncation flag; folders beyond the cap stay addressable by full name. INBOX first, then code-point order of the full name. Counts: one `STATUS (MESSAGES UNSEEN)` per selectable listed folder, never SELECT/EXAMINE; a failed STATUS or counting stopped at 75 % of the remaining deadline leaves null counts and `counts_complete=false`. `folder` is the verbatim full name; `name` (last segment) is a sanitized display field. |
| `search_messages` | Paged summaries (sender, subject, date, flags, size, has-attachment). Criteria: from, to, subject, text (IMAP BODY), `date_from`/`date_to` (INTERNALDATE, inclusive, interpreted in the server's timezone), `since`, `seen`/`flagged`/`answered`/`draft`, keywords, and `folder` or `folder_role` (mutually exclusive, default INBOX). `\Deleted` messages are not filtered out. Gmail `X-GM-RAW` (`gmail_raw`) only for Gmail accounts (protocol `gimaps`) that advertise `X-GM-EXT-1`, otherwise `UNSUPPORTED`, checked before any folder command. Signed cursor. |
| `get_message` | Text body by default (HTML converted to text), attachment list, optional headers. Character budget with `truncated` flag and continuation offset (limits in 5.4). Caps: 100 addresses per recipient list; headers 100 fields, 2000 characters per value, 32 000 total (`headers_truncated`). |
| `list_attachments` | Metadata only in v1: section `part`, name, type, size, `inline`; at most 100 entries with `truncated` and the true total. |

`search_messages`, "what's new": `since` matches `received_at` (INTERNALDATE) `>= since`, exact to the second. The first page (call without `cursor`) returns `as_of` = MCP host time of the search, floored to the second, minus 300 s; cursor pages return `as_of: null`. Passing `as_of` as `since` with the same criteria yields mail received since that search; consecutive windows overlap on purpose, so clients deduplicate by handle. Messages moved or copied into the folder keep their received time and are not reported as new. A `since` search whose coarse day-bounded SEARCH exceeds `tools.sinceMaxCandidates` (default 10 000) hits is refused with `INVALID_ARGUMENT` (`too_many_candidates`). Operational assumption: host and IMAP server clocks agree within 5 minutes.

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
- Input validation is done by the server, not the SDK (SDK input validation is disabled because its error shape bypasses ours): JSON Schema with `additionalProperties: false`, snake_case fields, JSON `null` on an optional field treated as absent, values outside published bounds rejected (never clamped). Failures are tool errors `INVALID_ARGUMENT` with `details.field`, `details.reason` and, where applicable, `details.max`.
- Folder full names are limited to 1000 UTF-8 bytes (the handle limit, 5.4): a longer `folder` argument is `INVALID_ARGUMENT` (`out_of_range`, max 1000) before any IMAP I/O; a role resolving to a longer name is `UNSUPPORTED` (`folder_name_length`); `list_folders` lists such folders with `selectable=false`.
- Untrusted output: every email-derived string (subject, sender/recipient display names and addresses, envelope dates and IDs, header values, body text, attachment filenames and content types) is returned only inside `untrusted` objects, sanitized and length-capped (single-line except body text); every response that carries them includes a `content_notice`. Sanitizing follows a fixed Unicode 16 table: it removes format and default-ignorable characters (bidi controls and marks, zero-width characters, soft hyphen, the tag block and the rest of U+E0000..U+E0FFF) and noncharacters, removes variation selectors except one U+FE0E/U+FE0F directly after a base character, keeps ZWNJ/ZWJ (U+200C/U+200D) only singly and directly after a base (or base plus selector), and replaces lone surrogates with U+FFFD. Single-line values turn each run of controls (C0, DEL, C1) and whitespace (including U+0085, U+2028, U+2029) into one space and are trimmed; body text keeps TAB and LF, maps CR, CRLF, U+0085, U+2028 and U+2029 to LF and drops the other controls; caps and offsets count code points after sanitizing. Folder names from LIST are identifiers and stay outside `untrusted`. Upstream server NO/BAD text in errors is sanitized, single-line and capped at 200 characters.
- Errors are actionable and structured: `{"error":{"code","message","retryable","hint","retry_after_ms"?,"details"?,"request_id"}}`. v1 codes: `INVALID_ARGUMENT`, `ACCOUNT_NOT_FOUND`, `INVALID_HANDLE`, `INVALID_CURSOR`, `STALE_REFERENCE` (`details.reason` `uidvalidity_changed` / `folder_missing`; "re-run search"), `MESSAGE_NOT_FOUND`, `FOLDER_NOT_FOUND`, `AUTH_FAILED`, `SERVER_BUSY`, `TIMEOUT`, `UPSTREAM_UNAVAILABLE`, `UPSTREAM_ERROR`, `UNSUPPORTED`, `INTERNAL_ERROR`. Only `SERVER_BUSY` (`retry_after_ms` 2000), `TIMEOUT` and `UPSTREAM_UNAVAILABLE` are retryable. Unknown, malformed, tampered and foreign-tenant account handles, message handles and cursors each yield one identical payload; `INVALID_CURSOR` with `criteria_mismatch` only after a valid MAC. A BADCHARSET answer is `UNSUPPORTED` (`utf8_search`). Payloads carry no stack traces, host names or exception text.
- No push or IDLE. "What's new" is a search with `since` (see `search_messages` above).
- Dropped from the original idea list: delete folder, quota, save draft.

## 5. Architecture

### 5.1 Stack

- Java 25 bytecode (virtual threads without pinning on blocking I/O in `synchronized`); builds on JDK 25 or newer.
- Official MCP Java SDK, Jakarta Mail (Angus Mail, incl. `gimap` provider). Gmail accounts use protocol `gimaps` (required for `X-GM-RAW`) and implicit TLS.
- No Jakarta EE app server. Embedded Jetty 12 (ee10 servlet) only in HTTP modes, using the SDK's servlet transport.
- Manual wiring or a small DI library. No JPA.
- Nimbus JOSE+JWT for token validation (`http-public`). Maven multi-module build, one shaded jar (classifier `all`) with two entry points, dispatched by `--mode`.

### 5.2 Modules

| Module | Responsibility |
|---|---|
| `imap-mcp-core` | Tool definitions and logic, IMAP access, pooling, handles/cursors, `AccountContext`, `CredentialStore` and `AccountRepository` interfaces. No transport or Jetty dependency. MCP SDK types only in its `..core.mcp` adapter package. |
| `imap-mcp-store` | `CredentialStore` / `AccountRepository` implementations: file-based (v1), JDBC + Flyway (v3). State directory (5.7) and keyring CLI resolver. |
| `imap-mcp-transport-stdio` | stdio transport setup, stdout hygiene. |
| `imap-mcp-transport-http` | Jetty, Streamable HTTP, bearer-token auth (local), Origin/Host validation; OAuth resource server (v3). |
| `imap-mcp-app` | Entry points, config loading, wiring, shaded jar, Docker image. |
| `imap-mcp-it` | End-to-end and integration tests. |

Jetty must stay off the classpath of the stdio entry point.

### 5.3 Tenancy and security

- `AccountContext` is derived only from the validated principal (fixed in `stdio` / `http-local`). Tools receive opaque account handles resolved via a tenant-scoped repository (`findByTenantAndHandle`; `findByTenant` lists a tenant's accounts with their handles). The repository owns handles: v1 derives them (`acc_` + 26 base32 characters from SHA-256 over the installation salt, tenant and account id); v3 stores a random value of the same format. Pool keys are internal `(tenantId, accountId)`, never client-supplied.
- Prompt injection: email content is untrusted. Return it as structured, labeled data; destructive tools are off by default. HTML-to-text conversion drops hidden content (`script`, `style`, `head`, `template`, `noscript`, `hidden` attribute, inline `display:none`, `visibility:hidden`/`collapse`, `font-size:0`, `opacity:0`) and shows only http, https and mailto link targets. This is best effort: text hidden by stylesheet rules (`<style>` classes), colour (white on white), tiny non-zero sizes or positioning comes through as visible text; the `untrusted` labeling, not the converter, is the defence.
- TLS always (v1): implicit TLS (`imaps` / `gimaps`) or STARTTLS on protocol `imap` with `mail.imap.starttls.required=true`. No plaintext option.
- SSRF (v3, multi-tenant): resolve the IMAP host once, block loopback, link-local, RFC1918 and metadata addresses, pin the resolved IP, allow only ports 993 and 143 (with the TLS rule above). Provider allowlist for the public deployment.
- `http-local`: bind to loopback by default, validate `Origin` and `Host`, constant-time bearer token comparison. Docker deviation: inside the container the server binds `0.0.0.0` only behind an explicit flag (`http.allowNonLoopbackBind` / `IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND`); the port is published host-side as `-p 127.0.0.1:8080:8080`; Host/Origin checks and the bearer token still apply.
  - Filter order on `/mcp`: Host -> Origin -> POST only (`405`, `Allow: POST`, before authentication) -> bearer token -> MCP. Bad Host or Origin: `403`. Missing token: `401` with `WWW-Authenticate: Bearer realm="imap-mcp"`; wrong token or scheme: `401` with `error="invalid_token"`. Any other path: `404`.
  - Defaults: `http.allowedHosts` `localhost:*`, `127.0.0.1:*`, `[::1]:*`; `http.allowedOrigins` empty (an absent Origin passes, any present one including `null` is rejected).
  - The token is accepted only in the `Authorization` header; a configured `http.token` must have at least 32 characters. Request bodies are capped at 1 MiB (`http.maxRequestBytes`).
  - `/health` is exempt from Host/Origin checks and the token (5.6).
- Credentials: never in the image. Env, Docker secret, mounted file, or OS keyring (v1: platform CLI, Linux/macOS only). Literal secrets in the config file are rejected. `env:` and `file:` references are read at connect time (rotated secrets apply to new connections); `keyring:<service>/<account>` references are resolved once at startup and held in memory (timeout `secrets.keyringTimeout`, default 5 s); unsupported platform or missing CLI fails startup. macOS keyring: ASCII passwords only in v1.
- `http-public` only (v3):
  - Account linking is a separate onboarding flow. Claude's token identifies the user, not the mailbox. No token passthrough.
  - Authorization server is delegated (Keycloak, Zitadel, Auth0 or Entra); this server is a resource server only (RFC 9728 metadata, `401` with `WWW-Authenticate`, JWT checks incl. `aud`, RFC 8707). Must verify the chosen IdP supports client registration for Claude and sets the audience.
  - Credentials encrypted at rest: AES-256-GCM envelope encryption, key wrapped by KMS/Vault, ciphertext bound to `tenant_id||account_id` as AAD.
  - Audit log of mutating calls (tenant, tool, handles, result; never content).

### 5.4 IMAP semantics

- Message handle = HMAC-signed `(account, folder full name, UIDVALIDITY, UID)`; the tenant is inside the signed data. Do not keep `Message` objects between calls. UIDVALIDITY mismatch is an error ("stale reference").
  - Wire format `m1.` + base64url: key id, UIDVALIDITY, UID, folder full name (max 1000 UTF-8 bytes) and a 16-byte HMAC-SHA256 tag. Tenant and account enter only the MAC, never the wire. Handles are deterministic and do not expire.
  - Keys: a key ring with key ids (rotation = add a key id; all configured ids verify, one signs). HKDF-SHA256 purpose subkeys `msg-handle`, `cursor`, `log`.
- Message sequence numbers never appear in output, handles, cursors or logs. Inside one call on one open folder they may be used on the wire where Angus offers no UID form (SEARCH, page summary, partial body and structure fetches); every fetched row is re-identified by its UID, and an expunge reported during the call never attaches a summary to the wrong UID.
- Never issue a plain `EXPUNGE`. Use `UID EXPUNGE` for exact UIDs only.
- Move: MOVE if available, else COPY + `\Deleted` + UID EXPUNGE of those UIDs. Return new UIDs (COPYUID/APPENDUID). A retried move that finds the source gone reports success with a note.
- Paging: signed cursor `c1.` (UIDVALIDITY, last UID, query hash; tenant, account and resolved folder bound by the MAC), default page size 25, max 100, keyset by UID descending. Changing only `page_size` keeps a cursor valid; changed criteria give `INVALID_CURSOR` (`criteria_mismatch`). Constant number of IMAP commands per page; the UID data transferred per page is bounded by the page size, not the folder size (except `since`, bounded by `tools.sinceMaxCandidates`); extra FETCH commands only after a concurrent expunge. Report total count (all matches at search time).
- Fetch with PEEK only; open folders read-only for read tools; close with `folder.close(false)`.
- Body output: pick the text part via BODYSTRUCTURE (inside `multipart/alternative` a non-empty `text/plain` wins over `text/html`; `message/rfc822` is not descended), partial fetch. Character budget in Unicode code points: default 20 000, hard cap (`max_chars`) 100 000. Always with `truncated` and a continuation offset. Per-part source limit 4 MiB (configurable, at most 64 MiB), for plain and HTML parts; beyond it the body is marked source-truncated.
- Resolve special-use roles by SPECIAL-USE attributes, never by name heuristics (Gmail localizes names); otherwise folders are addressed by full name. Angus issues a plain `LIST "" "*"`, so roles exist only where the server reports the attributes there; several folders with one attribute: the first in code-point order; `\Noselect`/`\NonExistent` folders never carry a role. Detect capabilities at connect time (MOVE, UIDPLUS, SORT, SPECIAL-USE, `X-GM-EXT-1`), cached per account with a TTL (default 10 min, configurable), and gate features on them.

### 5.5 Connection pooling

- Commons Pool 2 `GenericKeyedObjectPool`, keyed by `(tenant, account)`. Pool `IMAPStore` instances, leased exclusively per tool call. Never share a Store or Folder across concurrent calls.
- Set `mail.<protocol>.connectionpoolsize=1` so one Store means one socket. Use one `Session` per account, never `getDefaultInstance`.
- Property prefix must match the protocol: `mail.imaps.*` for `imaps`, `mail.gimaps.*` for `gimaps`, `mail.imap.*` for `imap` (a mismatched prefix is silently ignored).
- Defaults (all configurable unless noted):

| Setting | Default |
|---|---|
| Idle eviction | 5 min |
| Validate (NOOP) before reuse if idle longer than | 60 s |
| Max connection lifetime | 30 min |
| Max connections per mailbox | 3 (1–2 when running several replicas) |
| Max connections per tenant | 8 |
| Max connections total (global socket cap) | 64 |
| Acquire timeout (pool wait plus connect, TLS and LOGIN) | 10 s |
| Connect / read / write timeout | 10 s / 60 s / 60 s |
| Overall per-tool deadline | 90 s (at most 10 min) |
| Abandoned-lease backstop (not configurable) | max(120 s, deadline + 30 s) |

- Per-tenant semaphore and global socket cap. Fail fast with "busy, retry" instead of queueing. An acquire timeout is `UPSTREAM_UNAVAILABLE`.
- A Store returns to the pool only after a call without an escaping exception. On timeout, cancellation, `FolderClosedException`, `StoreClosedException`, any other connection-level failure or any unchecked exception: discard the connection, never return it to the pool. Closing a Store (LOGOUT) never blocks the caller or the evictor.
- v1 deviation: the per-tool deadline is enforced by interrupting the worker and discarding the leased Store. The interrupt is what unblocks a worker stuck in a socket read (including TLS); closing the Store from another thread does not. The cancel hook invalidates the Store and releases the admission permits at once, so the caps may be exceeded briefly until the interrupted worker has unwound. MCP `notifications/cancelled` is not wired in v1 (the SDK offers no hook). The cancellation seam is the per-call cancel: it has the deadline's effects at once (hooks run once, worker interrupted, Store discarded, permits released) and ends the call with `TIMEOUT`, never with a result the handler produces afterwards; it is logged as a cancellation, not counted as a timeout. Cancel hooks never block: the Store invalidation runs off the cancelling thread. Once `notifications/cancelled` is wired, a call cancelled that way gets no response.
- XOAUTH2 (v3): cache the access token, refresh about 5 min early, single-flight per account, one retry on `AUTHENTICATIONFAILED`, mark the account "needs re-link" when the refresh token is revoked.
- Virtual threads for request handling. Bound IMAP concurrency with semaphores, not thread pools.

### 5.6 Logging and observability

- Logback to file only. Capture the real stdout for the protocol, then `System.setOut(System.err)`. Pass the captured stream explicitly to the SDK's stdio transport. Logback `NopStatusListener`. Bridge JUL to SLF4J. Launch the stdio JVM with `-Xlog:disable -Xlog:all=warning:stderr` (unified JVM logging writes to stdout by default).
- Rolling file (10 MB per file, 7 days, 100 MB total); root level from `IMAP_MCP_LOG_LEVEL` (default INFO). Angus, `jakarta.mail`, the MCP SDK and Jetty are capped at INFO regardless of the root level (they log protocol data and JSON-RPC at lower levels).
- IMAP protocol trace (Jakarta Mail debug): off by default. Explicit opt-in diagnostic only (config `imap.trace`); logs a WARN at startup, routed to the logger `imapmcp.imap.trace` (level DEBUG independent of the root level) with `LOGIN`/`AUTHENTICATE` lines redacted. Documented that the trace can contain message content.
- Never log message bodies, credentials, subjects, or addresses (the only exception is the opt-in IMAP trace above; credentials stay redacted there too). Structured logs carry request ID (generated per tool call; the JSON-RPC id is not available), hashed tenant, tool name and hashed account. Hashed ids are HMAC-SHA256 under the `log` subkey, truncated to 12 hex characters.
- Metrics per hashed tenant: pool active/idle/wait, IMAP command latency, auth failures. v1: internal in-memory metrics only (no exporter or metrics endpoint), periodic log summary with hashed ids; pool created/destroyed counts are pool-wide. Health endpoint in HTTP modes: unauthenticated `GET /health`, liveness only (never touches IMAP), `200 {"status":"ok"}`, `503 {"status":"shutting_down"}` during shutdown.

### 5.7 Persistence

- v1: no database. Config file (accounts, hosts, policies) and secrets from env / mounted file / OS keyring, behind the `CredentialStore` and `AccountRepository` interfaces.
- Config: YAML bound to records, unknown keys rejected. Location precedence: `--config` > `IMAP_MCP_CONFIG` > `$XDG_CONFIG_HOME/imap-mcp/config.yaml` (only when set and absolute) > `~/.config/imap-mcp/config.yaml`. Environment overrides exist only for `IMAP_MCP_MODE`, `IMAP_MCP_LOG_LEVEL`, `IMAP_MCP_HTTP_BIND`, `IMAP_MCP_HTTP_PORT` and `IMAP_MCP_HTTP_ALLOW_NON_LOOPBACK_BIND`. Page size and the default character budget are fixed (5.4), not configurable; `tools.maxCharsCap` can only lower the 100 000 cap.
- State (bearer token, handle key, installation salt): location precedence `IMAP_MCP_STATE_DIR` > `$XDG_STATE_HOME/imap-mcp` (only when set and absolute; relative values are ignored per the XDG spec) > `~/.local/state/imap-mcp`. Logs in `<state>/logs`; `IMAP_MCP_LOG_DIR` overrides. Docker: the image sets `IMAP_MCP_CONFIG=/config/config.yaml`, `IMAP_MCP_STATE_DIR=/config/state` and `IMAP_MCP_LOG_DIR=/logs` explicitly, so XDG variables are irrelevant inside the (headless) container.
  - Files `http-token`, `handle-key`, `installation-salt` (random, base64url), mode 0600 in a 0700 directory, published without replacing an existing file (hard link), so concurrent first starts agree on one value. The token file is created only in `http-local`.
  - An existing state directory or state file with group/other permission bits is tightened to 0700/0600 when we own it (WARN); if tightening fails, the state counts as not securely persistable (the ephemeral/fail-startup rule below applies); a state file that is not a regular file (links not followed) fails startup; non-POSIX file systems only warn.
  - A configured `http.token` or `security.handleKey` (base64url of 32 bytes) wins and is never written. If the handle key or salt cannot be persisted, an ephemeral value is used with a WARN (handles change after restart); if the token cannot be persisted, startup fails.
- Docker image: runs as uid/gid 10001; `/config` must be writable by it (the state lives in `/config/state`). HEALTHCHECK uses a probe class from the jar (no curl), against `IMAP_MCP_HTTP_PORT` (default 8080). Base image pinned by digest.
- v3: Postgres via plain JDBC or jOOQ with Flyway. Tables: `tenant` (IdP `iss` + `sub`), `mailbox_account`, `credential` (encrypted blob, key id, version).

### 5.8 Testing

- GreenMail for fast tests. Dovecot via Testcontainers for protocol fidelity (SPECIAL-USE, UIDVALIDITY change, PEEK on the wire, constant commands per page; MOVE, UIDPLUS and SORT as detected capabilities in v1, their behaviour from v2). Toxiproxy for timeouts and dropped connections (pool eviction and discard paths). The Dovecot 2.4 fixture adds `\Archive` by config (the image has no `\Archive` mailbox); fixture files are copied into the container, not bind-mounted.
- End-to-end with the SDK's `McpClient`: stdio via the shaded jar as a subprocess, asserting every stdout line is valid JSON-RPC; Streamable HTTP with real tokens (v3: Keycloak container; wrong audience, expired, other tenant).
- Tenant-isolation suite: swapped/forged account handles, tampered or replayed cursors and handles, concurrent load from one tenant not affecting another's pool; v3: credential copied between tenants fails to decrypt.
- Periodic manual or nightly smoke test against real Gmail and Microsoft 365 accounts.

## 6. Open decisions

- Final package and groupId naming (currently `de.alexanderlindhorst.imapmcp`, placeholder).
- Which IdP for `http-public`, and whether Gmail/Microsoft 365 upstream OAuth is in the first public release.
