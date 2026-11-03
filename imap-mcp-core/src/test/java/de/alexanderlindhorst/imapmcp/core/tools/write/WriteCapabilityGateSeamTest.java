package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.imap.pool.ImapLease;
import de.alexanderlindhorst.imapmcp.core.imap.pool.LeaseTestSeams;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.Test;

/** The forced-capability seam is usable from the write package, and a refusal touches no folder. */
class WriteCapabilityGateSeamTest {

    private final IMAPStore store = mock(IMAPStore.class);
    private final ToolCallContext ctx = new ToolCallContext(new AccountContext(new TenantId("t1")), "rid");

    @Test
    void forcedNoUidplusIsRefusedWithoutTouchingTheStore() throws Exception {
        when(store.hasCapability("UIDPLUS")).thenReturn(true);
        ImapLease lease = LeaseTestSeams.lease(store, ctx);
        LeaseTestSeams.forceCapabilities(lease, false, false);
        var refusal = lease.capabilities().requireUidplus("rid");
        assertThat(refusal).isPresent();
        assertThat(refusal.get().code()).isEqualTo(ErrorCode.UNSUPPORTED);
        assertThat(lease.capabilities().move()).isFalse();
        verifyNoMoreInteractions(store);
    }

    @Test
    void unforcedLeaseReadsTheConnection() throws Exception {
        when(store.hasCapability("MOVE")).thenReturn(true);
        when(store.hasCapability("UIDPLUS")).thenReturn(false);
        ImapLease lease = LeaseTestSeams.lease(store, ctx);
        assertThat(lease.capabilities()).isEqualTo(new WriteCapabilities(true, false));
        verify(store).hasCapability("MOVE");
    }
}
