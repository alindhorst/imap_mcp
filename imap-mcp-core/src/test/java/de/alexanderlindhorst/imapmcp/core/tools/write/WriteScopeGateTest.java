package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.Scopes;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D3: the write tools and purge need {@code imap-mcp:write}, checked per call before any I/O (SPEC 4, 5.3). */
class WriteScopeGateTest {

    static final TenantId T = new TenantId("tenant-a");

    static MailAccount account(String id, boolean readOnly) {
        return new MailAccount(new AccountId(id), id, null, "127.0.0.1", 993, MailAccount.Security.IMPLICIT_TLS,
                MailAccount.Flavor.GENERIC, "u", new SecretRef("env:X"), readOnly);
    }

    final InMemoryAccountRepository repo = new InMemoryAccountRepository(
            Map.of(T, List.of(account("rw", false), account("ro", true))));
    final WritePreflight preflight = new WritePreflight(new AccountArgument(repo),
            new MessageLocator(new MessageHandles(new KeyRing(1, Map.of(1, new byte[32])))));

    static ToolCallContext ctx(Set<String> scopes) {
        return new ToolCallContext(scopes == null ? new AccountContext(T) : new AccountContext(T, scopes), "rid");
    }

    String handle(String id) {
        return InMemoryAccountRepository.handle(T, new AccountId(id));
    }

    WritePreflight.Result.Failed failed(WritePreflight.Result r) {
        return (WritePreflight.Result.Failed) r;
    }

    @Test
    void aTokenWithoutTheWriteScopeIsRefusedWithInsufficientScope() {
        var r = failed(preflight.check(ctx(Set.of(Scopes.READ)), handle("rw"), List.of("x"), null));
        assertThat(r.error().code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(r.error().details()).containsEntry("reason", "insufficient_scope")
                .containsEntry("scope", Scopes.WRITE);
    }

    @Test
    void theScopeIsCheckedBeforeTheReadOnlyAndTheHandles() {
        var r = failed(preflight.check(ctx(Set.of(Scopes.READ)), handle("ro"), List.of("not-a-handle"), null));
        assertThat(r.error().details()).containsEntry("reason", "insufficient_scope");
    }

    @Test
    void anUnknownAccountStaysAccountNotFoundSoTheScopeLeaksNothingAboutAccounts() {
        var r = failed(preflight.check(ctx(Set.of(Scopes.READ)), "acc_00000000000000000000000000", List.of("x"), null));
        assertThat(r.error().code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }

    @Test
    void withTheWriteScopeTheNormalChecksFollow() {
        var r = failed(preflight.check(ctx(Set.of(Scopes.READ, Scopes.WRITE)), handle("ro"), List.of("x"), null));
        assertThat(r.error().details()).containsEntry("reason", "account_read_only");
    }

    @Test
    void anUnscopedContextIsNotAffected() {
        var r = failed(preflight.check(ctx(null), handle("ro"), List.of("x"), null));
        assertThat(r.error().details()).containsEntry("reason", "account_read_only");
    }

    @Test
    void anEmptyScopeSetIsRefused() {
        var r = failed(preflight.check(ctx(Set.of()), handle("rw"), List.of("x"), null));
        assertThat(r.error().details()).containsEntry("reason", "insufficient_scope");
    }
}
