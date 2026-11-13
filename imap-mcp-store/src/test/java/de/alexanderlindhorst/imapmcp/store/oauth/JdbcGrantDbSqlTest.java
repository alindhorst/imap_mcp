package de.alexanderlindhorst.imapmcp.store.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.alexanderlindhorst.imapmcp.core.audit.EventInfo;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.store.crypto.CredentialCipher;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** SQL shape of {@link JdbcGrantDb} without a database (the Postgres behaviour is covered by the ITs, X2-4). */
class JdbcGrantDbSqlTest {

    private static final UUID T = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void everyStatementIsTenantScopedTheLockTimeoutIsLocalAndTheTransactionCommits() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        Statement st = mock(Statement.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        List<String> sql = new ArrayList<>();
        when(ds.getConnection()).thenReturn(c);
        when(c.createStatement()).thenReturn(st);
        when(c.prepareStatement(anyString())).thenAnswer(i -> {
            sql.add(i.getArgument(0));
            return ps;
        });
        when(ps.executeQuery()).thenReturn(rs);

        new JdbcGrantDb(ds).inTransaction(tx -> {
            tx.lockGrant(T, "a");
            tx.readRefresh(T, "a", 1);
            tx.insertRefresh(T, "a", 2, new CredentialCipher.Sealed("k", new byte[1], new byte[1], new byte[1]));
            tx.pointTo(T, "a", 2);
            tx.deleteRefresh(T, "a", 1);
            tx.markNeedsRelink(T, "a");
            tx.audit(new EventInfo("credential_revoked", new TenantId(T.toString()), new AccountId("a"), "r", "x"));
            return null;
        });

        assertThat(sql).hasSize(7).allSatisfy(s -> assertThat(s).contains("tenant_id"));
        assertThat(sql.getFirst()).contains("FOR UPDATE");
        assertThat(sql.get(5)).contains("tenant_id = ? AND id = ?");
        assertThat(sql).filteredOn(s -> s.contains("credential")).allSatisfy(s -> assertThat(s)
                .contains("oauth_refresh").doesNotContain("password"));
        InOrder order = inOrder(c, st);
        order.verify(c).setAutoCommit(false);
        order.verify(st).execute("SET LOCAL lock_timeout = '10s'");
        order.verify(c).commit();
    }
}
