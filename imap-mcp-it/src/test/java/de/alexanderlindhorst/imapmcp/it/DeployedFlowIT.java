package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The central evidence that http-public works as deployed (#431, SPEC 5.3, section 4, 5.4, 5.8 v0.25): one flow
 * through Caddy, Keycloak, the app container, Postgres and Dovecot, with no seam in the app. The steps run in order
 * (one test method each, state kept in the instance): connector token and empty {@code list_accounts}; the login at
 * {@code /accounts} that creates the tenant and the session cookie; a user without the role; linking (SSRF refusal,
 * Dovecot on 993); the v1 read tools with PEEK checked by doveadm; the write gate and one reversible write with its
 * audit row; a second tenant that is isolated, with no IMAP I/O for the first; the secret scan of the app log.
 *
 * <p>Users are created per run (kcadm, generated passwords, no realm setting changed): Alice and Bob with the role
 * {@code imap-mcp-user}, a third user without it. They are mapped to the two Dovecot users of the stack. The class
 * restores what it changes on the shared stack: both accounts are unlinked at the end and the flag set by the
 * write is undone in the flow; the tenants, sessions and audit rows of the per-run users stay (no other class uses
 * those users). Nothing secret and no mail data is printed: counts and booleans only.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DeployedFlowIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCOPE = "imap-mcp:read imap-mcp:write";
    private static final String SENDER = "sender@example.test";
    private static final Set<String> AUDIT_COLUMNS = Set.of("id", "occurred_at", "tenant_id", "account_id",
            "request_id", "event_type", "tool", "ref_hash", "ref_key_id", "item_status", "error_code",
            "confirmation", "result", "completed_at");

    private DeployedStack stack;
    private KeycloakAdmin admin;
    private String issuer;
    private String resource;
    private String linkUrl;

    private KeycloakAdmin.TestUser alice;
    private KeycloakAdmin.TestUser bob;
    private KeycloakAdmin.TestUser noRole;
    private DeployedDovecot.ImapUser aliceMail;
    private DeployedDovecot.ImapUser bobMail;
    private String aliceSub;
    private String bobSub;
    private String noRoleSub;

    private final List<McpSyncClient> mcpClients = new ArrayList<>();
    /** Every secret of this run, for the response and log scans (never printed). */
    private final List<String> secrets = new ArrayList<>();
    /** Every tool result payload seen, as text, for the password scan (never printed). */
    private final List<String> toolOutputs = new ArrayList<>();

    private AccountsClient aliceWeb;
    private AccountsClient bobWeb;
    private McpSyncClient aliceMcp;
    private McpSyncClient bobMcp;
    private String aliceAccount;
    private String bobAccount;
    private String messageHandle;

    @BeforeAll
    void start() throws Exception {
        stack = DeployedStack.instance();
        admin = new KeycloakAdmin(stack);
        issuer = stack.configValue("oauth", "issuer");
        resource = stack.configValue("oauth", "resource");
        linkUrl = stack.configValue("public", "baseUrl") + "/accounts";
        alice = admin.createUser();
        bob = admin.createUser();
        noRole = admin.createUserWithoutRole();
        aliceSub = admin.userId(alice);
        bobSub = admin.userId(bob);
        noRoleSub = admin.userId(noRole);
        aliceMail = stack.dovecotUsers().get(0);
        bobMail = stack.dovecotUsers().get(1);
        for (String s : List.of(alice.password(), bob.password(), noRole.password(), aliceMail.password().value(),
                bobMail.password().value())) {
            secrets.add(s);
        }
        aliceWeb = new AccountsClient(stack);
        bobWeb = new AccountsClient(stack);
    }

    @AfterAll
    void restoreTheStack() throws Exception {
        try {
            // unlink what the flow linked, so that the shared stack's Dovecot users are free again
            if (aliceAccount != null && aliceWeb != null) {
                assertThat(aliceWeb.post("/accounts/unlink", Map.of("account", aliceAccount)).statusCode())
                        .isEqualTo(303);
            }
            if (bobAccount != null && bobWeb != null) {
                assertThat(bobWeb.post("/accounts/unlink", Map.of("account", bobAccount)).statusCode())
                        .isEqualTo(303);
            }
        } finally {
            mcpClients.forEach(McpSyncClient::close);
        }
    }

    // ---- 1. Alice's connector token, no account yet ----------------------------------------------------------------

    @Test
    @Order(1)
    void aliceGetsAConnectorTokenAndSeesNoAccountButTheLinkUrl() throws Exception {
        assertThat(tenantRows(aliceSub)).as("no tenant before the first login").isZero();

        aliceMcp = mcpFor(alice);
        Map<String, Object> accounts = call(aliceMcp, "list_accounts");

        assertThat(accounts.get("accounts")).isEqualTo(List.of());
        assertThat(accounts.get("link_url")).isEqualTo(linkUrl);
    }

    // ---- 2. the login at /accounts creates the tenant -----------------------------------------------------------------

    @Test
    @Order(2)
    void theFirstLoginAtAccountsCreatesTheTenantAndTheHostOnlySessionCookie() throws Exception {
        HttpResponse<String> callback = aliceWeb.login(alice);

        assertThat(callback.statusCode()).isEqualTo(200);
        assertThat(callback.body()).contains("http-equiv=\"refresh\"").contains("url=/accounts");
        List<String> setCookies = callback.headers().allValues("Set-Cookie");
        AccountsClient.SetCookie session = setCookies.stream().map(AccountsClient.SetCookie::parse)
                .filter(c -> c.name().equals("__Host-session") && !c.value().isEmpty()).findFirst().orElseThrow();
        assertThat(session.hasFlag("Secure")).isTrue();
        assertThat(session.hasFlag("HttpOnly")).isTrue();
        assertThat(session.attribute("SameSite")).isEqualTo("Strict");
        assertThat(session.attribute("Path")).isEqualTo("/");
        assertThat(session.hasFlag("Domain")).as("a __Host- cookie has no Domain").isFalse();
        assertThat(tenantRows(aliceSub)).isEqualTo(1);

        HttpResponse<String> home = aliceWeb.get("/accounts");
        assertThat(home.statusCode()).isEqualTo(200);
        assertThat(AccountsClient.csrfToken(home.body())).isNotBlank();
        assertThat(home.body()).contains("No mail account is linked yet.");
    }

    // ---- 3. no role, no tenant -------------------------------------------------------------------------------------------

    @Test
    @Order(3)
    void aUserWithoutTheRoleIsRefusedAtTheCallbackAndGetsNoTenant() throws Exception {
        int tenantsBefore = count("select count(*) from tenant");
        AccountsClient web = new AccountsClient(stack);

        HttpResponse<String> callback = web.login(noRole);

        assertThat(callback.statusCode()).isEqualTo(403);
        assertThat(callback.headers().allValues("Set-Cookie").stream().map(AccountsClient.SetCookie::parse))
                .noneMatch(c -> c.name().equals("__Host-session") && !c.value().isEmpty());
        assertThat(tenantRows(noRoleSub)).isZero();
        assertThat(count("select count(*) from tenant")).isEqualTo(tenantsBefore);
    }

    // ---- 4. linking ----------------------------------------------------------------------------------------------------------

    @Test
    @Order(4)
    void aHostOutsideTheAllowListIsRefusedWithTheGenericMessageAndNothingIsStored() throws Exception {
        String tenant = tenantSql(aliceSub);

        HttpResponse<String> refused = aliceWeb.post("/accounts/link",
                linkFields("Elsewhere", "imap.example.org", aliceMail));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("This mail server cannot be used with this service.")
                .doesNotContain("imap.example.org");
        assertThat(count("select count(*) from mailbox_account where tenant_id = " + tenant)).isZero();
        assertThat(count("select count(*) from credential where tenant_id = " + tenant)).isZero();
        assertThat(count("select count(*) from audit_event where tenant_id = " + tenant
                + " and event_type = 'link' and result = 'refused_host'")).isEqualTo(1);
    }

    @Test
    @Order(5)
    void linkingDovecotOn993ProbesOnceAndStoresTheCredentialEncrypted() throws Exception {
        int loginsBefore = loginsOf(aliceMail.name());

        HttpResponse<String> linked = aliceWeb.post("/accounts/link",
                linkFields("Alice mail", DeployedDovecot.HOST, aliceMail));

        assertThat(linked.statusCode()).isEqualTo(303);
        assertThat(linked.headers().firstValue("Location")).hasValue("/accounts");
        assertThat(loginsOf(aliceMail.name())).as("the probe is the only Login: of Alice's Dovecot user")
                .isEqualTo(loginsBefore + 1);
        assertThat(aliceWeb.responseBodies()).noneMatch(b -> b.contains(aliceMail.password().value()));
        // SQL side only: a boolean comes back, never a credential column
        assertThat(psql("select count(*) from credential c join mailbox_account a on a.tenant_id = c.tenant_id"
                + " and a.id = c.account_id where a.tenant_id = " + tenantSql(aliceSub))).isEqualTo("1");
        assertThat(psql("select coalesce(bool_or(position(convert_to('" + aliceMail.password().value()
                + "', 'UTF8') in ciphertext) > 0 or position(convert_to('" + aliceMail.password().value()
                + "', 'UTF8') in wrapped_dek) > 0 or position(convert_to('" + aliceMail.password().value()
                + "', 'UTF8') in nonce) > 0), false) from credential")).isEqualTo("f");
        assertThat(count("select count(*) from mailbox_account where tenant_id = " + tenantSql(aliceSub)
                + " and read_only")).isEqualTo(1);
    }

    // ---- 5. the read tools, PEEK ---------------------------------------------------------------------------------------

    @Test
    @Order(6)
    void theReadToolsWorkAndLeaveTheMessageUnseenAsDoveadmShows() throws Exception {
        assertThat(flagsOf(aliceMail)).as("unread before").doesNotContain("\\Seen");

        Map<String, Object> accounts = call(aliceMcp, "list_accounts");
        List<Map<String, Object>> list = list(accounts, "accounts");
        assertThat(list).hasSize(1);
        assertThat(list.get(0)).containsEntry("status", "ok").containsEntry("read_only", true);
        aliceAccount = (String) list.get(0).get("account");

        List<Map<String, Object>> folders = list(call(aliceMcp, "list_folders", "account", aliceAccount), "folders");
        Map<String, Object> inbox = folders.stream().filter(f -> "INBOX".equals(f.get("folder"))).findFirst()
                .orElseThrow();
        assertThat(((Number) inbox.get("total")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) inbox.get("unread")).intValue()).isGreaterThanOrEqualTo(1);

        List<Map<String, Object>> found = list(call(aliceMcp, "search_messages", "account", aliceAccount, "folder",
                "INBOX", "subject", stack.canarySubject()), "messages");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("flags").toString()).doesNotContain("\\Seen");
        messageHandle = (String) found.get(0).get("handle");

        Map<String, Object> message = call(aliceMcp, "get_message", "account", aliceAccount, "message", messageHandle);
        assertThat(message.get("content_notice")).isInstanceOf(String.class);
        assertThat((String) message.get("content_notice")).isNotBlank();
        Map<String, Object> untrusted = map(message, "untrusted");
        assertThat(untrusted.get("subject")).isEqualTo(stack.canarySubject());
        assertThat((String) map(untrusted, "body").get("text")).contains("Seeded by the deployed-stack fixture.");

        assertThat(flagsOf(aliceMail)).as("PEEK: still unseen on the server, whatever the app says")
                .doesNotContain("\\Seen");
    }

    // ---- 6. the write gate and one reversible write ------------------------------------------------------------------

    @Test
    @Order(7)
    void setFlagsOnAReadOnlyAccountIsRefusedAndNothingChangesOnTheServer() throws Exception {
        List<String> before = flagsOf(aliceMail);

        Map<String, Object> refused = call(aliceMcp, "set_flags", "account", aliceAccount, "handles",
                List.of(messageHandle), "flagged", true);

        Map<String, Object> error = map(refused, "error");
        assertThat(error).containsEntry("code", "PERMISSION_DENIED");
        assertThat(map(error, "details")).containsEntry("reason", "account_read_only");
        assertThat(flagsOf(aliceMail)).isEqualTo(before).doesNotContain("\\Flagged");
        assertThat(auditItems(aliceSub)).as("a refused call writes no item row").isZero();
    }

    @Test
    @Order(8)
    void afterTheOwnerTurnsReadOnlyOffSetFlagsSucceedsAuditedAndIsUndone() throws Exception {
        HttpResponse<String> saved = aliceWeb.post("/accounts/settings",
                Map.of("account", aliceAccount, "label", "Alice mail", "access", "read_write"));
        assertThat(saved.statusCode()).isEqualTo(303);
        assertThat(list(call(aliceMcp, "list_accounts"), "accounts").get(0)).containsEntry("read_only", false);

        Map<String, Object> set = call(aliceMcp, "set_flags", "account", aliceAccount, "handles",
                List.of(messageHandle), "flagged", true);

        assertThat(map(set, "summary")).containsEntry("requested", 1).containsEntry("ok", 1);
        assertThat(flagsOf(aliceMail)).contains("\\Flagged").doesNotContain("\\Seen");
        assertThat(auditItems(aliceSub)).isEqualTo(1);
        assertAuditRowsHoldNoHandleFolderOrUid(aliceSub);

        Map<String, Object> undo = call(aliceMcp, "set_flags", "account", aliceAccount, "handles",
                List.of(messageHandle), "flagged", false);

        assertThat(map(undo, "summary")).containsEntry("ok", 1);
        assertThat(flagsOf(aliceMail)).doesNotContain("\\Flagged").doesNotContain("\\Seen");
        assertThat(auditItems(aliceSub)).isEqualTo(2);
    }

    // ---- 7. the second tenant -------------------------------------------------------------------------------------------

    @Test
    @Order(9)
    void bobsTenantIsIsolatedAndAlicesImapAccountSeesNoIo() throws Exception {
        assertThat(bobWeb.login(bob).statusCode()).isEqualTo(200);
        assertThat(tenantRows(bobSub)).isEqualTo(1);
        assertThat(tenantId(bobSub)).isNotEqualTo(tenantId(aliceSub));
        assertThat(bobWeb.post("/accounts/link", linkFields("Bob mail", DeployedDovecot.HOST, bobMail))
                .statusCode()).isEqualTo(303);
        bobMcp = mcpFor(bob);
        int aliceLoginsBefore = loginsOf(aliceMail.name());

        List<Map<String, Object>> accounts = list(call(bobMcp, "list_accounts"), "accounts");
        assertThat(accounts).hasSize(1);
        bobAccount = (String) accounts.get(0).get("account");
        assertThat(bobAccount).isNotEqualTo(aliceAccount);
        assertThat(bobWeb.get("/accounts").body()).doesNotContain("Alice mail");

        Map<String, Object> foreignAccount = call(bobMcp, "list_folders", "account", aliceAccount);
        assertThat(map(foreignAccount, "error")).containsEntry("code", "ACCOUNT_NOT_FOUND");
        Map<String, Object> foreignMessage = call(bobMcp, "get_message", "account", bobAccount, "message",
                messageHandle);
        assertThat(map(foreignMessage, "error")).containsEntry("code", "INVALID_HANDLE");

        assertThat(loginsOf(aliceMail.name())).as("no Login: for Alice's user during Bob's calls")
                .isEqualTo(aliceLoginsBefore);
        assertThat(flagsOf(aliceMail)).doesNotContain("\\Flagged").doesNotContain("\\Seen");
    }

    // ---- 8. the app log -----------------------------------------------------------------------------------------------------

    @Test
    @Order(10)
    void theAppLogAtInfoHoldsNoSubjectSenderPasswordTokenOrSession() throws Exception {
        stack.saveLogs();
        String log = Files.readString(stack.runDir().resolve("logs/app.log"), StandardCharsets.UTF_8);
        assertThat(log).as("the app log was captured").isNotEmpty();
        List<String> canaries = new ArrayList<>(secrets);
        canaries.add(stack.canarySubject());
        canaries.add(SENDER);
        canaries.addAll(aliceWeb.cookieValues());
        canaries.addAll(bobWeb.cookieValues());
        canaries.add(aliceWeb.csrf());
        for (String canary : canaries) {
            assertThat(log.contains(canary)).as("a canary of length %d is in the app log", canary.length()).isFalse();
        }
        for (String body : aliceWeb.responseBodies()) {
            assertThat(body).doesNotContain(aliceMail.password().value());
        }
        for (String out : toolOutputs) {
            assertThat(out).doesNotContain(aliceMail.password().value()).doesNotContain(bobMail.password().value());
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------------

    private McpSyncClient mcpFor(KeycloakAdmin.TestUser user) throws Exception {
        ConnectorClient client = new ConnectorClient(stack);
        String token = accessToken(client, user);
        secrets.add(token);
        secrets.addAll(client.cookieValues());
        McpSyncClient mcp = client.mcp(token);
        mcpClients.add(mcp);
        mcp.initialize();
        return mcp;
    }

    /** The connector's authorization code flow with PKCE (ConnectorClient steps), returning the access token. */
    private String accessToken(ConnectorClient client, KeycloakAdmin.TestUser user) throws Exception {
        HttpResponse<String> unauthorized = client.initializeWithoutToken();
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        AuthParams.Challenge challenge = ConnectorClient.bearerChallenge(unauthorized);
        JsonNode metadata = JSON.readTree(client.get(challenge.param("resource_metadata")).body());
        String authorizationServer = metadata.get("authorization_servers").get(0).asString();
        JsonNode as = JSON.readTree(client.get(authorizationServer + "/.well-known/openid-configuration").body());
        String verifier = Pkce.newVerifier();
        Map<String, String> params = client.authorizationParams(ConnectorClient.CLAUDE_AI_CALLBACK, SCOPE, resource,
                verifier);
        secrets.add(verifier);
        ConnectorClient.AuthorizationOutcome outcome = client.authorize(as.get("authorization_endpoint").asString(),
                params, user);
        assertThat(outcome.redirect()).isNotNull();
        String code = query(outcome.redirect()).get("code");
        assertThat(code).isNotBlank();
        secrets.add(code);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", ConnectorClient.CLAUDE_AI_CALLBACK);
        form.put("client_id", ConnectorClient.CLIENT_ID);
        form.put("code_verifier", verifier);
        form.put("resource", resource);
        HttpResponse<String> token = client.token(as.get("token_endpoint").asString(), form);
        assertThat(token.statusCode()).isEqualTo(200);
        JsonNode json = JSON.readTree(token.body());
        secrets.add(json.get("refresh_token").asString());
        return json.get("access_token").asString();
    }

    private Map<String, Object> call(McpSyncClient mcp, String tool, Object... kv) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            args.put((String) kv[i], kv[i + 1]);
        }
        Map<String, Object> payload = ToolResults.payload(mcp.callTool(new McpSchema.CallToolRequest(tool, args)));
        toolOutputs.add(String.valueOf(payload));
        return payload;
    }

    private static Map<String, String> linkFields(String label, String host, DeployedDovecot.ImapUser mail) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("label", label);
        f.put("host", host);
        f.put("port", String.valueOf(DeployedDovecot.PORT));
        f.put("security", "IMPLICIT_TLS");
        f.put("username", mail.name());
        f.put("password", mail.password().value());
        f.put("access", "read_only");
        return f;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> q = new LinkedHashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            q.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return q;
    }

    // Postgres, through psql in the container (stdout only; the credential columns are only ever tested SQL-side)

    /** SQL for the tenant id of the (issuer, sub) pair: a sub-select, so no id ever has to be read back. */
    private String tenantSql(String sub) {
        return "(select id from tenant where issuer = '" + issuer + "' and subject = '" + sub + "')";
    }

    private String psql(String sql) throws Exception {
        DeployedCompose.ExecResult r = stack.exec("postgres", List.of(), "psql", "-U", "postgres", "-d", "imapmcp",
                "-tAq", "-c", sql);
        assertThat(r.exitCode()).as("psql").isZero();
        return r.stdout().trim();
    }

    private int count(String sql) throws Exception {
        return Integer.parseInt(psql(sql));
    }

    private int tenantRows(String sub) throws Exception {
        return count("select count(*) from tenant where issuer = '" + issuer + "' and subject = '" + sub + "'");
    }

    private String tenantId(String sub) throws Exception {
        return psql("select id from tenant where issuer = '" + issuer + "' and subject = '" + sub + "'");
    }

    private int auditItems(String sub) throws Exception {
        return count("select count(*) from audit_event where tenant_id = " + tenantSql(sub)
                + " and tool = 'set_flags' and ref_hash is not null and item_status = 'ok'");
    }

    private void assertAuditRowsHoldNoHandleFolderOrUid(String sub) throws Exception {
        String rows = psql("select row_to_json(a)::text from audit_event a where tenant_id = " + tenantSql(sub)
                + " and tool = 'set_flags' and ref_hash is not null");
        assertThat(rows).isNotBlank();
        for (String row : rows.split("\n")) {
            JsonNode json = JSON.readTree(row);
            List<String> keys = new ArrayList<>();
            json.propertyNames().forEach(keys::add);
            assertThat(keys).isSubsetOf(AUDIT_COLUMNS);
            assertThat(row.contains(messageHandle)).as("the message handle is in the audit row").isFalse();
            assertThat(row.contains(aliceAccount)).as("the account handle is in the audit row").isFalse();
            assertThat(row.toLowerCase(java.util.Locale.ROOT).contains("inbox")).as("a folder name is in the row")
                    .isFalse();
            assertThat(row.contains(stack.canarySubject())).as("the subject is in the audit row").isFalse();
        }
    }

    // Dovecot: login lines counted per user, flags read with doveadm (independent of the app)

    private int loginsOf(String user) throws Exception {
        stack.saveLogs();
        return (int) Files.readAllLines(stack.runDir().resolve("logs/dovecot.log")).stream()
                .filter(l -> l.contains("Login:") && l.contains("user=<" + user + ">")).count();
    }

    private List<String> flagsOf(DeployedDovecot.ImapUser user) throws Exception {
        DeployedCompose.ExecResult r = stack.exec("dovecot", List.of(), "doveadm", "fetch", "-u", user.name(),
                "flags", "mailbox", "INBOX", "subject", stack.canarySubject());
        assertThat(r.exitCode()).as("doveadm fetch").isZero();
        List<String> flags = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            if (line.startsWith("flags:")) {
                for (String f : line.substring("flags:".length()).trim().split("\\s+")) {
                    if (!f.isEmpty()) {
                        flags.add(f);
                    }
                }
            }
        }
        return flags;
    }
}
