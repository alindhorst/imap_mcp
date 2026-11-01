package de.alexanderlindhorst.imapmcp.core.mime;

import static de.alexanderlindhorst.imapmcp.core.mime.BodyStructureFixtures.binary;
import static de.alexanderlindhorst.imapmcp.core.mime.BodyStructureFixtures.message;
import static de.alexanderlindhorst.imapmcp.core.mime.BodyStructureFixtures.text;
import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.user.GreenMailUser;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.Properties;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Fixtures are real BODYSTRUCTUREs fetched from GreenMail by UID over IMAPS. */
class BodyPartSelectorTest {

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

    @Test
    void singlePartTextIsSectionOne() throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setFrom("a@localhost");
        m.setSubject("s");
        m.setText("hello", "UTF-8", "plain");
        m.saveChanges();

        SelectedPart p = BodyPartSelector.select(structureOf(m)).orElseThrow();
        assertThat(p.sectionPath()).isEqualTo("1");
        assertThat(p.contentType()).isEqualTo("text/plain");
        assertThat(p.charsetParam()).isEqualToIgnoringCase("UTF-8");
        assertThat(p.encodedSize()).isPositive();
        assertThat(p.transferEncoding()).isNotBlank();
    }

    @Test
    void alternativePrefersPlain() throws Exception {
        MimeMultipart mp = new MimeMultipart("alternative");
        mp.addBodyPart(text("html", "<p>hi</p>"));
        mp.addBodyPart(text("plain", "hi"));

        SelectedPart p = BodyPartSelector.select(structureOf(message(mp))).orElseThrow();
        assertThat(p.sectionPath()).isEqualTo("2");
        assertThat(p.contentType()).isEqualTo("text/plain");
    }

    @Test
    void alternativeWithEmptyPlainUsesHtml() throws Exception {
        MimeMultipart mp = new MimeMultipart("alternative");
        mp.addBodyPart(text("plain", ""));
        mp.addBodyPart(text("html", "<p>hi</p>"));

        SelectedPart p = BodyPartSelector.select(structureOf(message(mp))).orElseThrow();
        assertThat(p.sectionPath()).isEqualTo("2");
        assertThat(p.contentType()).isEqualTo("text/html");
    }

    @Test
    void alternativeInsideMixedWithPdfIsOneOne() throws Exception {
        MimeMultipart alt = new MimeMultipart("alternative");
        alt.addBodyPart(text("plain", "hi"));
        alt.addBodyPart(text("html", "<p>hi</p>"));
        MimeBodyPart wrapper = new MimeBodyPart();
        wrapper.setContent(alt);
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(wrapper);
        mixed.addBodyPart(binary("application/pdf", new byte[10], "attachment; filename=\"a.pdf\""));

        SelectedPart p = BodyPartSelector.select(structureOf(message(mixed))).orElseThrow();
        assertThat(p.sectionPath()).isEqualTo("1.1");
        assertThat(p.contentType()).isEqualTo("text/plain");
    }

    @Test
    void htmlInsideRelatedInsideAlternativeIsFoundWhenPlainIsAbsent() throws Exception {
        MimeMultipart related = new MimeMultipart("related");
        related.addBodyPart(text("html", "<img src=cid:x>"));
        related.addBodyPart(binary("image/png", new byte[5], null));
        MimeBodyPart wrapper = new MimeBodyPart();
        wrapper.setContent(related);
        MimeMultipart alt = new MimeMultipart("alternative");
        alt.addBodyPart(wrapper);

        SelectedPart p = BodyPartSelector.select(structureOf(message(alt))).orElseThrow();
        assertThat(p.sectionPath()).isEqualTo("1.1");
        assertThat(p.contentType()).isEqualTo("text/html");
    }

    @Test
    void textInsideForwardedMessageIsNotABody() throws Exception {
        MimeMultipart innerMp = new MimeMultipart("mixed");
        innerMp.addBodyPart(text("plain", "inner"));
        MimeBodyPart fwd = new MimeBodyPart();
        fwd.setContent(message(innerMp), "message/rfc822");
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(fwd);

        assertThat(BodyPartSelector.select(structureOf(message(mp)))).isEmpty();
    }

    @Test
    void attachmentTextPartIsNotSelected() throws Exception {
        MimeBodyPart notes = text("plain", "notes");
        notes.setHeader("Content-Disposition", "attachment; filename=notes.txt");
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(notes);

        assertThat(BodyPartSelector.select(structureOf(message(mp)))).isEmpty();
    }

    @Test
    void attachmentTextPartIsSkippedForTheNextTextPart() throws Exception {
        MimeBodyPart notes = text("plain", "notes");
        notes.setHeader("Content-Disposition", "attachment; filename=notes.txt");
        MimeMultipart mp = new MimeMultipart("mixed");
        mp.addBodyPart(notes);
        mp.addBodyPart(text("plain", "body"));

        assertThat(BodyPartSelector.select(structureOf(message(mp))).orElseThrow().sectionPath()).isEqualTo("2");
    }

    @Test
    void nullStructureYieldsEmpty() {
        assertThat(BodyPartSelector.select(null)).isEmpty();
    }
}
