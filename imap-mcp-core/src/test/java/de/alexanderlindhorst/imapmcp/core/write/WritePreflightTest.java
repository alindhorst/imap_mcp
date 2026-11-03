package de.alexanderlindhorst.imapmcp.core.write;

import static org.assertj.core.api.Assertions.assertThat;

import de.alexanderlindhorst.imapmcp.core.account.InMemoryAccountRepository;
import de.alexanderlindhorst.imapmcp.core.account.MailAccount;
import de.alexanderlindhorst.imapmcp.core.account.SecretRef;
import de.alexanderlindhorst.imapmcp.core.crypto.KeyRing;
import de.alexanderlindhorst.imapmcp.core.error.ErrorCode;
import de.alexanderlindhorst.imapmcp.core.error.ToolError;
import de.alexanderlindhorst.imapmcp.core.handle.MessageHandles;
import de.alexanderlindhorst.imapmcp.core.handle.MessageRef;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountContext;
import de.alexanderlindhorst.imapmcp.core.tenancy.AccountId;
import de.alexanderlindhorst.imapmcp.core.tenancy.TenantId;
import de.alexanderlindhorst.imapmcp.core.tool.ToolCallContext;
import de.alexanderlindhorst.imapmcp.core.tools.AccountArgument;
import de.alexanderlindhorst.imapmcp.core.tools.MessageLocator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test: the preflight has no IMAP dependency at all (no lease, no store), so a bad call cannot cost a
 * connection by construction.
 */
class WritePreflightTest {

    static final TenantId A = new TenantId("tenant-a");
    static final TenantId B = new TenantId("tenant-b");

    static byte[] key(int fill) {
        byte[] k = new byte[32];
        Arrays.fill(k, (byte) fill);
        return k;
    }

    static final Map<Integer, byte[]> KEYS = Map.of(1, key(0x11), 2, key(0x22));
    static final MessageHandles KID1 = new MessageHandles(new KeyRing(1, KEYS));
    static final MessageHandles KID2 = new MessageHandles(new KeyRing(2, KEYS));

    static MailAccount account(String id, boolean readOnly) {
        return new MailAccount(new AccountId(id), "L " + id, null, "127.0.0.1", 993,
                MailAccount.Security.IMPLICIT_TLS, MailAccount.Flavor.GENERIC, "u", new SecretRef("env:X"), readOnly);
    }

    final InMemoryAccountRepository repo = new InMemoryAccountRepository(Map.of(
            A, List.of(account("one", false), account("ro", true)),
            B, List.of(account("two", false))));
    final WritePreflight preflight = new WritePreflight(new AccountArgument(repo), new MessageLocator(KID2));

    static ToolCallContext ctx(TenantId t) {
        return new ToolCallContext(new AccountContext(t), "RID");
    }

    static String acc(TenantId t, String id) {
        return InMemoryAccountRepository.handle(t, new AccountId(id));
    }

    static String h(MessageHandles hs, TenantId t, String account, String folder, long uid) {
        return hs.encode(t, new AccountId(account), new MessageRef(folder, 7, uid));
    }

    static String h(String folder, long uid) {
        return h(KID2, A, "one", folder, uid);
    }

    WritePreflight.Result run(List<String> handles, String target) {
        return preflight.check(ctx(A), acc(A, "one"), handles, target);
    }

    static ToolError error(WritePreflight.Result r) {
        return ((WritePreflight.Result.Failed) r).error();
    }

    @Test
    void unknownAndForeignAccountAreAccountNotFound() {
        for (String a : new String[] {"acc_nonsense", acc(A, "missing"), acc(B, "two")}) {
            WritePreflight.Result r = preflight.check(ctx(A), a, List.of(h("INBOX", 1)), null);
            assertThat(error(r).code()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
        }
    }

    @Test
    void readOnlyAccountIsDeniedBeforeHandlesAreChecked() {
        WritePreflight.Result r = preflight.check(ctx(A), acc(A, "ro"), List.of("garbage"), null);
        ToolError e = error(r);
        assertThat(e.code()).isEqualTo(ErrorCode.PERMISSION_DENIED);
        assertThat(e.details()).containsEntry("reason", "account_read_only");
    }

    @Test
    void firstBadHandleNamesItsPositionAndAllClassesAreIdentical() {
        String good = h("INBOX", 1);
        String tampered = good.substring(0, good.length() - 2) + (good.endsWith("AA") ? "BB" : "AA");
        String foreignTenant = h(KID2, B, "one", "INBOX", 9);
        String otherAccount = h(KID2, A, "other", "INBOX", 9);
        String unknownKey = KID2.encode(A, new AccountId("one"), new MessageRef("INBOX", 7, 9));
        // unknown key id: a ring that signs with kid 3, which the preflight's ring does not know
        String kid3 = new MessageHandles(new KeyRing(3, Map.of(3, key(0x33)))).encode(A, new AccountId("one"),
                new MessageRef("INBOX", 7, 9));
        assertThat(unknownKey).isNotEqualTo(kid3);
        List<String> bad = List.of("garbage", tampered, foreignTenant, otherAccount, kid3);
        String expected = null;
        for (String b : bad) {
            ToolError e = error(run(List.of(good, b, "also-bad"), null));
            assertThat(e.code()).isEqualTo(ErrorCode.INVALID_HANDLE);
            assertThat(e.details()).containsEntry("field", "handles[1]");
            String payload = e.toMap().toString();
            expected = expected == null ? payload : expected;
            assertThat(payload).isEqualTo(expected);
        }
        assertThat(error(run(List.of("garbage"), null)).details()).containsEntry("field", "handles[0]");
    }

    @Test
    void sameDecodedReferenceUnderTwoKeyIdsIsDuplicateAtLaterIndex() {
        String viaKid1 = h(KID1, A, "one", "Work", 5);
        String viaKid2 = h(KID2, A, "one", "Work", 5);
        assertThat(viaKid1).isNotEqualTo(viaKid2);
        ToolError e = error(run(List.of(h("Work", 4), viaKid1, h("Work", 6), viaKid2), null));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.details()).containsEntry("field", "handles[3]").containsEntry("reason", "duplicate");
    }

    @Test
    void inboxSpellingsAreOneFolderForDuplicates() {
        ToolError e = error(run(List.of(h("INBOX", 1), h("inbox", 1)), null));
        assertThat(e.details()).containsEntry("field", "handles[1]").containsEntry("reason", "duplicate");
    }

    @Test
    void tenFoldersPassElevenAreTooMany() {
        List<String> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(h("F" + i, 1));
        }
        assertThat(run(ten, null)).isInstanceOf(WritePreflight.Result.Ok.class);
        List<String> eleven = new ArrayList<>(ten);
        eleven.add(h("F10", 1));
        ToolError e = error(run(eleven, null));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.details()).containsEntry("field", "handles").containsEntry("reason", "too_many_folders")
                .containsEntry("max", 10);
    }

    @Test
    void groupsByVerbatimFolderInOrderOfFirstAppearanceKeepingInputIndex() {
        List<String> in = List.of(h("B", 1), h("a", 1), h("inbox", 2), h("B", 2), h("A", 1), h("INBOX", 3));
        WritePreflight.Result.Ok ok = (WritePreflight.Result.Ok) run(in, null);
        assertThat(ok.account().account().id()).isEqualTo(new AccountId("one"));
        assertThat(ok.groups()).extracting(WritePreflight.Group::folder).containsExactly("B", "a", "INBOX", "A");
        assertThat(ok.groups().get(0).items()).extracting(WritePreflight.Item::index).containsExactly(0, 3);
        assertThat(ok.groups().get(2).items()).extracting(WritePreflight.Item::index).containsExactly(2, 5);
        assertThat(ok.groups().get(2).items()).extracting(i -> i.ref().folder()).containsOnly("INBOX");
        assertThat(ok.groups().get(0).items().get(1).handle()).isEqualTo(in.get(3));
        assertThat(ok.groups().get(0).items().get(1).ref().uid()).isEqualTo(2);
    }

    @Test
    void targetEqualToASourceFolderIsSameFolder() {
        ToolError e = error(run(List.of(h("Work", 1), h("Other", 1)), "Other"));
        assertThat(e.code()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        assertThat(e.details()).containsEntry("field", "target_folder").containsEntry("reason", "same_folder");
        // INBOX as in v1: case-insensitive
        assertThat(error(run(List.of(h("INBOX", 1)), "Inbox")).details()).containsEntry("reason", "same_folder");
        // other folder names are verbatim
        assertThat(run(List.of(h("Work", 1)), "work")).isInstanceOf(WritePreflight.Result.Ok.class);
        assertThat(run(List.of(h("Work", 1)), null)).isInstanceOf(WritePreflight.Result.Ok.class);
    }
}
