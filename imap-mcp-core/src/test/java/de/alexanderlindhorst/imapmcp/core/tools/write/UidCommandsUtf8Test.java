package de.alexanderlindhorst.imapmcp.core.tools.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.Folder;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.eclipse.angus.mail.iap.Argument;
import org.eclipse.angus.mail.iap.Protocol;
import org.eclipse.angus.mail.iap.Response;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPFolder.ProtocolCommand;
import org.eclipse.angus.mail.imap.protocol.IMAPProtocol;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** Mailbox encoding follows the protocol state like Angus writeMailboxName (UTF8=ACCEPT enabled: raw UTF-8). */
class UidCommandsUtf8Test {

    private static final String NAME = "Entwürfe";

    private byte[] copyArgument(boolean utf8) throws Exception {
        IMAPFolder folder = mock(IMAPFolder.class);
        when(folder.isOpen()).thenReturn(true);
        when(folder.getMode()).thenReturn(Folder.READ_WRITE);
        IMAPProtocol p = mock(IMAPProtocol.class);
        when(p.supportsUtf8()).thenReturn(utf8);
        Response tagged = mock(Response.class);
        when(tagged.isBYE()).thenReturn(false);
        when(p.command(anyString(), any(Argument.class))).thenReturn(new Response[] {tagged});
        when(folder.doCommand(any())).thenAnswer(inv -> ((ProtocolCommand) inv.getArgument(0)).doCommand(p));
        UidCommands.uidCopy(folder, List.of(1L), NAME);
        ArgumentCaptor<Argument> arg = ArgumentCaptor.forClass(Argument.class);
        verify(p).command(eq("UID COPY 1"), arg.capture());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        // getOutputStream() is protected: answer it by name
        Protocol sink = mock(Protocol.class,
                inv -> "getOutputStream".equals(inv.getMethod().getName()) ? out : Mockito.RETURNS_DEFAULTS.answer(inv));
        arg.getValue().write(sink);
        return bytes.toByteArray();
    }

    @Test
    void utf8ModeWritesRawUtf8() throws Exception {
        byte[] out = copyArgument(true);
        assertThat(new String(out, StandardCharsets.ISO_8859_1))
                .contains(new String(NAME.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    }

    @Test
    void legacyModeWritesModifiedUtf7() throws Exception {
        assertThat(new String(copyArgument(false), StandardCharsets.ISO_8859_1)).contains("Entw&APw-rfe");
    }
}
