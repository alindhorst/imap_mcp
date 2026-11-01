package de.alexanderlindhorst.imapmcp.core.mime;

import static de.alexanderlindhorst.imapmcp.core.mime.BodyStructureFixtures.binary;
import static de.alexanderlindhorst.imapmcp.core.mime.BodyStructureFixtures.message;
import static de.alexanderlindhorst.imapmcp.core.mime.BodyStructureFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.mime.AttachmentList.Entry;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.internet.MimeUtility;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Fixtures are real BODYSTRUCTUREs fetched from GreenMail by UID over IMAPS (no body fetch). */
class AttachmentClassifierTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private GreenMailUser user;

    @BeforeEach
    void purge() throws Exception {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
        user = GREEN_MAIL.setUser("user@localhost", "user", "secret");
    }

    private BODYSTRUCTURE structureOf(MimeMessage msg) throws Exception {
        return BodyStructureFixtures.fetch(GREEN_MAIL, user, msg);
    }

    private static String enc(String s) throws Exception {
        return MimeUtility.encodeText(s, "UTF-8", "B");
    }

    // ---- AC1 ----

    @Test
    void textPlusPdfIsOneAttachment() throws Exception {
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(text("plain", "hello"));
        mp.addBodyPart(binary("application/pdf", new byte[300], "attachment; filename=\"Report.PDF\""));
        BODYSTRUCTURE bs = structureOf(message(mp));

        AttachmentList list = AttachmentClassifier.classify(bs, "1");
        assertThat(list.entries()).hasSize(1);
        Entry e = list.entries().get(0);
        assertThat(e.part()).isEqualTo("2");
        assertThat(e.untrusted().contentType()).isEqualTo("application/pdf");
        assertThat(e.untrusted().filename()).isEqualTo("Report.PDF");
        assertThat(e.inline()).isFalse();
        assertThat(e.size()).isGreaterThan(0).isLessThan(600);
        assertThat(list.total()).isEqualTo(1);
        assertThat(list.truncated()).isFalse();
        assertThat(AttachmentClassifier.hasAttachments(bs, "1")).isTrue();
    }

    // ---- AC2 ----

    @Test
    void alternativeOnlyHasNoAttachments() throws Exception {
        MimeMultipart mp = new MimeMultipart("alternative");
        mp.addBodyPart(text("plain", "hello"));
        mp.addBodyPart(text("html", "<p>hello</p>"));
        BODYSTRUCTURE bs = structureOf(message(mp));

        assertThat(AttachmentClassifier.classify(bs, "1").entries()).isEmpty();
        assertThat(AttachmentClassifier.hasAttachments(bs, "1")).isFalse();
    }

    // ---- AC3 ----

    @Test
    void relatedCidImageWithoutFilenameIsNotAnAttachment() throws Exception {
        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(text("html", "<img src=cid:x>"));
        MimeBodyPart img = binary("image/png", new byte[50], null);
        img.setContentID("<x@localhost>");
        related.addBodyPart(img);
        BODYSTRUCTURE bs = structureOf(message(related));

        assertThat(AttachmentClassifier.classify(bs, "1").entries()).isEmpty();
        assertThat(AttachmentClassifier.hasAttachments(bs, "1")).isFalse();
    }

    @Test
    void relatedCidImageWithFilenameIsInlineAttachment() throws Exception {
        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(text("html", "<img src=cid:x>"));
        MimeBodyPart img = binary("image/png", new byte[50], "inline; filename=\"logo.png\"");
        img.setContentID("<x@localhost>");
        related.addBodyPart(img);
        BODYSTRUCTURE bs = structureOf(message(related));

        AttachmentList list = AttachmentClassifier.classify(bs, "1");
        assertThat(list.entries()).hasSize(1);
        assertThat(list.entries().get(0).part()).isEqualTo("2");
        assertThat(list.entries().get(0).inline()).isTrue();
        assertThat(list.entries().get(0).untrusted().filename()).isEqualTo("logo.png");
        assertThat(AttachmentClassifier.hasAttachments(bs, "1")).isFalse();
    }

    // ---- AC4 ----

    @Test
    void forwardedMessageIsOneEntryWithoutRecursion() throws Exception {
        MimeMultipart innerMp = new MimeMultipart("mixed");
        innerMp.addBodyPart(text("plain", "inner"));
        innerMp.addBodyPart(binary("application/pdf", new byte[20], "attachment; filename=\"inner.pdf\""));
        MimeMessage inner = message(innerMp);

        MimeBodyPart fwd = new MimeBodyPart();
        fwd.setContent(inner, "message/rfc822");
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(text("plain", "see attached"));
        mp.addBodyPart(fwd);
        BODYSTRUCTURE bs = structureOf(message(mp));

        AttachmentList list = AttachmentClassifier.classify(bs, "1");
        assertThat(list.entries()).hasSize(1);
        assertThat(list.entries().get(0).part()).isEqualTo("2");
        assertThat(list.entries().get(0).untrusted().contentType()).isEqualTo("message/rfc822");
        assertThat(list.entries().get(0).untrusted().filename()).isNull();
    }

    // ---- AC5 ----

    @Test
    void hostileEncodedFilenameIsSanitized() throws Exception {
        String hostile = "..\\..\\evil\u202Efdp.exe";
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(text("plain", "x"));
        mp.addBodyPart(binary("application/octet-stream", new byte[5],
                "attachment; filename=\"" + enc(hostile) + "\""));
        BODYSTRUCTURE bs = structureOf(message(mp));

        String name = AttachmentClassifier.classify(bs, "1").entries().get(0).untrusted().filename();
        assertThat(name).isEqualTo("evilfdp.exe");
    }

    @Test
    void rfc2231FilenameIsDecodedWithoutGlobalProperty() throws Exception {
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(text("plain", "x"));
        mp.addBodyPart(binary("application/octet-stream", new byte[5],
                "attachment; filename*=UTF-8''%C3%A4%2Ftxt.doc"));
        BODYSTRUCTURE bs = structureOf(message(mp));

        assertThat(AttachmentClassifier.classify(bs, "1").entries().get(0).untrusted().filename())
                .isEqualTo("txt.doc");
    }

    @Test
    void emptyFilenameBecomesNullAndUnnamedNonTextInMixedCounts() throws Exception {
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(text("plain", "x"));
        mp.addBodyPart(binary("image/jpeg", new byte[5], "inline; filename=\"/\""));
        mp.addBodyPart(binary("application/zip", new byte[5], null));
        BODYSTRUCTURE bs = structureOf(message(mp));

        AttachmentList list = AttachmentClassifier.classify(bs, "1");
        assertThat(list.entries()).hasSize(2);
        assertThat(list.entries().get(0).untrusted().filename()).isNull();
        assertThat(list.entries().get(1).untrusted().filename()).isNull();
        assertThat(list.entries().get(1).untrusted().contentType()).isEqualTo("application/zip");
        assertThat(list.entries().get(1).inline()).isFalse();
    }

    // ---- AC6 ----

    @Test
    void capsEntriesAtHundredKeepingTrueTotal() throws Exception {
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(text("plain", "x"));
        for (int i = 0; i < 150; i++) {
            mp.addBodyPart(binary("application/pdf", new byte[3], "attachment; filename=\"f" + i + ".pdf\""));
        }
        BODYSTRUCTURE bs = structureOf(message(mp));

        AttachmentList list = AttachmentClassifier.classify(bs, "1");
        assertThat(list.entries()).hasSize(100);
        assertThat(list.total()).isEqualTo(150);
        assertThat(list.truncated()).isTrue();
    }

    // ---- AC7 ----

    @Test
    void pathologicalNestingStopsWithoutException() throws Exception {
        MimeMultipart current = new MimeMultipart("mixed");
        current.addBodyPart(binary("application/pdf", new byte[3], "attachment; filename=\"deep.pdf\""));
        for (int i = 0; i < 45; i++) {
            MimeBodyPart wrapper = new MimeBodyPart();
            wrapper.setContent(current);
            MimeMultipart outer = new MimeMultipart("mixed");
            outer.addBodyPart(wrapper);
            current = outer;
        }
        BODYSTRUCTURE bs = structureOf(message(current));

        AttachmentList list = AttachmentClassifier.classify(bs, null);
        assertThat(list.entries()).isEmpty();
    }

    @Test
    void nullStructureYieldsEmptyList() {
        assertThat(AttachmentClassifier.classify(null, null).entries()).isEmpty();
    }

    @Test
    void sizeOfBase64PartIsDecodedEstimate() throws Exception {
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(text("plain", "x"));
        mp.addBodyPart(binary("application/pdf", new byte[3000], "attachment; filename=\"a.pdf\""));
        BODYSTRUCTURE bs = structureOf(message(mp));

        long size = AttachmentClassifier.classify(bs, "1").entries().get(0).size();
        assertThat(size).isBetween(3000L, 3300L);
    }
}
