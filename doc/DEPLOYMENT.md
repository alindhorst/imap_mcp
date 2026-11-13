# Deploying http-public for testing

The operator's view of the `http-public` test deployment: what to provide, how the parts fit, which checks to run in which order, and what is not yet verified. The commands are in [`dist/docker/README.md`](../dist/docker/README.md); this page does not repeat them. Reference: the sections [http-public](../README.md#http-public) and [Keycloak setup for the Claude connector](../README.md#keycloak-setup-for-the-claude-connector) of the README, and [SPEC.md](SPEC.md) (5.3, 5.7).

This is a test environment, not a production deployment.

Tenant model in one sentence: every user who logs in at `/accounts` becomes a tenant with their own mail accounts, which they link themselves; mailboxes are never defined in the app's config and never shared between tenants (details in [TUTORIAL.md](TUTORIAL.md#6-public-multi-tenant-mode-http-public)).

## 1. What you must provide

- A Linux host with Docker Compose v2 or Podman (rootless works), a public IPv4 address, NTP-synchronized time and about 3 GB of RAM.
- Two DNS names (A records) pointing to the host: `MCP_HOST` for this server and `IDP_HOST` for Keycloak. Two names give two origins, so pages served by Keycloak cannot reach the cookies of the linking surface `/accounts`.
- Inbound TCP 80 and 443 (Let's Encrypt validates on both). Rootless Podman cannot bind them by default: set `net.ipv4.ip_unprivileged_port_start=80`, or forward the ports to 8081 and 8443 (and set `HTTP_PORT` and `HTTPS_PORT`). Open the ports in firewalld.
- An email address for ACME, or your own publicly trusted certificates. A private CA does not work for the public path: neither Claude nor the JVM trusts it.
- Outbound TCP 443 (ACME, the IdP) and 993 or 143 to your IMAP host.
- The shaded jar: the released `imap-mcp-app-<version>-all.jar` (check it against its `.sha256`) or a local build.
- The IMAP hosts users may link; they become `ssrf.allowedHosts`.

## 2. Architecture in one paragraph

Caddy terminates TLS for both names and forwards only an allowlist of paths: on `MCP_HOST` the MCP endpoint, the linking surface and the two metadata documents, on `IDP_HOST` the Keycloak realm paths. The app (`http-public`) is an OAuth 2.1 resource server and fetches discovery and the signing keys from the IdP itself over https; inside the compose network the name `IDP_HOST` is an alias of Caddy, so no hairpin NAT is needed. Keycloak and the app use separate databases in one Postgres. Not public: the Keycloak admin console, the master realm, client registration, the account console, Keycloak's health and metrics, and the app's `/health` (the users are created with kcadm inside the Keycloak container).

## 3. Your manual checks, in order

The full checklist with the commands is in [`dist/docker/README.md`](../dist/docker/README.md#owner-checklist). In short:

1. Preflight: DNS, certificates, issuer discovery, metadata document, the 401 challenge on `/mcp`, 404 for the paths that must not be public.
2. Browser login to `https://<MCP_HOST>/accounts`: TOTP set-up, the callback creates the tenant; a user without the role `imap-mcp-user` gets 403.
3. Link a mail account with `read_only` on; a host outside the allowlist is refused.
4. Claude connector smoke test: URL `https://<MCP_HOST>/mcp`, client id `claude-connector`, no secret; the consent screen lists read and write.
5. Read tools, then (with `read_only` off) write tools. Reading leaves messages unread.
6. Only then consider `http.stateful` and `purge.enabled`, and run the v3b smoke checklist in the [README](../README.md#smoke-checklist-for-v3b).

## 4. Assumptions, what the tests observed, and their symptoms

What is stated as observed comes from the deployed integration tests (section 6, Keycloak 26.4 behind Caddy, local names and a local CA). What is stated as unknown needs the real connector on a public host.

- **`Origin` header from Claude.** Observed with the shipped config: a request without `Origin` passes, and `https://claude.ai` and `null` get 403 without a `WWW-Authenticate` header. With `http.allowedOrigins: ["https://claude.ai"]` and a restart of the app, `https://claude.ai` passes, another origin is still 403, and a request without `Origin` still passes. Linked accounts survive the restart. Unknown: whether Claude sends an `Origin` at all, and which. A 403 on the first request to `/mcp` means the origin check refused it: set `http.allowedOrigins: ["https://claude.ai"]` and restart the app.
- **Callbacks.** Observed: the realm accepts both `https://claude.ai/api/mcp/auth_callback` and `https://claude.com/api/mcp/auth_callback` for `claude-connector` (authorization and token exchange), and refuses an unregistered redirect URI with an error page. Unknown: which of them the real connector uses; only the `claude.ai` callback is documented. A redirect-URI error at Keycloak points here.
- **Claude Code** is not supported (its loopback redirect is not registered).
- **Authorization server metadata.** Observed: the document at `/.well-known/oauth-authorization-server/realms/imap-mcp` (RFC 8414 with the path inserted, the first location in the MCP discovery order) answers 200 through Caddy, and `authorization_response_iss_parameter_supported` is true. The advertised `registration_endpoint` answers 404 through Caddy (client registration is not public). `token_endpoint_auth_methods_supported` does not contain `none`, although the token exchange of the public client works without a secret. Unknown: whether the real connector accepts that; a client that insists on `none` in the metadata would refuse.
- **Access token expiry.** Observed: a token 10 seconds past its `exp` is still accepted (the 60 second skew), 63 seconds past it is refused with 401 and `error="invalid_token"`; a refresh then gives a token that works.
- **Scopes in the authorization request.** Observed with the shipped realm: `openid` is accepted in addition to the two `imap-mcp` scopes; `offline_access`, `profile` and `email` are refused with `invalid_scope`. A write-scope refusal on a tool call is a `PERMISSION_DENIED` tool result, not an HTTP challenge; a token with only the write scope gets 403 `insufficient_scope`, and authorizing again with both scopes works.
- **Refresh token rotation.** The shipped realm rotates refresh tokens (`revokeRefreshToken` true, `refreshTokenMaxReuse` 0). Observed: every refresh returns a new refresh token and the old one is refused with `invalid_grant`; presenting the old one again also revokes the session, so the new one is refused too. Two refreshes with the same token at the same moment usually end with one 200 and one `invalid_grant`, after which the new token of the winner is refused as well, so the client is logged out; in some rounds both succeeded. That is a plausible cause of sporadic reconnects. Recommendation, not tested: keep rotation, and if reconnects after refresh races are seen, raise `refreshTokenMaxReuse` to 1 (Realm settings, Tokens). That variant itself has not been tested, and the shipped realm is unchanged.

Upstream OAuth (XOAUTH2 for Gmail and Microsoft 365, [README](../README.md#upstream-oauth-experimental-unverified)) is built on the following assumptions. None has been verified against the real providers; each bullet gives the symptom to look for. A user sees `relink_required` or `xoauth2_rejected` as a tool error, the operator sees the others in the log and metrics.

- **Upstream OAuth endpoints and scopes.** Assumed: the fixed endpoints and scopes of `google` and `microsoft` (SPEC 5.7) are right. Symptom: the provider's sign-in page shows an error, or `oauth_client_rejected` at the first refresh.
- **Refresh token on re-consent.** Assumed: a refresh token is returned again on re-consent with `prompt=consent`. Symptom: a re-link succeeds at the provider but stores nothing, or the next refresh gives `relink_required`.
- **Microsoft refresh tokens.** Assumed: Microsoft rotates refresh tokens, and its access tokens last 60 to 90 minutes. Symptom: `relink_required` after a refresh race, or the metric `oauth_grant_lost` rising after a failed commit.
- **Google apps in "Testing" status.** Assumed: their refresh tokens expire after 7 days. Symptom: a weekly `relink_required` for every Google account; publish the app or accept the weekly re-link.
- **Gmail restricted-scope verification.** Assumed: a public app needs the restricted-scope verification (CASA) for `https://mail.google.com/`. Symptom: the provider shows an "unverified app" warning or refuses users outside the test users; owner task #325.
- **Microsoft and IMAP sessions.** Unknown: whether Microsoft drops an open IMAP session at token expiry. Symptom: sporadic connection drops (`UPSTREAM_UNAVAILABLE`) on a long-lived pooled connection; the pool closes a connection before its token expires, so it should show only as a retry.
- **Gmail XOAUTH2 user.** Assumed: the XOAUTH2 user for Gmail is the mail address. Symptom: `xoauth2_rejected` for every Gmail account while Microsoft works.
- **Error JSON of a failed `AUTHENTICATE`.** Assumed: the shape of the error the IMAP server answers to a refused XOAUTH2 `AUTHENTICATE`. Symptom: a refused token is not retried once as designed, or `xoauth2_rejected` appears at the first call after a token was renewed.

To replace what is still unknown with facts, record one real connector session with the opt-in kit: [Recording a connector session](../dist/docker/README.md#recording-a-connector-session).

## 5. Podman notes

- podman-compose needs a recent version for `service_healthy`; 1.6.0 was verified.
- `podman compose` uses the docker-compose provider and needs the socket (`systemctl --user enable --now podman.socket`).
- Rootless healthchecks need systemd user timers: enable linger.
- Bind mounts carry `:Z`; do not use `keep-id`.
- The compose network uses the default subnet `172.28.0.0/24`; if it collides, set `BACKEND_SUBNET` (an IPv4 CIDR, in `.env` or via `BACKEND_SUBNET=... ./setup.sh`); it is used for `networks.backend` and for `KC_PROXY_TRUSTED_ADDRESSES`.

## 6. What was verified, and what was not

Verified by the deployed integration tests of `imap-mcp-it` (`*DeployedIT`; see the [README](../README.md#build) for how to run them). They start the stack of `dist/docker` from a copy on rootless Podman 5.8.2, with local `.test` names on port 8443, a local CA and a forward proxy, and every request goes through Caddy:

- `ComposeLifecycleDeployedIT`: the compose harness leaves nothing behind after a pass or a failed start, and keeps the logs of a failed start.
- `StackPreflightDeployedIT`: the backend network is internal and without IPv6, every backend container is inside the subnet and not blocked by the production SSRF policy, the run uses a copy of the project directory, the proxy allows only `CONNECT` to the test domain on 8443, Dovecot presents its certificate on 993 chained to the test CA, and no generated secret appears in captured output.
- `DiscoveryDeployedIT`: both metadata paths return the same document, the 401 challenge on `/mcp` is verbatim, and `/health`, `/admin/`, `/realms/master/` and client registration answer 404.
- `ConnectorClientDeployedIT`: the sequence of the connector from the challenge to an authenticated MCP session (metadata, authorization with PKCE `S256` and `claude-connector`, the consent page shown to a first-time user, the token request without a secret, `tools/list`, `list_accounts`), both callbacks, the refusal of an authorization request without `code_challenge`, of `plain`, of an unregistered redirect URI, of a wrong verifier and of a replayed code, and the discovery values listed in section 4.
- `FlowDeployedIT`: the first login at `/accounts` creates the tenant and sets the `__Host-session` cookie (`Secure`, `HttpOnly`, `SameSite=Strict`, no `Domain`); a user without `imap-mcp-user` gets 403 and no tenant; linking Dovecot on 993 under the production SSRF policy takes one LOGIN probe, and a host outside the allowlist gets the generic refusal and stores nothing; the read tools work and leave the message unseen as `doveadm` shows (PEEK); `set_flags` on a `read_only` account is refused and changes nothing; with `read_only` off one reversible write succeeds and leaves audit rows; a second tenant is isolated (`ACCOUNT_NOT_FOUND` for the first tenant's account handle, `INVALID_HANDLE` for its message handle, no IMAP I/O for the first tenant's account); the app log at INFO holds no subject, sender, password, token or session.
- `TokenLifecycleDeployedIT`: expiry, refresh and rotation as described in section 4, parallel refresh, the 403 `insufficient_scope` challenge and the re-authorization, and the scope strings the realm accepts.
- `OriginDeployedIT`: the `Origin` behaviour of section 4 and that linked accounts survive an app restart. The `Host` check is not tested through Caddy: a foreign name is answered by Caddy itself and never reaches the app.
- `ConnectorContractDeployedIT`: the client's connector sequence conforms to the committed connector fixture and replays its statuses. That fixture is still `source: assumed`, so this proves the stack against an assumed sequence only; the log line of the run names the source.

The same `*DeployedIT` suite also ran green with native Docker on a GitHub-hosted CI runner (`ubuntu-24.04`, x64): GitHub Actions run `verify` 43304462127 on commit 48c29d1 on 2026-11-12, as a non-root user and with a cold Maven cache. The run had 3270 tests, 0 failures, 0 errors, 0 skipped, of them 48 deployed-IT tests in 8 classes, and every `*IT` class of `imap-mcp-it` produced a report. `ConnectorContractDeployedIT` ran with `source=assumed` there too. The Docker engine and compose versions and the verbatim `test-completeness gate: OK (...)` line were not recorded, because the job log was not kept; the gate passed because the run was green (it fails `verify` on any skip).

An earlier manual run (rootless Podman 5.8.2, `podman compose` and podman-compose 1.6.0) also checked: both databases and the Flyway migrations, the resolved realm placeholders, and the refusal of a token without the audience.

Not verified, because it needs a public run: real Google/Microsoft upstream OAuth (linking, refresh and XOAUTH2 login; tested only against a fake provider, until a run of the smoke harness #328 is recorded here), the real Claude connector (whether it sends an `Origin` and which, its `User-Agent` and protocol version, whether it accepts metadata without `none`), the public host with DNS and Let's Encrypt (ports 80 and 443), and the browser login with TOTP. These are what the checks in section 3 are for.

## 7. Security notes

- No secret is committed: `.env` is generated by `setup.sh` with mode 0600, `app/config.yaml` and everything generated for the local variant are ignored by git.
- Linked credentials are encrypted with a local key-encryption key from `.env`. Vault Transit is described in the [README](../README.md#http-public) only and is not wired into this environment.
- One replica, a local KEK and a single host: do not use it for production.
- Mail content is untrusted input. `http.stateful` and `purge.enabled` stay off until the checks above have passed.
