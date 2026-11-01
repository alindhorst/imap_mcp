package de.alexanderlindhorst.imapmcp.core.mime;

import java.util.Optional;
import org.eclipse.angus.mail.imap.protocol.BODYSTRUCTURE;

/** Picks the text part that serves as message body from a fetched BODYSTRUCTURE (no body fetch). */
public final class BodyPartSelector {

    private BodyPartSelector() {
    }

    public static Optional<SelectedPart> select(BODYSTRUCTURE bs) {
        return null;
    }
}
