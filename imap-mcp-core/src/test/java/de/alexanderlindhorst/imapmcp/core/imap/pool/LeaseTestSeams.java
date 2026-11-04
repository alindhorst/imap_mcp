package de.alexanderlindhorst.imapmcp.core.imap.pool;

import static org.mockito.Mockito.mock;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import org.eclipse.angus.mail.imap.IMAPStore;

/** Test-only access to package-private lease internals; lives in test sources, so production cannot reach it. */
public final class LeaseTestSeams {

    private LeaseTestSeams() { }

    /** A lease over the given (typically mocked) store, with a mocked pool. */
    public static ImapLease lease(IMAPStore store, ToolCallContext ctx) {
        return new ImapLease(mock(StorePool.class), mock(PoolKey.class), store, ctx, null);
    }

    public static void forceCapabilities(ImapLease lease, boolean move, boolean uidplus) {
        lease.overrideCapabilities(new WriteCapabilities(move, uidplus));
    }
}
