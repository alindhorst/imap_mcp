package de.alexanderlindhorst.imapmcp.it;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * Run-specific scope of the smoke write checks (#258): two test-owned labels/folders named after a fresh run id, and
 * messages whose subject carries the run id. Everything the write checks touch is created here, found through the
 * run id, and removed in {@link #close()}; nothing else in the mailbox is ever addressed. Messages are appended
 * directly into the test label (a test-side IMAP connection, the server has no append tool) so no existing mail is
 * used. Cleanup moves the test messages to Trash through {@code delete_messages} (the server cannot delete
 * permanently); the mail provider purges Trash on its own schedule (Gmail: 30 days).
 */
final class SmokeWriteFixture implements AutoCloseable {

    /** Sender and recipient of the test messages: never a real address, and always with a domain. */
    private static final String TEST_ADDRESS = "imap-mcp-smoke@example.invalid";

    final SmokeClient client;
    final String runId = SmokeEnv.runLabel();
    /** Full names of the two test folders. */
    final String label;
    final String label2;
    private final SmokeEnv.Settings settings;
    private final List<String> cleanupRoles;

    /** @param cleanupRoles special-use roles whose folders may hold test messages (Gmail: all, junk). */
    SmokeWriteFixture(SmokeEnv.Settings settings, List<String> cleanupRoles) throws Exception {
        this.settings = settings;
        this.cleanupRoles = cleanupRoles;
        this.client = SmokeClient.start(settings, true);
        this.label = createFolder(runId);
        this.label2 = createFolder(runId + "-b");
    }

    private String createFolder(String name) {
        SmokeClient.Result r = client.ok("create_folder", "name", name);
        assertThat(r.structured()).containsEntry("created", true);
        return (String) r.structured().get("folder");
    }

    String subject(String tag) {
        return runId + " " + tag;
    }

    /** Appends a new message into {@link #label} and returns its handle. */
    String seed(String tag) throws Exception {
        try (IMAPStore store = connect()) {
            MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
            m.setFrom(new InternetAddress(TEST_ADDRESS));
            m.setRecipient(Message.RecipientType.TO, new InternetAddress(TEST_ADDRESS));
            m.setSubject(subject(tag));
            m.setText("imap-mcp smoke test message, safe to delete");
            Folder f = store.getFolder(label);
            f.appendMessages(new Message[] {m});
        }
        return only(label, tag);
    }

    /** The handle of the one test message with this tag in the folder; fails if there is none or several. */
    String only(String folder, String tag) {
        List<Map<String, Object>> found = find(folder, tag);
        assertThat(found).as("messages tagged %s in the folder", tag).hasSize(1);
        return (String) found.get(0).get("handle");
    }

    List<Map<String, Object>> find(String folder, String tag) {
        return client.search("folder", folder, "subject", subject(tag));
    }

    private IMAPStore connect() throws MessagingException {
        Properties p = new Properties();
        if (settings.trustStore() != null) { // local dry run against the Dovecot test CA only
            p.put("mail.imaps.ssl.trust", "*");
            p.put("mail.imaps.ssl.checkserveridentity", "false");
        }
        p.put("mail.imaps.connectiontimeout", "15000");
        p.put("mail.imaps.timeout", "60000");
        IMAPStore store = (IMAPStore) Session.getInstance(p).getStore("imaps");
        store.connect(settings.host(), settings.port(), settings.user(), settings.password());
        return store;
    }

    @Override
    public void close() throws Exception {
        List<String> folders = new ArrayList<>(List.of(label, label2));
        for (String role : cleanupRoles) {
            SmokeClient.Result r = client.tool("search_messages", "folder_role", role, "subject", runId);
            if (!r.isError()) {
                trash(r.list("messages"), "folder_role", role);
            }
        }
        for (String folder : folders) {
            trash(client.search("folder", folder, "subject", runId), "folder", folder);
        }
        client.close();
        try (IMAPStore store = connect()) {
            for (String folder : folders) {
                // Only folders of this run id are ever deleted.
                if (folder.contains(runId)) {
                    Folder f = store.getFolder(folder);
                    if (f.exists()) {
                        f.delete(false);
                    }
                }
            }
        }
    }

    private void trash(List<Map<String, Object>> messages, String kind, String where) {
        if (messages.isEmpty()) {
            return;
        }
        client.tool("delete_messages", "handles", messages.stream().map(m -> m.get("handle")).toList());
    }
}
