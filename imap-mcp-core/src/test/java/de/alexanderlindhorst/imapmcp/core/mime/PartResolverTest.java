package de.alexanderlindhorst.imapmcp.core.mime;

import static de.alexanderlindhorst.imapmcp.core.mime.BodyStructureFixtures.binary;
import static de.alexanderlindhorst.imapmcp.core.mime.BodyStructureFixtures.message;
import static de.alexanderlindhorst.imapmcp.core.mime.BodyStructureFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.alexanderlindhorst.imapmcp.core.mime.PartResolver.Result;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** SPEC 4, download_attachment step 7: the part is resolved by the list_attachments walk. */
class PartResolverTest {

    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.IMAPS.dynamicPort());

    private GreenMailUser user;

    @BeforeEach
    void purge() {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
        user = GREEN_MAIL.setUser("user@localhost", "user", "secret");
    }

    private BODYSTRUCTURE structureOf(MimeMessage msg) throws Exception {
        return BodyStructureFixtures.fetch(GREEN_MAIL, user, msg);
    }

    private BODYSTRUCTURE mixed() throws Exception {
        MimeMultipart alt = new MimeMultipart("alternative");
        alt.addBodyPart(text("plain", "hello"));
        alt.addBodyPart(text("html", "<p>hello</p>"));
        MimeBodyPart altPart = new MimeBodyPart();
        altPart.setContent(alt);
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(altPart);
        mp.addBodyPart(binary("application/pdf", new byte[300], "attachment; filename=\"Report.PDF\""));
        MimeBodyPart inner = new MimeBodyPart();
        inner.setContent("Subject: inner\r\n\r\nbody", "message/rfc822");
        inner.setHeader("Content-Type", "message/rfc822");
        mp.addBodyPart(inner);
        return structureOf(message(mp));
    }

    private static PartResolver.Leaf leaf(Result r) {
        assertThat(r).isInstanceOf(PartResolver.Leaf.class);
        return (PartResolver.Leaf) r;
    }

    @Test
    void anAttachmentLeafCarriesTheListAttachmentsDerivedValues() throws Exception {
        PartResolver.Leaf l = leaf(PartResolver.resolve(mixed(), "2"));

        assertThat(l.part()).isEqualTo("2");
        assertThat(l.filename()).isEqualTo("Report.PDF");
        assertThat(l.contentType()).isEqualTo("application/pdf");
        assertThat(l.rfc822()).isFalse();
        assertThat(l.encodedSize()).isPositive();
        assertThat(l.encoding()).isNotNull();
    }

    @Test
    void anyLeafCanBeResolvedAlsoOneThatIsNotAnAttachment() throws Exception {
        PartResolver.Leaf l = leaf(PartResolver.resolve(mixed(), "1.2"));

        assertThat(l.filename()).isNull();
        assertThat(l.contentType()).isEqualTo("text/html");
    }

    @Test
    void aMultipartIsNotALeaf() throws Exception {
        assertThat(PartResolver.resolve(mixed(), "1")).isInstanceOf(PartResolver.NotALeaf.class);
    }

    @Test
    void aMissingPartIsNotFound() throws Exception {
        BODYSTRUCTURE bs = mixed();

        assertThat(PartResolver.resolve(bs, "4")).isEqualTo(new PartResolver.NotFound(false));
        assertThat(PartResolver.resolve(bs, "1.3")).isEqualTo(new PartResolver.NotFound(false));
        assertThat(PartResolver.resolve(bs, "2.1")).isEqualTo(new PartResolver.NotFound(false));
    }

    @Test
    void anRfc822PartIsALeafAndNothingBelowItIsAddressable() throws Exception {
        BODYSTRUCTURE bs = mixed();
        PartResolver.Leaf l = leaf(PartResolver.resolve(bs, "3"));

        assertThat(l.rfc822()).isTrue();
        assertThat(l.contentType()).isEqualTo("message/rfc822");
        assertThat(PartResolver.resolve(bs, "3.1")).isEqualTo(new PartResolver.NotFound(true));
        assertThat(PartResolver.resolve(bs, "3.1.1")).isEqualTo(new PartResolver.NotFound(true));
    }

    @Test
    void aSinglePartMessageHasPartOne() throws Exception {
        MimeMessage m = new MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()));
        m.setFrom("a@localhost");
        m.setRecipients(jakarta.mail.Message.RecipientType.TO, "user@localhost");
        m.setText("plain");
        m.saveChanges();
        BODYSTRUCTURE bs = structureOf(m);

        assertThat(leaf(PartResolver.resolve(bs, "1")).contentType()).isEqualTo("text/plain");
        assertThat(PartResolver.resolve(bs, "2")).isInstanceOf(PartResolver.NotFound.class);
        assertThat(PartResolver.resolve(bs, "1.1")).isEqualTo(new PartResolver.NotFound(false));
    }

    @Test
    void theDepthLimitOfListAttachmentsApplies() throws Exception {
        assertThat(PartResolver.resolve(nested(32), "1" + ".1".repeat(31))).isInstanceOf(PartResolver.Leaf.class);
        assertThat(PartResolver.resolve(nested(33), "1" + ".1".repeat(32))).isInstanceOf(PartResolver.NotFound.class);
    }

    /** {@code levels} multiparts, one inside the other, with a pdf leaf in the innermost. */
    private BODYSTRUCTURE nested(int levels) throws Exception {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
        MimeBodyPart current = binary("application/pdf", new byte[10], "attachment; filename=\"x.pdf\"");
        for (int i = 0; i < levels - 1; i++) {
            MimeMultipart mp = new MimeMultipart("mixed");
            mp.addBodyPart(current);
            MimeBodyPart wrapper = new MimeBodyPart();
            wrapper.setContent(mp);
            current = wrapper;
        }
        MimeMultipart top = new MimeMultipart("mixed");
        top.addBodyPart(current);
        return structureOf(message(top));
    }

    @Test
    void thePartCountLimitOfListAttachmentsApplies() throws Exception {
        MimeMultipart mp = new MimeMultipart("mixed");
        for (int i = 0; i < 501; i++) {
            mp.addBodyPart(text("plain", "p" + i));
        }
        BODYSTRUCTURE bs = structureOf(message(mp));

        assertThat(PartResolver.resolve(bs, "499")).isInstanceOf(PartResolver.Leaf.class);
        assertThat(PartResolver.resolve(bs, "500")).isInstanceOf(PartResolver.NotFound.class);
    }
}
