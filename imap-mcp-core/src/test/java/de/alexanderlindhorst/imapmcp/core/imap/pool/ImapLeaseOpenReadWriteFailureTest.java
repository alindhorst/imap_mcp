package de.alexanderlindhorst.imapmcp.core.imap.pool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.alexanderlindhorst.imapmcp.core.account.PoolKey;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.MessagingException;
import jakarta.mail.ReadOnlyFolderException;
import jakarta.mail.StoreClosedException;
import java.io.IOException;
import org.eclipse.angus.mail.iap.CommandFailedException;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.junit.jupiter.api.Test;

/** Failure classification of {@link ImapLease#openReadWrite} against a scripted folder (no server). */
class ImapLeaseOpenReadWriteFailureTest {

    private final IMAPStore store = mock(IMAPStore.class);
    private final IMAPFolder folder = mock(IMAPFolder.class);
    private final ToolCallContext ctx = new ToolCallContext(new AccountContext(new TenantId("t1")), "rid");
    private final ImapLease lease = new ImapLease(mock(StorePool.class), mock(PoolKey.class), store, ctx, null);

    private void openFailsWith(MessagingException e) throws Exception {
        when(store.getFolder(anyString())).thenReturn(folder);
        doThrow(e).when(folder).open(Folder.READ_WRITE);
    }

    private boolean isBroken() throws Exception {
        var f = ImapLease.class.getDeclaredField("broken");
        f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicBoolean) f.get(lease)).get();
    }

    @Test
    void connectionLevelFailuresMarkTheLeaseBroken() throws Exception {
        for (MessagingException e : new MessagingException[] {
                new FolderClosedException(folder, "gone"),
                new StoreClosedException(store, "gone"),
                new MessagingException("io", new IOException("reset"))}) {
            ImapLease l = new ImapLease(mock(StorePool.class), mock(PoolKey.class), store, ctx, null);
            when(store.getFolder(anyString())).thenReturn(folder);
            doThrow(e).when(folder).open(Folder.READ_WRITE);
            assertThatThrownBy(() -> l.openReadWrite("X")).isSameAs(e);
            var f = ImapLease.class.getDeclaredField("broken");
            f.setAccessible(true);
            assertThat(((java.util.concurrent.atomic.AtomicBoolean) f.get(l)).get()).as(e.getClass().getSimpleName()).isTrue();
        }
    }

    @Test
    void readOnlyFolderExceptionIsRethrownAndLeaseStaysUsable() throws Exception {
        ReadOnlyFolderException e = new ReadOnlyFolderException(folder, "READ-ONLY");
        openFailsWith(e);
        assertThatThrownBy(() -> lease.openReadWrite("X")).isSameAs(e);
        assertThat(isBroken()).isFalse();
        assertThat(lease.store()).isSameAs(store);
    }

    @Test
    void noAnswerIsRethrownAndLeaseStaysUsable() throws Exception {
        MessagingException e = new MessagingException("NO [NOPERM] denied",
                new CommandFailedException("NO denied"));
        openFailsWith(e);
        assertThatThrownBy(() -> lease.openReadWrite("X")).isSameAs(e);
        assertThat(isBroken()).isFalse();
        assertThat(lease.store()).isSameAs(store);
    }
}
