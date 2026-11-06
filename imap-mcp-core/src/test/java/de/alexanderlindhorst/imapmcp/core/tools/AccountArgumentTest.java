package de.alexanderlindhorst.imapmcp.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AccountArgumentTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");

    static MailAccount account(String id) {
        return new MailAccount(new AccountId(id), "Label " + id, null, "127.0.0.1", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "u", new SecretRef("env:X"));
    }

    final InMemoryAccountRepository repo = new InMemoryAccountRepository(
            Map.of(A, List.of(account("one")), B, List.of(account("two"))));
    final AccountArgument argument = new AccountArgument(repo);

    static ToolCallContext ctx(TenantId t, String rid) {
        return new ToolCallContext(new AccountContext(t), rid);
    }

    static String handleOf(TenantId t, String id) {
        return InMemoryAccountRepository.handle(t, new AccountId(id));
    }

    @Test
    void ownHandleResolvesWithPoolKey() {
        ToolCallContext c = ctx(A, "r1");
        AccountArgument.Result r = argument.resolve(c, handleOf(A, "one"));
        AccountArgument.Result.Ok ok = (AccountArgument.Result.Ok) r;
        assertThat(ok.account().account().id()).isEqualTo(new AccountId("one"));
        assertThat(ok.key()).isEqualTo(PoolKey.of(c.accountContext(), ok.account()));
        assertThat(ok.key().tenantId()).isEqualTo(A);
    }

    @Test
    void malformedUnknownAndForeignHandlesGiveIdenticalPayloads() {
        String unknown = handleOf(A, "does-not-exist");
        String foreign = handleOf(B, "two");
        List<String> inputs = java.util.Arrays.asList(null, "", "garbage", "acc_", "acc_%%%", unknown, foreign);
        String expected = null;
        for (String in : inputs) {
            AccountArgument.Result r = argument.resolve(ctx(A, "RID"), in);
            assertThat(r).as("input %s", in).isInstanceOf(AccountArgument.Result.NotFound.class);
            ToolError e = ((AccountArgument.Result.NotFound) r).error();
            assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
            String payload = e.toMap().toString();
            if (expected == null) {
                expected = payload;
            }
            assertThat(payload).as("input %s", in).isEqualTo(expected);
        }
        // only request_id differs
        ToolError other = ((AccountArgument.Result.NotFound) argument.resolve(ctx(A, "OTHER"), foreign)).error();
        assertThat(other.toMap().toString()).isEqualTo(expected.replace("RID", "OTHER"));
    }

    static MailAccount relinkAccount(String id) {
        return new MailAccount(new AccountId(id), "Label " + id, null, "127.0.0.1", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "u", new SecretRef("env:X"), false,
                false, MailAccount.Auth.PASSWORD, MailAccount.Status.NEEDS_RELINK, 4);
    }

    @Test
    void aNeedsRelinkAccountIsRefusedBeforeAnyIoWithTheLinkUrlAndStaysBoundForTheAudit() {
        InMemoryAccountRepository r = new InMemoryAccountRepository(Map.of(A, List.of(relinkAccount("old"))));
        AccountArgument arg = new AccountArgument(r, "https://mail.example.com/accounts");
        ToolCallContext c = ctx(A, "r9");
        AccountArgument.Result res = arg.resolve(c, handleOf(A, "old"));
        ToolError e = ((AccountArgument.Result.NotFound) res).error();
        assertThat(e.code()).isEqualTo(ErrorCode.AUTH_FAILED);
        assertThat(e.details()).containsEntry("reason", "relink_required")
                .containsEntry("link_url", "https://mail.example.com/accounts");
        assertThat(e.toMap().toString()).contains("r9");
    }

    @Test
    void aNeedsRelinkAccountOfAnotherTenantIsStillJustNotFound() {
        InMemoryAccountRepository r = new InMemoryAccountRepository(Map.of(A, List.of(relinkAccount("old")),
                B, List.of(account("two"))));
        AccountArgument arg = new AccountArgument(r, "https://mail.example.com/accounts");
        ToolError e = ((AccountArgument.Result.NotFound) arg.resolve(ctx(B, "r"), handleOf(A, "old"))).error();
        assertThat(e.code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
    }
}
