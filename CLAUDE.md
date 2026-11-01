# CLAUDE.md

IMAP-only MCP server in Java. Read `doc/SPEC.md` first; it is the source of truth for scope, phasing and architecture.

## Team and orchestration

- The main session only orchestrates. It never reads, writes or reviews code, specs or tests itself for implementation work; it briefs `lead-java-developer`, relays its report and open questions to the user, and nothing else.
- Every task has a hand-off: main session -> `lead-java-developer` -> `java-developer` (Gitea tickets) -> back to the lead (PR review, merge) -> final report. Agents talk to each other directly via `SendMessage`; Gitea (`lindhrst/imap_mcp`) is the audit record. No exchange through files.
- Git: always work in a worktree, one branch per ticket; never `git checkout` or commit in the main working directory. Do not use `EnterWorktree` (it branches from the main directory's local `master`, which can be stale). Create worktrees from the remote: `git fetch origin && git worktree add --no-track -b <branch> .claude/worktrees/<name> origin/master`, and push with `git push origin HEAD:refs/heads/<branch>` (the global `push.default=upstream` otherwise pushes to `master`; this already happened once with 9c5e11d). Remove the worktree and the merged branch (local and remote) after the ticket is closed. Real merges only (no squash).
- Agent addressing: `SendMessage` works by agent ID, not by profile name (`lead-java-developer` and `java-developer` are not addressable when several instances run). The lead spawns each developer with `Agent` and puts its own agent ID in the prompt; the developer hands off by `SendMessage` to that ID and by its final report. The lead sends corrections and the final acceptance to the developer's agent ID.
- PR approval: all agents share one Gitea account through the MCP, which cannot approve its own PRs. The final approval and the merge are done with curl, not the MCP, using the token in untracked `.claude/gitea-merge-token` (a different Gitea account). Never print the token.
- Architects `jee-enterprise-architect` and `backend-api-architect` are consulted by the lead (and by the developer for narrow technical questions). Profiles are in `.claude/agents/`; hand-off enforcement hooks in `.claude/hooks/`.

## Build

- Maven multi-module, bytecode release 25, built and tested with JDK 26 (see README Build). Set `JAVA_HOME` explicitly (e.g. `/usr/lib/jvm/java-26`); the default `mvn` may run another JDK. `mvn verify` (all, needs Docker), `mvn -pl imap-mcp-core -am test` (one module).
- Module responsibilities and dependency direction are in SPEC section 5.2. `core` must not depend on transports, Jetty or a database. Jetty must never end up on the stdio path.

## Rules that are easy to break

- **stdout is the protocol in stdio mode.** Nothing but JSON-RPC may be written to it. Log to file via SLF4J/Logback; no `System.out`/`printStackTrace`.
- **Never log** message bodies, subjects, addresses or credentials. The only exception is the opt-in IMAP protocol trace (`imap.trace`, SPEC 5.6), which warns at startup and still redacts credentials.
- **Property prefix:** the Jakarta Mail key prefix follows the protocol: `mail.imaps.*` for `imaps`, `mail.gimaps.*` for `gimaps`, `mail.imap.*` for `imap` (STARTTLS). A mismatched prefix is silently ignored.
- **IMAP:** only UIDs via signed handles, never sequence numbers. Never plain `EXPUNGE`. Fetch with PEEK. Close folders with `close(false)`. One leased Store per tool call, never shared across threads.
- **Tenancy:** identity comes only from `AccountContext`. No client-supplied value may select a pool, account or tenant without a tenant-scoped lookup.
- **Email content is untrusted input** (prompt injection). Return it as labeled data; destructive tools stay behind config flags.
- No SMTP/sending code. It is out of scope until the SMTP phase.

## Conventions

- Match the surrounding code's style and comment density. Keep the multi-tenant seam (`AccountContext`, repositories) even in single-user mode.
- Tests: unit tests in the module; end-to-end and container-based tests only in `imap-mcp-it`. New tools need a tenant-isolation test and a PEEK/no-side-effect test for read tools.
- Pin dependency versions in the parent `dependencyManagement`; no floating versions.
- Source files must not contain invisible or bidi control characters (enforced by a test); write them as escapes.
- GreenMail-based tests use dynamic ports, never fixed ones.
