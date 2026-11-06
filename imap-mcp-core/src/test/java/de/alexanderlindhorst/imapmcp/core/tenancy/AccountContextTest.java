package de.alexanderlindhorst.imapmcp.core.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AccountContextTest {

    final TenantId tenant = new TenantId("t1");

    @Test
    void aContextWithoutScopesIsNotScopeLimited() {
        AccountContext ctx = new AccountContext(tenant);
        assertThat(ctx.scopeLimited()).isFalse();
        assertThat(ctx.hasScope("imap-mcp:write")).isTrue();
    }

    @Test
    void aContextWithScopesGrantsExactlyThose() {
        AccountContext ctx = new AccountContext(tenant, Set.of("imap-mcp:read"));
        assertThat(ctx.scopeLimited()).isTrue();
        assertThat(ctx.hasScope("imap-mcp:read")).isTrue();
        assertThat(ctx.hasScope("imap-mcp:write")).isFalse();
        assertThat(ctx.hasScope("imap-mcp:rea")).isFalse();
    }

    @Test
    void anEmptyScopeSetGrantsNothing() {
        AccountContext ctx = new AccountContext(tenant, Set.of());
        assertThat(ctx.scopeLimited()).isTrue();
        assertThat(ctx.hasScope("imap-mcp:read")).isFalse();
    }

    @Test
    void scopesAreCopiedAndImmutable() {
        java.util.HashSet<String> mutable = new java.util.HashSet<>(Set.of("imap-mcp:read"));
        AccountContext ctx = new AccountContext(tenant, mutable);
        mutable.add("imap-mcp:write");
        assertThat(ctx.hasScope("imap-mcp:write")).isFalse();
    }
}
