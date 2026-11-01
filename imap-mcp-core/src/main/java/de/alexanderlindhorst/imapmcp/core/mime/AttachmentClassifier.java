package de.alexanderlindhorst.imapmcp.core.mime;

import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;

/** Classifies attachments from a fetched BODYSTRUCTURE; never fetches a body (SPEC 4, 5.4). */
public final class AttachmentClassifier {

    private AttachmentClassifier() {
    }

    public static AttachmentList classify(BODYSTRUCTURE bs, String selectedBodyPartPath) {
        return null;
    }

    public static boolean hasAttachments(BODYSTRUCTURE bs, String selectedBodyPartPath) {
        return false;
    }
}
