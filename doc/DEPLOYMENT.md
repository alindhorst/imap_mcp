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

## 4. Assumptions not yet verified, and their symptoms

- **`Origin` header from Claude.** A 403 on the first request to `/mcp` means the origin check refused it: set `http.allowedOrigins: ["https://claude.ai"]` and restart the app.
- **Callback `https://claude.com/api/mcp/auth_callback`.** It is registered as an assumption; only the `claude.ai` callback is documented. A redirect-URI error at Keycloak points here.
- **Claude Code** is not supported (its loopback redirect is not registered).
- **Refresh token rotation.** If Claude reconnects sporadically, set `revokeRefreshToken` to false in the realm.

## 5. Podman notes

- podman-compose needs a recent version for `service_healthy`; 1.6.0 was verified.
- `podman compose` uses the docker-compose provider and needs the socket (`systemctl --user enable --now podman.socket`).
- Rootless healthchecks need systemd user timers: enable linger.
- Bind mounts carry `:Z`; do not use `keep-id`.
- The compose network uses the fixed subnet `172.28.0.0/24`; if it collides, change it in `networks.backend` and in `KC_PROXY_TRUSTED_ADDRESSES`.

## 6. What was verified, and what was not

Verified (rootless Podman 5.8.2, `podman compose` and podman-compose 1.6.0, local `.test` names on port 8443 with a local CA): all containers healthy, both databases and the Flyway migrations, discovery through the proxy, 404 for the non-public paths, the resolved realm placeholders, the 401 challenge, and a token round trip (`initialize`, `tools/list`, `list_accounts` with `link_url`), including refusal of a token without the audience.

Not verified: a run on a native Docker daemon, a run on a public host (DNS, Let's Encrypt, ports 80 and 443), the browser login with TOTP, the Claude connector itself, and linking a real IMAP account. These are what the checks in section 3 are for.

## 7. Security notes

- No secret is committed: `.env` is generated by `setup.sh` with mode 0600, `app/config.yaml` and everything generated for the local variant are ignored by git.
- Linked credentials are encrypted with a local key-encryption key from `.env`. Vault Transit is described in the [README](../README.md#http-public) only and is not wired into this environment.
- One replica, a local KEK and a single host: do not use it for production.
- Mail content is untrusted input. `http.stateful` and `purge.enabled` stay off until the checks above have passed.
