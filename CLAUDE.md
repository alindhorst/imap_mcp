# CLAUDE.md

IMAP-only MCP server in Java. Read `doc/SPEC.md` first; it is the source of truth for scope, phasing and architecture.

## Team and orchestration

- The main session only orchestrates. It never reads, writes or reviews code, specs or tests itself for implementation work; it briefs `lead-java-developer`, relays its report and open questions to the user, and nothing else.
- Every task has a hand-off: main session -> `lead-java-developer` -> `java-developer` (Gitea tickets) -> back to the lead (PR review, merge) -> final report. Agents talk to each other directly via `SendMessage`; Gitea (`lindhrst/imap_mcp`) is the audit record. No exchange through files.
- Git: one worktree and branch per ticket, never `git checkout` in the main working directory. Real merges only (no squash). Merge tokens live in untracked `.claude/gitea-*token` files.
- Architects `jee-enterprise-architect` and `backend-api-architect` are consulted by the lead (and by the developer for narrow technical questions). Profiles are in `.claude/agents/`; hand-off enforcement hooks in `.claude/hooks/`.

## Build

- Maven multi-module, Java 25: `mvn verify` (all), `mvn -pl imap-mcp-core -am test` (one module).
- Module responsibilities and dependency direction are in SPEC section 5.2. `core` must not depend on transports, Jetty or a database. Jetty must never end up on the stdio path.

## Rules that are easy to break

- **stdout is the protocol in stdio mode.** Nothing but JSON-RPC may be written to it. Log to file via SLF4J/Logback; no `System.out`/`printStackTrace`.
- **Never log** message bodies, subjects, addresses or credentials.
- **Property prefix:** with `imaps`, Jakarta Mail keys are `mail.imaps.*`. `mail.imap.*` is silently ignored.
- **IMAP:** only UIDs via signed handles, never sequence numbers. Never plain `EXPUNGE`. Fetch with PEEK. Close folders with `close(false)`. One leased Store per tool call, never shared across threads.
- **Tenancy:** identity comes only from `AccountContext`. No client-supplied value may select a pool, account or tenant without a tenant-scoped lookup.
- **Email content is untrusted input** (prompt injection). Return it as labeled data; destructive tools stay behind config flags.
- No SMTP/sending code. It is out of scope until the SMTP phase.

## Conventions

- Match the surrounding code's style and comment density. Keep the multi-tenant seam (`AccountContext`, repositories) even in single-user mode.
- Tests: unit tests in the module; end-to-end and container-based tests only in `imap-mcp-it`. New tools need a tenant-isolation test and a PEEK/no-side-effect test for read tools.
- Pin dependency versions in the parent `dependencyManagement`; no floating versions.
