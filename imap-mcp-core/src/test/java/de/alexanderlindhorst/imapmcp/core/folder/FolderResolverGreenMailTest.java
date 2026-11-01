package de.alexanderlindhorst.imapmcp.core.folder;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.account.CredentialStore;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Flavor;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount.Security;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.ResolvedAccount;
import de.alexanderlindhorst.imapmcp.core.account.Secret;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.imap.CapabilityCache;
import de.alexanderlindhorst.imapmcp.core.imap.ImapSessionFactory;
import de.alexanderlindhorst.imapmcp.core.imap.ImapTimeouts;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.PoolSettings;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StoreLeases;
import de.alexanderlindhorst.imapmcp.core.imap.pool.StorePool;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Folder resolution against GreenMail, which does not advertise SPECIAL-USE (AC2, AC3). */
class FolderResolverGreenMailTest {

    private static final String USER = "resolveuser";
    private static final TenantId TENANT = new TenantId("t1");

    private GreenMail greenMail;
    private ImapSessionFactory sessions;
    private StorePool pool;
    private StoreLeases leases;
    private PoolKey key;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(ServerSetupTest.IMAPS.dynamicPort());
        greenMail.start();
        greenMail.setUser(USER + "@localhost", USER, "pw");
        MailAccount account = new MailAccount(new AccountId("a"), "label", null, "127.0.0.1",
                greenMail.getImaps().getPort(), Security.IMPLICIT_TLS, Flavor.GENERIC, USER, new SecretRef("env:X"));
        key = PoolKey.of(new AccountContext(TENANT), new ResolvedAccount(TENANT, account));
        CredentialStore credentials = (t, a) -> Optional.of(new Secret("pw".toCharArray()));
        sessions = new ImapSessionFactory(ImapTimeouts.DEFAULTS, p -> {
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
            return p;
        }, true);
        PoolSettings s = new PoolSettings(3, 64, Duration.ofMinutes(5), Duration.ofSeconds(60),
                Duration.ofMinutes(30), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(120));
        pool = new StorePool(sessions, (t, a) -> credentials.resolve(t, a), new CapabilityCache(),
                k -> k.equals(key) ? account : null, s);
        leases = new StoreLeases(pool, Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() {
        pool.close();
        sessions.close();
        greenMail.stop();
    }

    private FolderResolver.Resolution resolve(String folder, FolderRole role) throws Exception {
        try (ImapLease lease = leases.lease(key, new ToolCallContext(new AccountContext(TENANT), "rid"))) {
            return FolderResolver.resolve(lease, folder, role, "rid");
        }
    }

    private void createFolder(String name) throws Exception {
        greenMail.getManagers().getImapHostManager().createMailbox(
                greenMail.getUserManager().getUserByEmail(USER + "@localhost"), name);
    }

    @Test
    void roleSentIsNotFoundEvenWhenFolderNamedSentExists() throws Exception {
        createFolder("Sent");
        FolderResolver.Resolution r = resolve(null, FolderRole.SENT);
        assertThat(r.error().code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(r.error().details()).isEqualTo(Map.of("folder_role", "sent"));
    }

    @Test
    void inboxResolvesByNameAndByRoleAndByDefault() throws Exception {
        assertThat(resolve("INBOX", null).fullName()).isEqualTo("INBOX");
        assertThat(resolve("inbox", null).fullName()).isEqualTo("INBOX");
        assertThat(resolve(null, FolderRole.INBOX).fullName()).isEqualTo("INBOX");
        assertThat(resolve(null, null).fullName()).isEqualTo("INBOX");
    }

    @Test
    void existingFolderResolvesAndUnknownIsNotFound() throws Exception {
        createFolder("Work");
        assertThat(resolve("Work", null).fullName()).isEqualTo("Work");
        FolderResolver.Resolution r = resolve("NoSuchFolder", null);
        assertThat(r.error().code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(r.error().details()).isEqualTo(Map.of("folder", "NoSuchFolder"));
    }

    @Test
    void noselectFolderIsNotFound() throws Exception {
        createFolder("Parent.Child");
        try (ImapLease l = leases.lease(key, new ToolCallContext(new AccountContext(TENANT), "rid"))) {
            var pf = (org.eclipse.angus.mail.imap.IMAPFolder) l.store().getFolder("Parent");
            assertThat(pf.exists()).isTrue(); // the parent exists in LIST ...
            assertThat(pf.getAttributes()).contains("\\Noselect"); // ... but is not selectable
        }
        FolderResolver.Resolution r = resolve("Parent", null);
        assertThat(r.error().code()).isEqualTo(ErrorCode.FOLDER_NOT_FOUND);
        assertThat(r.error().details()).isEqualTo(Map.of("folder", "Parent"));
        assertThat(resolve("Parent.Child", null).fullName()).isEqualTo("Parent.Child");
    }

    @Test
    void bothParametersAreInvalidArgumentAndNeedNoFolderOpen() throws Exception {
        FolderResolver.Resolution r = resolve("INBOX", FolderRole.SENT);
        assertThat(r.error().code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void resolutionIssuesOnlyStoreLevelCommands() throws Exception {
        createFolder("Work");
        // closing the lease after resolve must not find an open folder: resolving never SELECT/EXAMINEs
        try (ImapLease lease = leases.lease(key, new ToolCallContext(new AccountContext(TENANT), "rid"))) {
            FolderResolver.resolve(lease, "Work", null, "rid");
            FolderResolver.resolve(lease, null, FolderRole.SENT, "rid");
            assertThat(lease.store()).isNotNull(); // would throw if a folder were open
        }
    }
}
